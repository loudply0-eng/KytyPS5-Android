package com.kytyps5.android.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PkgCntReaderTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ps5-cnt-reader").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun readsBoundedEntryNamesAndParamJsonMetadata() {
        val file = makeCnt("valid.cnt")
        val result = PkgCntReader.read(file, 0L, file.length())
        assertNotNull(result)
        assertEquals(3, result!!.entryCount)
        assertEquals("sce_sys/param.json", result.entries[1].name)
        assertEquals("{\"title\":\"Synthetic\"}", result.paramJson)
        assertEquals("sce_sys/icon0.png", result.entries[2].name)
        assertNotNull(result.iconPng)
        assertTrue(result.iconPng!!.isNotEmpty())
    }

    @Test
    fun doesNotDecodeEncryptedParamJson() {
        val file = makeCnt("encrypted-param.cnt") { raf ->
            writeBe32(raf, 0x5c0L + 0x08L, 0x80000000L)
        }
        val result = PkgCntReader.read(file, 0L, file.length())
        assertNotNull(result)
        assertTrue(result!!.entries[1].encrypted)
        assertNull(result.paramJson)
    }

    @Test
    fun doesNotDecodeEncryptedNameTableButKeepsOtherMetadata() {
        val file = makeCnt("encrypted-names.cnt") { raf ->
            writeBe32(raf, 0x5a0L + 0x08L, 0x80000000L)
        }
        val result = PkgCntReader.read(file, 0L, file.length())
        assertNotNull(result)
        assertTrue(result!!.entries[0].encrypted)
        assertNull(result.entries[0].name)
        assertNull(result.entries[1].name)
        assertEquals("{\"title\":\"Synthetic\"}", result.paramJson)
    }

    @Test
    fun doesNotExposeEncryptedIconPayload() {
        val file = makeCnt("encrypted-icon.cnt") { raf ->
            writeBe32(raf, 0x5e0L + 0x08L, 0x80000000L)
        }
        val result = PkgCntReader.read(file, 0L, file.length())
        assertNotNull(result)
        assertNull(result!!.iconPng)
    }

    @Test
    fun rejectsEntryTableOutsideCntSegment() {
        val file = makeCnt("table-outside.cnt") { raf ->
            writeBe32(raf, 0x18L, 0x8d0L)
        }
        assertNull(PkgCntReader.read(file, 0L, file.length()))
    }

    @Test
    fun rejectsEntryDataOutsideCntSegment() {
        val file = makeCnt("data-outside.cnt") { raf ->
            writeBe32(raf, 0x5a0L + 0x20L + 0x10L, 0x8f0L)
            writeBe32(raf, 0x5a0L + 0x20L + 0x14L, 0x100L)
        }
        assertNull(PkgCntReader.read(file, 0L, file.length()))
    }

    @Test
    fun rejectsNameOffsetOutsideNameTable() {
        val file = makeCnt("bad-name-offset.cnt") { raf ->
            writeBe32(raf, 0x5a0L + 0x20L + 0x04L, 0xffffL)
        }
        assertNull(PkgCntReader.read(file, 0L, file.length()))
    }

    @Test
    fun rejectsCntRangeOutsideFile() {
        val file = makeCnt("truncated.cnt")
        assertNull(PkgCntReader.read(file, 0L, file.length() + 1L))
    }

    @Test
    fun rejectsNonCntMagic() {
        val file = makeCnt("wrong-magic.cnt") { raf ->
            raf.seek(0)
            raf.write(byteArrayOf(1, 2, 3, 4))
        }
        assertNull(PkgCntReader.read(file, 0L, file.length()))
    }

    private fun makeCnt(name: String, mutate: (RandomAccessFile) -> Unit = {}): File {
        val bytes = ByteArray(0x900)
        bytes[0] = 0x7f
        bytes[1] = 'C'.code.toByte()
        bytes[2] = 'N'.code.toByte()
        bytes[3] = 'T'.code.toByte()
        putBe32(bytes, 0x10, 3)
        putBe32(bytes, 0x18, 0x5a0)

        // Entry 0: the NUL-terminated entry-name table.
        putBe32(bytes, 0x5a0, 0x0200)
        putBe32(bytes, 0x5a0 + 0x10, 0x700)
        putBe32(bytes, 0x5a0 + 0x14, 0x40)

        // Entry 1: param.json, with its name offset into the names table.
        putBe32(bytes, 0x5c0, 0x2000)
        putBe32(bytes, 0x5c0 + 0x04, 1)
        putBe32(bytes, 0x5c0 + 0x10, 0x740)
        val json = "{\"title\":\"Synthetic\"}".toByteArray(Charsets.UTF_8)
        putBe32(bytes, 0x5c0 + 0x14, json.size)
        // Entry 2: optional, unencrypted icon0.png presentation metadata.
        val icon = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABSK+kcQAAAABJRU5ErkJggg==")
        putBe32(bytes, 0x5e0, 0x1200)
        putBe32(bytes, 0x5e0 + 0x04, 20)
        putBe32(bytes, 0x5e0 + 0x10, 0x780)
        putBe32(bytes, 0x5e0 + 0x14, icon.size)
        val names = byteArrayOf(0) +
            "sce_sys/param.json".toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            "sce_sys/icon0.png".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        names.copyInto(bytes, 0x700)
        json.copyInto(bytes, 0x740)
        icon.copyInto(bytes, 0x780)

        val file = File(root, name)
        file.writeBytes(bytes)
        RandomAccessFile(file, "rw").use(mutate)
        return file
    }

    private fun putBe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
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
}
