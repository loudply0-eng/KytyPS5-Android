package com.kytyps5.android.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class NapsLayoutHeaderTest {
    @Test
    fun parsesHeaderAndBoundsAllDeclaredTables() {
        val blob = fixture(fileCount = 1, shuffleCount = 1, uBlockCount = 2,
            outerBlockCount = 1, cblockInfoCount = 2)
        val parsed = NapsLayoutHeader.parse(blob)
        assertNotNull(parsed)
        parsed!!
        assertEquals(1, parsed.counts.fileCount)
        assertEquals(1, parsed.counts.shufflePatternCount)
        assertEquals(2, parsed.counts.uncompressedBlockCount)
        assertEquals(1, parsed.counts.outerBlockCount)
        assertEquals(2, parsed.counts.cblockInfoCount)
        assertEquals(16L, parsed.sections.outerDigestsOffset)
        assertEquals(24L, parsed.sections.shufflePatternsOffset)
        assertEquals(32L, parsed.sections.fileOffsetsOffset)
        assertEquals(38L, parsed.sections.u2cOffsetsOffset)
        assertEquals(48L, parsed.sections.cblockInfoOffset)
        assertEquals(66L, parsed.sections.endOffset)
    }

    @Test
    fun rejectsTruncatedHeaderAndTables() {
        assertNull(NapsLayoutHeader.parse(ByteArray(15)))
        assertNull(NapsLayoutHeader.parse(fixture(1, 1, 2, 1, 2).copyOf(65)))
    }

    @Test
    fun rejectsZeroLogicalBlockCount() {
        assertNull(NapsLayoutHeader.parse(fixture(1, 0, 0, 1, 2)))
    }

    private fun fixture(
        fileCount: Int, shuffleCount: Int, uBlockCount: Int,
        outerBlockCount: Int, cblockInfoCount: Int,
    ): ByteArray {
        val word0 = ((fileCount - 1).toLong() and 0xFFFFFFL) or
            ((shuffleCount.toLong() and 0xFL) shl 28) or
            ((uBlockCount.toLong() and 0xFFFFFFL) shl 32)
        val word1 = (outerBlockCount.toLong() and 0xFFFFFFL) or
            (((cblockInfoCount - 2).toLong() and 0xFFFFFFL) shl 24)
        val size = 66
        val bytes = ByteArray(size)
        for (i in 0 until 8) {
            bytes[i] = (word0 ushr (8 * i)).toByte()
            bytes[8 + i] = (word1 ushr (8 * i)).toByte()
        }
        return bytes
    }
}
