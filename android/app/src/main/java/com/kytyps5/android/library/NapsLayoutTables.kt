package com.kytyps5.android.library

/**
 * Bounded reader for the fixed NAPS layout tables following its 16-byte header.
 *
 * This stage exposes the raw section data and decodes only the documented fixed-width
 * fields. It does not infer a complete logical-block map, select a NAPS dialect, or
 * decompress the image. In particular, fidx/u2c/CblockInfo values are preserved so
 * later validation can reject unsupported or ambiguous layouts instead of guessing.
 *
 * The 9-byte CblockInfo field positions were cross-checked against the public
 * pearlxcore/ProsperoPkgTool NAPS format work (MIT); this Kotlin decoder is independently
 * expressed and does not copy its implementation.
 */
data class NapsFileOffsetEntry(
    val type: Int,
    val uncompressedOffsetStart: Long,
)

data class NapsU2cEntry(
    val infoOffset9BBase: Int,
    val deltaFromBase: List<Int>,
)

data class NapsCblockInfoEntry(
    val rawRecord: ByteArray,
    val isRunBase: Boolean,
    val reservedBit19: Boolean,
    // STD record fields; null for RUN records.
    val coffsetStartMod256k: Int? = null,
    val uoffsetStart: Int? = null,
    val clenEvenMinus1: Int? = null,
    val even: Int? = null,
    val odd: Int? = null,
    val kdePredictor: Int? = null,
    val shuffleIndex: Int? = null,
    val reservedBit67: Boolean? = null,
    // RUN record fields; null for STD records.
    val coffsetEndMod256k: Int? = null,
    val tweakIndexStart: Long? = null,
    val keyTableIndex: Int? = null,
    val coffsetStart256k: Int? = null,
)

