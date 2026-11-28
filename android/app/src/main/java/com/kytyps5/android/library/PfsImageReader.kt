package com.kytyps5.android.library

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Read-only reader for a deliberately narrow PFS subset:
 * unsigned 32-bit inodes, plaintext data, standard 0xA8-byte inode records,
 * direct blocks and one level of indirect blocks.
 *
 * This is intended for an already available inner PFS image. It does not read
 * PS5 outer-PFS signatures, decrypt sectors, decode PFSC, or install packages.
 */
data class PfsImageEntry(
    val path: String,
    val inode: Long,
    val size: Long,
    val directory: Boolean,
    val compressed: Boolean,
)

class PfsImageReader private constructor(
    private val raf: RandomAccessFile,
    private val segmentOffset: Long,
    private val segmentSize: Long,
    val superblock: PfsSuperblockInfo,
    private val inodeSlotsPerBlock: Long,
) : Closeable {

    private var scanAttempted = false
    private var scannedEntries: List<PfsImageEntry>? = null

    /**
     * Lists the tree below the super-root's `uroot` directory.
     * Returns null for malformed directories, unsupported inode types, or limit violations.
     */
    fun listEntries(): List<PfsImageEntry>? {
        if (scanAttempted) return scannedEntries
        scanAttempted = true
        scannedEntries = runCatching { scanTree() }.getOrNull()
        return scannedEntries
    }

    /**
     * Reads one small, uncompressed file by path. Reads are bounded by [maxBytes].
     * Directory names are matched case-insensitively only when the PFS mode requests it.
     */
    fun readFile(path: String, maxBytes: Long = DEFAULT_MAX_FILE_BYTES): ByteArray? {
        return try {
            if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return null
            val normalized = path.split('/').filter { it.isNotEmpty() }
            if (normalized.isEmpty() || normalized.any { it == "." || it == ".." }) return null
            val canonical = normalized.joinToString("/")
            val entry = listEntries()?.firstOrNull {
                !it.directory && samePath(it.path, canonical)
            } ?: return null
            if (entry.compressed || entry.size < 0L || entry.size > maxBytes || entry.size > Int.MAX_VALUE) return null
            val inode = readInode(entry.inode) ?: return null
            if ((inode.mode and INODE_MODE_FILE) == 0) return null
            readInodeData(inode, entry.size, maxBytes)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override fun close() {
        raf.close()
    }

    private fun scanTree(): List<PfsImageEntry> {
        val superRoot = readInode(superblock.superRootInode) ?: return emptyListOrNull()
        if ((superRoot.mode and INODE_MODE_DIR) == 0) return emptyListOrNull()
        val rootEntry = readDirectory(superRoot).firstOrNull {
            it.type == DIRENT_DIRECTORY && sameName(it.name, "uroot")
        } ?: return emptyListOrNull()

        val results = ArrayList<PfsImageEntry>()
        val visited = HashSet<Long>()
        val pending = ArrayDeque<PendingDirectory>()
        pending.add(PendingDirectory(rootEntry.inode, ""))

        while (pending.isNotEmpty()) {
            if (results.size >= MAX_ENTRIES) return emptyListOrNull()
            val current = pending.removeFirst()
            if (!visited.add(current.inode)) return emptyListOrNull()
            if (current.path.count { it == '/' } >= MAX_DEPTH) return emptyListOrNull()

            val inode = readInode(current.inode) ?: return emptyListOrNull()
            if ((inode.mode and INODE_MODE_DIR) == 0 || inode.compressed) return emptyListOrNull()
            val children = readDirectory(inode)
            for (child in children) {
                if (child.type == DIRENT_DOT || child.type == DIRENT_DOTDOT) continue
                val childPath = if (current.path.isEmpty()) child.name else "${current.path}/${child.name}"
                val childInode = readInode(child.inode) ?: return emptyListOrNull()
                val isDirectory = child.type == DIRENT_DIRECTORY
                if (isDirectory && (childInode.mode and INODE_MODE_DIR) == 0) return emptyListOrNull()
                if (!isDirectory && (childInode.mode and INODE_MODE_FILE) == 0) return emptyListOrNull()

                results += PfsImageEntry(
                    path = childPath,
                    inode = child.inode,
                    size = childInode.size,
                    directory = isDirectory,
                    compressed = childInode.compressed,
                )
                if (results.size > MAX_ENTRIES) return emptyListOrNull()
                if (isDirectory) pending.add(PendingDirectory(child.inode, childPath))
            }
        }
        return results
    }

    private fun readDirectory(inode: PfsInode): List<PfsDirent> {
        if ((inode.mode and INODE_MODE_DIR) == 0 || inode.compressed) throw IOException("Unsupported PFS directory inode")
        if (inode.size < 0L || inode.size > MAX_DIRECTORY_BYTES) throw IOException("PFS directory exceeds limit")
        val bytes = readInodeData(inode, inode.size, MAX_DIRECTORY_BYTES) ?: throw IOException("Cannot read PFS directory")
        val out = ArrayList<PfsDirent>()
        var cursor = 0
        while (cursor < bytes.size) {
            if (bytes.size - cursor < DIRENT_HEADER_SIZE) throw IOException("Truncated PFS dirent")
            val inodeNumber = u32(bytes, cursor)
            val type = u32(bytes, cursor + 4).toInt()
            val nameLength = u32(bytes, cursor + 8)
            val entrySize = u32(bytes, cursor + 12)
            if (entrySize < 24L || (entrySize and 7L) != 0L ||
                entrySize > (bytes.size - cursor).toLong() ||
                inodeNumber >= superblock.inodeCount ||
                nameLength <= 0L || nameLength > MAX_NAME_BYTES ||
                17L + nameLength > entrySize
            ) throw IOException("Invalid PFS dirent geometry")

            val end = cursor + entrySize.toInt()
            val nameStart = cursor + DIRENT_HEADER_SIZE
            val nameEnd = nameStart + nameLength.toInt()
            if (nameEnd >= end || bytes[nameEnd] != 0.toByte()) throw IOException("Invalid PFS dirent name")
            val name = decodeUtf8(bytes.copyOfRange(nameStart, nameEnd)) ?: throw IOException("Invalid PFS name")
            val safeName = when (type) {
                DIRENT_DOT -> name == "."
                DIRENT_DOTDOT -> name == ".."
                else -> isSafeName(name)
            }
            if (!safeName) throw IOException("Unsafe PFS path component")
            if (type !in setOf(DIRENT_FILE, DIRENT_DIRECTORY, DIRENT_DOT, DIRENT_DOTDOT)) {
                throw IOException("Unsupported PFS dirent type")
            }
            out += PfsDirent(inodeNumber, type, name)
            if (out.size > MAX_DIRECTORY_ENTRIES) throw IOException("Too many PFS directory entries")
            cursor = end
        }
        return out
    }

    private fun readInode(inodeNumber: Long): PfsInode? {
        if (inodeNumber < 0L || inodeNumber >= superblock.inodeCount) return null
        val inodeBlockIndex = inodeNumber / inodeSlotsPerBlock
        val slotInBlock = inodeNumber % inodeSlotsPerBlock
        if (inodeBlockIndex >= superblock.inodeBlockCount) return null
        val relative = superblock.blockSize + inodeBlockIndex * superblock.blockSize + slotInBlock * INODE_SIZE
        if (!rangeFits(relative, INODE_SIZE, segmentSize)) return null
        val raw = readBytes(segmentOffset + relative, INODE_SIZE.toInt()) ?: return null
        return PfsInode(
            mode = u16(raw, 0),
            flags = u32(raw, 4),
            size = u64(raw, 8),
            blocks = u32(raw, 0x60),
            direct = LongArray(DIRECT_BLOCK_COUNT) { index -> u32(raw, 0x64 + index * 4) },
            indirect = LongArray(INDIRECT_LEVEL_COUNT) { index -> u32(raw, 0x94 + index * 4) },
        )
    }

    private fun readInodeData(inode: PfsInode, size: Long, maxBytes: Long): ByteArray? {
        if (inode.compressed || size < 0L || size > maxBytes || size > Int.MAX_VALUE) return null
        if (size == 0L) return ByteArray(0)
        val blockSize = superblock.blockSize
        val blockCount = (size + blockSize - 1L) / blockSize
        val pointersPerBlock = blockSize / 4L
        val firstDataBlock = 1L + superblock.inodeBlockCount
        val dataBlockLimit = firstDataBlock + superblock.dataBlockCount
        if (blockCount > DIRECT_BLOCK_COUNT + pointersPerBlock) return null

        val out = ByteArray(size.toInt())
        var outputOffset = 0
        var logicalBlock = 0L
        while (logicalBlock < blockCount) {
            val blockNumber = if (logicalBlock < DIRECT_BLOCK_COUNT) {
                inode.direct[logicalBlock.toInt()]
            } else {
                val indirectBlock = inode.indirect[0]
                if (indirectBlock < firstDataBlock || indirectBlock >= dataBlockLimit) return null
                val pointerOffset = segmentOffset + indirectBlock * blockSize + (logicalBlock - DIRECT_BLOCK_COUNT) * 4L
                if (!rangeFits(indirectBlock * blockSize, blockSize, segmentSize) ||
                    !rangeFits(pointerOffset - segmentOffset, 4L, segmentSize)
                ) return null
                readU32At(pointerOffset) ?: return null
            }
            if (blockNumber < firstDataBlock || blockNumber >= dataBlockLimit ||
                blockNumber >= segmentSize / blockSize
            ) return null
            val count = minOf(blockSize.toInt(), out.size - outputOffset)
            val blockOffset = segmentOffset + blockNumber * blockSize
            if (!rangeFits(blockNumber * blockSize, count.toLong(), segmentSize)) return null
            raf.seek(blockOffset)
            raf.readFully(out, outputOffset, count)
            outputOffset += count
            logicalBlock++
        }
        return out
    }

    private fun readBytes(absoluteOffset: Long, count: Int): ByteArray? {
        if (count < 0 || absoluteOffset < segmentOffset ||
            !rangeFits(absoluteOffset - segmentOffset, count.toLong(), segmentSize)
        ) return null
        return ByteArray(count).also {
            raf.seek(absoluteOffset)
            raf.readFully(it)
        }
    }

    private fun readU32At(absoluteOffset: Long): Long? {
        val raw = readBytes(absoluteOffset, 4) ?: return null
        return u32(raw, 0)
    }

    private fun sameName(a: String, b: String): Boolean =
        if (superblock.caseInsensitive) a.equals(b, ignoreCase = true) else a == b

    private fun samePath(a: String, b: String): Boolean =
        if (superblock.caseInsensitive) a.equals(b, ignoreCase = true) else a == b

    private fun emptyListOrNull(): Nothing? = null

    private data class PendingDirectory(val inode: Long, val path: String)
    private data class PfsDirent(val inode: Long, val type: Int, val name: String)
    private data class PfsInode(
        val mode: Int,
        val flags: Long,
        val size: Long,
        val blocks: Long,
        val direct: LongArray,
        val indirect: LongArray,
    ) {
        val compressed: Boolean get() = (flags and INODE_FLAG_COMPRESSED) != 0L
    }

    companion object {
        private const val INODE_SIZE = 0xA8L
        private const val INODE_MODE_DIR = 0x4000
        private const val INODE_MODE_FILE = 0x8000
        private const val INODE_FLAG_COMPRESSED = 0x1L
        private const val DIRECT_BLOCK_COUNT = 12
        private const val INDIRECT_LEVEL_COUNT = 5
        private const val DIRENT_HEADER_SIZE = 16
        private const val DIRENT_FILE = 2
        private const val DIRENT_DIRECTORY = 3
        private const val DIRENT_DOT = 4
        private const val DIRENT_DOTDOT = 5
        private const val MAX_DEPTH = 48
        private const val MAX_ENTRIES = 100_000
        private const val MAX_DIRECTORY_ENTRIES = 100_000
        private const val MAX_NAME_BYTES = 1024L
        private const val MAX_DIRECTORY_BYTES = 16L * 1024L * 1024L
        private const val DEFAULT_MAX_FILE_BYTES = 32L * 1024L * 1024L

        fun open(file: File, offset: Long = 0L, size: Long = file.length()): PfsImageReader? {
            if (!file.isFile || offset < 0L || size < 0L) return null
            val raf = try { RandomAccessFile(file, "r") } catch (_: IOException) { return null }
            try {
                if (!rangeFits(offset, size, raf.length())) return null.also { raf.close() }
                val sb = PfsSuperblockReader.read(raf, offset, size) ?: return null.also { raf.close() }
                // This reader deliberately refuses signed, 64-bit, or encrypted PFS variants.
                if (sb.signedInodes || sb.wideInodes || sb.encrypted) return null.also { raf.close() }
                val inodeSlots = sb.blockSize / INODE_SIZE
                if (inodeSlots <= 0L || sb.inodeBlockCount > Long.MAX_VALUE / inodeSlots ||
                    sb.inodeCount > sb.inodeBlockCount * inodeSlots ||
                    sb.inodeBlockCount > Long.MAX_VALUE / sb.blockSize
                ) {
                    return null.also { raf.close() }
                }
                val inodeBytes = sb.inodeBlockCount * sb.blockSize
                if (!rangeFits(sb.blockSize, inodeBytes, size) ||
                    sb.inodeBlockCount >= Long.MAX_VALUE ||
                    sb.dataBlockCount > Long.MAX_VALUE - (1L + sb.inodeBlockCount)
                ) return null.also { raf.close() }
                val dataBlockLimit = 1L + sb.inodeBlockCount + sb.dataBlockCount
                if (dataBlockLimit > size / sb.blockSize) return null.also { raf.close() }
                return PfsImageReader(raf, offset, size, sb, inodeSlots)
            } catch (_: IOException) {
                raf.close()
                return null
            } catch (_: SecurityException) {
                raf.close()
                return null
            }
        }

        private fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                name.none { it == '\u0000' || it == '/' || it == '\\' }

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)

        private fun u64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xffL) shl (i * 8))
            return value
        }

        private fun rangeFits(offset: Long, length: Long, total: Long): Boolean =
            offset >= 0L && length >= 0L && total >= 0L && offset <= total && length <= total - offset
    }
}
