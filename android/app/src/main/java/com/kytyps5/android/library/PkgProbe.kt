package com.kytyps5.android.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Conservative PS5 package probe.
 *
 * It recognizes known outer-container signatures and validates the documented
 * FIH PFS segment bounds. It does not parse, decrypt, extract, or launch content.
 */
data class PkgProbeResult(val kind: Kind) {
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
    private const val FIH_PFS_OFFSET_FIELD = 0x10L
    private const val FIH_PFS_SIZE_FIELD = 0x18L
    private const val REQUIRED_PFS_OFFSET = 0x10000L

    /** Probe a package without reading its payload into memory. */
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
                    // LIH is signature recognition only; its layout is not validated.
                    "\u007fLIH" -> PkgProbeResult(PkgProbeResult.Kind.PS5_LIH)
                    else -> PkgProbeResult(PkgProbeResult.Kind.NOT_PS5_PKG)
                }
            }
        } catch (_: IOException) {
            PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }
    }

    private fun probeFih(raf: RandomAccessFile, fileSize: Long): PkgProbeResult {
        if (fileSize < FIH_HEADER_SIZE) {
            return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }

        raf.seek(FIH_PFS_OFFSET_FIELD)
        val pfsOffset = readLe64(raf)
        raf.seek(FIH_PFS_SIZE_FIELD)
        val pfsSize = readLe64(raf)

        // FIH places the PFS image immediately after its fixed 64 KiB header.
        // Subtraction-based bounds checking avoids offset + size overflow.
        if (pfsOffset != REQUIRED_PFS_OFFSET || pfsSize <= 0L ||
            !rangeFits(pfsOffset, pfsSize, fileSize)
        ) {
            return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }
        return PkgProbeResult(PkgProbeResult.Kind.PS5_FIH)
    }

    private fun rangeFits(offset: Long, size: Long, containerSize: Long): Boolean {
        if (offset < 0L || size < 0L || containerSize < 0L) return false
        return offset <= containerSize && size <= containerSize - offset
    }

    private fun readLe64(raf: RandomAccessFile): Long {
        val bytes = ByteArray(8)
        raf.readFully(bytes)
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[i].toLong() and 0xffL) shl (i * 8))
        }
        return value
    }
}
