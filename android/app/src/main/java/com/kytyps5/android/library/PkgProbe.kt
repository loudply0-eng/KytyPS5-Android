package com.kytyps5.android.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Conservative PS5 package probe.
 *
 * This class only identifies known PS5 outer-container signatures and validates
 * file-size fields that are safe to validate without decrypting or extracting.
 * It does NOT parse PFS/CNT entries, decrypt packages, or make a package launchable.
 */
data class PkgProbeResult(
    val kind: Kind,
    val packageSize: Long? = null,
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
    private const val HEADER_MIN_SIZE = 0x60L
    private const val PACKAGE_SIZE_OFFSET = 0x18L
    private const val FIH_PFS_OFFSET = 0x10L
    private const val FIH_PFS_SIZE = 0x18L
    private const val MAX_HEADER_READ = 0x10000

    /** Probe a package without reading its payload into memory. */
    fun probe(file: File): PkgProbeResult {
        if (!file.isFile || file.length() < MAGIC_SIZE) return PkgProbeResult(PkgProbeResult.Kind.NOT_PS5_PKG)

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(MAGIC_SIZE)
                raf.readFully(magic)

                val kind = when (String(magic, Charsets.US_ASCII)) {
                    "\u007fFIH" -> PkgProbeResult.Kind.PS5_FIH
                    "\u007fLIH" -> PkgProbeResult.Kind.PS5_LIH
                    else -> return PkgProbeResult(PkgProbeResult.Kind.NOT_PS5_PKG)
                }

                if (file.length() < HEADER_MIN_SIZE) {
                    return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
                }

                if (file.length() > Long.MAX_VALUE - 0L) {
                    return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
                }

                raf.seek(PACKAGE_SIZE_OFFSET)
                val packageSize = readLe64(raf)
                if (packageSize <= 0L || packageSize > file.length()) {
                    return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
                }

                if (kind == PkgProbeResult.Kind.PS5_FIH) {
                    raf.seek(FIH_PFS_OFFSET)
                    val pfsOffset = readLe64(raf)
                    raf.seek(FIH_PFS_SIZE)
                    val pfsSize = readLe64(raf)
                    if (!rangeFits(pfsOffset, pfsSize, packageSize)) {
                        return PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
                    }
                }

                PkgProbeResult(kind, packageSize)
            }
        } catch (_: IOException) {
            PkgProbeResult(PkgProbeResult.Kind.MALFORMED_PS5_PKG)
        }
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
