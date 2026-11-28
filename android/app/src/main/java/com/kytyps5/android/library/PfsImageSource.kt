package com.kytyps5.android.library

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bounded random-access byte source used by the inner PFS reader.
 * Ownership is transferred to PfsImageReader.open(source); close the returned reader
 * to release owned resources. A mapped NAPS source does not own its outer-PFS parent.
 */
internal interface PfsImageSource : Closeable {
    val length: Long
    fun readAt(offset: Long, length: Int): ByteArray?
}

internal class FilePfsImageSource(file: File) : PfsImageSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    override fun readAt(offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length < 0 || offset > this.length ||
            length.toLong() > this.length - offset
        ) return null
        return try {
            ByteArray(length).also {
                raf.seek(offset)
                raf.readFully(it)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override fun close() {
        raf.close()
    }
}

/**
 * Virtual inner-image source backed by NAPS mappings. It reads only requested logical ranges
 * and avoids materializing an entire PFS image on temporary storage.
 */
internal class NapsMappedPfsImageSource(
    private val map: NapsLogicalBlockMap,
    private val readPackedRange: (offset: Long, length: Int) -> ByteArray?,
) : PfsImageSource {
    override val length: Long
        get() = map.logicalSize

    override fun readAt(offset: Long, length: Int): ByteArray? =
        map.readLogicalRange(offset, length, readPackedRange)

    override fun close() {
        // The enclosing OuterPfsPlainReader owns the packed PKG file handle.
    }
}
