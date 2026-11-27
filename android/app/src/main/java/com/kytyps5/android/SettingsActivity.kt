package com.kytyps5.android

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** User-facing settings for PS5 Game Hub. Internal engine identifiers are never shown here. */
class SettingsActivity : Activity() {
    private lateinit var preferences: LauncherSettings
    private lateinit var updater: InAppUpdater
    private val colors = listOf(
        "Blue" to 0xFF1976D2.toInt(), "Purple" to 0xFF7E57C2.toInt(),
        "Red" to 0xFFE53935.toInt(), "Green" to 0xFF43A047.toInt(),
        "Cyan" to 0xFF00ACC1.toInt(), "Orange" to 0xFFFB8C00.toInt(),
        "Pink" to 0xFFD81B60.toInt(), "Gold" to 0xFFFFB300.toInt(),
        "Teal" to 0xFF00897B.toInt(), "Indigo" to 0xFF3949AB.toInt(),
        "White" to 0xFFEEEEEE.toInt(), "Grey" to 0xFF757575.toInt(),
    )

    private val language get() = preferences.language
    private val accent get() = preferences.accentColor
    private fun tr(value: String): String = LauncherI18n.translate(value, language)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = LauncherSettings(this)
        updater = InAppUpdater(this, savedInstanceState)
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        updater.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (LauncherI18n.isRtl(language)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(dp(18), dp(20), dp(18), dp(20))
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        root.addView(text("PS5 Game Hub", 25f, true))
        root.addView(text(tr("Settings"), 19f, true))
        root.addView(section(tr("Updates")))
        root.addView(toggleButton(
            tr("Automatically check and download updates"),
            preferences.autoUpdatesEnabled
        ) { preferences.autoUpdatesEnabled = it })
        root.addView(updater.buildPanel())

        root.addView(section(tr("Language")))
        root.addView(actionButton(tr("Choose language") + ": " + LauncherI18n.nativeName(language)) {
            chooseLanguage()
        })

        root.addView(section(tr("Interface color")))
        root.addView(text(tr("Choose an accent color. Your choice is saved automatically."), 13f, false))
        val palette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colors.chunked(3).forEach { rowColors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { (name, color) ->
                val selected = preferences.accentColor == color
                val button = Button(this).apply {
                    text = (if (selected) "✓ " else "") + tr(name)
                    textSize = 12f
                    setTextColor(if (name == "White" || name == "Gold" || name == "Cyan") Color.BLACK else Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(color)
                    setOnClickListener {
                        preferences.accentColor = color
                        recreate()
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            palette.addView(row)
        }
        root.addView(palette)

        root.addView(section(tr("About")))
        root.addView(actionButton(tr("App information and changelog")) { showAbout() })
        root.addView(actionButton(tr("Back")) { finish() })
        // Keep the complete settings page reachable on short screens and in landscape.
        // Explicit child layout params prevent the ScrollView from measuring the content as a
        // viewport-sized child, which can make the lower settings appear unreachable.
        return ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(20))
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun chooseLanguage() {
        val options = LauncherI18n.languages
        val labels = options.map { it.nativeName + "  (" + it.englishName + ")" }.toTypedArray()
        val selected = options.indexOfFirst { it.code == language }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(tr("Choose language"))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                preferences.language = options[which].code
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        }.getOrDefault("Unknown")
        val changelog = listOf(
            "Launcher interface",
            "Language and accent color preferences",
            "App updates with package integrity verification",
            "Ongoing emulator engine and compatibility improvements",
        ).joinToString("\n") { "• " + tr(it) }
        val message = tr("App name: ") + "PS5 Game Hub\n" +
            tr("Version: ") + version + "\n\n" +
            tr("Changelog") + "\n" + changelog
        AlertDialog.Builder(this)
            .setTitle(tr("About PS5 Game Hub"))
            .setMessage(message)
            .setPositiveButton(tr("OK"), null)
            .show()
    }

    private fun toggleButton(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        android.widget.CheckBox(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        backgroundTintList = ColorStateList.valueOf(accent)
        setOnClickListener { action() }
    }

    private fun section(value: String) = text(value, 18f, true).apply {
        setPadding(0, dp(18), 0, dp(5))
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }



    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
