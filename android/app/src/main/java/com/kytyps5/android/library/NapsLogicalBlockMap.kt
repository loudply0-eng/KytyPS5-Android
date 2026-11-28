package com.kytyps5.android.library

/**
 * Conservative logical-block mapper for the RUN-addressed, contiguous NAPS dialect.
 *
 * This class reconstructs byte ranges only. It does not decrypt image sectors, decode
 * Kraken, apply shuffle transforms, or claim support for sparse-fidx dialects. Unknown
 * RUN key tables and ambiguous block classes are rejected.
 *
 * The algorithm is intentionally limited to layouts where every logical byte is covered
 * by an explicit STD block (no implicit sparse pages). fidx and u2c are cross-checked
 * against the resulting boundaries before a map is returned.
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
            if (logicalCursor <= lowerBound || logicalCursor > upperBound) return null

            if (!validateU2c(tables, stdRecords, logicalStarts, pageCount)) return null
            val boundaries = logicalStarts.toHashSet()
            val fileBoundaries = ArrayList<Long>(tables.fileOffsets.size)
            var priorFileOffset = 0L
            for (i in 0 until tables.fileOffsets.lastIndex) {
                val file = tables.fileOffsets[i]
                val offset = file.uncompressedOffsetStart
                if (file.type == SPARSE_FIDX_MARKER || offset > logicalCursor ||
                    offset < priorFileOffset || offset !in boundaries
                ) return null
                fileBoundaries += offset
                priorFileOffset = offset
            }
            val sentinel = tables.fileOffsets.lastOrNull() ?: return null
            if (sentinel.type != MOUNT_END_MARKER || sentinel.uncompressedOffsetStart != logicalCursor) return null

            return NapsLogicalBlockMap(
                blocks = blocks,
                logicalSize = logicalCursor,
                pageCount = pageCount,
                fileBoundaries = fileBoundaries,
            )
        }

        private fun validateU2c(
            tables: NapsLayoutTables,
            stdRecords: List<StdRecord>,
            logicalStarts: List<Long>,
            pageCount: Int,
        ): Boolean {
            var expectedStdPosition = 0
            var previousIndex = -1L
            for (page in 0 until pageCount) {
                val group = tables.u2cEntries.getOrNull(page / 8) ?: return false
                val slot = page % 8
                val delta = if (slot == 0) 0 else group.deltaFromBase.getOrNull(slot - 1) ?: return false
                val mappedIndex = group.infoOffset9BBase.toLong() + delta.toLong()
                if (mappedIndex < previousIndex || mappedIndex > Int.MAX_VALUE) return false
                previousIndex = mappedIndex

                val pageStart = page.toLong() * LOGICAL_BLOCK_SIZE
                while (expectedStdPosition + 1 < logicalStarts.size &&
                    logicalStarts[expectedStdPosition] < pageStart
                ) {
                    expectedStdPosition++
                }
                val expectedRecordIndex = stdRecords.getOrNull(expectedStdPosition)?.recordIndex ?: return false
                if (mappedIndex != expectedRecordIndex.toLong()) return false
                if (tables.cblockInfos.getOrNull(mappedIndex.toInt())?.isRunBase != false) return false
            }
            return true
        }
    }
}
