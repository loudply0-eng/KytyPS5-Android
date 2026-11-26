package com.kytyps5.android

import android.util.Log
import com.kytyps5.android.library.ParamSfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONException
import org.json.JSONObject

/**
 * A game the emulator can load: an extracted dump folder (contains sce_sys/param.json) or a
 * dump image the emulator mounts read-only without extracting: a ZArchive file (*.zar) or an
 * exFAT disk image (*.exfat).
 * [titleId] and [icon] (sce_sys/icon0.png) are known for extracted folders only.
 */
data class GameEntry(
    val folder: File,
    val title: String,
    val titleId: String? = null,
    val icon: File? = null,
) {
    val path: String get() = folder.absolutePath
}

sealed interface ScanResult {
    data class Found(val games: List<GameEntry>) : ScanResult
    data class Failure(val message: String) : ScanResult
}

object GameLibrary {
    private const val TAG = "GameLibrary"
    const val PARAM_JSON = "sce_sys/param.json"
    const val PARAM_SFO = "sce_sys/param.sfo"
    const val ICON = "sce_sys/icon0.png"
    private const val MAX_SFO_BYTES = 64 * 1024
    private const val MAX_PARAM_JSON_BYTES = 1024 * 1024
    const val ZARCHIVE_EXTENSION = "zar"
    const val EXFAT_EXTENSION = "exfat"
    const val IMG_EXTENSION = "img"

    /** Subtitle for a dump image in the grid; empty for anything else. */
    fun imageLabel(file: File): String = when (file.extension.lowercase()) {
        ZARCHIVE_EXTENSION -> "ZArchive"
        EXFAT_EXTENSION -> "exFAT image"
        IMG_EXTENSION -> "exFAT disk image"
        else -> ""
    }

    /** How deep below the chosen folder to look; dumps are often nested one or two levels. */
    const val MAX_SCAN_DEPTH = 3

    fun scan(root: File): ScanResult {
        if (!root.exists()) return ScanResult.Failure("Folder not found: ${root.path}")
        if (!root.isDirectory) return ScanResult.Failure("Not a folder: ${root.path}")
        if (root.listFiles() == null) {
            return ScanResult.Failure("Cannot read ${root.path}. Check all-files access.")
        }
        val games = mutableListOf<GameEntry>()
        collect(root, depth = 0, into = games)
        return ScanResult.Found(games.sortedBy { it.title.lowercase() })
    }

    /** The chosen folder may itself be a game; otherwise look below it, without entering games. */
    private fun collect(folder: File, depth: Int, into: MutableList<GameEntry>) {
        if (isGameFolder(folder)) {
            into += readEntry(folder)
            return
        }
        val children = folder.listFiles() ?: return
        children
            .filter { it.isFile && isSupportedImage(it) }
            .mapTo(into) { GameEntry(folder = it, title = it.nameWithoutExtension) }
        if (depth >= MAX_SCAN_DEPTH) return
        children.filter { it.isDirectory && !it.name.startsWith(".") }
            .forEach { collect(it, depth + 1, into) }
    }

    private fun isSupportedImage(file: File): Boolean = when (file.extension.lowercase()) {
        ZARCHIVE_EXTENSION, EXFAT_EXTENSION -> true
        // .img is generic; list it only when the contents identify an exFAT volume.
        IMG_EXTENSION -> isExfatImage(file)
        else -> false
    }

