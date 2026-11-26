package com.kytyps5.android.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Conservative PS5 package probe.
 *
 * It identifies known PS5 outer-container signatures and, for FIH, validates
 * the documented PFS range without decrypting or extracting package contents.
 * It does not make a package launchable.
 */
data class PkgProbeResult(
    val kind: Kind,
) {
    enum class Kind {
        PS5_FIH,
        PS5_LIH,
        NOT_PS5_PKG,
        MALFORMED_PS5_PKG,
    }
}

object PkgProbe {
    private const val MAGIC_SIZE = 4
    private const val FIH_HEADER_MIN_SIZE = 0x20L
    private const val FIH_PFS_OFFSET = 0x10L
    private const val FIH_PFS_SIZE = 0x18L

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
                    // LIH is a recognized patch-container signature only. Its
                    // internal layout is intentionally not claimed as validated.
                    "\u007fLIH" -> PkgProbeResult(PkgProbeResult.Kind.PS5_LIH)
                    else -> PkgProbeResult(PkgProbeResult.Kind.NOT_PS5_PKG)
                }
            }
        } catch (_: IOException) {
            PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }
    }

    private fun probeFih(raf: RandomAccessFile, fileSize: Long): PkgProbeResult {
        if (fileSize < FIH_HEADER_MIN_SIZE) {
            return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }

        raf.seek(FIH_PFS_OFFSET)
        val pfsOffset = readLe64(raf)
        raf.seek(FIH_PFS_SIZE)
        val pfsSize = readLe64(raf)

        // A PFS region must be non-empty, begin after the fixed FIH header,
        // and fit using subtraction so offset + size cannot overflow.
        if (pfsOffset < FIH_HEADER_MIN_SIZE || pfsSize <= 0L ||
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
        val b = ByteArray(8)
        raf.readFully(b)
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((b[i].toLong() and 0xffL) shl (i * 8))
        }
        return value
    }
}
