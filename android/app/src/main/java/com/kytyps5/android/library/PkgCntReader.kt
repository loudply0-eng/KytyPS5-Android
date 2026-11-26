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
) {
    data class Entry(
        val id: Long,
        val name: String?,
        val dataSize: Long,
    )
}

object PkgCntReader {
    private const val HEADER_SIZE = 0x5A0L
    private const val ENTRY_SIZE = 0x20L
    private const val ENTRY_NAMES_ID = 0x0200L
    private const val PARAM_JSON_ID = 0x2000L
    private const val MAX_ENTRIES = 65_536L
    private const val MAX_NAME_TABLE_SIZE = 4L * 1024L * 1024L
    private const val MAX_PARAM_JSON_SIZE = 1024L * 1024L
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
                }

                val nameBytes = namesEntry?.let {
                    if (it.dataSize <= 0L || it.dataSize > MAX_NAME_TABLE_SIZE) return null
                    readBytes(raf, cntOffset + it.dataOffset, it.dataSize.toInt())
                }

                val publicEntries = rawEntries.map { entry ->
                    val name = if (nameBytes == null || entry.id == ENTRY_NAMES_ID) {
                        null
                    } else {
                        readName(nameBytes, entry.nameOffset) ?: return null
                    }
                    PkgCntMetadata.Entry(entry.id, name, entry.dataSize)
                }

                val paramJson = paramEntry?.let {
                    if (it.dataSize <= 0L || it.dataSize > MAX_PARAM_JSON_SIZE) return null
                    val bytes = readBytes(raf, cntOffset + it.dataOffset, it.dataSize.toInt())
                    decodeUtf8(bytes) ?: return null
                }

                PkgCntMetadata(entryCount.toInt(), publicEntries, paramJson)
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

    private fun readBytes(raf: RandomAccessFile, offset: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }

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
