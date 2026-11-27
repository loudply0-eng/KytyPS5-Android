package com.kytyps5.android.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded, metadata-only reader for the embedded PS5 CNT segment.
 *
 * Offsets in CNT entry records are interpreted relative to the beginning of
 * the CNT segment. This class never follows paths, extracts files, or decrypts
 * package data.
 */
data class PkgCntMetadata(
    val entryCount: Int,
    val entries: List<Entry>,
    val paramJson: String?,
    /** Unencrypted, bounded sce_sys/icon0.png payload from CNT, when present and valid. */
    val iconPng: ByteArray? = null,
    /** Plaintext 0x30-byte CNT content identifier, when the field is valid ASCII. */
    val contentId: String? = null,
) {
    data class Entry(
        val id: Long,
        val name: String?,
        val dataSize: Long,
        val encrypted: Boolean,
        val dataOffset: Long,
    )
}

object PkgCntReader {
    private const val HEADER_SIZE = 0x5A0L
    private const val ENTRY_SIZE = 0x20L
    private const val ENTRY_NAMES_ID = 0x0200L
    private const val ICON0_ID = 0x1200L
    private const val PARAM_JSON_ID = 0x2000L
    private const val MAX_ENTRIES = 65_536L
    private const val MAX_NAME_TABLE_SIZE = 4L * 1024L * 1024L
    private const val MAX_PARAM_JSON_SIZE = 1024L * 1024L
    private const val MAX_ICON_PNG_SIZE = 4L * 1024L * 1024L
    private const val MAX_NAME_BYTES = 4096

