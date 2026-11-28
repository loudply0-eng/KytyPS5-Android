package com.kytyps5.android.library

import java.io.IOException
import java.io.RandomAccessFile

/**
 * Validates only the fixed PS5 PFS superblock at the start of a readable image.
 *
 * It does not traverse inode tables, read directory entries, decrypt blocks, or extract files.
 */
data class PfsSuperblockInfo(
    val version: Long,
    val magic: Long,
    val mode: Int,
    val blockSize: Long,
    val backupCount: Long,
    val inodeCount: Long,
    val dataBlockCount: Long,
    val inodeBlockCount: Long,
    val superRootInode: Long,
) {
    val signedInodes: Boolean get() = (mode and MODE_SIGNED_INODES) != 0
    val wideInodes: Boolean get() = (mode and MODE_64_BIT_INODES) != 0
    val encrypted: Boolean get() = (mode and MODE_ENCRYPTED) != 0
    val caseInsensitive: Boolean get() = (mode and MODE_CASE_INSENSITIVE) != 0

    companion object {
        const val MODE_SIGNED_INODES = 0x0001
        const val MODE_64_BIT_INODES = 0x0002
        const val MODE_ENCRYPTED = 0x0004
        const val MODE_CASE_INSENSITIVE = 0x0008
    }
}

object PfsSuperblockReader {
    private const val HEADER_SIZE = 0x50L
    private const val OUTER_PFS_BLOCK_SIZE = 0x10000L
    private const val SUPERBLOCK_MAGIC = 20130315L

    /**
     * Finds a valid superblock on 64 KiB boundaries inside a finalized-image PFS segment.
     * PS5 outer PFS images can be data-first, so the superblock is not guaranteed to be
     * at the first byte of the segment. Only the small magic field is probed per block;
     * full geometry validation is performed only for matching candidates.
     */
    fun find(raf: RandomAccessFile, offset: Long, segmentSize: Long): Pair<Long, PfsSuperblockInfo>? {
        val fileSize = try { raf.length() } catch (_: IOException) { return null }
        if (offset < 0L || segmentSize < HEADER_SIZE || offset > fileSize ||
            segmentSize > fileSize - offset
        ) return null

        var relative = 0L
        while (relative <= segmentSize - HEADER_SIZE) {
            val absolute = offset + relative
            try {
                raf.seek(absolute + 0x08L)
                if (readLe64(raf) == SUPERBLOCK_MAGIC) {
                    val info = read(raf, absolute, segmentSize - relative)
                    if (info != null) return absolute to info
                }
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            if (relative > Long.MAX_VALUE - OUTER_PFS_BLOCK_SIZE) return null
            relative += OUTER_PFS_BLOCK_SIZE
        }
        return null
    }
    private const val PS5_VERSION = 2L
    private const val PS5_MAGIC = 20130315L
    private const val MIN_BLOCK_SIZE = 0x1000L
    private const val MAX_BLOCK_SIZE = 0x2000000L

    /**
     * Reads the 0x50-byte little-endian superblock at [offset] within a bounded PFS segment.
     * Returns null for truncated, out-of-range, or internally inconsistent geometry.
     */
    internal fun read(source: PfsImageSource, offset: Long, segmentSize: Long): PfsSuperblockInfo? {
        if (offset < 0L || segmentSize < HEADER_SIZE || offset > source.length ||
            segmentSize > source.length - offset
        ) return null
        val bytes = source.readAt(offset, HEADER_SIZE.toInt()) ?: return null
        if (bytes.size != HEADER_SIZE.toInt()) return null

        val version = readLe64(bytes, 0)
        val magic = readLe64(bytes, 8)
        val mode = readLe16(bytes, 0x1c).toInt()
        val blockSize = readLe32(bytes, 0x20)
        val backupCount = readLe32(bytes, 0x24)
        val nBlock = readLe64(bytes, 0x28)
        val inodeCount = readLe64(bytes, 0x30)
        val dataBlockCount = readLe64(bytes, 0x38)
        val inodeBlockCount = readLe64(bytes, 0x40)
        val superRootInode = readLe64(bytes, 0x48)

        if (version != PS5_VERSION || magic != PS5_MAGIC) return null
        if (blockSize !in MIN_BLOCK_SIZE..MAX_BLOCK_SIZE ||
            (blockSize and (blockSize - 1L)) != 0L
        ) return null
        if (backupCount < 0L || nBlock < 0L || inodeCount <= 0L ||
            dataBlockCount < 0L || inodeBlockCount <= 0L ||
            superRootInode < 0L || superRootInode >= inodeCount
        ) return null

        return PfsSuperblockInfo(
            version = version,
            magic = magic,
            mode = mode,
            blockSize = blockSize,
            backupCount = backupCount,
            inodeCount = inodeCount,
            dataBlockCount = dataBlockCount,
            inodeBlockCount = inodeBlockCount,
            superRootInode = superRootInode,
        )
    }

    fun read(raf: RandomAccessFile, offset: Long, segmentSize: Long): PfsSuperblockInfo? {
        val fileSize = try { raf.length() } catch (_: IOException) { return null }
        if (offset < 0L || segmentSize < HEADER_SIZE || fileSize < 0L ||
            offset > fileSize || segmentSize > fileSize - offset
        ) return null

        return try {
            raf.seek(offset)
            val version = readLe64(raf)
            val magic = readLe64(raf)
            raf.seek(offset + 0x1cL)
            val mode = readLe16(raf).toInt()
            raf.seek(offset + 0x20L)
            val blockSize = readLe32(raf)
            val backupCount = readLe32(raf)
            val nBlock = readLe64(raf)
            val inodeCount = readLe64(raf)
            val dataBlockCount = readLe64(raf)
            val inodeBlockCount = readLe64(raf)
            val superRootInode = readLe64(raf)

            if (version != PS5_VERSION || magic != PS5_MAGIC) return null
            if (blockSize !in MIN_BLOCK_SIZE..MAX_BLOCK_SIZE ||
                (blockSize and (blockSize - 1L)) != 0L
            ) return null
            if (backupCount < 0L || nBlock < 0L || inodeCount <= 0L ||
                dataBlockCount < 0L || inodeBlockCount <= 0L ||
                superRootInode < 0L || superRootInode >= inodeCount
            ) return null

            PfsSuperblockInfo(
                version = version,
                magic = magic,
                mode = mode,
                blockSize = blockSize,
                backupCount = backupCount,
                inodeCount = inodeCount,
                dataBlockCount = dataBlockCount,
                inodeBlockCount = inodeBlockCount,
                superRootInode = superRootInode,
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun readLe16(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8)
    }

    private fun readLe32(raf: RandomAccessFile): Long {
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLe64(raf: RandomAccessFile): Long {
        val bytes = ByteArray(8)
        raf.readFully(bytes)
        var value = 0L
        for (index in bytes.indices) {
            value = value or ((bytes[index].toLong() and 0xffL) shl (index * 8))
        }
        return value
    }
}
