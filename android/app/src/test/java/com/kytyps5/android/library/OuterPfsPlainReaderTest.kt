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
    fun inspectsInnerPfsMetadataFromCompleteSyntheticDebugPkg() {
        val packageFile = createMappedInnerPfsFixture()
        try {
            val probe = PkgProbe.probe(packageFile)
            assertEquals(PkgProbeResult.Kind.PS5_FIH, probe.kind)
            // The inner PFS superblock is at the beginning of pfs_image.dat. The outer
            // superblock is later in the data-first segment and is identified by its seed.
            assertEquals(0x60000L, probe.pfsSuperblockOffset)

            val outer = OuterPfsPlainReader.open(packageFile, probe)
            assertNotNull(outer)
            outer!!.use { reader ->
                val inner = reader.openMappedInnerPfsReader()
                assertNotNull(inner)
                inner!!.use { pfs ->
                    val entries = pfs.listEntries()
                    assertNotNull(entries)
                    assertEquals(listOf("sce_sys", "sce_sys/param.json"), entries!!.map { it.path })
                    assertArrayEquals(
                        """{"titleName":"Mapped PKG Fixture"}""".toByteArray(),
                        pfs.readFile("sce_sys/param.json"),
                    )
                }
            }

            val summary = OuterPfsInspector.inspect(packageFile, probe)
            assertEquals(OuterPfsInspectionState.MAPPED, summary.state)
            assertEquals(2, summary.innerPfsEntryCount)
            assertEquals(true, summary.innerPfsParamJsonReadable)
            assertTrue(summary.innerPfsPaths.contains("sce_sys/param.json"))
        } finally {
            packageFile.delete()
        }
    }

    @Test
    fun readsInnerParamSfoMetadataWhenParamJsonIsAbsent() {
        val packageFile = createMappedInnerPfsFixture(useSfo = true)
        try {
            val probe = PkgProbe.probe(packageFile)
            assertEquals(PkgProbeResult.Kind.PS5_FIH, probe.kind)

            val summary = OuterPfsInspector.inspect(packageFile, probe)
            assertEquals(OuterPfsInspectionState.MAPPED, summary.state)
            assertEquals(2, summary.innerPfsEntryCount)
            assertEquals(false, summary.innerPfsParamJsonReadable)
            assertEquals(true, summary.innerPfsParamSfoReadable)
            assertEquals("Mapped SFO Fixture", summary.innerPfsTitle)
            assertEquals("PPSA99999", summary.innerPfsTitleId)
            assertEquals("01.001", summary.innerPfsApplicationVersion)
            assertTrue(summary.innerPfsPaths.contains("sce_sys/param.sfo"))
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

    private fun createMappedInnerPfsFixture(useSfo: Boolean = false): File {
        val pfsOffset = 0x10000
        val pfsSize = (9 * BLOCK_SIZE).toInt()
        val superblockIndex = 5
        val cntOffset = pfsOffset + pfsSize
        val inner = createInnerPfsImage(useSfo)
        val packedImage = ByteArray(0x40000).also { inner.copyInto(it) }
        val napsLayout = NapsLogicalBlockMapTest().singlePageStoredLayoutFixture()
        val bytes = ByteArray(cntOffset + 0x1000)

        putMagic(bytes, 0, "FIH")
        bytes[0x05] = 0x00
        putLe64(bytes, 0x10, pfsOffset.toLong())
        putLe64(bytes, 0x18, pfsSize.toLong())
        putLe64(bytes, 0x58, cntOffset.toLong())
        putLe64(bytes, 0xa0, 0x1000L)
        putMagic(bytes, cntOffset, "CNT")

        val superblockOffset = pfsOffset + superblockIndex * BLOCK_SIZE.toInt()
        putLe64(bytes, superblockOffset, 2L)
        putLe64(bytes, superblockOffset + 0x08, 20130315L)
        putLe16(bytes, superblockOffset + 0x1c, 0)
        putLe32(bytes, superblockOffset + 0x20, BLOCK_SIZE.toLong())
        putLe32(bytes, superblockOffset + 0x24, 0L)
        putLe64(bytes, superblockOffset + 0x28, 9L)
        putLe64(bytes, superblockOffset + 0x30, 5L)
        putLe64(bytes, superblockOffset + 0x38, 3L)
        putLe64(bytes, superblockOffset + 0x40, 1L)
        putLe64(bytes, superblockOffset + 0x48, 0L)
        "PPRPLAIN-NOAUTH!".toByteArray(Charsets.US_ASCII)
            .copyInto(bytes, superblockOffset + 0x370)

        val inodeTableOffset = pfsOffset + 6 * BLOCK_SIZE.toInt()
        writeInode(bytes, inodeTableOffset, 2, 0x4000, 120L, 1, 7)
        writeInode(bytes, inodeTableOffset, 3, 0x8000, napsLayout.size.toLong(), 1, 8)
        writeInodeWithBlocks(
            bytes, inodeTableOffset, 4, 0x8000, packedImage.size.toLong(),
            listOf(0, 1, 2, 3),
        )

        val directory = ByteArray(120)
        var cursor = 0
        cursor = writeDirent(directory, cursor, 2, 4, ".")
        cursor = writeDirent(directory, cursor, 2, 5, "..")
        cursor = writeDirent(directory, cursor, 3, 2, "naps_pkg_layout.dat")
        cursor = writeDirent(directory, cursor, 4, 2, "pfs_image.dat")
        check(cursor == directory.size)

        packedImage.copyInto(bytes, pfsOffset)
        directory.copyInto(bytes, pfsOffset + 7 * BLOCK_SIZE.toInt())
        napsLayout.copyInto(bytes, pfsOffset + 8 * BLOCK_SIZE.toInt())
        return File.createTempFile("ps5-mapped-inner-", ".pkg").also { it.writeBytes(bytes) }
    }

    private fun createInnerPfsImage(useSfo: Boolean): ByteArray {
        val blockSize = 0x1000
        val metadataName = if (useSfo) "param.sfo" else "param.json"
        val metadataBytes = if (useSfo) {
            SfoFixture.build(
                "TITLE" to "Mapped SFO Fixture",
                "TITLE_ID" to "PPSA99999",
                "APP_VER" to "01.001",
            )
        } else {
            """{"titleName":"Mapped PKG Fixture"}""".toByteArray()
        }
        val superRoot = ByteArray(72)
        var cursor = 0
        cursor = writeDirent(superRoot, cursor, 0, 4, ".")
        cursor = writeDirent(superRoot, cursor, 0, 5, "..")
        cursor = writeDirent(superRoot, cursor, 1, 3, "uroot")

        val root = ByteArray(72)
        cursor = 0
        cursor = writeDirent(root, cursor, 1, 4, ".")
        cursor = writeDirent(root, cursor, 0, 5, "..")
        cursor = writeDirent(root, cursor, 2, 3, "sce_sys")

        val sceSys = ByteArray(80)
        cursor = 0
        cursor = writeDirent(sceSys, cursor, 2, 4, ".")
        cursor = writeDirent(sceSys, cursor, 1, 5, "..")
        cursor = writeDirent(sceSys, cursor, 3, 2, metadataName)

        val image = ByteArray(6 * blockSize)
        putLe64(image, 0, 2L)
        putLe64(image, 0x08, 20130315L)
        putLe16(image, 0x1c, 0x0008)
        putLe32(image, 0x20, blockSize.toLong())
        putLe32(image, 0x24, 0L)
        putLe64(image, 0x28, 1L)
        putLe64(image, 0x30, 4L)
        putLe64(image, 0x38, 4L)
        putLe64(image, 0x40, 1L)
        putLe64(image, 0x48, 0L)

        val inodeTable = blockSize
        writeInnerInode(image, inodeTable, 0, 0x4000, superRoot.size.toLong(), 2)
        writeInnerInode(image, inodeTable, 1, 0x4000, root.size.toLong(), 3)
        writeInnerInode(image, inodeTable, 2, 0x4000, sceSys.size.toLong(), 4)
        writeInnerInode(image, inodeTable, 3, 0x8000, metadataBytes.size.toLong(), 5)

        superRoot.copyInto(image, 2 * blockSize)
        root.copyInto(image, 3 * blockSize)
        sceSys.copyInto(image, 4 * blockSize)
        metadataBytes.copyInto(image, 5 * blockSize)
        return image
    }

    private fun writeInnerInode(
        bytes: ByteArray,
        inodeTableOffset: Int,
        inodeIndex: Int,
        mode: Int,
        size: Long,
        block: Int,
    ) {
        val offset = inodeTableOffset + inodeIndex * 0xA8
        putLe16(bytes, offset, mode)
        putLe32(bytes, offset + 4, 0L)
        putLe64(bytes, offset + 8, size)
        putLe32(bytes, offset + 0x60, 1L)
        putLe32(bytes, offset + 0x64, block.toLong())
    }

    private fun writeInodeWithBlocks(
        bytes: ByteArray,
        tableOffset: Int,
        inodeIndex: Int,
        mode: Int,
        size: Long,
        blocks: List<Int>,
    ) {
        val offset = tableOffset + inodeIndex * 0x2c8
        putLe16(bytes, offset, mode)
        putLe64(bytes, offset + 8, size)
        putLe64(bytes, offset + 16, size)
        putLe32(bytes, offset + 0x60, blocks.size.toLong())
        blocks.forEachIndexed { slot, block ->
            putLe32(bytes, offset + 0x64 + slot * 36 + 32, block.toLong())
        }
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
