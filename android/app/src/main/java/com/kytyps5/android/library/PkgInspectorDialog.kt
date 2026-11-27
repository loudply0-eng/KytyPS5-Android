package com.kytyps5.android.library

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kytyps5.android.GameEntry
import com.kytyps5.android.LauncherI18n
import com.kytyps5.android.LauncherSettings
import org.json.JSONObject
import java.util.Locale

/**
 * Read-only inspector for PS5 FIH packages.
 *
 * This deliberately reports the package structure and readable CNT metadata only. It does not
 * decrypt PFS, install packages, process licences, or pass a PKG to the emulator.
 */
class PkgInspectorDialog(
    private val activity: Activity,
    private val settings: LauncherSettings,
) {
    private val density = activity.resources.displayMetrics.density
    private val Int.dp: Int get() = (this * density).toInt()
    private fun tr(value: String): String = LauncherI18n.translate(value, settings.language)

    fun show(game: GameEntry, probe: PkgProbeResult, metadata: PkgCntMetadata?) {
        val rtl = LauncherI18n.isRtl(settings.language)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
        }

        val json = metadata?.paramJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val mode = when (probe.fihSignedByte) {
            0x00 -> tr("Debug package")
            0x80 -> tr("Retail / submitted")
            else -> tr("Unknown")
        }
        val pfsSize = probe.pfsSize
        val plainCount = metadata?.entries?.count { !it.encrypted } ?: 0
        val encryptedCount = metadata?.entries?.count { it.encrypted } ?: 0

        addSection(content, tr("Package details"))
        addRow(content, tr("Package file"), game.folder.name)
        addRow(content, tr("File size"), formatBytes(game.folder.length()))
        addRow(content, tr("FIH mode"), mode)
        addRow(content, tr("PFS segment"), pfsSize?.let(::formatBytes) ?: tr("Not available"))
        addRow(
            content,
            tr("Outer PFS encryption"),
            probe.pfsEncrypted?.let { tr(if (it) "Encrypted" else "Plaintext") } ?: tr("Not available"),
        )
        addRow(content, tr("CNT metadata segment"), probe.cntSize?.let(::formatBytes) ?: tr("Not available"))
        addRow(content, tr("CNT entries"), metadata?.entryCount?.toString() ?: tr("Not available"))
        addRow(content, tr("Plain entries"), plainCount.toString())
        addRow(content, tr("Encrypted entries"), encryptedCount.toString())
        addRow(content, tr("Cover image"), tr(if (metadata?.iconPng != null) "Available" else "Not found"))
        addRow(content, tr("Content ID"), game.contentId ?: tr("Not available"))
        addRow(content, tr("Game ID"), game.titleId ?: json?.optString("masterDataId")?.takeIf(::hasValue) ?: tr("Not available"))
        addRow(
            content,
            tr("Application version"),
            json?.optString("applicationVersion")?.takeIf(::hasValue)
                ?: json?.optString("version")?.takeIf(::hasValue)
                ?: tr("Not available"),
        )

        addSection(content, tr("Package file entries"))
        val entries = metadata?.entries.orEmpty()
        if (entries.isEmpty()) {
            addRow(content, tr("Entries"), tr("Not available"))
        } else {
            entries.take(MAX_VISIBLE_ENTRIES).forEach { entry ->
                val id = "0x" + java.lang.Long.toHexString(entry.id).uppercase(Locale.US).padStart(4, '0')
                val name = entry.name?.take(MAX_NAME_LENGTH) ?: tr("Unnamed entry")
                val state = tr(if (entry.encrypted) "Encrypted" else "Plaintext")
                val line = "$id · $name · ${formatBytes(entry.dataSize)} · $state"
                content.addView(TextView(activity).apply {
                    text = line
                    textSize = 12f
                    setPadding(0, 4.dp, 0, 4.dp)
                })
            }
            if (entries.size > MAX_VISIBLE_ENTRIES) {
                addRow(content, tr("Showing first 60 entries"), (entries.size - MAX_VISIBLE_ENTRIES).toString())
            }
        }

        addSection(content, tr("Important limitation"))
        content.addView(TextView(activity).apply {
            text = tr(
                "This build can inspect readable PKG metadata and covers only. It does not decrypt or launch commercial PKG files. Use an extracted game dump or a supported game image to test the emulator.",
            )
            textSize = 13f
            setPadding(0, 4.dp, 0, 8.dp)
        })

        val scroll = ScrollView(activity).apply {
            layoutDirection = content.layoutDirection
            addView(content)
        }
        AlertDialog.Builder(activity)
            .setTitle(game.title)
            .setView(scroll)
            .setPositiveButton(tr("OK"), null)
            .show()
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 12.dp, 0, 4.dp)
        })
    }

    private fun addRow(parent: LinearLayout, label: String, value: String) {
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = parent.layoutDirection
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 3.dp, 0, 3.dp)
            addView(TextView(activity).apply {
                text = label
                textSize = 12f
                alpha = 0.72f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            addView(TextView(activity).apply {
                text = value
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        })
    }

    private fun hasValue(value: String?): Boolean =
        !value.isNullOrBlank() && value != "null"

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private companion object {
        const val MAX_VISIBLE_ENTRIES = 60
        const val MAX_NAME_LENGTH = 120
    }
}
