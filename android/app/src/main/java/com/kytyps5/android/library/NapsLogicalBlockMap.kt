package com.kytyps5.android.library

/**
 * Conservative logical-block mapper for the RUN-addressed, contiguous NAPS dialect.
 *
 * This class reconstructs byte ranges only. It does not decrypt image sectors, decode
 * Kraken, apply shuffle transforms, or claim support for sparse-fidx dialects. Unknown
 * RUN key tables and ambiguous block classes are rejected.
 *
 * The first supported layouts are contiguous and a conservative sparse-fidx variant. Sparse
 * pages are inserted only when a 0x40 fidx marker, the exact mount sentinel, and every u2c
 * page anchor agree. fidx/u2c disagreement or unsupported RUN/KDE layouts are rejected.
 */
enum class NapsBlockStorage {
    STORED,
    KRAKEN,
    ZERO_HOLE,
    ALIAS,
}

data class NapsLogicalBlock(
    val sourceOffset: Long,
    /** Stored source bytes; zero for aliases. */
    val sourceLength: Int,
    /** Compressed length of the first 128 KiB chunk for Kraken blocks. */
    val firstChunkCompressedLength: Int,
    val logicalOffset: Long,
    val logicalLength: Int,
    val storage: NapsBlockStorage,
    /** Earlier block index when [storage] is [NapsBlockStorage.ALIAS], otherwise -1. */
    val aliasSourceBlock: Int = -1,
    val runRecordIndex: Int,
    val evenSelector: Int,
    val oddSelector: Int,
    val kdePredictor: Int,
    val shuffleIndex: Int,
    /** Decoder selector bits derived from Even/Odd. No decompression is performed here. */
    val codecFlags: Int,
)

enum class NapsImageBuildResult {
    SUCCESS,
    NEEDS_KRAKEN,
    INVALID_PAYLOAD,
}

data class NapsPayloadRead(
    /** Requested logical block index, including an alias when that is what was requested. */
    val logicalBlockIndex: Int,
    val logicalBlock: NapsLogicalBlock,
    /** Earlier non-alias block supplying the bytes, or the same index for non-alias blocks. */
    val payloadBlockIndex: Int,
    val payloadBlock: NapsLogicalBlock,
    /** Null means an implicit all-zero hole; otherwise stored bytes or encoded Kraken bytes. */
    val payloadBytes: ByteArray?,
)

