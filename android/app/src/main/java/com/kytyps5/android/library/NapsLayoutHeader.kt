package com.kytyps5.android.library

/**
 * Bounded decoder for the fixed NAPS layout header and section geometry.
 *
 * The field layout and section offsets are based on the publicly documented NAPS format.
 * The section arithmetic is independently expressed here; no package payload is decoded.
 */
data class NapsLayoutCounts(
    val fileCount: Int,
    val compressionType: Int,
    val keyCount: Int,
    val shufflePatternCount: Int,
    val uncompressedBlockCount: Int,
    val outerBlockCount: Int,
    val cblockInfoCount: Int,
)

data class NapsLayoutSections(
    val headerOffset: Long,
    val outerDigestsOffset: Long,
    val shufflePatternsOffset: Long,
    val fileOffsetsOffset: Long,
    val u2cOffsetsOffset: Long,
    val cblockInfoOffset: Long,
    val endOffset: Long,
    val u2cEntryCount: Int,
)

data class NapsLayoutHeader(
    val counts: NapsLayoutCounts,
    val sections: NapsLayoutSections,
) {
    companion object {
        private const val HEADER_SIZE = 16L
        private const val MAX_FILES = 1_000_000
        private const val MAX_LAYOUT_BYTES = Int.MAX_VALUE.toLong()

        /**
         * Parses only the 16-byte header and validates that all declared tables fit in [blob].
         * Returns null on truncated, overflowing, or unreasonable layouts.
         */
        fun parse(blob: ByteArray): NapsLayoutHeader? {
            if (blob.size < HEADER_SIZE) return null
            return runCatching {
                val word0 = readLe64(blob, 0)
                val word1 = readLe64(blob, 8)

                val files = ((word0 and 0xFFFFFFL) + 1L)
                val compression = ((word0 ushr 24) and 0x3L).toInt()
                val keys = (((word0 ushr 26) and 0x3L) + 1L).toInt()
                val shuffles = ((word0 ushr 28) and 0xFL).toInt()
                val uBlocks = ((word0 ushr 32) and 0xFFFFFFL).toInt()
                val outerBlocks = (word1 and 0xFFFFFFL).toInt()
                val cblockInfos = (((word1 ushr 24) and 0xFFFFFFL) + 2L).toInt()

                if (files < 1L || files > MAX_FILES.toLong() || uBlocks <= 0 ||
                    outerBlocks <= 0 || cblockInfos < 2
                ) return null

                val counts = NapsLayoutCounts(
                    fileCount = files.toInt(),
                    compressionType = compression,
                    keyCount = keys,
                    shufflePatternCount = shuffles,
                    uncompressedBlockCount = uBlocks,
                    outerBlockCount = outerBlocks,
                    cblockInfoCount = cblockInfos,
                )

                val outerOffset = HEADER_SIZE
                val shuffleOffset = checkedAdd(outerOffset, checkedMul(outerBlocks.toLong(), 8L))
                val fidxOffset = checkedAdd(shuffleOffset, checkedMul(shuffles.toLong(), 8L))
                val u2cOffset = checkedAdd(fidxOffset, checkedMul(files, 6L))
                val u2cCount = (uBlocks.toLong() + 8L) / 8L
                val u2cEnd = checkedAdd(u2cOffset, checkedMul(u2cCount, 10L))

                // NAPS places CblockInfo at the 8-byte-aligned native offset.
                val rawCblockOffset = checkedAdd(
                    checkedAdd(checkedMul((shuffles + outerBlocks).toLong(), 8L),
                        checkedMul(u2cCount, 10L)),
                    checkedAdd(checkedMul(files - 1L, 6L), 29L),
                )
                val cblockOffset = rawCblockOffset and -8L
                if (cblockOffset < u2cEnd) return null
                val end = checkedAdd(cblockOffset, checkedMul(cblockInfos.toLong(), 9L))
                if (end > blob.size.toLong() || end > MAX_LAYOUT_BYTES) return null

                NapsLayoutHeader(
                    counts,
                    NapsLayoutSections(
                        headerOffset = 0L,
                        outerDigestsOffset = outerOffset,
                        shufflePatternsOffset = shuffleOffset,
                        fileOffsetsOffset = fidxOffset,
                        u2cOffsetsOffset = u2cOffset,
                        cblockInfoOffset = cblockOffset,
                        endOffset = end,
                        u2cEntryCount = u2cCount.toInt(),
                    ),
                )
            }.getOrNull()
        }

        private fun readLe64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((bytes[offset + i].toLong() and 0xffL) shl (i * 8))
            }
            return value
        }

        private fun checkedMul(a: Long, b: Long): Long {
            require(a >= 0L && b >= 0L)
            return Math.multiplyExact(a, b)
        }

        private fun checkedAdd(a: Long, b: Long): Long {
            require(a >= 0L && b >= 0L)
            return Math.addExact(a, b)
        }
    }
}