    fun read(file: File, cntOffset: Long, cntSize: Long): PkgCntMetadata? {
        if (!file.isFile || cntOffset < 0L || cntSize < HEADER_SIZE) return null
        val fileSize = file.length()
        if (!rangeFits(cntOffset, cntSize, fileSize)) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(cntOffset)
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (!magic.contentEquals(byteArrayOf(0x7f, 0x43, 0x4e, 0x54))) return null

                val contentId = readContentId(raf, cntOffset)
                val entryCount = readBe32(raf, cntOffset + 0x10L)
                val tableOffset = readBe32(raf, cntOffset + 0x18L)
                if (entryCount <= 0L || entryCount > MAX_ENTRIES ||
                    tableOffset < HEADER_SIZE
                ) return null

                val tableBytes = entryCount * ENTRY_SIZE
                if (!rangeFits(tableOffset, tableBytes, cntSize)) return null

                val rawEntries = ArrayList<RawEntry>(entryCount.toInt())
                var namesEntry: RawEntry? = null
                var paramEntry: RawEntry? = null
                var iconEntry: RawEntry? = null

                for (index in 0 until entryCount) {
                    val recordOffset = cntOffset + tableOffset + index * ENTRY_SIZE
                    val id = readBe32(raf, recordOffset)
                    val nameOffset = readBe32(raf, recordOffset + 0x04L)
                    val flags1 = readBe32(raf, recordOffset + 0x08L)
                    val dataOffset = readBe32(raf, recordOffset + 0x10L)
                    val dataSize = readBe32(raf, recordOffset + 0x14L)

                    if (!rangeFits(dataOffset, dataSize, cntSize)) return null
                    val entry = RawEntry(id, nameOffset, flags1, dataOffset, dataSize)
                    rawEntries += entry

                    if (id == ENTRY_NAMES_ID) {
                        if (namesEntry != null) return null
                        namesEntry = entry
                    }
                    if (id == PARAM_JSON_ID) {
                        if (paramEntry != null) return null
                        paramEntry = entry
                    }
                    if (id == ICON0_ID) {
                        if (iconEntry != null) return null
                        iconEntry = entry
                    }
                }

                // An encrypted name table is valid metadata we cannot decode. Keep the
                // package entry list, but leave names unavailable instead of treating ciphertext
                // as UTF-8 or rejecting the entire CNT segment.
                val nameBytes = namesEntry?.let {
                    if ((it.flags1 and 0x80000000L) != 0L) return@let null
                    if (it.dataSize <= 0L || it.dataSize > MAX_NAME_TABLE_SIZE) return null
                    readBytes(raf, cntOffset + it.dataOffset, it.dataSize.toInt())
                }

                val publicEntries = rawEntries.map { entry ->
                    val name = if (nameBytes == null || entry.id == ENTRY_NAMES_ID || entry.nameOffset == 0L) {
                        null
                    } else {
                        readName(nameBytes, entry.nameOffset) ?: return null
                    }
                    PkgCntMetadata.Entry(
                        entry.id,
                        name,
                        entry.dataSize,
                        (entry.flags1 and 0x80000000L) != 0L,
                        entry.dataOffset,
                    )
                }

                val paramJson = paramEntry?.let {
                    if ((it.flags1 and 0x80000000L) != 0L) return@let null
                    if (it.dataSize <= 0L || it.dataSize > MAX_PARAM_JSON_SIZE) return null
                    val bytes = readBytes(raf, cntOffset + it.dataOffset, it.dataSize.toInt())
                    decodeUtf8(bytes) ?: return null
                }

                // icon0.png is a presentation asset, not game content. Read it only when the
                // entry is explicitly unencrypted, small, and has a sane PNG header.
                val iconPng = iconEntry?.let { entry ->
                    if ((entry.flags1 and 0x80000000L) != 0L ||
                        entry.dataSize !in 24L..MAX_ICON_PNG_SIZE
                    ) {
                        null
                    } else {
                        readBytes(raf, cntOffset + entry.dataOffset, entry.dataSize.toInt())
                            .takeIf(::isPng)
                    }
                }

                PkgCntMetadata(entryCount.toInt(), publicEntries, paramJson, iconPng, contentId)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private data class RawEntry(
        val id: Long,
        val nameOffset: Long,
        val flags1: Long,
        val dataOffset: Long,
        val dataSize: Long,
    )

    private fun readBe32(raf: RandomAccessFile, offset: Long): Long {
        raf.seek(offset)
        return ((raf.readUnsignedByte().toLong() shl 24) or
            (raf.readUnsignedByte().toLong() shl 16) or
            (raf.readUnsignedByte().toLong() shl 8) or
            raf.readUnsignedByte().toLong())
    }

    private fun readContentId(raf: RandomAccessFile, cntOffset: Long): String? {
        val bytes = readBytes(raf, cntOffset + 0x40L, 0x30)
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        if (end == 0) return null
        if ((0 until end).any { (bytes[it].toInt() and 0xff) !in 0x20..0x7e }) return null
        return String(bytes, 0, end, StandardCharsets.US_ASCII).trim().ifEmpty { null }
    }

    private fun readBytes(raf: RandomAccessFile, offset: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 24) return false
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        if (!bytes.copyOfRange(0, 8).contentEquals(signature)) return false
        if (readBe32(bytes, 8) != 13L ||
            !bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))
        ) return false
        val width = readBe32(bytes, 16)
        val height = readBe32(bytes, 20)
        return width in 1L..4096L && height in 1L..4096L && width * height <= 16L * 1024L * 1024L
    }

    private fun readBe32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun readName(table: ByteArray, offset: Long): String? {
        if (offset < 0L || offset >= table.size.toLong()) return null
        val start = offset.toInt()
        val limit = minOf(table.size, start + MAX_NAME_BYTES)
        var end = start
        while (end < limit && table[end].toInt() != 0) end++
        if (end == limit || end == start) return null
        return decodeUtf8(table.copyOfRange(start, end))
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    private fun rangeFits(offset: Long, size: Long, containerSize: Long): Boolean {
        if (offset < 0L || size < 0L || containerSize < 0L) return false
        return offset <= containerSize && size <= containerSize - offset
    }
}