data class NapsLogicalBlockMap(
    val blocks: List<NapsLogicalBlock>,
    val logicalSize: Long,
    val pageCount: Int,
    val fileBoundaries: List<Long>,
) {
    /**
     * Reads one mapped payload without decoding Kraken.
     *
     * Alias records are followed only backwards and are bounded by the map's block count.
     * ZERO_HOLE is represented by null payload bytes so callers can synthesize zeros lazily.
     * The callback must return exactly the requested source length.
     */
    fun readPayload(
        blockIndex: Int,
        readRange: (offset: Long, length: Int) -> ByteArray?,
    ): NapsPayloadRead? {
        val logical = blocks.getOrNull(blockIndex) ?: return null
        val (payloadIndex, payloadBlock) = resolvePayloadBlock(blockIndex) ?: return null
        val bytes = when (payloadBlock.storage) {
            NapsBlockStorage.ZERO_HOLE -> null
            NapsBlockStorage.STORED, NapsBlockStorage.KRAKEN -> {
                if (payloadBlock.sourceOffset < 0L || payloadBlock.sourceLength <= 0) return null
                val data = readRange(payloadBlock.sourceOffset, payloadBlock.sourceLength) ?: return null
                if (data.size != payloadBlock.sourceLength) return null
                data
            }
            NapsBlockStorage.ALIAS -> return null
        }
        return NapsPayloadRead(
            logicalBlockIndex = blockIndex,
            logicalBlock = logical,
            payloadBlockIndex = payloadIndex,
            payloadBlock = payloadBlock,
            payloadBytes = bytes,
        )
    }

    /**
     * Reconstructs the logical image sequentially to [output], without materializing it in memory.
     * Stored spans, aliases, and zero holes are supported. If any logical block resolves to Kraken,
     * NEEDS_KRAKEN is returned before writing any output bytes. The caller owns [output].
     */
    fun writeUncompressedImage(
        output: java.io.OutputStream,
        readRange: (offset: Long, length: Int) -> ByteArray?,
    ): NapsImageBuildResult {
        for (index in blocks.indices) {
            val resolved = resolvePayloadBlock(index) ?: return NapsImageBuildResult.INVALID_PAYLOAD
            if (resolved.second.storage == NapsBlockStorage.KRAKEN) {
                return NapsImageBuildResult.NEEDS_KRAKEN
            }
        }

        val zeroBuffer = ByteArray(16 * 1024)
        var outputOffset = 0L
        for (index in blocks.indices) {
            val block = blocks[index]
            if (block.logicalOffset != outputOffset || block.logicalLength <= 0) {
                return NapsImageBuildResult.INVALID_PAYLOAD
            }
            val payload = readPayload(index, readRange) ?: return NapsImageBuildResult.INVALID_PAYLOAD
            when (payload.payloadBlock.storage) {
                NapsBlockStorage.STORED -> {
                    val bytes = payload.payloadBytes ?: return NapsImageBuildResult.INVALID_PAYLOAD
                    if (bytes.size < block.logicalLength) return NapsImageBuildResult.INVALID_PAYLOAD
                    output.write(bytes, 0, block.logicalLength)
                }
                NapsBlockStorage.ZERO_HOLE -> {
                    var remaining = block.logicalLength
                    while (remaining > 0) {
                        val take = minOf(remaining, zeroBuffer.size)
                        output.write(zeroBuffer, 0, take)
                        remaining -= take
                    }
                }
                NapsBlockStorage.KRAKEN -> return NapsImageBuildResult.NEEDS_KRAKEN
                NapsBlockStorage.ALIAS -> return NapsImageBuildResult.INVALID_PAYLOAD
            }
            outputOffset = Math.addExact(outputOffset, block.logicalLength.toLong())
        }
        return if (outputOffset == logicalSize) NapsImageBuildResult.SUCCESS
        else NapsImageBuildResult.INVALID_PAYLOAD
    }

    private fun resolvePayloadBlock(blockIndex: Int): Pair<Int, NapsLogicalBlock>? {
        if (blockIndex !in blocks.indices) return null
        val logical = blocks[blockIndex]
        var payloadIndex = blockIndex
        var payloadBlock = logical
        var hops = 0
        while (payloadBlock.storage == NapsBlockStorage.ALIAS) {
            val sourceIndex = payloadBlock.aliasSourceBlock
            if (sourceIndex !in 0 until payloadIndex || ++hops > blocks.size) return null
            payloadIndex = sourceIndex
            payloadBlock = blocks.getOrNull(sourceIndex) ?: return null
            if (payloadBlock.logicalLength < logical.logicalLength) return null
        }
        return payloadIndex to payloadBlock
    }

    companion object {
        private const val LOGICAL_BLOCK_SIZE = 0x40000L
        private const val CHUNK_SIZE = 0x20000
        private const val MODULO_MASK = 0x3ffffL
        private const val MAX_MAPPED_BLOCKS = 250_000
        private const val MAX_MAPPED_PAGES = 250_000
        private const val SPARSE_FIDX_MARKER = 0x40
        private const val MOUNT_END_MARKER = 0x40

        private data class StdRecord(
            val recordIndex: Int,
            val entry: NapsCblockInfoEntry,
            val runRecordIndex: Int,
            val endCompressedModulo: Int,
        )

        /**
         * Returns a map only for a bounded, contiguous RUN-addressed layout whose
         * fidx/u2c indexes agree with the reconstructed STD boundaries.
         */
        fun parse(napsBlob: ByteArray, packedImageSize: Long): NapsLogicalBlockMap? {
            if (packedImageSize < 0L) return null
            return try {
                build(napsBlob, packedImageSize)
            } catch (_: ArithmeticException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun build(napsBlob: ByteArray, packedImageSize: Long): NapsLogicalBlockMap? {
            val tables = NapsLayoutTables.parse(napsBlob) ?: return null
            val counts = tables.header.counts
            if (counts.uncompressedBlockCount !in 1..MAX_MAPPED_PAGES) return null
            if (counts.cblockInfoCount !in 3..(MAX_MAPPED_BLOCKS + 2)) return null
            if (counts.fileCount < 1) return null

            val cblocks = tables.cblockInfos
            if (cblocks.size < 3 || !cblocks.first().isRunBase || cblocks.last().isRunBase) return null

            val stdRecords = ArrayList<StdRecord>(minOf(cblocks.size, MAX_MAPPED_BLOCKS + 2))
            var activeRunIndex = -1
            for (recordIndex in cblocks.indices) {
                val entry = cblocks[recordIndex]
                if (entry.isRunBase) {
                    if (recordIndex > 0 && cblocks[recordIndex - 1].isRunBase) return null
                    if (entry.keyTableIndex != 0 || entry.coffsetStart256k == null ||
                        entry.coffsetEndMod256k == null
                    ) return null
                    activeRunIndex = recordIndex
                    continue
                }
                if (activeRunIndex < 0) return null
                val start = entry.coffsetStartMod256k ?: return null
                val end = if (recordIndex + 1 < cblocks.size) {
                    val next = cblocks[recordIndex + 1]
                    if (next.isRunBase) next.coffsetEndMod256k ?: return null
                    else next.coffsetStartMod256k ?: return null
                } else {
                    start
                }
                stdRecords += StdRecord(recordIndex, entry, activeRunIndex, end)
                if (stdRecords.size > MAX_MAPPED_BLOCKS + 1) return null
            }
            if (stdRecords.size < 2 || stdRecords.size - 1 > MAX_MAPPED_BLOCKS) return null

            val blocks = ArrayList<NapsLogicalBlock>(stdRecords.size - 1)
            val logicalStarts = ArrayList<Long>(stdRecords.size)
            val sourceOwners = HashMap<Long, Int>()
            var logicalCursor = 0L
            var packedCursor = 0L
            var previousRunIndex = -1

            for (index in 0 until stdRecords.lastIndex) {
                val current = stdRecords[index]
                val next = stdRecords[index + 1]
                val entry = current.entry
                val run = cblocks[current.runRecordIndex]

                val sourceModulo = entry.coffsetStartMod256k ?: return null
                val logicalModulo = entry.uoffsetStart ?: return null
                val evenMinusOne = entry.clenEvenMinus1 ?: return null
                val even = entry.even ?: return null
                val odd = entry.odd ?: return null
                val kde = entry.kdePredictor ?: return null
                val shuffle = entry.shuffleIndex ?: return null

                // This first implementation does not process alternate key tables or KDE modes.
                if (run.keyTableIndex != 0 || kde != 0) return null

                logicalStarts += logicalCursor
                if ((logicalCursor and MODULO_MASK) != logicalModulo.toLong()) return null

                val nextLogicalModulo = next.entry.uoffsetStart ?: return null
                val logicalLength = (((nextLogicalModulo.toLong() - logicalModulo.toLong() - 1L) and
                    MODULO_MASK) + 1L).toInt()
                if (logicalLength !in 1..LOGICAL_BLOCK_SIZE.toInt()) return null

                if (current.runRecordIndex != previousRunIndex) {
                    val runPage = run.coffsetStart256k?.toLong() ?: return null
                    packedCursor = Math.addExact(
                        Math.multiplyExact(runPage, LOGICAL_BLOCK_SIZE),
                        sourceModulo.toLong(),
                    )
                    previousRunIndex = current.runRecordIndex
                }
                if ((packedCursor and MODULO_MASK) != sourceModulo.toLong()) return null

                var compressedLength = ((current.endCompressedModulo.toLong() - sourceModulo.toLong()) and
                    MODULO_MASK).toInt()
                val firstChunkLength = Math.addExact(evenMinusOne, 1)
                val zeroHole = (even == 0 && odd == 0 && kde == 0) ||
                    (even == 1 && kde == 0 && evenMinusOne == 7 &&
                        ((odd == 1 && compressedLength == 16 && logicalLength > 16) ||
                         (odd == 0 && compressedLength == 8 && logicalLength > 8)))

                val isStored = !zeroHole &&
                    even == 1 &&
                    odd == (if (logicalLength > CHUNK_SIZE) 1 else 0) &&
                    firstChunkLength == minOf(logicalLength, CHUNK_SIZE) &&
                    compressedLength == (logicalLength.toLong() and MODULO_MASK).toInt()

                val storage: NapsBlockStorage
                var aliasSource = -1
                if (zeroHole) {
                    storage = NapsBlockStorage.ZERO_HOLE
                    if (compressedLength != 0 && compressedLength != 8 && compressedLength != 16) return null
                } else if (isStored) {
                    storage = NapsBlockStorage.STORED
                    compressedLength = logicalLength
                } else if (compressedLength == 0) {
                    storage = NapsBlockStorage.ALIAS
                    aliasSource = sourceOwners[packedCursor] ?: return null
                    if (aliasSource !in blocks.indices || blocks[aliasSource].logicalLength < logicalLength) return null
                } else if (compressedLength < logicalLength) {
                    storage = NapsBlockStorage.KRAKEN
                    if (firstChunkLength <= 0 || firstChunkLength > compressedLength) return null
                    if (logicalLength > CHUNK_SIZE && firstChunkLength == compressedLength) return null
                } else {
                    // Unknown or contradictory record shape: do not guess raw/compressed semantics.
                    return null
                }

                if (storage != NapsBlockStorage.ALIAS &&
                    (packedCursor > packedImageSize || compressedLength.toLong() > packedImageSize - packedCursor)
                ) return null

                val codecFlags =
                    (if ((even and 0x04) != 0) 0x02 else 0) or
                    (if ((even and 0x02) != 0) 0x01 else 0) or
                    (if (logicalLength > CHUNK_SIZE && (odd and 0x04) != 0) 0x20 else 0) or
                    (if (logicalLength > CHUNK_SIZE && (odd and 0x02) != 0) 0x10 else 0)

                blocks += NapsLogicalBlock(
                    sourceOffset = packedCursor,
                    sourceLength = if (storage == NapsBlockStorage.ALIAS) 0 else compressedLength,
                    firstChunkCompressedLength = if (storage == NapsBlockStorage.KRAKEN) firstChunkLength else 0,
                    logicalOffset = logicalCursor,
                    logicalLength = logicalLength,
                    storage = storage,
                    aliasSourceBlock = aliasSource,
                    runRecordIndex = current.runRecordIndex,
                    evenSelector = even,
                    oddSelector = odd,
                    kdePredictor = kde,
                    shuffleIndex = shuffle,
                    codecFlags = codecFlags,
                )

                if (storage != NapsBlockStorage.ALIAS && storage != NapsBlockStorage.ZERO_HOLE) {
                    sourceOwners.putIfAbsent(packedCursor, blocks.lastIndex)
                }
                if (storage != NapsBlockStorage.ALIAS) {
                    packedCursor = Math.addExact(packedCursor, compressedLength.toLong())
                }
                logicalCursor = Math.addExact(logicalCursor, logicalLength.toLong())
            }

            // The final STD is a terminator and must point at the exact end of the reconstructed mount.
            val terminal = stdRecords.last()
            val terminalLogicalModulo = terminal.entry.uoffsetStart ?: return null
            if ((logicalCursor and MODULO_MASK) != terminalLogicalModulo.toLong()) return null
            logicalStarts += logicalCursor

            val pageCount = counts.uncompressedBlockCount
            val lowerBound = Math.multiplyExact((pageCount - 1L), LOGICAL_BLOCK_SIZE)
            val upperBound = Math.multiplyExact(pageCount.toLong(), LOGICAL_BLOCK_SIZE)
            val sentinel = tables.fileOffsets.lastOrNull() ?: return null
            if (sentinel.type != MOUNT_END_MARKER) return null
            val declaredEnd = sentinel.uncompressedOffsetStart
            if (declaredEnd <= lowerBound || declaredEnd > upperBound) return null

            val sparseMarkers = tables.fileOffsets.dropLast(1).filter {
                it.type == SPARSE_FIDX_MARKER
            }
            if (sparseMarkers.isEmpty()) {
                if (declaredEnd != logicalCursor) return null
            } else {
                if (declaredEnd <= logicalCursor ||
                    (declaredEnd - logicalCursor) % LOGICAL_BLOCK_SIZE != 0L
                ) return null
            }

            val restored = restoreLogicalCoverage(
                tables = tables,
                stdRecords = stdRecords,
                compactBlocks = blocks,
                compactStarts = logicalStarts,
                compactEnd = logicalCursor,
                pageCount = pageCount,
                declaredEnd = declaredEnd,
                sparseMarkers = sparseMarkers,
            ) ?: return null

            return NapsLogicalBlockMap(
                blocks = restored.blocks,
                logicalSize = declaredEnd,
                pageCount = pageCount,
                fileBoundaries = restored.fileBoundaries,
            )
        }

        private data class RestoredLogicalCoverage(
            val blocks: List<NapsLogicalBlock>,
            val fileBoundaries: List<Long>,
        )

        /**
         * Restores omitted whole logical pages from absolute u2c page anchors. The 18-bit STD
         * uoffset field alone cannot reveal a page-sized gap, so the fidx mount sentinel and
         * sparse markers are checked independently before any hole is accepted.
         */
        private fun restoreLogicalCoverage(
            tables: NapsLayoutTables,
            stdRecords: List<StdRecord>,
            compactBlocks: List<NapsLogicalBlock>,
            compactStarts: List<Long>,
            compactEnd: Long,
            pageCount: Int,
            declaredEnd: Long,
            sparseMarkers: List<NapsFileOffsetEntry>,
        ): RestoredLogicalCoverage? {
            if (stdRecords.size != compactBlocks.size + 1 ||
                compactStarts.size != stdRecords.size ||
                compactStarts.lastOrNull() != compactEnd
            ) return null

            val anchors = HashMap<Int, Long>()
            var previousIndex = -1L
            for (page in 0 until pageCount) {
                val group = tables.u2cEntries.getOrNull(page / 8) ?: return null
                val slot = page % 8
                val delta = if (slot == 0) 0 else group.deltaFromBase.getOrNull(slot - 1) ?: return null
                val mappedIndex = group.infoOffset9BBase.toLong() + delta.toLong()
                if (mappedIndex < previousIndex ||
                    mappedIndex >= tables.cblockInfos.size.toLong()
                ) return null
                previousIndex = mappedIndex
                val record = tables.cblockInfos.getOrNull(mappedIndex.toInt()) ?: return null
                if (record.isRunBase) return null
                val recordIndex = mappedIndex.toInt()
                val pageStart = Math.multiplyExact(page.toLong(), LOGICAL_BLOCK_SIZE)
                val priorAnchor = anchors.put(recordIndex, pageStart)
                if (priorAnchor != null && priorAnchor != pageStart) return null
            }

            val restored = ArrayList<NapsLogicalBlock>(
                minOf(MAX_MAPPED_BLOCKS, compactBlocks.size + sparseMarkers.size + pageCount)
            )
            val restoredStarts = LongArray(stdRecords.size)
            val sourceIndices = IntArray(compactBlocks.size) { -1 }
            var shift = 0L
            var insertedHolePages = 0
            val insertedHoleStarts = HashSet<Long>()
            for (index in stdRecords.indices) {
                val original = compactStarts[index]
                var logical = Math.addExact(original, shift)
                val stdRecordIndex = stdRecords[index].recordIndex
                val pageAnchor = anchors[stdRecordIndex]
                if (pageAnchor != null && logical < pageAnchor) {
                    val distance = pageAnchor - logical
                    val rounded = Math.addExact(distance, LOGICAL_BLOCK_SIZE - 1L)
                    val gap = Math.multiplyExact(rounded / LOGICAL_BLOCK_SIZE, LOGICAL_BLOCK_SIZE)
                    if (gap <= 0L || gap > declaredEnd - compactEnd - shift ||
                        gap % LOGICAL_BLOCK_SIZE != 0L
                    ) return null
                    var added = 0L
                    while (added < gap) {
                        if (restored.size >= MAX_MAPPED_BLOCKS) return null
                        val holeStart = Math.addExact(logical, added)
                        if (!insertedHoleStarts.add(holeStart)) return null
                        restored += NapsLogicalBlock(
                            sourceOffset = 0L,
                            sourceLength = 0,
                            firstChunkCompressedLength = 0,
                            logicalOffset = holeStart,
                            logicalLength = LOGICAL_BLOCK_SIZE.toInt(),
                            storage = NapsBlockStorage.ZERO_HOLE,
                            aliasSourceBlock = -1,
                            runRecordIndex = -1,
                            evenSelector = 0,
                            oddSelector = 0,
                            kdePredictor = 0,
                            shuffleIndex = 0,
                            codecFlags = 0,
                        )
                        added += LOGICAL_BLOCK_SIZE
                        insertedHolePages++
                    }
                    shift = Math.addExact(shift, gap)
                    logical = Math.addExact(logical, gap)
                }

                val expectedModulo = stdRecords[index].entry.uoffsetStart ?: return null
                if ((logical and MODULO_MASK) != expectedModulo.toLong()) return null
                restoredStarts[index] = logical
                if (index == compactBlocks.size) break

                val block = compactBlocks[index]
                var aliasSource = block.aliasSourceBlock
                if (block.storage == NapsBlockStorage.ALIAS) {
                    if (aliasSource !in 0 until index) return null
                    aliasSource = sourceIndices[aliasSource]
                    if (aliasSource !in restored.indices) return null
                    if (restored[aliasSource].logicalLength < block.logicalLength) return null
                }
                if (restored.size >= MAX_MAPPED_BLOCKS) return null
                sourceIndices[index] = restored.size
                restored += block.copy(
                    logicalOffset = logical,
                    aliasSourceBlock = aliasSource,
                )
            }

            if (restoredStarts.lastOrNull() != declaredEnd ||
                Math.addExact(compactEnd, shift) != declaredEnd
            ) return null
            if (sparseMarkers.isEmpty() != (insertedHolePages == 0)) return null

            // Every page's u2c anchor must identify the first STD whose restored span covers it.
            var expectedStdPosition = 0
            for (page in 0 until pageCount) {
                val pageStart = Math.multiplyExact(page.toLong(), LOGICAL_BLOCK_SIZE)
                while (expectedStdPosition + 1 < restoredStarts.size &&
                    restoredStarts[expectedStdPosition] < pageStart
                ) {
                    expectedStdPosition++
                }
                val expectedIndex = stdRecords.getOrNull(expectedStdPosition)?.recordIndex ?: return null
                val group = tables.u2cEntries.getOrNull(page / 8) ?: return null
                val slot = page % 8
                val delta = if (slot == 0) 0 else group.deltaFromBase.getOrNull(slot - 1) ?: return null
                val mappedIndex = group.infoOffset9BBase.toLong() + delta.toLong()
                if (mappedIndex != expectedIndex.toLong()) return null
            }

            val payloadStarts = restoredStarts.toList().dropLast(1).toHashSet()
            val holeStarts = restored.asSequence()
                .filter { it.storage == NapsBlockStorage.ZERO_HOLE }
                .map { it.logicalOffset }
                .toHashSet()
            val fileBoundaries = ArrayList<Long>(tables.fileOffsets.size - 1)
            var previousOffset = 0L
            for (index in 0 until tables.fileOffsets.lastIndex) {
                val entry = tables.fileOffsets[index]
                val offset = entry.uncompressedOffsetStart
                if (offset > declaredEnd || offset < previousOffset) return null
                val validBoundary = if (entry.type == SPARSE_FIDX_MARKER) {
                    offset in holeStarts
                } else {
                    offset in payloadStarts
                }
                if (!validBoundary) return null
                fileBoundaries += offset
                previousOffset = offset
            }
            val markerOffsets = sparseMarkers.map { it.uncompressedOffsetStart }
            if (markerOffsets.size != markerOffsets.toSet().size ||
                markerOffsets.toSet() != insertedHoleStarts
            ) return null
            if (insertedHolePages != sparseMarkers.size) return null
            if (sparseMarkers.any { it.uncompressedOffsetStart !in holeStarts }) return null

            return RestoredLogicalCoverage(restored, fileBoundaries)
        }
    }
}
