package com.kytyps5.android.library

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuterPfsPlainReaderTest {
    @Test
    fun opensOnlyPlainNoAuthDebugOuterPfsAndReadsUrootFiles() {
        val packageFile = createFixture()
        try {
            val probe = PkgProbe.probe(packageFile)
            assertEquals(PkgProbeResult.Kind.PS5_FIH, probe.kind)
            assertEquals(0x30000L, probe.pfsSuperblockOffset)

            val reader = OuterPfsPlainReader.open(packageFile, probe)
            assertNotNull(reader)
            reader!!.use {
                assertEquals(0x10000L, it.packedImageSize)
                val files = it.listUrootFiles()
                assertNotNull(files)
                assertTrue(files!!.any { entry -> entry.name == "pfs_image.dat" })
                assertTrue(files.any { entry -> entry.name == "naps_pkg_layout.dat" })
                assertArrayEquals("NAPS_DATA".toByteArray(), it.readNapsLayout())
                assertArrayEquals("IMG!".toByteArray(), it.readPackedImageRegion(0L, 4))
                assertNull(it.readPackedImageRegion(0x10000L, 1))
                assertNull(it.openMappedInnerPfsReader())
            }
            val summary = OuterPfsInspector.inspect(packageFile, probe)
            assertEquals(OuterPfsInspectionState.NAPS_NOT_RECOGNIZED, summary.state)
            assertTrue(summary.fileNames.contains("pfs_image.dat"))
            assertTrue(summary.fileNames.contains("naps_pkg_layout.dat"))
            assertEquals(0x10000L, summary.packedImageSize)
            assertNull(summary.innerPfsEntryCount)
            assertNull(summary.innerPfsParamJsonReadable)
        } finally {
            packageFile.delete()
        }
    }

    @Test
    fun rejectsDebugPfsWithoutExplicitPlainNoAuthSeed() {
        val packageFile = createFixture(seed = "NOT-A-PLAIN-KEY!".toByteArray())
        try {
            val probe = PkgProbe.probe(packageFile)
            assertEquals(PkgProbeResult.Kind.PS5_FIH, probe.kind)
            assertNull(OuterPfsPlainReader.open(packageFile, probe))
            assertEquals(
                OuterPfsInspectionState.NOT_SUPPORTED,
                OuterPfsInspector.inspect(packageFile, probe).state,
            )
        } finally {
            packageFile.delete()
        }
    }

    @Test
    fun refusesRetailFihEvenWhenPfsBytesContainPlainMarker() {
        val packageFile = createFixture(signedByte = 0x80)
        try {
            val probe = PkgProbe.probe(packageFile)
            assertEquals(PkgProbeResult.Kind.PS5_FIH, probe.kind)
            assertNull(OuterPfsPlainReader.open(packageFile, probe))
        } finally {
            packageFile.delete()
        }
    }

    private fun createFixture(
        seed: ByteArray = "PPRPLAIN-NOAUTH!".toByteArray(),
        signedByte: Int = 0x00,
    ): File {
        require(seed.size == 16)
        val pfsOffset = 0x10000
        val pfsSize = 0x60000
        val cntOffset = pfsOffset + pfsSize
        val cntSize = 0x1000
        val bytes = ByteArray(cntOffset + cntSize)

        putMagic(bytes, 0, "FIH")
        bytes[0x05] = signedByte.toByte()
        putLe64(bytes, 0x10, pfsOffset.toLong())
        putLe64(bytes, 0x18, pfsSize.toLong())
        putLe64(bytes, 0x58, cntOffset.toLong())
        putLe64(bytes, 0xa0, cntSize.toLong())
        putMagic(bytes, cntOffset, "CNT")

        val superblockOffset = pfsOffset + 2 * BLOCK_SIZE
        putLe64(bytes, superblockOffset, 2L)
        putLe64(bytes, superblockOffset + 0x08, 20130315L)
        putLe16(bytes, superblockOffset + 0x1c, 0)
        putLe32(bytes, superblockOffset + 0x20, BLOCK_SIZE.toLong())
        putLe32(bytes, superblockOffset + 0x24, 0L)
        putLe64(bytes, superblockOffset + 0x28, 6L)
        putLe64(bytes, superblockOffset + 0x30, 5L)
        putLe64(bytes, superblockOffset + 0x38, 3L)
        putLe64(bytes, superblockOffset + 0x40, 1L)
        putLe64(bytes, superblockOffset + 0x48, 0L)
        seed.copyInto(bytes, superblockOffset + 0x370)

        val inodeTableOffset = pfsOffset + 3 * BLOCK_SIZE
        writeInode(bytes, inodeTableOffset, 2, 0x4000, 120L, 1, 4)
        writeInode(bytes, inodeTableOffset, 3, 0x8000, "NAPS_DATA".length.toLong(), 1, 5)
        writeInode(bytes, inodeTableOffset, 4, 0x8000, BLOCK_SIZE.toLong(), 1, 0)

        val directory = ByteArray(120)
        var cursor = 0
        cursor = writeDirent(directory, cursor, 2, 4, ".")
        cursor = writeDirent(directory, cursor, 2, 5, "..")
        cursor = writeDirent(directory, cursor, 3, 2, "naps_pkg_layout.dat")
        cursor = writeDirent(directory, cursor, 4, 2, "pfs_image.dat")
        check(cursor == directory.size)

        directory.copyInto(bytes, pfsOffset + 4 * BLOCK_SIZE)
        "NAPS_DATA".toByteArray().copyInto(bytes, pfsOffset + 5 * BLOCK_SIZE)
        "IMG!".toByteArray().copyInto(bytes, pfsOffset)

        return File.createTempFile("ps5-outer-pfs-", ".pkg").also { it.writeBytes(bytes) }
    }

    private fun writeInode(
        bytes: ByteArray,
        tableOffset: Int,
        inodeIndex: Int,
        mode: Int,
        size: Long,
        blockCount: Int,
        firstBlock: Int,
    ) {
        val offset = tableOffset + inodeIndex * 0x2c8
        putLe16(bytes, offset, mode)
        putLe64(bytes, offset + 8, size)
        putLe64(bytes, offset + 16, size)
        putLe32(bytes, offset + 0x60, blockCount.toLong())
        putLe32(bytes, offset + 0x64 + 32, firstBlock.toLong())
    }

    private fun writeDirent(
        bytes: ByteArray,
        offset: Int,
        inode: Int,
        type: Int,
        name: String,
    ): Int {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val rawSize = 16 + nameBytes.size
        val entrySize = (rawSize + 7) and -8
        putLe32(bytes, offset, inode.toLong())
        putLe32(bytes, offset + 4, type.toLong())
        putLe32(bytes, offset + 8, nameBytes.size.toLong())
        putLe32(bytes, offset + 12, entrySize.toLong())
        nameBytes.copyInto(bytes, offset + 16)
        return offset + entrySize
    }

    private fun putMagic(bytes: ByteArray, offset: Int, value: String) {
        bytes[offset] = 0x7f
        value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset + 1)
    }

    private fun putLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    companion object {
        private const val BLOCK_SIZE = 0x10000
    }
}
