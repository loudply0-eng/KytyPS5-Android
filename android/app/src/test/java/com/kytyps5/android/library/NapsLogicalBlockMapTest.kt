package com.kytyps5.android.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class NapsLogicalBlockMapTest {
    @Test
    fun streamsStoredImageWithoutHoldingWholeImageInMemory() {
        val map = NapsLogicalBlockMap.parse(
            fixture(
                logicalLengths = listOf(0x40000),
                storage = listOf(Storage.STORED),
                fileStarts = listOf(0L),
            ),
            0x40000L,
        )!!
        val expected = ByteArray(0x40000) { (it and 0xff).toByte() }
        val output = ByteArrayOutputStream()
        val result = map.writeUncompressedImage(output) { offset, length ->
            assertEquals(0L, offset)
            assertEquals(0x40000, length)
            expected
        }
        assertEquals(NapsImageBuildResult.SUCCESS, result)
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun streamsZeroHolesAndAliasesAsLogicalBytes() {
        val holeMap = NapsLogicalBlockMap.parse(
            fixture(
                logicalLengths = listOf(0x10000),
                storage = listOf(Storage.ZERO_HOLE),
                fileStarts = listOf(0L),
            ),
            8L,
        )!!
        val holeOutput = ByteArrayOutputStream()
        assertEquals(
            NapsImageBuildResult.SUCCESS,
            holeMap.writeUncompressedImage(holeOutput) { _, _ ->
                throw AssertionError("Zero holes must not read packed bytes")
            },
        )
        assertEquals(0x10000, holeOutput.size())
        assertTrue(holeOutput.toByteArray().all { it == 0.toByte() })

        val aliasMap = NapsLogicalBlockMap.parse(
            fixture(
                logicalLengths = listOf(0x10000, 0x10000),
                storage = listOf(Storage.STORED, Storage.ALIAS),
                fileStarts = listOf(0L, 0x10000L),
                secondRunPage = 0,
            ),
            0x10000L,
        )!!
        val source = ByteArray(0x10000) { (it and 0xff).toByte() }
        val aliasOutput = ByteArrayOutputStream()
        assertEquals(
            NapsImageBuildResult.SUCCESS,
            aliasMap.writeUncompressedImage(aliasOutput) { offset, length ->
                assertEquals(0L, offset)
                assertEquals(0x10000, length)
                source
            },
        )
        assertArrayEquals(source + source, aliasOutput.toByteArray())
    }

    @Test
    fun refusesKrakenImageBeforeWritingPartialOutput() {
        val map = NapsLogicalBlockMap.parse(
            fixture(
                logicalLengths = listOf(0x10000),
                storage = listOf(Storage.KRAKEN),
                fileStarts = listOf(0L),
            ),
            0x200L,
        )!!
        val output = ByteArrayOutputStream()
        val result = map.writeUncompressedImage(output) { _, _ ->
            throw AssertionError("Kraken must be refused before reading payloads")
        }
        assertEquals(NapsImageBuildResult.NEEDS_KRAKEN, result)
        assertEquals(0, output.size())
    }

    @Test
    fun mapsOneStored256KiBBlockAndChecksIndexes() {
        val blob = fixture(
            logicalLengths = listOf(0x40000),
            storage = listOf(Storage.STORED),
            fileStarts = listOf(0L),
        )
        val map = NapsLogicalBlockMap.parse(blob, 0x40000L)
        assertNotNull(map)
        map!!
        assertEquals(1, map.blocks.size)
        assertEquals(0x40000L, map.logicalSize)
        assertEquals(0L, map.blocks.single().sourceOffset)
        assertEquals(0x40000, map.blocks.single().sourceLength)
        assertEquals(NapsBlockStorage.STORED, map.blocks.single().storage)
        assertEquals(0, map.blocks.single().runRecordIndex)
    }

    @Test
    fun mapsKrakenSpanWithoutDecodingIt() {
        val blob = fixture(
            logicalLengths = listOf(0x10000),
            storage = listOf(Storage.KRAKEN),
            fileStarts = listOf(0L),
        )
        val map = NapsLogicalBlockMap.parse(blob, 0x200L)
        assertNotNull(map)
        map!!
        val block = map.blocks.single()
        assertEquals(NapsBlockStorage.KRAKEN, block.storage)
        assertEquals(0x200, block.sourceLength)
        assertEquals(0x100, block.firstChunkCompressedLength)
        assertEquals(0x10000, block.logicalLength)
        assertEquals(0x02, block.codecFlags)

        val expected = ByteArray(0x200) { (it and 0xff).toByte() }
        val payload = map.readPayload(0) { offset, length ->
            assertEquals(0L, offset)
            assertEquals(0x200, length)
            expected
        }
        assertNotNull(payload)
        payload!!
        assertEquals(NapsBlockStorage.KRAKEN, payload.payloadBlock.storage)
        assertArrayEquals(expected, payload.payloadBytes)
    }

    @Test
    fun mapsZeroHoleWithoutReadingPlaceholderBytes() {
        val blob = fixture(
            logicalLengths = listOf(0x10000),
            storage = listOf(Storage.ZERO_HOLE),
            fileStarts = listOf(0L),
        )
        val map = NapsLogicalBlockMap.parse(blob, 8L)
        assertNotNull(map)
        map!!
        assertEquals(NapsBlockStorage.ZERO_HOLE, map.blocks.single().storage)
        assertEquals(8, map.blocks.single().sourceLength)
        val payload = map.readPayload(0) { _, _ -> throw AssertionError("Zero hole must not read source bytes") }
        assertNotNull(payload)
        payload!!
        assertNull(payload.payloadBytes)
        assertEquals(NapsBlockStorage.ZERO_HOLE, payload.payloadBlock.storage)
    }

    @Test
    fun resolvesAliasOnlyToEarlierBlockWithSamePackedOffset() {
        val blob = fixture(
            logicalLengths = listOf(0x10000, 0x10000),
            storage = listOf(Storage.STORED, Storage.ALIAS),
            fileStarts = listOf(0L, 0x10000L),
            secondRunPage = 0,
        )
        val map = NapsLogicalBlockMap.parse(blob, 0x10000L)
        assertNotNull(map)
        map!!
        assertEquals(2, map.blocks.size)
        assertEquals(NapsBlockStorage.ALIAS, map.blocks[1].storage)
        assertEquals(0, map.blocks[1].aliasSourceBlock)
        assertEquals(0L, map.blocks[1].sourceOffset)
        val payloadBytes = ByteArray(0x10000) { (it and 0xff).toByte() }
        val payload = map.readPayload(1) { offset, length ->
            assertEquals(0L, offset)
            assertEquals(0x10000, length)
            payloadBytes
        }
        assertNotNull(payload)
        payload!!
        assertEquals(1, payload.logicalBlockIndex)
        assertEquals(0, payload.payloadBlockIndex)
        assertEquals(NapsBlockStorage.ALIAS, payload.logicalBlock.storage)
        assertEquals(NapsBlockStorage.STORED, payload.payloadBlock.storage)
        assertArrayEquals(payloadBytes, payload.payloadBytes)
    }

    @Test
    fun rejectsOutOfBoundsPackedPayloadAndBadU2cAnchor() {
        val blob = fixture(
            logicalLengths = listOf(0x10000),
            storage = listOf(Storage.KRAKEN),
            fileStarts = listOf(0L),
        )
        assertNull(NapsLogicalBlockMap.parse(blob, 0x100L))

        val badU2c = fixture(
            logicalLengths = listOf(0x10000),
            storage = listOf(Storage.KRAKEN),
            fileStarts = listOf(0L),
            u2cIndex = 0,
        )
        assertNull(NapsLogicalBlockMap.parse(badU2c, 0x200L))
    }

    @Test
    fun rejectsSparseFidxMarkersUntilSparseDialectExists() {
        val blob = fixture(
            logicalLengths = listOf(0x10000),
            storage = listOf(Storage.KRAKEN),
            fileStarts = listOf(0L),
            sparseFirstFileMarker = true,
        )
        assertNull(NapsLogicalBlockMap.parse(blob, 0x200L))
    }

    private enum class Storage { STORED, KRAKEN, ZERO_HOLE, ALIAS }

    /**
     * Creates a compact contiguous NAPS image with one or more 128 KiB-or-smaller STD spans,
     * except the full 256 KiB stored case. The single fidx terminal and u2c anchor are explicit.
     */
    private fun fixture(
        logicalLengths: List<Int>,
        storage: List<Storage>,
        fileStarts: List<Long>,
        secondRunPage: Int = -1,
        u2cIndex: Int = 1,
        sparseFirstFileMarker: Boolean = false,
    ): ByteArray {
        require(logicalLengths.isNotEmpty() && logicalLengths.size == storage.size)
        require(logicalLengths.all { it in 1..0x40000 })
        val blockCount = logicalLengths.size
        val fileCount = fileStarts.size + 1
        val uBlocks = ((logicalLengths.sum().toLong() + 0x3ffffL) / 0x40000L).toInt()
        require(uBlocks == 1) { "Test fixtures currently use one logical 256 KiB page" }
        val cblockCount = blockCount + 2 + if (secondRunPage >= 0) 1 else 0

        val shuffleCount = 0
        val outerCount = 1
        val u2cCount = (uBlocks + 8) / 8
        val fidxOffset = 16 + outerCount * 8
        val u2cOffset = fidxOffset + fileCount * 6
        val rawCblock = (shuffleCount + outerCount) * 8 + u2cCount * 10 +
            (fileCount - 1) * 6 + 29
        val cblockOffset = rawCblock and -8
        val endOffset = cblockOffset + cblockCount * 9
        val blob = ByteArray(endOffset)

        val word0 = ((fileCount - 1).toLong() and 0xffffffL) or
            ((uBlocks.toLong() and 0xffffffL) shl 32)
        val word1 = (outerCount.toLong() and 0xffffffL) or
            (((cblockCount - 2).toLong() and 0xffffffL) shl 24)
        putLe64(blob, 0, word0)
        putLe64(blob, 8, word1)

        var fidx = fidxOffset
        for ((index, start) in fileStarts.withIndex()) {
            putLe40(blob, fidx, start)
            blob[fidx + 5] = if (sparseFirstFileMarker && index == 0) 0x40 else 0
            fidx += 6
        }
        val totalLogical = logicalLengths.sum().toLong()
        putLe40(blob, fidx, totalLogical)
        blob[fidx + 5] = 0x40

        // One u2c entry, referencing the first STD (or the explicitly requested wrong index).
        putLe24(blob, u2cOffset, u2cIndex)

        val records = ArrayList<ByteArray>()
        records += run(startPage = 0, endModulo = 0)
        var logicalStart = 0L
        var sourceCursor = 0L
        for (index in logicalLengths.indices) {
            val length = logicalLengths[index]
            val mode = storage[index]
            val nextSource = when (mode) {
                Storage.STORED -> sourceCursor + length
                Storage.KRAKEN -> sourceCursor + 0x200
                Storage.ZERO_HOLE -> sourceCursor + 8
                Storage.ALIAS -> sourceCursor
            }
            val terminalModulo = (logicalStart + length) and 0x3ffffL
            val cOffsetModulo = sourceCursor and 0x3ffffL
            val nextCoff = nextSource and 0x3ffffL
            val evenMinusOne: Int
            val even: Int
            val odd: Int
            when (mode) {
                Storage.STORED -> {
                    evenMinusOne = minOf(length, 0x20000) - 1
                    even = 1
                    odd = if (length > 0x20000) 1 else 0
                }
                Storage.KRAKEN -> {
                    evenMinusOne = 0xff
                    even = 4
                    odd = 0
                }
                Storage.ZERO_HOLE -> {
                    evenMinusOne = 7
                    even = 1
                    odd = 0
                }
                Storage.ALIAS -> {
                    evenMinusOne = 0xff
                    even = 4
                    odd = 0
                }
            }
            val followingIsRun = mode == Storage.STORED && index == 0 &&
                storage.size > 1 && storage[1] == Storage.ALIAS
            records += std(
                coffset = cOffsetModulo.toInt(),
                uoffset = logicalStart.toInt() and 0x3ffff,
                evenMinusOne = evenMinusOne,
                even = even,
                odd = odd,
            )
            if (followingIsRun) {
                records += run(startPage = secondRunPage, endModulo = nextCoff.toInt())
                sourceCursor = 0L
            } else {
                sourceCursor = nextSource
            }
            logicalStart += length
        }
        records += std(
            coffset = sourceCursor.toInt() and 0x3ffff,
            uoffset = logicalStart.toInt() and 0x3ffff,
            evenMinusOne = 0,
            even = 0,
            odd = 0,
        )

        require(records.size == cblockCount) { "Fixture CblockInfo count mismatch: ${records.size} != $cblockCount" }
        records.forEachIndexed { index, record ->
            record.copyInto(blob, cblockOffset + index * 9)
        }
        return blob
    }

    private fun run(startPage: Int, endModulo: Int): ByteArray {
        var low = (endModulo.toLong() and 0x3ffffL) or
            (1L shl 18) or
            ((0L and 0x0fffffffL) shl 20) or
            (0L shl 48) or
            (((startPage.toLong() and 0x3fffL) shl 50))
        val high = (startPage ushr 14) and 0xff
        return ByteArray(9).also {
            putLe64(it, 0, low)
            it[8] = high.toByte()
        }
    }

    private fun std(
        coffset: Int,
        uoffset: Int,
        evenMinusOne: Int,
        even: Int,
        odd: Int,
    ): ByteArray {
        var low = (coffset.toLong() and 0x3ffffL) or
            ((uoffset.toLong() and 0x3ffffL) shl 20) or
            ((evenMinusOne.toLong() and 0x1ffffL) shl 38) or
            ((even.toLong() and 7L) shl 55) or
            ((odd.toLong() and 7L) shl 58)
        return ByteArray(9).also { putLe64(it, 0, low) }
    }

    private fun putLe24(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
    }

    private fun putLe40(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 5) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }
}