data class NapsLayoutTables(
    val header: NapsLayoutHeader,
    val outerBlockDigests: List<ByteArray>,
    val shufflePatterns: List<ByteArray>,
    val fileOffsets: List<NapsFileOffsetEntry>,
    val u2cEntries: List<NapsU2cEntry>,
    val cblockInfos: List<NapsCblockInfoEntry>,
    /** Bytes between the end of u2c and the aligned CblockInfo table, retained verbatim. */
    val cblockInfoPadding: ByteArray,
    /** Optional 0..15 zero bytes used to pad the end of the NAPS blob. */
    val trailingPadding: ByteArray,
) {
    companion object {
        private const val OUTER_DIGEST_STRIDE = 8
        private const val SHUFFLE_PATTERN_STRIDE = 8
        private const val FIDX_STRIDE = 6
        private const val U2C_STRIDE = 10
        private const val CBLOCK_INFO_STRIDE = 9
        private const val MAX_TRAILING_PADDING = 15L

        /**
         * Reads the header-declared tables only when all sections fit the supplied blob.
         * Returns null for truncated, overflowing, unreasonably padded, or malformed data.
         */
        fun parse(blob: ByteArray): NapsLayoutTables? {
            val header = NapsLayoutHeader.parse(blob) ?: return null
            val sections = header.sections
            val counts = header.counts

            return try {
                val end = sections.endOffset
                val trailingSize = blob.size.toLong() - end
                if (trailingSize < 0L || trailingSize > MAX_TRAILING_PADDING) return null

                val u2cBytes = Math.multiplyExact(sections.u2cEntryCount.toLong(), U2C_STRIDE.toLong())
                val u2cEnd = Math.addExact(sections.u2cOffsetsOffset, u2cBytes)
                val cblockPaddingSize = sections.cblockInfoOffset - u2cEnd
                if (cblockPaddingSize !in 0L..7L) return null

                val outer = copyEntries(blob, sections.outerDigestsOffset,
                    counts.outerBlockCount, OUTER_DIGEST_STRIDE) ?: return null
                val shuffle = copyEntries(blob, sections.shufflePatternsOffset,
                    counts.shufflePatternCount, SHUFFLE_PATTERN_STRIDE) ?: return null

                val fidxRaw = copyEntries(blob, sections.fileOffsetsOffset,
                    counts.fileCount, FIDX_STRIDE) ?: return null
                val fidx = fidxRaw.map(::decodeFileOffsetEntry)

                val u2cRaw = copyEntries(blob, sections.u2cOffsetsOffset,
                    sections.u2cEntryCount, U2C_STRIDE) ?: return null
                val u2c = u2cRaw.map(::decodeU2cEntry)

                val cblockRaw = copyEntries(blob, sections.cblockInfoOffset,
                    counts.cblockInfoCount, CBLOCK_INFO_STRIDE) ?: return null
                val cblocks = cblockRaw.map(::decodeCblockInfoEntry)

                val paddingStart = u2cEnd.toInt()
                val paddingEnd = sections.cblockInfoOffset.toInt()
                val cblockPadding = blob.copyOfRange(paddingStart, paddingEnd)

                val trailing = blob.copyOfRange(end.toInt(), blob.size)
                if (trailing.any { it != 0.toByte() }) return null

                NapsLayoutTables(
                    header = header,
                    outerBlockDigests = outer,
                    shufflePatterns = shuffle,
                    fileOffsets = fidx,
                    u2cEntries = u2c,
                    cblockInfos = cblocks,
                    cblockInfoPadding = cblockPadding,
                    trailingPadding = trailing,
                )
            } catch (_: ArithmeticException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }

        private fun copyEntries(
            blob: ByteArray,
            offset: Long,
            count: Int,
            stride: Int,
        ): List<ByteArray>? {
            if (offset < 0L || count < 0 || stride <= 0) return null
            val byteCount = Math.multiplyExact(count.toLong(), stride.toLong())
            if (!rangeFits(offset, byteCount, blob.size.toLong()) || byteCount > Int.MAX_VALUE) return null
            val start = offset.toInt()
            return List(count) { index ->
                val entryStart = start + index * stride
                blob.copyOfRange(entryStart, entryStart + stride)
            }
        }

        private fun decodeFileOffsetEntry(entry: ByteArray): NapsFileOffsetEntry {
            var offset = 0L
            for (i in 0 until 5) {
                offset = offset or ((entry[i].toLong() and 0xffL) shl (8 * i))
            }
            return NapsFileOffsetEntry(
                type = entry[5].toInt() and 0xff,
                uncompressedOffsetStart = offset,
            )
        }

        private fun decodeU2cEntry(entry: ByteArray): NapsU2cEntry {
            val base = (entry[0].toInt() and 0xff) or
                ((entry[1].toInt() and 0xff) shl 8) or
                ((entry[2].toInt() and 0xff) shl 16)
            val deltas = List(7) { entry[3 + it].toInt() and 0xff }
            return NapsU2cEntry(base, deltas)
        }

        private fun decodeCblockInfoEntry(entry: ByteArray): NapsCblockInfoEntry {
            var low = 0L
            for (i in 0 until 8) {
                low = low or ((entry[i].toLong() and 0xffL) shl (8 * i))
            }
            val high = entry[8].toInt() and 0xff
            val isRunBase = ((low ushr 18) and 1L) != 0L
            val reservedBit19 = ((low ushr 19) and 1L) != 0L
            val low18 = (low and 0x3ffffL).toInt()
            val raw = entry.copyOf(CBLOCK_INFO_STRIDE)

            return if (!isRunBase) {
                val kdePredictor = (((high and 0x07) shl 3) or
                    ((low ushr 61) and 0x07L).toInt())
                NapsCblockInfoEntry(
                    rawRecord = raw,
                    isRunBase = false,
                    reservedBit19 = reservedBit19,
                    coffsetStartMod256k = low18,
                    uoffsetStart = ((low ushr 20) and 0x3ffffL).toInt(),
                    clenEvenMinus1 = ((low ushr 38) and 0x1ffffL).toInt(),
                    even = ((low ushr 55) and 0x07L).toInt(),
                    odd = ((low ushr 58) and 0x07L).toInt(),
                    kdePredictor = kdePredictor,
                    shuffleIndex = (high ushr 4) and 0x0f,
                    reservedBit67 = (high and 0x08) != 0,
                )
            } else {
                NapsCblockInfoEntry(
                    rawRecord = raw,
                    isRunBase = true,
                    reservedBit19 = reservedBit19,
                    coffsetEndMod256k = low18,
                    tweakIndexStart = (low ushr 20) and 0x0fffffffL,
                    keyTableIndex = ((low ushr 48) and 0x03L).toInt(),
                    coffsetStart256k = (((high and 0xff) shl 14) or
                        ((low ushr 50) and 0x3fffL).toInt()),
                )
            }
        }

        private fun rangeFits(offset: Long, size: Long, containerSize: Long): Boolean {
            if (offset < 0L || size < 0L || containerSize < 0L) return false
            return offset <= containerSize && size <= containerSize - offset
        }
    }
}