    /** Mirrors the native exFAT probe for raw volumes and MBR/GPT-partitioned disk images. */
    private fun isExfatImage(file: File): Boolean {
        if (!file.isFile || file.length() < 512L) return false
        return try {
            RandomAccessFile(file, "r").use { image ->
            fun readSector(lba: Long): ByteArray? {
                if (lba < 0L || lba > (image.length() - 512L) / 512L) return null
                return ByteArray(512).also { bytes ->
                    image.seek(lba * 512L)
                    image.readFully(bytes)
                }
            }
            fun u32(bytes: ByteArray, offset: Int): Long =
                ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            fun u64(bytes: ByteArray, offset: Int): Long =
                ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

            fun validBoot(sector: ByteArray?, base: Long): Boolean {
                if (sector == null || String(sector, 3, 8, Charsets.US_ASCII) != "EXFAT   ") return false
                if (sector[510] != 0x55.toByte() || sector[511] != 0xAA.toByte()) return false
                if ((11 until 64).any { sector[it] != 0.toByte() }) return false
                val bytesShift = sector[108].toInt() and 0xFF
                val clusterShift = sector[109].toInt() and 0xFF
                val fatCount = sector[110].toInt() and 0xFF
                if (bytesShift !in 9..12 || bytesShift + clusterShift > 25 || fatCount !in 1..2) return false
                val volumeLength = u64(sector, 72)
                val fatOffset = u32(sector, 80)
                val fatLength = u32(sector, 84)
                val heapOffset = u32(sector, 88)
                val clusterCount = u32(sector, 92)
                val rootCluster = u32(sector, 96)
                if (volumeLength <= 0L || fatLength == 0L || fatOffset < 24L) return false
                if (heapOffset < fatOffset + fatCount.toLong() * fatLength) return false
                if (fatLength * (1L shl bytesShift) < (clusterCount + 2L) * 4L) return false
                if (clusterCount == 0L || rootCluster < 2L || rootCluster >= 2L + clusterCount) return false
                val heapEnd = heapOffset + (clusterCount shl clusterShift)
                if (heapEnd < heapOffset || heapEnd > volumeLength) return false
                val sectorBytes = 1L shl bytesShift
                return base >= 0L && base <= image.length() && volumeLength <= (image.length() - base) / sectorBytes
            }

            if (validBoot(readSector(0), 0L)) return true
            val mbr = readSector(0) ?: return@use false
            if (mbr[510] != 0x55.toByte() || mbr[511] != 0xAA.toByte()) return@use false
            for (index in 0 until 4) {
                val entry = 446 + index * 16
                if ((mbr[entry + 4].toInt() and 0xFF) != 0) {
                    val lba = u32(mbr, entry + 8)
                    if (lba > 0L && validBoot(readSector(lba), lba * 512L)) return true
                }
            }
            val gpt = readSector(1) ?: return@use false
            if (String(gpt, 0, 8, Charsets.US_ASCII) != "EFI PART") return@use false
            val entriesLba = u64(gpt, 72)
            val entryCount = minOf(u32(gpt, 80), 128L).toInt()
            val entryBytes = u32(gpt, 84)
            if (entriesLba < 0L || entryBytes !in 128L..512L) return@use false
            // Bound the LBA before multiplying: a malformed GPT can otherwise overflow Long.
            if (entriesLba > (image.length() - 128L) / 512L) return@use false
            val entriesOffset = entriesLba * 512L
            for (index in 0 until entryCount) {
                val offset = entriesOffset + index.toLong() * entryBytes
                if (offset < entriesOffset || offset > image.length() - 128L) break
                image.seek(offset)
                val entry = ByteArray(128)
                image.readFully(entry)
                val firstLba = u64(entry, 32)
                if (firstLba > 0L && validBoot(readSector(firstLba), firstLba * 512L)) return true
            }
                false
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isGameFolder(folder: File): Boolean = File(folder, PARAM_JSON).isFile

    /** Title (param.json, then param.sfo TITLE, then folder name), title id and icon of a game folder. */
    fun readEntry(folder: File): GameEntry {
        val json = readParamJson(folder)
        val sfo = readParamSfo(folder)
        val title = json?.let { titleFromParamJson(it, "") }?.ifEmpty { null }
            ?: (sfo?.get("TITLE") as? String)?.trim()?.ifEmpty { null }
            ?: folder.name
        val titleId = json?.stringOr("titleId")?.trim()?.ifEmpty { null }
            ?: (sfo?.get("TITLE_ID") as? String)?.trim()?.ifEmpty { null }
        return GameEntry(folder, title, titleId, File(folder, ICON).takeIf { it.isFile })
    }

    private fun readParamJson(folder: File): JSONObject? = try {
        val file = File(folder, PARAM_JSON)
        if (file.length() > MAX_PARAM_JSON_BYTES) throw IOException("${file.path} is too large")
        JSONObject(file.readText())
    } catch (e: JSONException) {
        Log.w(TAG, "Malformed $PARAM_JSON in ${folder.path}", e)
        null
    } catch (e: IOException) {
        null
    }

    private fun readParamSfo(folder: File): Map<String, Any>? {
        val file = File(folder, PARAM_SFO)
        if (!file.isFile || file.length() > MAX_SFO_BYTES) return null
        return try {
            ParamSfo.parse(file.readBytes())
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Picks the display title: default-language localized titleName, then "en", then any other
     * localized titleName, then the top-level titleName. Falls back to [fallback].
     */
    fun titleFromParamJson(json: JSONObject, fallback: String): String {
        val candidates = mutableListOf<JSONObject>()
        json.optJSONObject("localizedParameters")?.let { localized ->
            val defaultLanguage = localized.stringOr("defaultLanguage", "en")
            localized.optJSONObject(defaultLanguage)?.let(candidates::add)
            localized.optJSONObject("en")?.let(candidates::add)
            localized.keys().forEach { key ->
                if (key != "defaultLanguage") {
                    localized.optJSONObject(key)?.let(candidates::add)
                }
            }
        }
        candidates.add(json)
        return candidates
            .asSequence()
            .map { it.stringOr("titleName").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: fallback
    }
}
