package com.kytyps5.android

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GameLibraryGptImageTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kytyps5-gpt-games").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun scanDiscoversExfatVolumeInsideGptPartition() {
        File(root, "gpt-game.img").writeBytes(gptDiskWithExfatPartition())

        val result = GameLibrary.scan(root) as ScanResult.Found

        assertEquals(listOf("gpt-game"), result.games.map { it.title })
        assertEquals("exFAT disk image", GameLibrary.imageLabel(result.games.single().folder))
    }

    @Test
    fun ignoresGptHeaderWithOverflowingPartitionArrayOffset() {
        val disk = ByteArray(1024)
        disk[446 + 4] = 0xEE.toByte()
        disk[510] = 0x55.toByte()
        disk[511] = 0xAA.toByte()
        val header = 512
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(disk, header)
        val badLba = Long.MAX_VALUE / 512L + 1L
        for (i in 0 until 8) disk[header + 72 + i] = (badLba ushr (8 * i)).toByte()
        disk[header + 80] = 1
        disk[header + 84] = 128

        File(root, "malformed-gpt.img").writeBytes(disk)

        val result = GameLibrary.scan(root) as ScanResult.Found
        assertEquals(emptyList<String>(), result.games.map { it.title })
    }

    private fun gptDiskWithExfatPartition(): ByteArray {
        val partitionStartLba = 2048
        val volumeSectors = 100
        val disk = ByteArray((partitionStartLba + volumeSectors) * 512)

        fun put32(target: ByteArray, offset: Int, value: Int) {
            for (i in 0 until 4) target[offset + i] = (value ushr (8 * i)).toByte()
        }
        fun put64(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) target[offset + i] = (value ushr (8 * i)).toByte()
        }

        // Protective MBR.
        disk[446 + 4] = 0xEE.toByte()
        put32(disk, 446 + 8, 1)
        disk[510] = 0x55.toByte()
        disk[511] = 0xAA.toByte()

        // GPT header at LBA 1. The scanner validates the signature and entry geometry.
        val header = 512
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(disk, header)
        put64(disk, header + 72, 2L) // partition-entry array begins at LBA 2
        put32(disk, header + 80, 1)
        put32(disk, header + 84, 128)

        // One partition entry at LBA 2, with its first LBA at byte offset 32.
        val entry = 2 * 512
        disk[entry] = 1.toByte() // non-empty type GUID
        put64(disk, entry + 32, partitionStartLba.toLong())

        // Minimal structurally valid exFAT boot sector at the partition start.
        val boot = partitionStartLba * 512
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(disk, boot + 3)
        put64(disk, boot + 72, volumeSectors.toLong())
        put32(disk, boot + 80, 24)
        put32(disk, boot + 84, 1)
        put32(disk, boot + 88, 25)
        put32(disk, boot + 92, 1)
        put32(disk, boot + 96, 2)
        disk[boot + 108] = 9
        disk[boot + 109] = 0.toByte()
        disk[boot + 110] = 1.toByte()
        disk[boot + 510] = 0x55
        disk[boot + 511] = 0xAA.toByte()

        return disk
    }
}
