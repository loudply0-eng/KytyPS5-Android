package com.kytyps5.android.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PfsImageReaderTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pfs-image-reader").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun readsTreeAndSmallFileFromPlaintextUnsigned32BitPfs() {
        val file = buildPfs("inner.pfs")
        PfsImageReader.open(file)?.use { reader ->
            val entries = reader.listEntries()
            assertNotNull(entries)
            assertEquals(listOf("sce_sys", "sce_sys/param.json"), entries!!.map { it.path })
            assertEquals(true, entries[0].directory)
            assertEquals(false, entries[1].directory)
            assertEquals("""{"titleName":"Synthetic PFS"}""", String(reader.readFile("sce_sys/param.json")!!))
            assertArrayEquals(
                """{"titleName":"Synthetic PFS"}""".toByteArray(),
                reader.readFile("SCE_SYS/PARAM.JSON"),
            )
        } ?: throw AssertionError("Expected supported PFS image")
    }

    @Test
    fun readsTreeAndSmallFileDirectlyFromMappedNapsImage() {
        val file = buildPfs("virtual-naps-source.pfs")
        val bytes = file.readBytes()
        val firstLength = 0x40000
        val blocks = listOf(
            mappedBlock(0L, 0L, firstLength),
            mappedBlock(firstLength.toLong(), firstLength.toLong(), bytes.size - firstLength),
        )
        val map = NapsLogicalBlockMap(
            blocks = blocks,
            logicalSize = bytes.size.toLong(),
            pageCount = 2,
            fileBoundaries = emptyList(),
        )
        val source = NapsMappedPfsImageSource(map) { offset, length ->
            if (offset < 0L || length < 0 || offset > bytes.size.toLong() ||
                length.toLong() > bytes.size.toLong() - offset
            ) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

        PfsImageReader.open(source)?.use { reader ->
            val entries = reader.listEntries()
            assertNotNull(entries)
            assertEquals(listOf("sce_sys", "sce_sys/param.json"), entries!!.map { it.path })
            assertArrayEquals(
                """{"titleName":"Synthetic PFS"}""".toByteArray(),
                reader.readFile("sce_sys/param.json"),
            )
        } ?: throw AssertionError("Expected supported mapped NAPS PFS source")
    }

    private fun mappedBlock(sourceOffset: Long, logicalOffset: Long, length: Int) =
        NapsLogicalBlock(
            sourceOffset = sourceOffset,
            sourceLength = length,
            firstChunkCompressedLength = 0,
            logicalOffset = logicalOffset,
            logicalLength = length,
            storage = NapsBlockStorage.STORED,
            aliasSourceBlock = -1,
            runRecordIndex = 0,
            evenSelector = 1,
            oddSelector = 0,
            kdePredictor = 0,
            shuffleIndex = 0,
            codecFlags = 0,
        )

    @Test
    fun readsFileUsingSingleIndirectBlockPointer() {
        val file = buildPfsWithSingleIndirect()
        PfsImageReader.open(file)?.use { reader ->
            val bytes = reader.readFile("sce_sys/large.bin", 13L * BLOCK_SIZE)
            assertNotNull(bytes)
            assertEquals((13L * BLOCK_SIZE).toInt(), bytes!!.size)
            assertEquals(5.toByte(), bytes[0])
            assertEquals(16.toByte(), bytes[(11L * BLOCK_SIZE).toInt()])
            assertEquals(0x5a.toByte(), bytes[(12L * BLOCK_SIZE).toInt()])
        } ?: throw AssertionError("Expected supported PFS image")
    }

    @Test
    fun readsFileUsingDoubleIndirectBlockPointers() {
        val file = buildPfsWithDoubleIndirect()
        PfsImageReader.open(file)?.use { reader ->
            val bytes = reader.readFile("sce_sys/large.bin", maxBytes = 5L * 1024L * 1024L)
            assertNotNull(bytes)
            assertEquals((1037L * 0x1000L).toInt(), bytes!!.size)
            assertEquals(1.toByte(), bytes[0])
            assertEquals(12.toByte(), bytes[(11L * 0x1000L).toInt()])
            assertEquals(13.toByte(), bytes[(12L * 0x1000L).toInt()])
            assertEquals(76.toByte(), bytes[(1035L * 0x1000L).toInt()])
            assertEquals(77.toByte(), bytes[(1036L * 0x1000L).toInt()])
        } ?: throw AssertionError("Expected supported PFS image")
    }

    private fun buildPfsWithDoubleIndirect(): File {
        val blockSize = 0x1000L
        val dataBlockCount = 1037L
        val dataStartBlock = 5L
        val firstIndirectDataBlock = dataStartBlock + 12L
        val leafOne = dataStartBlock + dataBlockCount
        val leafTwo = leafOne + 1L
        val root = leafTwo + 1L
        val totalBlocks = 1L + 1L + 1043L // superblock + inode table + data/pointer blocks
        val file = File(root, "double-indirect.pfs")

        val superRootEntries = dirents(
            Entry(0, 4, "."),
            Entry(0, 5, ".."),
            Entry(1, 3, "uroot"),
        )
        val rootEntries = dirents(
            Entry(1, 4, "."),
            Entry(0, 5, ".."),
            Entry(2, 3, "sce_sys"),
        )
        val sceSysEntries = dirents(
            Entry(2, 4, "."),
            Entry(1, 5, ".."),
            Entry(3, 2, "large.bin"),
        )

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(totalBlocks * blockSize)
            putLe64(raf, 0x00, 2L)
            putLe64(raf, 0x08, 20130315L)
            putLe16(raf, 0x1c, 0x0008)
            putLe32(raf, 0x20, blockSize)
            putLe32(raf, 0x24, 0L)
            putLe64(raf, 0x28, 1L)
            putLe64(raf, 0x30, 4L)
            putLe64(raf, 0x38, 1043L)
            putLe64(raf, 0x40, 1L)
            putLe64(raf, 0x48, 0L)

            fun writeInodeRecord(index: Int, mode: Int, size: Long, blocks: Long, direct: LongArray = LongArray(12), indirectRoot: Long = 0L) {
                val offset = blockSize + index.toLong() * INODE_SIZE
                putLe16(raf, offset, mode)
                putLe64(raf, offset + 8L, size)
                putLe64(raf, offset + 0x10L, size)
                putLe32(raf, offset + 0x60L, blocks)
                direct.forEachIndexed { slot, block ->
                    putLe32(raf, offset + 0x64L + slot * 4L, block)
                }
                putLe32(raf, offset + 0x94L, indirectRoot)
            }

            writeInodeRecord(0, INODE_DIR, superRootEntries.size.toLong(), 1L, longArrayOf(2L))
            writeInodeRecord(1, INODE_DIR, rootEntries.size.toLong(), 1L, longArrayOf(3L))
            writeInodeRecord(2, INODE_DIR, sceSysEntries.size.toLong(), 1L, longArrayOf(4L))
            val direct = LongArray(12) { slot -> dataStartBlock + slot }
            writeInodeRecord(
                3,
                INODE_FILE,
                dataBlockCount * blockSize,
                dataBlockCount + 3L,
                direct,
                root,
            )

            raf.seek(2L * blockSize); raf.write(superRootEntries)
            raf.seek(3L * blockSize); raf.write(rootEntries)
            raf.seek(4L * blockSize); raf.write(sceSysEntries)

            // File data blocks: 12 direct blocks, then 1024 + 1 through two leaf tables.
            repeat(dataBlockCount.toInt()) { logical ->
                val data = ByteArray(blockSize.toInt()) { ((logical % 240) + 1).toByte() }
                raf.seek((dataStartBlock + logical) * blockSize)
                raf.write(data)
            }

            // First leaf contains 1024 data block pointers; second leaf contains the last pointer.
            raf.seek(leafOne * blockSize)
            repeat(1024) { slot -> writeLe32(raf, firstIndirectDataBlock + slot) }
            raf.seek(leafTwo * blockSize)
            writeLe32(raf, firstIndirectDataBlock + 1024L)

            // Double-indirect root selects the leaf containing the requested data pointer.
            raf.seek(root * blockSize)
            writeLe32(raf, leafOne)
            writeLe32(raf, leafTwo)
        }
        return file
    }

    @Test
    fun refusesEncryptedSignedAndWideInodeImages() {
        val encrypted = buildPfs("encrypted.pfs", mode = 0x000c)
        val signed = buildPfs("signed.pfs", mode = 0x0009)
        val wide = buildPfs("wide.pfs", mode = 0x000a)
        assertNull(PfsImageReader.open(encrypted))
        assertNull(PfsImageReader.open(signed))
        assertNull(PfsImageReader.open(wide))
    }

    @Test
    fun rejectsMalformedDirectoryEntryBounds() {
        val file = buildPfs("bad-dirent.pfs")
        RandomAccessFile(file, "rw").use { raf ->
            // The uroot directory starts at block 3; make its first dirent run past the directory.
            raf.seek(3L * BLOCK_SIZE + 12L)
            raf.write(byteArrayOf(0x7f, 0x00, 0x00, 0x00))
        }
        PfsImageReader.open(file)?.use { reader ->
            assertNull(reader.listEntries())
        } ?: throw AssertionError("Expected valid superblock")
    }

    @Test
    fun rejectsUnsafePathsAndOversizedFileReads() {
        val file = buildPfs("paths.pfs")
        PfsImageReader.open(file)?.use { reader ->
            assertNull(reader.readFile("../param.json"))
            assertNull(reader.readFile("/sce_sys/param.json"))
            assertNull(reader.readFile("sce_sys/param.json", maxBytes = 4))
        } ?: throw AssertionError("Expected supported PFS image")
    }

    private fun buildPfs(name: String, mode: Int = 0x0008): File {
        val file = File(root, name)
        val json = """{"titleName":"Synthetic PFS"}""".toByteArray()
        val superRootEntries = dirents(
            Entry(0, 4, "."),
            Entry(0, 5, ".."),
            Entry(1, 3, "uroot"),
        )
        val rootEntries = dirents(
            Entry(1, 4, "."),
            Entry(0, 5, ".."),
            Entry(2, 3, "sce_sys"),
        )
        val sceSysEntries = dirents(
            Entry(2, 4, "."),
            Entry(1, 5, ".."),
            Entry(3, 2, "param.json"),
        )

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(6L * BLOCK_SIZE)
            // PFS superblock.
            putLe64(raf, 0x00, 2L)
            putLe64(raf, 0x08, 20130315L)
            putLe16(raf, 0x1c, mode)
            putLe32(raf, 0x20, BLOCK_SIZE)
            putLe32(raf, 0x24, 0)
            putLe64(raf, 0x28, 1L)
            putLe64(raf, 0x30, 4L)
            putLe64(raf, 0x38, 4L)
            putLe64(raf, 0x40, 1L)
            putLe64(raf, 0x48, 0L)

            writeInode(raf, 0, INODE_DIR, superRootEntries.size.toLong(), 2L)
            writeInode(raf, 1, INODE_DIR, rootEntries.size.toLong(), 3L)
            writeInode(raf, 2, INODE_DIR, sceSysEntries.size.toLong(), 4L)
            writeInode(raf, 3, INODE_FILE, json.size.toLong(), 5L)

            writeBlock(raf, 2, superRootEntries)
            writeBlock(raf, 3, rootEntries)
            writeBlock(raf, 4, sceSysEntries)
            raf.seek(5L * BLOCK_SIZE)
            raf.write(json)
        }
        return file
    }

    private fun buildPfsWithSingleIndirect(): File {
        val file = File(root, "single-indirect.pfs")
        val size = 13L * BLOCK_SIZE
        val superRootEntries = dirents(
            Entry(0, 4, "."),
            Entry(0, 5, ".."),
            Entry(1, 3, "uroot"),
        )
        val rootEntries = dirents(
            Entry(1, 4, "."),
            Entry(0, 5, ".."),
            Entry(2, 3, "sce_sys"),
        )
        val sceSysEntries = dirents(
            Entry(2, 4, "."),
            Entry(1, 5, ".."),
            Entry(3, 2, "large.bin"),
        )

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(19L * BLOCK_SIZE)
            putLe64(raf, 0x00, 2L)
            putLe64(raf, 0x08, 20130315L)
            putLe16(raf, 0x1c, 0x0008)
            putLe32(raf, 0x20, BLOCK_SIZE)
            putLe32(raf, 0x24, 0)
            putLe64(raf, 0x28, 1L)
            putLe64(raf, 0x30, 4L)
            putLe64(raf, 0x38, 17L)
            putLe64(raf, 0x40, 1L)
            putLe64(raf, 0x48, 0L)

            writeInode(raf, 0, INODE_DIR, superRootEntries.size.toLong(), 2L)
            writeInode(raf, 1, INODE_DIR, rootEntries.size.toLong(), 3L)
            writeInode(raf, 2, INODE_DIR, sceSysEntries.size.toLong(), 4L)

            val inodeOffset = BLOCK_SIZE + 3L * INODE_SIZE
            raf.seek(inodeOffset)
            raf.write(byteArrayOf(INODE_FILE.toByte(), (INODE_FILE ushr 8).toByte()))
            raf.seek(inodeOffset + 8L)
            writeLe64(raf, size)
            raf.seek(inodeOffset + 0x10L)
            writeLe64(raf, size)
            raf.seek(inodeOffset + 0x60L)
            writeLe32(raf, 14L) // 13 data blocks plus the indirect pointer block.
            for (index in 0 until 12) {
                raf.seek(inodeOffset + 0x64L + index * 4L)
                writeLe32(raf, 5L + index)
            }
            raf.seek(inodeOffset + 0x94L)
            writeLe32(raf, 17L)

            writeBlock(raf, 2, superRootEntries)
            writeBlock(raf, 3, rootEntries)
            writeBlock(raf, 4, sceSysEntries)
            for (index in 0 until 12) {
                writeBlock(raf, 5L + index, ByteArray(BLOCK_SIZE.toInt()) { (5 + index).toByte() })
            }
            raf.seek(17L * BLOCK_SIZE)
            writeLe32(raf, 18L)
            writeBlock(raf, 18, ByteArray(BLOCK_SIZE.toInt()) { 0x5a.toByte() })
        }
        return file
    }

    private data class Entry(val inode: Long, val type: Int, val name: String)

    private fun dirents(vararg entries: Entry): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val entrySize = ((16 + name.size + 1 + 7) / 8) * 8
            val bytes = ByteArray(entrySize)
            putLe32(bytes, 0, entry.inode)
            putLe32(bytes, 4, entry.type.toLong())
            putLe32(bytes, 8, name.size.toLong())
            putLe32(bytes, 12, entrySize.toLong())
            name.copyInto(bytes, 16)
            bytes[16 + name.size] = 0
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun writeBlock(raf: RandomAccessFile, block: Long, bytes: ByteArray) {
        raf.seek(block * BLOCK_SIZE)
        raf.write(bytes)
    }

    private fun writeInode(raf: RandomAccessFile, index: Int, mode: Int, size: Long, dataBlock: Long) {
        val offset = BLOCK_SIZE + index.toLong() * INODE_SIZE
        raf.seek(offset)
        raf.write(byteArrayOf(mode.toByte(), (mode ushr 8).toByte()))
        raf.seek(offset + 8L)
        writeLe64(raf, size)
        raf.seek(offset + 0x10L)
        writeLe64(raf, size)
        raf.seek(offset + 0x60L)
        writeLe32(raf, if (size == 0L) 0L else 1L)
        writeLe32(raf, dataBlock)
    }

    private fun putLe16(raf: RandomAccessFile, offset: Long, value: Int) {
        raf.seek(offset)
        raf.write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
    }

    private fun putLe32(raf: RandomAccessFile, offset: Long, value: Long) {
        raf.seek(offset)
        writeLe32(raf, value)
    }

    private fun writeLe32(raf: RandomAccessFile, value: Long) {
        raf.write(byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        ))
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { i -> bytes[offset + i] = (value ushr (i * 8)).toByte() }
    }

    private fun writeLe64(raf: RandomAccessFile, value: Long) {
        repeat(8) { i -> raf.write((value ushr (i * 8)).toInt()) }
    }

    private fun putLe64(raf: RandomAccessFile, offset: Long, value: Long) {
        raf.seek(offset)
        writeLe64(raf, value)
    }

    private companion object {
        const val BLOCK_SIZE = 0x10000L
        const val INODE_SIZE = 0xA8L
        const val INODE_DIR = 0x4000
        const val INODE_FILE = 0x8000
    }
}
