package com.kytyps5.android.library

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun recognizesFihWithCanonicalPfsOffsetAndValidRange() {
        val file = writeFih("valid.pkg", 0x10000L, 0x100L, 0x10100)
        assertEquals(PkgProbeResult.Kind.PS5_FIH, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsNonCanonicalPfsOffset() {
        val file = writeFih("wrong-offset.pkg", 0x10001L, 0xFFL, 0x10100)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsPfsRangeBeyondFile() {
        val file = writeFih("truncated-pfs.pkg", 0x10000L, 0x200L, 0x10100)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsEmptyPfsRange() {
        val file = writeFih("empty-pfs.pkg", 0x10000L, 0L, 0x10000)
        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsPfsRangeOverlappingFihHeader() {
        val file = writeFih("header-overlap.pkg", 0x80L, 0x40L, 0x10100)
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

    private fun writeFih(name: String, offset: Long, size: Long, fileSize: Int): File {
        val bytes = ByteArray(fileSize)
        putMagic(bytes, "FIH")
        putLe64(bytes, 0x10, offset)
        putLe64(bytes, 0x18, size)
        return File(root, name).apply { writeBytes(bytes) }
    }

    private fun putMagic(bytes: ByteArray, suffix: String) {
        bytes[0] = 0x7f
        bytes[1] = suffix[0].code.toByte()
        bytes[2] = suffix[1].code.toByte()
        bytes[3] = suffix[2].code.toByte()
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
}
