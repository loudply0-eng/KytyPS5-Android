package com.kytyps5.android.library

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Read-only outer-PFS reader for explicitly unencrypted Debug/Homebrew images.
 *
 * It accepts only the documented PPRPLAIN-NOAUTH! seed marker. No Retail key, passcode
 * derivation, AES-XTS operation, authentication bypass, or encrypted-block interpretation
 * is attempted. The reader resolves S32 inode block lists and exposes the outer uroot files.
 *
 * This reader is deliberately limited to the contiguous, data-first outer layout:
 * pfs_image.dat occupies blocks preceding the plaintext superblock. All metadata blocks
 * are read verbatim only because the explicit no-auth seed marker was verified.
 */
data class OuterPfsFileInfo(
    val name: String,
    val inodeIndex: Int,
    val size: Long,
    val compressedSize: Long,
    val blockCount: Int,
    val directory: Boolean,
)

class OuterPfsPlainReader private constructor(
    private val raf: RandomAccessFile,
    private val pfsOffset: Long,
    private val pfsSize: Long,
    private val blockCount: Int,
    val superblockIndex: Int,
    private val superblockInfo: PfsSuperblockInfo,
    private val inodeTableStart: Int,
) : Closeable {

    private var inodeCache: Array<OuterInode>? = null
    private var filesCache: List<OuterPfsFileInfo>? = null
    private var napsMapCache: NapsLogicalBlockMap? = null

    /** The packed pfs_image.dat byte region before the outer plaintext superblock. */
    val packedImageSize: Long
        get() = (superblockIndex - 1L).coerceAtLeast(0L) * BLOCK_SIZE

    /** Lists files directly inside the outer PFS uroot directory. */
    fun listUrootFiles(): List<OuterPfsFileInfo>? {
        filesCache?.let { return it }
        val inodes = readInodes() ?: return null
        val uroot = inodes.getOrNull(UROOT_INODE) ?: return null
        if ((uroot.mode and MODE_TYPE_MASK) != MODE_DIRECTORY ||
            uroot.size <= 0L || uroot.size > MAX_DIRECTORY_BYTES
        ) return null

        val directoryBytes = readInodeBytes(uroot, MAX_DIRECTORY_BYTES) ?: return null
        val parsed = parseDirectory(directoryBytes, inodes.size) ?: return null
        val result = ArrayList<OuterPfsFileInfo>(parsed.size)
        val names = HashSet<String>()

        for (entry in parsed) {
            if (entry.name == "." || entry.name == "..") continue
            if (!names.add(entry.name.lowercase(java.util.Locale.ROOT))) return null
            val inode = inodes.getOrNull(entry.inodeIndex) ?: return null
            val directory = entry.type.toLong() == DIRENT_DIRECTORY
            val expectedMode = if (directory) MODE_DIRECTORY else MODE_FILE
            if ((inode.mode and MODE_TYPE_MASK) != expectedMode) return null
            val blocks = resolveBlocks(inode) ?: return null
            result += OuterPfsFileInfo(
                name = entry.name,
                inodeIndex = entry.inodeIndex,
                size = inode.size,
                compressedSize = inode.compressedSize,
                blockCount = blocks.size,
                directory = directory,
            )
        }
        filesCache = result
        return result
    }

    /** Reads one small outer metadata file, such as naps_pkg_layout.dat. */
    fun readFile(name: String, maxBytes: Long = MAX_METADATA_FILE_BYTES): ByteArray? {
        if (name.isBlank() || maxBytes < 0L) return null
        val file = listUrootFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        if (file.directory || file.size < 0L || file.size > maxBytes) return null
        val inode = readInodes()?.getOrNull(file.inodeIndex) ?: return null
        return readInodeBytes(inode, maxBytes)
    }

    /** Reads naps_pkg_layout.dat after validating that pfs_image.dat is also present. */
    fun readNapsLayout(): ByteArray? {
        val files = listUrootFiles() ?: return null
        if (files.none { it.name.equals(PFS_IMAGE_NAME, ignoreCase = true) && !it.directory }) return null
        if (files.none { it.name.equals(NAPS_LAYOUT_NAME, ignoreCase = true) && !it.directory }) return null
        return readFile(NAPS_LAYOUT_NAME, MAX_NAPS_BYTES)
    }

    /** Builds and caches a validated NAPS map against the actual packed-image region length. */
    fun readNapsLogicalBlockMap(): NapsLogicalBlockMap? {
        napsMapCache?.let { return it }
        val naps = readNapsLayout() ?: return null
        val map = NapsLogicalBlockMap.parse(naps, packedImageSize) ?: return null
        napsMapCache = map
        return map
    }

    /**
     * Reads a logical NAPS block's stored/encoded payload from pfs_image.dat.
     * Kraken data is returned encoded; zero holes are represented without allocating
     * a full logical block; aliases resolve to their earlier physical payload block.
     */
    fun readNapsBlockPayload(blockIndex: Int): NapsPayloadRead? {
        val map = readNapsLogicalBlockMap() ?: return null
        return map.readPayload(blockIndex) { offset, length ->
            readPackedImageRegion(offset, length)
        }
    }

    /**
     * Reads up to 16 MiB from the reconstructed logical inner image without creating a
     * temporary full-size image. Returns null if the requested range needs Kraken decoding.
     */
    fun readNapsLogicalRange(offset: Long, length: Int): ByteArray? {
        val map = readNapsLogicalBlockMap() ?: return null
        return map.readLogicalRange(offset, length) { sourceOffset, sourceLength ->
            readPackedImageRegion(sourceOffset, sourceLength)
        }
    }

    /**
     * Opens the mapped inner PFS as a virtual random-access source, without constructing
     * a full temporary image file. The returned PFS reader must be closed before this reader.
     * It returns null when the PFS metadata touches a Kraken-compressed range.
     */
    fun openMappedInnerPfsReader(): PfsImageReader? {
        val map = readNapsLogicalBlockMap() ?: return null
        return PfsImageReader.open(NapsMappedPfsImageSource(map) { offset, length ->
            readPackedImageRegion(offset, length)
        })
    }

    /** Streams a raw-only reconstructed inner image to the caller-owned output stream. */
    fun writeUncompressedNapsImage(output: java.io.OutputStream): NapsImageBuildResult {
        val map = readNapsLogicalBlockMap() ?: return NapsImageBuildResult.INVALID_PAYLOAD
        return map.writeUncompressedImage(output) { offset, length ->
            readPackedImageRegion(offset, length)
        }
    }

    /**
     * Reads a byte range from the contiguous pfs_image.dat region. The caller supplies
     * a mapped range; this method never expands/decompresses the block data.
     */
    fun readPackedImageRegion(offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length < 0) return null
        val size = length.toLong()
        if (offset > packedImageSize || size > packedImageSize - offset) return null
        return readPfsBytes(offset, length)
    }

    override fun close() {
        raf.close()
    }

    private fun readInodes(): Array<OuterInode>? {
        inodeCache?.let { return it }
        val inodeCount = superblockInfo.inodeCount
        val inodeBlockCount = superblockInfo.inodeBlockCount
        if (inodeCount !in 3L..MAX_INODES.toLong() ||
            inodeBlockCount !in 1L..MAX_INODE_BLOCKS.toLong()
        ) return null

        val tableBytes = Math.multiplyExact(inodeBlockCount, BLOCK_SIZE)
        if (tableBytes > MAX_INODE_TABLE_BYTES || tableBytes > Int.MAX_VALUE) return null
        val requiredBytes = Math.multiplyExact(inodeCount, S32_INODE_SIZE.toLong())
        if (requiredBytes > tableBytes) return null

        val first = Math.multiplyExact(inodeTableStart.toLong(), BLOCK_SIZE)
        if (!rangeFits(first, tableBytes, pfsSize)) return null
        val raw = readPfsBytes(first, tableBytes.toInt()) ?: return null

        val parsed = Array(inodeCount.toInt()) { index ->
            val start = index * S32_INODE_SIZE
            val bytes = raw
            val mode = u16(bytes, start)
            val size = i64(bytes, start + 8)
            val compressedSize = i64(bytes, start + 16)
            val blocks = u32(bytes, start + S32_BLOCK_COUNT_OFFSET)
            val direct = IntArray(DIRECT_BLOCK_COUNT) { slot ->
                signedBlockIndex(bytes, start + S32_DIRECT_OFFSET + slot * BLOCK_SIG32_STRIDE + BLOCK_INDEX_OFFSET)
            }
            val indirect = IntArray(INDIRECT_LEVEL_COUNT) { slot ->
                signedBlockIndex(bytes, start + S32_INDIRECT_OFFSET + slot * BLOCK_SIG32_STRIDE + BLOCK_INDEX_OFFSET)
            }
            OuterInode(mode, size, compressedSize, blocks, direct, indirect)
        }
        inodeCache = parsed
        return parsed
    }

    private fun readInodeBytes(inode: OuterInode, maxBytes: Long): ByteArray? {
        if (inode.size < 0L || inode.size > maxBytes || inode.size > Int.MAX_VALUE) return null
        val blockIndexes = resolveBlocks(inode) ?: return null
        val requiredBlocks = if (inode.size == 0L) 0L else (inode.size + BLOCK_SIZE - 1L) / BLOCK_SIZE
        if (requiredBlocks > blockIndexes.size.toLong()) return null

        val output = ByteArray(inode.size.toInt())
        var written = 0
        for (blockIndex in blockIndexes) {
            if (written >= output.size) break
            val block = readRawBlock(blockIndex) ?: return null
            val take = minOf(BLOCK_SIZE.toInt(), output.size - written)
            block.copyInto(output, written, 0, take)
            written += take
        }
        return output.takeIf { written == output.size }
    }

    private fun resolveBlocks(inode: OuterInode): List<Int>? {
        if (inode.blockCount > blockCount.toLong() || inode.blockCount > MAX_FILE_BLOCKS) return null
        val expected = inode.blockCount.toInt()
        if (expected == 0) return emptyList()

        val result = ArrayList<Int>(expected)
        val directCount = minOf(DIRECT_BLOCK_COUNT, expected)
        for (slot in 0 until directCount) {
            val block = inode.directBlocks[slot]
            if (block < 0 || block >= blockCount) return null
            result += block
        }

        if (result.size < expected) {
            val visited = HashSet<Int>()
            var nodesVisited = 0
            for (level in 0 until INDIRECT_LEVEL_COUNT) {
                if (result.size >= expected) break
                val root = inode.indirectBlocks[level]
                if (root == 0) continue
                if (root < 0 || root >= blockCount) return null
                if (!collectIndirect(
                        rootBlock = root,
                        depth = level + 1,
                        expected = expected,
                        output = result,
                        visited = visited,
                        nodesVisited = { nodesVisited += 1; nodesVisited },
                    )
                ) return null
            }
        }
        return result.takeIf { it.size == expected }
    }

    private fun collectIndirect(
        rootBlock: Int,
        depth: Int,
        expected: Int,
        output: MutableList<Int>,
        visited: MutableSet<Int>,
        nodesVisited: () -> Int,
    ): Boolean {
        if (output.size >= expected) return true
        if (depth !in 1..INDIRECT_LEVEL_COUNT || rootBlock <= 0 || rootBlock >= blockCount) return false
        if (!visited.add(rootBlock) || nodesVisited() > MAX_INDIRECT_NODES) return false
        val raw = readRawBlock(rootBlock) ?: return false

        for (slot in 0 until INDIRECT_ENTRIES_PER_BLOCK) {
            if (output.size >= expected) break
            val child = signedBlockIndex(raw, slot * BLOCK_SIG32_STRIDE + BLOCK_INDEX_OFFSET)
            if (depth == 1) {
                if (child < 0 || child >= blockCount) return false
                output += child
            } else {
                if (child <= 0 || child >= blockCount ||
                    !collectIndirect(child, depth - 1, expected, output, visited, nodesVisited)
                ) return false
            }
        }
        return output.size >= expected
    }

    private fun parseDirectory(bytes: ByteArray, inodeCount: Int): List<OuterDirent>? {
        val result = ArrayList<OuterDirent>()
        var cursor = 0
        while (cursor < bytes.size) {
            if (bytes.size - cursor < DIRENT_HEADER_SIZE) {
                if (bytes.copyOfRange(cursor, bytes.size).all { it == 0.toByte() }) break
                return null
            }
            val inode = u32(bytes, cursor)
            val type = u32(bytes, cursor + 4)
            val nameLength = u32(bytes, cursor + 8)
            val entrySize = u32(bytes, cursor + 12)

            if (entrySize == 0L && bytes.copyOfRange(cursor, bytes.size).all { it == 0.toByte() }) break
            if (entrySize < DIRENT_HEADER_SIZE || entrySize % 8L != 0L ||
                entrySize > (bytes.size - cursor).toLong() ||
                inode >= inodeCount.toLong() ||
                nameLength !in 1L..MAX_NAME_BYTES ||
                DIRENT_HEADER_SIZE.toLong() + nameLength > entrySize
            ) return null

            val start = cursor + DIRENT_HEADER_SIZE
            val end = start + nameLength.toInt()
            val name = decodeUtf8(bytes.copyOfRange(start, end)) ?: return null
            if (type !in setOf(DIRENT_FILE, DIRENT_DIRECTORY, DIRENT_DOT, DIRENT_DOTDOT)) return null
            if (type != DIRENT_DOT && type != DIRENT_DOTDOT && !isSafeName(name)) return null

            result += OuterDirent(inode.toInt(), type.toInt(), name)
            if (result.size > MAX_DIRECTORY_ENTRIES) return null
            cursor += entrySize.toInt()
        }
        return result
    }

    private fun readRawBlock(blockIndex: Int): ByteArray? {
        if (blockIndex < 0 || blockIndex >= blockCount) return null
        return readPfsBytes(blockIndex.toLong() * BLOCK_SIZE, BLOCK_SIZE.toInt())
    }

    private fun readPfsBytes(relativeOffset: Long, length: Int): ByteArray? {
        if (length < 0 || !rangeFits(relativeOffset, length.toLong(), pfsSize)) return null
        val absolute = pfsOffset + relativeOffset
        val fileLength = try { raf.length() } catch (_: IOException) { return null }
        if (absolute < 0L || !rangeFits(absolute, length.toLong(), fileLength)) return null
        return try {
            ByteArray(length).also {
                raf.seek(absolute)
                raf.readFully(it)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private data class OuterInode(
        val mode: Int,
        val size: Long,
        val compressedSize: Long,
        val blockCount: Long,
        val directBlocks: IntArray,
        val indirectBlocks: IntArray,
    )

    private data class OuterDirent(val inodeIndex: Int, val type: Int, val name: String)

    companion object {
        private const val PFS_OFFSET = 0x10000L
        private const val BLOCK_SIZE = 0x10000L
        private const val SUPERBLOCK_MAGIC = 20130315L
        private const val SEED_OFFSET = 0x370
        private val PLAIN_NO_AUTH_SEED = "PPRPLAIN-NOAUTH!".toByteArray(Charsets.US_ASCII)

        private const val S32_INODE_SIZE = 0x2C8
        private const val S32_BLOCK_COUNT_OFFSET = 0x60
        private const val S32_DIRECT_OFFSET = 0x64
        private const val S32_INDIRECT_OFFSET = 0x214
        private const val BLOCK_SIG32_STRIDE = 36
        private const val BLOCK_INDEX_OFFSET = 32
        private const val DIRECT_BLOCK_COUNT = 12
        private const val INDIRECT_LEVEL_COUNT = 5
        private const val INDIRECT_ENTRIES_PER_BLOCK = BLOCK_SIZE.toInt() / BLOCK_SIG32_STRIDE

        private const val MODE_TYPE_MASK = 0xF000
        private const val MODE_DIRECTORY = 0x4000
        private const val MODE_FILE = 0x8000
        private const val DIRENT_HEADER_SIZE = 16
        private const val DIRENT_FILE = 2L
        private const val DIRENT_DIRECTORY = 3L
        private const val DIRENT_DOT = 4L
        private const val DIRENT_DOTDOT = 5L
        private const val UROOT_INODE = 2
        private const val MAX_OUTER_BLOCKS = 1_000_000L
        private const val MAX_INODES = 150_000
        private const val MAX_INODE_BLOCKS = 2048
        private const val MAX_INODE_TABLE_BYTES = 128L * 1024L * 1024L
        private const val MAX_FILE_BLOCKS = 1_000_000L
        private const val MAX_INDIRECT_NODES = 100_000
        private const val MAX_DIRECTORY_ENTRIES = 100_000
        private const val MAX_NAME_BYTES = 1024L
        private const val MAX_DIRECTORY_BYTES = 16L * 1024L * 1024L
        private const val MAX_METADATA_FILE_BYTES = 64L * 1024L * 1024L
        private const val MAX_NAPS_BYTES = 32L * 1024L * 1024L
        private const val PFS_IMAGE_NAME = "pfs_image.dat"
        private const val NAPS_LAYOUT_NAME = "naps_pkg_layout.dat"

        fun open(file: File, probe: PkgProbeResult): OuterPfsPlainReader? {
            if (!file.isFile || probe.kind != PkgProbeResult.Kind.PS5_FIH ||
                probe.fihSignedByte != 0x00
            ) return null

            val pfsSize = probe.pfsSize ?: return null
            val superblockAbsolute = probe.pfsSuperblockOffset ?: return null
            if (pfsSize < BLOCK_SIZE || pfsSize % BLOCK_SIZE != 0L) return null
            val blockCountLong = pfsSize / BLOCK_SIZE
            if (blockCountLong !in 3L..MAX_OUTER_BLOCKS || blockCountLong > Int.MAX_VALUE) return null

            val relativeSuperblock = superblockAbsolute - PFS_OFFSET
            if (relativeSuperblock < 0L || relativeSuperblock % BLOCK_SIZE != 0L) return null
            val superblockIndexLong = relativeSuperblock / BLOCK_SIZE
            if (superblockIndexLong !in 1 until blockCountLong) return null
            val superblockIndex = superblockIndexLong.toInt()

            val info = probe.pfsSuperblock ?: try {
                RandomAccessFile(file, "r").use { raf ->
                    PfsSuperblockReader.read(raf, superblockAbsolute, pfsSize - relativeSuperblock)
                }
            } catch (_: IOException) {
                null
            } ?: return null

            // Only this plaintext/no-auth marker permits raw reads of metadata blocks.
            if (info.blockSize != BLOCK_SIZE || info.encrypted || info.wideInodes) return null
            val raf = try { RandomAccessFile(file, "r") } catch (_: IOException) { return null }
            try {
                val fileLength = raf.length()
                val segmentEnd = PFS_OFFSET + pfsSize
                if (!rangeFits(PFS_OFFSET, pfsSize, fileLength) ||
                    !rangeFits(superblockAbsolute, 0x5A0L, segmentEnd)
                ) {
                    raf.close()
                    return null
                }

                val superblock = ByteArray(0x5A0)
                raf.seek(superblockAbsolute)
                raf.readFully(superblock)
                if (readI64(superblock, 0x08) != SUPERBLOCK_MAGIC ||
                    !superblock.copyOfRange(SEED_OFFSET, SEED_OFFSET + 16).contentEquals(PLAIN_NO_AUTH_SEED)
                ) {
                    raf.close()
                    return null
                }

                var indirectCount = 0
                for (level in 0 until INDIRECT_LEVEL_COUNT) {
                    val pointer = readI64(superblock, 0x50 + 0x244 + level * 40 + 8)
                    if (pointer < 0L || pointer >= blockCountLong) {
                        if (pointer != 0L) {
                            raf.close()
                            return null
                        }
                    } else if (pointer > 0L) {
                        indirectCount++
                    }
                }

                val inodeStartLong = superblockIndexLong + 1L + indirectCount.toLong()
                val inodeBlocks = info.inodeBlockCount
                if (inodeBlocks <= 0L || inodeBlocks > MAX_INODE_BLOCKS ||
                    inodeStartLong < 0L || inodeStartLong > blockCountLong ||
                    inodeBlocks > blockCountLong - inodeStartLong
                ) {
                    raf.close()
                    return null
                }
                val inodeTableBytes = Math.multiplyExact(inodeBlocks, BLOCK_SIZE)
                if (inodeTableBytes > MAX_INODE_TABLE_BYTES) {
                    raf.close()
                    return null
                }

                return OuterPfsPlainReader(
                    raf = raf,
                    pfsOffset = PFS_OFFSET,
                    pfsSize = pfsSize,
                    blockCount = blockCountLong.toInt(),
                    superblockIndex = superblockIndex,
                    superblockInfo = info,
                    inodeTableStart = inodeStartLong.toInt(),
                )
            } catch (_: IOException) {
                raf.close()
                return null
            } catch (_: ArithmeticException) {
                raf.close()
                return null
            } catch (_: SecurityException) {
                raf.close()
                return null
            }
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun u32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)

        private fun i64(bytes: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

        private fun readI64(bytes: ByteArray, offset: Int): Long = i64(bytes, offset)

        private fun signedBlockIndex(bytes: ByteArray, offset: Int): Int {
            val value = u32(bytes, offset)
            return if (value > Int.MAX_VALUE.toLong()) -1 else value.toInt()
        }

        private fun rangeFits(offset: Long, size: Long, containerSize: Long): Boolean {
            if (offset < 0L || size < 0L || containerSize < 0L) return false
            return offset <= containerSize && size <= containerSize - offset
        }

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }

        private fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." &&
                name.none { it == '\u0000' || it == '/' || it == '\\' }
    }
}


/** Read-only summary surfaced by the package inspector. */
enum class OuterPfsInspectionState {
    NOT_SUPPORTED,
    INVALID_OUTER_PFS,
    NAPS_NOT_RECOGNIZED,
    MAPPED,
}

data class OuterPfsInspectionSummary(
    val state: OuterPfsInspectionState,
    val fileNames: List<String> = emptyList(),
    val packedImageSize: Long? = null,
    val logicalImageSize: Long? = null,
    val logicalBlockCount: Int? = null,
    val storedBlockCount: Int = 0,
    val krakenBlockCount: Int = 0,
    val zeroHoleCount: Int = 0,
    val aliasBlockCount: Int = 0,
    val firstPayloadReadable: Boolean? = null,
)

/**
 * Performs the potentially expensive Debug/Homebrew outer-PFS/NAPS probe off the UI thread.
 * Retail or encrypted packages remain explicitly unsupported here.
 */
object OuterPfsInspector {
    fun inspect(file: File, probe: PkgProbeResult): OuterPfsInspectionSummary {
        if (probe.fihSignedByte != 0x00) {
            return OuterPfsInspectionSummary(OuterPfsInspectionState.NOT_SUPPORTED)
        }
        val reader = try {
            OuterPfsPlainReader.open(file, probe)
        } catch (_: IOException) {
            null
        } catch (_: ArithmeticException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return OuterPfsInspectionSummary(OuterPfsInspectionState.NOT_SUPPORTED)

        return try {
            reader.use { pfs ->
                val files = pfs.listUrootFiles()
                    ?: return OuterPfsInspectionSummary(OuterPfsInspectionState.INVALID_OUTER_PFS)
                val names = files.map { it.name }.take(20)
                val packedSize = pfs.packedImageSize
                val map = pfs.readNapsLogicalBlockMap()
                    ?: return OuterPfsInspectionSummary(
                        state = OuterPfsInspectionState.NAPS_NOT_RECOGNIZED,
                        fileNames = names,
                        packedImageSize = packedSize,
                    )
                val firstPayloadReadable = if (map.blocks.isEmpty()) {
                    false
                } else {
                    map.readPayload(0) { offset, length ->
                        pfs.readPackedImageRegion(offset, length)
                    } != null
                }
                val counts = map.blocks.groupingBy { it.storage }.eachCount()
                OuterPfsInspectionSummary(
                    state = OuterPfsInspectionState.MAPPED,
                    fileNames = names,
                    packedImageSize = packedSize,
                    logicalImageSize = map.logicalSize,
                    logicalBlockCount = map.blocks.size,
                    storedBlockCount = counts[NapsBlockStorage.STORED] ?: 0,
                    krakenBlockCount = counts[NapsBlockStorage.KRAKEN] ?: 0,
                    zeroHoleCount = counts[NapsBlockStorage.ZERO_HOLE] ?: 0,
                    aliasBlockCount = counts[NapsBlockStorage.ALIAS] ?: 0,
                    firstPayloadReadable = firstPayloadReadable,
                )
            }
        } catch (_: IOException) {
            OuterPfsInspectionSummary(OuterPfsInspectionState.INVALID_OUTER_PFS)
        } catch (_: ArithmeticException) {
            OuterPfsInspectionSummary(OuterPfsInspectionState.INVALID_OUTER_PFS)
        } catch (_: SecurityException) {
            OuterPfsInspectionSummary(OuterPfsInspectionState.INVALID_OUTER_PFS)
        }
    }
}
