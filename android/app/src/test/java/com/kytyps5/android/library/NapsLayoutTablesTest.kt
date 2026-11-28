package com.kytyps5.android.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NapsLayoutTablesTest {
    @Test
    fun parsesFixedTablesAndDecodesStdAndRunCblockRecords() {
        val blob = fixture(padded = false)
        val parsed = NapsLayoutTables.parse(blob)
        assertNotNull(parsed)
        parsed!!

        assertEquals(1, parsed.outerBlockDigests.size)
        assertEquals(listOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17),
            parsed.outerBlockDigests.single().map { it.toInt() and 0xff })
        assertEquals(1, parsed.shufflePatterns.size)
        assertEquals(0x50, parsed.shufflePatterns.single()[0].toInt() and 0xff)

        assertEquals(0x0102030405L, parsed.fileOffsets.single().uncompressedOffsetStart)
        assertEquals(0x7e, parsed.fileOffsets.single().type)

        assertEquals(0x123456, parsed.u2cEntries.single().infoOffset9BBase)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), parsed.u2cEntries.single().deltaFromBase)

        val std = parsed.cblockInfos[0]
        assertFalse(std.isRunBase)
        assertTrue(std.reservedBit19)
        assertEquals(0x12345, std.coffsetStartMod256k)
        assertEquals(0x23456, std.uoffsetStart)
        assertEquals(0x12345, std.clenEvenMinus1)
        assertEquals(5, std.even)
        assertEquals(3, std.odd)
        assertEquals(0x2d, std.kdePredictor)
        assertEquals(0x0a, std.shuffleIndex)
        assertTrue(std.reservedBit67 == true)
        assertEquals(9, std.rawRecord.size)

        val run = parsed.cblockInfos[1]
        assertTrue(run.isRunBase)
        assertTrue(run.reservedBit19)
        assertEquals(0x23456, run.coffsetEndMod256k)
        assertEquals(0x0abcde1L, run.tweakIndexStart)
        assertEquals(2, run.keyTableIndex)
        assertEquals(0x2abcde, run.coffsetStart256k)
        assertNull(run.uoffsetStart)
        assertEquals(0, parsed.cblockInfoPadding.size)
        assertEquals(0, parsed.trailingPadding.size)
    }

    @Test
    fun acceptsOnlyZeroEndPaddingUpToFifteenBytes() {
        val parsed = NapsLayoutTables.parse(fixture(padded = true))
        assertNotNull(parsed)
        assertEquals(14, parsed!!.trailingPadding.size)

        val nonZeroPadding = fixture(padded = true).also { it[it.lastIndex] = 1 }
        assertNull(NapsLayoutTables.parse(nonZeroPadding))

        val tooMuchPadding = fixture(padded = true).copyOf(82)
        assertNull(NapsLayoutTables.parse(tooMuchPadding))
    }

    @Test
    fun rejectsTruncatedTableData() {
        assertNull(NapsLayoutTables.parse(fixture(padded = false).copyOf(65)))
    }


    @Test
    fun readsLargeCblockTableOnDemand() {
        // A large but realistic table size must not require one allocated object per record.
        val cblockCount = 450_700
        val word0 = 1L shl 32 // one uncompressed block
        val word1 = 1L or ((cblockCount - 2L) shl 24) // one outer block and CblockInfo count
        val end = 40L + cblockCount.toLong() * 9L
        val blob = ByteArray(end.toInt())
        putLe64(blob, 0, word0)
        putLe64(blob, 8, word1)

        val parsed = NapsLayoutTables.parse(blob)
        assertNotNull(parsed)
        parsed!!
        assertEquals(cblockCount, parsed.cblockInfos.size)
        assertEquals(1, parsed.u2cEntries.size)
        assertEquals(9, parsed.cblockInfos[cblockCount - 1].rawRecord.size)
    }

    private fun fixture(padded: Boolean): ByteArray {
        val contentSize = 66
        val bytes = ByteArray(if (padded) 80 else contentSize)

        val word0 = (0L) or // one file
            (1L shl 28) or // one shuffle pattern
            (2L shl 32) // two uncompressed blocks
        val word1 = 1L or // one outer block
            (0L shl 24) // two CblockInfo records
        putLe64(bytes, 0, word0)
        putLe64(bytes, 8, word1)

        // Outer digest table at 0x10.
        for (i in 0 until 8) bytes[16 + i] = (0x10 + i).toByte()
        // Shuffle-pattern table at 0x18.
        for (i in 0 until 8) bytes[24 + i] = (0x50 + i).toByte()
        // fidx at 0x20: five-byte LE offset + type.
        val fidxOffset = 0x0102030405L
        for (i in 0 until 5) bytes[32 + i] = (fidxOffset ushr (8 * i)).toByte()
        bytes[37] = 0x7e
        // u2c at 0x26: uint24 base followed by seven deltas.
        bytes[38] = 0x56
        bytes[39] = 0x34
        bytes[40] = 0x12
        for (i in 0 until 7) bytes[41 + i] = (i + 1).toByte()

        // STD: 72-bit LE record; reserved bits are preserved, not assigned semantics.
        var stdLow = 0x12345L or
            (1L shl 19) or
            (0x23456L shl 20) or
            (0x12345L shl 38) or
            (5L shl 55) or
            (3L shl 58) or
            (5L shl 61)
        val stdHigh = (5 shl 0) or (0x0a shl 4) or 0x08
        putLe64(bytes, 48, stdLow)
        bytes[56] = stdHigh.toByte()

        // RUN: end offset, run marker, tweak index, key table and 22-bit start page.
        val runLow = 0x23456L or
            (1L shl 18) or
            (1L shl 19) or
            (0x0abcde1L shl 20) or
            (2L shl 48) or
            ((0x2abcdeL.toLong() and 0x3fffL) shl 50)
        val runHigh = (0x2abcdeL ushr 14).toInt() and 0xff
        putLe64(bytes, 57, runLow)
        bytes[65] = runHigh.toByte()

        return bytes
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }
}
