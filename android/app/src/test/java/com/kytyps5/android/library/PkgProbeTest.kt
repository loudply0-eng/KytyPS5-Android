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
    fun recognizesFihAndValidatesPfsRange() {
        val file = File(root, "game.pkg")
        val bytes = ByteArray(0x100)
        bytes[0] = 0x7f
        bytes[1] = 'F'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = 'H'.code.toByte()
        putLe64(bytes, 0x10, 0x80)
        putLe64(bytes, 0x18, 0x40)
        file.writeBytes(bytes)

        assertEquals(PkgProbeResult.Kind.PS5_FIH, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsFihPfsRangeBeyondFile() {
        val file = File(root, "broken.pkg")
        val bytes = ByteArray(0x100)
        bytes[0] = 0x7f
        bytes[1] = 'F'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = 'H'.code.toByte()
        putLe64(bytes, 0x10, 0xF0)
        putLe64(bytes, 0x18, 0x40)
        file.writeBytes(bytes)

        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun recognizesLihWithoutGuessingItsInternalLayout() {
        val file = File(root, "patch.pkg")
        val bytes = ByteArray(4)
        bytes[0] = 0x7f
        bytes[1] = 'L'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = 'H'.code.toByte()
        file.writeBytes(bytes)

        assertEquals(PkgProbeResult.Kind.PS5_LIH, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsEmptyPfsRange() {
        val file = File(root, "empty-pfs.pkg")
        val bytes = ByteArray(0x100)
        bytes[0] = 0x7f
        bytes[1] = 'F'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = 'H'.code.toByte()
        putLe64(bytes, 0x10, 0x80)
        putLe64(bytes, 0x18, 0)
        file.writeBytes(bytes)

        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun rejectsPfsRangeOverlappingFihHeader() {
        val file = File(root, "header-overlap.pkg")
        val bytes = ByteArray(0x100)
        bytes[0] = 0x7f
        bytes[1] = 'F'.code.toByte()
        bytes[2] = 'I'.code.toByte()
        bytes[3] = 'H'.code.toByte()
        putLe64(bytes, 0x10, 0x10)
        putLe64(bytes, 0x18, 0x40)
        file.writeBytes(bytes)

        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    @Test
    fun truncatedFihIsMalformed() {
        val file = File(root, "truncated.pkg")
        file.writeBytes(byteArrayOf(0x7f, 'F'.code.toByte(), 'I'.code.toByte(), 'H'.code.toByte()))

        assertEquals(PkgProbeResult.Kind.MALFORMED_PS5_PKG, PkgProbe.probe(file).kind)
    }

    private fun putLe64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = (value ushr (i * 8)).toByte()
        }
    }
}
