package com.kytyps5.android.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class PkgProbeTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ps5-pkg-probe").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun rejectsNonPkgFile() {
        val file = File(root, "game.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertEquals(PkgProbeResult.Kind.NOT_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun recognizesFihWithValidPfsAndCntSegments() {
        val file = writeFih("valid.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L)
        val result = PkgProbe.probe(file)
        assertEquals(PkgProbeResult.Kind.PS5_FIH, result.kind)
        assertEquals(0x20000L, result.cntOffset)
        assertEquals(0x5A0L, result.cntSize)
        assertEquals(0x10000L, result.pfsSize)
        assertEquals(false, result.pfsEncrypted)
        assertEquals(0x10000L, result.pfsSuperblock?.blockSize)
        assertEquals(1L, result.pfsSuperblock?.inodeCount)
        assertEquals(8L, result.pfsSuperblock?.dataBlockCount)
        assertEquals(1L, result.pfsSuperblock?.inodeBlockCount)
        assertEquals(0L, result.pfsSuperblock?.superRootInode)
        assertEquals(true, result.pfsSuperblock?.caseInsensitive)
        assertEquals(0x00, result.fihSignedByte)
    }

    @Test
    fun preservesRetailSignedByteAndReadsPlaintextPfsSuperblock() {
        val file = writeFih("retail-marker.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L, signedByte = 0x80)
        val result = PkgProbe.probe(file)
        assertEquals(PkgProbeResult.Kind.PS5_FIH, result.kind)
        assertEquals(0x80, result.fihSignedByte)
        assertEquals(false, result.pfsEncrypted)
        assertNotNull(result.pfsSuperblock)
    }

    @Test
    fun recognizesDebugPfsEncryptionModeBit() {
        val file = writeFih("encrypted-debug-pfs.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L, pfsMode = 0x000c)
        val result = PkgProbe.probe(file)
        assertEquals(PkgProbeResult.Kind.PS5_FIH, result.kind)
        assertEquals(true, result.pfsEncrypted)
    }

    @Test
    fun recognizesRetailFihWithPlaintextSuperblockAndEncryptedDataBlocks() {
        val file = writeFih("retail-encrypted-pfs.pkg", 0x10000L, 0x20000L, 0x30000L, 0x5A0L,
            signedByte = 0x80, pfsMode = 0x000c)
        RandomAccessFile(file, "rw").use { raf ->
            // Block zero remains plaintext; the following data block is ciphertext.
            raf.seek(0x20000L)
            raf.write(ByteArray(0x10000) { 0xA5.toByte() })
        }

        val result = PkgProbe.probe(file)
        assertEquals(PkgProbeResult.Kind.PS5_FIH, result.kind)
        assertEquals(0x80, result.fihSignedByte)
        assertEquals(0x20000L, result.pfsSize)
        assertNotNull(result.pfsSuperblock)
        assertEquals(true, result.pfsEncrypted)
    }

    @Test
    fun rejectsInvalidPfsBlockSize() {
        val file = writeFih("invalid-pfs-block-size.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0x10000L + 0x20L)
            raf.write(byteArrayOf(0x00, 0x80.toByte(), 0x01, 0x00)) // 0x18000, not a power of two.
        }
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsPfsSuperRootOutsideInodeTable() {
        val file = writeFih("bad-pfs-superroot.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0x10000L + 0x48L)
            raf.write(ByteArray(8) { if (it == 0) 1 else 0 })
        }
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsUnknownFihSignedByte() {
        val file = writeFih("unknown-signed-byte.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L, signedByte = 0x40)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }
    @Test
    fun readMetadataConnectsValidatedFihToBoundedCntReader() {
        val cntOffset = 0x20000L
        val file = writeFih("metadata.pkg", 0x10000L, 0x10000L, cntOffset, 0x800L)
        val json = """{"title":"Synthetic FIH"}""".toByteArray(Charsets.UTF_8)
        val names = byteArrayOf(0) + "sce_sys/param.json".toByteArray(Charsets.UTF_8) + byteArrayOf(0)

        RandomAccessFile(file, "rw").use { raf ->
            writeBe32(raf, cntOffset + 0x10L, 2)
            writeBe32(raf, cntOffset + 0x18L, 0x5a0)
            // Name-table entry.
            writeBe32(raf, cntOffset + 0x5a0L, 0x0200)
            writeBe32(raf, cntOffset + 0x5a0L + 0x10L, 0x700)
            writeBe32(raf, cntOffset + 0x5a0L + 0x14L, 0x40)
            // param.json entry.
            writeBe32(raf, cntOffset + 0x5c0L, 0x2000)
            writeBe32(raf, cntOffset + 0x5c0L + 0x04L, 1)
            writeBe32(raf, cntOffset + 0x5c0L + 0x10L, 0x740)
            writeBe32(raf, cntOffset + 0x5c0L + 0x14L, json.size.toLong())
            raf.seek(cntOffset + 0x700L)
            raf.write(names)
            raf.seek(cntOffset + 0x740L)
            raf.write(json)
        }

        val metadata = PkgProbe.readMetadata(file)
        assertNotNull(metadata)
        assertEquals(2, metadata!!.entryCount)
        assertEquals("sce_sys/param.json", metadata.entries[1].name)
        assertEquals("""{"title":"Synthetic FIH"}""", metadata.paramJson)
    }

    @Test
    fun readMetadataRejectsMalformedCntEntryTableInsideValidFih() {
        val cntOffset = 0x20000L
        val file = writeFih("malformed-cnt-table.pkg", 0x10000L, 0x10000L, cntOffset, 0x800L)
        RandomAccessFile(file, "rw").use { raf ->
            // Two records at 0x7f0 cannot fit inside the declared 0x800-byte CNT.
            writeBe32(raf, cntOffset + 0x10L, 2)
            writeBe32(raf, cntOffset + 0x18L, 0x7f0)
        }

        assertEquals(null, PkgProbe.readMetadata(file))
    }

    @Test
    fun rejectsNonCanonicalPfsOffset() {
        val file = writeFih("wrong-offset.pkg", 0x10001L, 0xFFL, 0x10100L, 0x5A0L)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsPfsRangeBeyondFile() {
        val file = writeFih("truncated-pfs.pkg", 0x10000L, 0x20000L, 0x30000L, 0x5A0L, fileSizeOverride = 0x2ffff)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsEmptyPfsRange() {
        val file = writeFih("empty-pfs.pkg", 0x10000L, 0L, 0x10000L, 0x5A0L)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsCntOverlappingPfsSegment() {
        val file = writeFih("overlap.pkg", 0x10000L, 0x20000L, 0x20000L, 0x5A0L, fileSizeOverride = 0x305a0)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsCntSegmentOutsideFile() {
        val file = writeFih("cnt-outside.pkg", 0x10000L, 0x10000L, 0x20000L, 0x1000L, fileSizeOverride = 0x20500)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsCntSegmentShorterThanMinimumHeader() {
        val file = writeFih("short-cnt.pkg", 0x10000L, 0x10000L, 0x20000L, 0x100L)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsWrongCntMagic() {
        val file = writeFih("wrong-cnt.pkg", 0x10000L, 0x10000L, 0x20000L, 0x5A0L, cntMagic = "BAD")
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsTruncatedFihHeader() {
        val file = File(root, "truncated.pkg")
        val bytes = ByteArray(0x100)
        putMagic(bytes, "FIH")
        file.writeBytes(bytes)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun recognizesLihSignatureWithoutClaimingLayoutValidation() {
        val file = File(root, "patch.pkg")
        val bytes = ByteArray(4)
        putMagic(bytes, "LIH")
        file.writeBytes(bytes)
        assertEquals(PkgProbeResult.Kind.PS5_LIH, PkgProbe.probe(file).kind)
    }

    private fun writeFih(
        name: String,
        pfsOffset: Long,
        pfsSize: Long,
        cntOffset: Long,
        cntSize: Long,
        cntMagic: String = "CNT",
        fileSizeOverride: Int? = null,
        signedByte: Int = 0x00,
        pfsMode: Int = 0x0008,
    ): File {
        val fileSize = fileSizeOverride ?: (cntOffset + cntSize).coerceAtLeast(0x10000L).toInt()
        val bytes = ByteArray(fileSize)
        putMagic(bytes, "FIH")
        bytes[0x05] = signedByte.toByte()
        putLe64(bytes, 0x10, pfsOffset)
        putLe64(bytes, 0x18, pfsSize)
        putLe64(bytes, 0x58, cntOffset)
        putLe64(bytes, 0xA0, cntSize)
        if (pfsOffset >= 0 && pfsOffset + 0x24L <= bytes.size.toLong()) {
            putLe64(bytes, pfsOffset.toInt(), 2L)
            putLe64(bytes, pfsOffset.toInt() + 0x08, 20130315L)
            putLe16(bytes, pfsOffset.toInt() + 0x1c, pfsMode)
            putLe32(bytes, pfsOffset.toInt() + 0x20, 0x10000L)
            putLe32(bytes, pfsOffset.toInt() + 0x24, 0L)
            putLe64(bytes, pfsOffset.toInt() + 0x28, 1L)
            putLe64(bytes, pfsOffset.toInt() + 0x30, 1L)
            putLe64(bytes, pfsOffset.toInt() + 0x38, 8L)
            putLe64(bytes, pfsOffset.toInt() + 0x40, 1L)
            putLe64(bytes, pfsOffset.toInt() + 0x48, 0L)
        }
        if (cntOffset >= 0 && cntOffset + 4 <= bytes.size.toLong()) {
            putMagicAt(bytes, cntOffset.toInt(), cntMagic)
        }
        return File(root, name).apply { writeBytes(bytes) }
    }

    private fun putMagic(bytes: ByteArray, suffix: String) = putMagicAt(bytes, 0, suffix)

    private fun putMagicAt(bytes: ByteArray, offset: Int, suffix: String) {
        bytes[offset] = 0x7f
        bytes[offset + 1] = suffix[0].code.toByte()
        bytes[offset + 2] = suffix[1].code.toByte()
        bytes[offset + 3] = suffix[2].code.toByte()
    }

    private fun writeBe32(raf: RandomAccessFile, offset: Long, value: Long) {
        raf.seek(offset)
        raf.write(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        ))
    }

    private fun putLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
}
