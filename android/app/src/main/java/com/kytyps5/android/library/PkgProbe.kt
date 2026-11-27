package com.kytyps5.android.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Conservative PS5 package probe.
 *
 * It validates FIH/CNT structure. Debug FIH images expose a plaintext outer-PFS
 * superblock; retail FIH images encrypt that superblock, so only their segment
 * geometry and readable CNT metadata are checked. This class never decrypts,
 * extracts, installs, or launches package content.
 */
data class PkgProbeResult(
    val kind: Kind,
    val cntOffset: Long? = null,
    val cntSize: Long? = null,
    /** Raw FIH byte at 0x05: 0x00 debug, 0x80 retail/submitted in the documented layout. */
    val fihSignedByte: Int? = null,
    /** Exact PFS segment byte length from the FIH header. */
    val pfsSize: Long? = null,
    /** Outer-PFS mode bit 2 from a readable Debug superblock; null when the superblock is encrypted. */
    val pfsSuperblock: PfsSuperblockInfo? = null,
) {
    val pfsEncrypted: Boolean? get() = pfsSuperblock?.encrypted
    enum class Kind {
        PS5_FIH,
        PS5_LIH,
        NOT_PS5_PKG,
        MALFORMED_PS5_PKG,
    }
}

object PkgProbe {
    private const val MAGIC_SIZE = 4
    private const val FIH_HEADER_SIZE = 0x10000L
    private const val FIH_SIGNED_BYTE_FIELD = 0x05L
    private const val FIH_PFS_OFFSET_FIELD = 0x10L
    private const val FIH_PFS_SIZE_FIELD = 0x18L
    private const val FIH_CNT_OFFSET_FIELD = 0x58L
    private const val FIH_CNT_SIZE_FIELD = 0xA0L
    private const val REQUIRED_PFS_OFFSET = 0x10000L
    private const val PFS_BLOCK_SIZE = 0x10000L
    private const val CNT_HEADER_MIN_SIZE = 0x5A0L

    fun probe(file: File): PkgProbeResult {
        if (!file.isFile || file.length() < MAGIC_SIZE) {
            return PkgProbeResult(PkgProbeResult.Kind.NOT_PS5_PKG)
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(MAGIC_SIZE)
                raf.readFully(magic)
                when (String(magic, Charsets.US_ASCII)) {
                    "\u007fFIH" -> probeFih(raf, file.length())
                    // LIH is signature recognition only; its internal layout is unknown here.
                    "\u007fLIH" -> PkgProbeResult(PkgProbeResult.Kind.PS5_LIH)
                    else -> PkgProbeResult(PkgProbeResult.Kind.NOT_PS5_PKG)
                }
            }
        } catch (_: IOException) {
            PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }
    }

    /** Parse only bounded CNT metadata from a validated FIH package. */
    fun readMetadata(file: File): PkgCntMetadata? {
        val result = probe(file)
        if (result.kind != PkgProbeResult.Kind.PS5_FIH) return null
        val offset = result.cntOffset ?: return null
        val size = result.cntSize ?: return null
        return PkgCntReader.read(file, offset, size)
    }

    private fun probeFih(raf: RandomAccessFile, fileSize: Long): PkgProbeResult {
        if (fileSize < FIH_HEADER_SIZE) return malformed()

        raf.seek(FIH_SIGNED_BYTE_FIELD)
        val fihSignedByte = raf.readUnsignedByte()
        raf.seek(FIH_PFS_OFFSET_FIELD)
        val pfsOffset = readLe64(raf)
        raf.seek(FIH_PFS_SIZE_FIELD)
        val pfsSize = readLe64(raf)
        raf.seek(FIH_CNT_OFFSET_FIELD)
        val cntOffset = readLe64(raf)
        raf.seek(FIH_CNT_SIZE_FIELD)
        val cntSize = readLe64(raf)

        // FIH has a fixed 64 KiB header followed by PFS, CNT (SC), then SI.
        if (pfsOffset != REQUIRED_PFS_OFFSET || pfsSize < PFS_BLOCK_SIZE ||
            !rangeFits(pfsOffset, pfsSize, fileSize)
        ) return malformed()

        if (fihSignedByte != 0x00 && fihSignedByte != 0x80) return malformed()

        var pfsSuperblock: PfsSuperblockInfo? = null
        if (fihSignedByte == 0x00) {
            // Debug FIH exposes this superblock. Validate the full fixed geometry,
            // but do not traverse inode or data blocks at this stage.
            pfsSuperblock = PfsSuperblockReader.read(raf, pfsOffset, pfsSize) ?: return malformed()
        }
        // A retail FIH encrypts the outer-PFS superblock itself. Do not interpret
        // ciphertext as a PFS header; retain only the FIH range checks and CNT probe.

        // rangeFits proves this addition cannot overflow.
        val pfsEnd = pfsOffset + pfsSize
        if (cntOffset < pfsEnd || cntSize < CNT_HEADER_MIN_SIZE ||
            !rangeFits(cntOffset, cntSize, fileSize)
        ) return malformed()

        raf.seek(cntOffset)
        val cntMagic = ByteArray(MAGIC_SIZE)
        raf.readFully(cntMagic)
        if (!cntMagic.contentEquals(byteArrayOf(0x7f, 'C'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte()))) {
            return malformed()
        }

        return PkgProbeResult(PkgProbeResult.Kind.PS5_FIH, cntOffset, cntSize, fihSignedByte, pfsSize, pfsSuperblock)
    }

    private fun malformed() =
        PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)

    private fun rangeFits(offset: Long, size: Long, containerSize: Long): Boolean {
        if (offset < 0L || size < 0L || containerSize < 0L) return false
        return offset <= containerSize && size <= containerSize - offset
    }


}
