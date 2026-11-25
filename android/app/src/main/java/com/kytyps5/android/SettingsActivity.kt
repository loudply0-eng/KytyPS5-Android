package com.kytyps5.android

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
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

    private val arabic get() = preferences.language == "ar"
    private val accent get() = preferences.accentColor

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
            setPadding(dp(18), dp(20), dp(18), dp(20))
            setBackgroundColor(Color.rgb(18, 20, 27))
        }
        root.addView(text("PS5 Game Hub", 25f, true))
        root.addView(text(if (arabic) "الإعدادات" else "Settings", 19f, true))
        root.addView(section(if (arabic) "التحديثات" else "Updates"))
        root.addView(toggleButton(
            if (arabic) "التحقق من التحديثات وتنزيلها تلقائياً" else "Automatically check and download updates",
            preferences.autoUpdatesEnabled
        ) { preferences.autoUpdatesEnabled = it })
        root.addView(updater.buildPanel())

        root.addView(section(if (arabic) "اللغة" else "Language"))
        root.addView(actionButton(if (arabic) "العربية" else "Arabic") { chooseLanguage("ar") })
        root.addView(actionButton(if (arabic) "الإنجليزية" else "English") { chooseLanguage("en") })

        root.addView(section(if (arabic) "لون الواجهة" else "Interface color"))
        root.addView(text(
            if (arabic) "اختر اللون الذي تريده؛ يتم حفظ اختيارك تلقائياً." else "Choose an accent color. Your choice is saved automatically.",
            13f, false
        ))
        val palette = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colors.chunked(3).forEach { rowColors ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { (name, color) ->
                val selected = preferences.accentColor == color
                val button = Button(this).apply {
                    text = (if (selected) "✓ " else "") + colorName(name, arabic)
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

        root.addView(section(if (arabic) "حول التطبيق" else "About"))
        root.addView(actionButton(if (arabic) "معلومات التطبيق وسجل التغييرات" else "App information and changelog") {
            showAbout()
        })
        root.addView(actionButton(if (arabic) "رجوع" else "Back") { finish() })
        return ScrollView(this).apply { addView(root) }
    }

    private fun chooseLanguage(value: String) {
        preferences.language = value
        recreate()
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        }.getOrDefault("Unknown")
        val changelog = if (arabic) {
            "• واجهة PS5 Game Hub\n• إعدادات اللغة والألوان\n• تحديثات التطبيق مع التحقق من سلامة الملف\n• تحسينات مستمرة للمحرك والتوافق"
        } else {
            "• PS5 Game Hub launcher interface\n• Language and accent color preferences\n• App updates with package integrity verification\n• Ongoing emulator engine and compatibility improvements"
        }
        val message = (if (arabic) "اسم التطبيق: " else "App name: ") + "PS5 Game Hub\n" +
            (if (arabic) "الإصدار: " else "Version: ") + version + "\n\n" +
            (if (arabic) "سجل التغييرات\n" else "Changelog\n") + changelog
        AlertDialog.Builder(this)
            .setTitle(if (arabic) "حول PS5 Game Hub" else "About PS5 Game Hub")
            .setMessage(message)
            .setPositiveButton(if (arabic) "حسناً" else "OK", null)
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

    private fun colorName(name: String, isArabic: Boolean): String =
        if (!isArabic) name else when (name) {
            "Blue" -> "أزرق"; "Purple" -> "بنفسجي"; "Red" -> "أحمر"
            "Green" -> "أخضر"; "Cyan" -> "سماوي"; "Orange" -> "برتقالي"
            "Pink" -> "وردي"; "Gold" -> "ذهبي"; "Teal" -> "فيروزي"
            "Indigo" -> "نيلي"; "White" -> "أبيض"; else -> "رمادي"
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
