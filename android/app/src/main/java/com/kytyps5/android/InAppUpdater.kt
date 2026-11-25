package com.kytyps5.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * In-app updater for the unified PS5 Game Hub / KytyPS5 application.
 * Modified from the project's earlier updater implementation on 2026-11-24.
 * Android's system installer always remains responsible for final user approval.
 */
internal class InAppUpdater(
    private val activity: Activity,
    savedInstanceState: Bundle?,
) {
    private lateinit var status: TextView
    private lateinit var updateButton: Button
    private lateinit var progress: ProgressBar
    private var pendingInstallFile: File? = savedInstanceState
        ?.getString(STATE_PENDING_INSTALL_FILE)
        ?.takeIf { it == UPDATE_APK_FILE }
        ?.let { File(File(activity.cacheDir, UPDATE_DIRECTORY), it).takeIf(File::isFile) }

    fun buildPanel(): View {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 12)
        }
        panel.addView(TextView(activity).apply {
            text = "APP UPDATES"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        status = TextView(activity).apply {
            text = "Installed version: ${installedVersionName()}"
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        panel.addView(status)
        progress = ProgressBar(activity).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        panel.addView(progress)
        updateButton = Button(activity).apply {
            text = "Check for updates"
            setOnClickListener { checkForUpdates() }
        }
        panel.addView(updateButton)
        panel.addView(TextView(activity).apply {
            text = "Stable updates are checked from the official project releases. Downloads are verified before Android's installer opens; Android requires your approval to install."
            textSize = 12f
            alpha = 0.75f
            setPadding(0, 6, 0, 0)
        })
        return panel
    }

    fun checkOnLaunch() { if (LauncherSettings(activity).autoUpdatesEnabled) checkForUpdates() }

    fun onSaveInstanceState(outState: Bundle) {
        pendingInstallFile?.takeIf(File::isFile)?.let {
            outState.putString(STATE_PENDING_INSTALL_FILE, UPDATE_APK_FILE)
        }
    }

    fun onResume() {
        val pending = pendingInstallFile ?: return
        if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = null
            openSystemInstaller(pending)
        }
    }

    private fun checkForUpdates() {
        setBusy(true, "Checking the latest stable release…")
        thread {
            try {
                val release = getJson(RELEASES_API)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    throw IllegalStateException("The latest release is not a stable published release.")
                }
                val tag = release.optString("tag_name")
                val latestVersion = UpdatePolicy.parseVersion(tag)
                    ?: throw IllegalStateException("Release tag is not a valid stable version: $tag")
                val currentVersion = UpdatePolicy.parseVersion(installedVersionName())
                    ?: throw IllegalStateException("Installed app version is invalid.")
                if (UpdatePolicy.compareVersions(latestVersion, currentVersion) <= 0) {
                    activity.runOnUiThread {
                        setBusy(false, "You are using the latest version (${installedVersionName()}).")
                    }
                    return@thread
                }

                val assets = release.optJSONArray("assets")
                    ?: throw IllegalStateException("Release has no assets.")
                val apkAssets = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                if (apkAssets.size != 1) {
                    throw IllegalStateException("Release must contain exactly one APK asset; update blocked.")
                }
                val asset = apkAssets.single()
                val digest = asset.optString("digest")
                if (!digest.matches(Regex("^sha256:[0-9a-fA-F]{64}$"))) {
                    throw IllegalStateException("Release APK has no valid GitHub SHA-256 digest. Update blocked.")
                }
                val downloadUrl = asset.optString("browser_download_url")
                if (UpdatePolicy.trustedDownloadUrl(downloadUrl)?.host != "github.com") {
                    throw SecurityException("APK URL is not an approved HTTPS GitHub release URL.")
                }
                val notes = release.optString("body").take(2500)
                activity.runOnUiThread {
                    setBusy(false, "Update available: $tag")
                    AlertDialog.Builder(activity)
                        .setTitle("Update available — $tag")
                        .setMessage(
                            "Current version: ${installedVersionName()}\nNew version: $tag\n\n$notes\n\n" +
                                "The APK will be downloaded and checked before Android's installer opens."
                        )
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Download and install") { _, _ ->
                            downloadAndInstall(downloadUrl, digest.removePrefix("sha256:"))
                        }
                        .show()
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    setBusy(false, "Update check failed: ${error.message ?: "network error"}")
                }
            }
        }
    }

    private fun downloadAndInstall(downloadUrl: String, expectedSha256: String) {
        setBusy(true, "Downloading update…")
        thread {
            var temp: File? = null
            var connection: HttpURLConnection? = null
            try {
                val updateDirectory = File(activity.cacheDir, UPDATE_DIRECTORY)
                if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                    throw IllegalStateException("Cannot create the private update cache.")
                }
                val activeConnection = openVerifiedDownloadConnection(downloadUrl)
                connection = activeConnection
                val length = activeConnection.contentLengthLong
                if (length <= 0L || length > MAX_APK_BYTES) {
                    throw IllegalStateException("APK size is missing or exceeds the safety limit.")
                }
                temp = File(updateDirectory, UPDATE_TEMP_FILE)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                activeConnection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES) {
                                throw IllegalStateException("APK exceeds the safety limit.")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != length) {
                    throw IllegalStateException("Downloaded APK size does not match the server response.")
                }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw SecurityException("APK SHA-256 mismatch. The downloaded update was rejected.")
                }

                val apkFile = File(updateDirectory, UPDATE_APK_FILE)
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IllegalStateException("Cannot replace cached update.")
                }
                if (!temp.renameTo(apkFile)) throw IllegalStateException("Cannot finalize downloaded APK.")
                temp = null

                activity.runOnUiThread {
                    setBusy(false, "Download verified. Preparing Android installer…")
                    if (Build.VERSION.SDK_INT >= 26 &&
                        !activity.packageManager.canRequestPackageInstalls()
                    ) {
                        pendingInstallFile = apkFile
                        AlertDialog.Builder(activity)
                            .setTitle("Allow app updates")
                            .setMessage(
                                "Android needs permission to install updates initiated by this app. " +
                                    "Enable it on the next screen, then return here."
                            )
                            .setPositiveButton("Open settings") { _, _ ->
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${activity.packageName}")
                                    )
                                )
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        openSystemInstaller(apkFile)
                    }
                }
            } catch (error: Exception) {
                temp?.delete()
                activity.runOnUiThread {
                    setBusy(false, "Update download rejected: ${error.message ?: "unknown error"}")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun openVerifiedDownloadConnection(address: String): HttpURLConnection {
        var current = UpdatePolicy.trustedDownloadUrl(address)
            ?: throw SecurityException("APK URL is not on the HTTPS GitHub allowlist.")
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PS5-Game-Hub-Android")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val response = try {
                connection.responseCode
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (response in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || hop == MAX_REDIRECTS) {
                    throw SecurityException("APK download exceeded the redirect limit or omitted Location.")
                }
                current = UpdatePolicy.resolveTrustedRedirect(current, location)
                    ?: throw SecurityException("APK redirect is not on the HTTPS GitHub allowlist.")
            } else {
                if (response !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("Download server returned HTTP $response.")
                }
                return connection
            }
        }
        throw SecurityException("APK download redirect limit exceeded.")
    }

    private fun openSystemInstaller(apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            status.text = "Verified update handed to Android installer. Approve it to complete installation."
        } catch (error: Exception) {
            status.text = "Could not open Android installer: ${error.message}"
        }
    }

    private fun getJson(address: String): JSONObject {
        val requested = URL(address)
        if (requested.protocol != "https" || requested.host != "api.github.com" ||
            requested.userInfo != null || requested.port !in setOf(-1, 443)
        ) {
            throw SecurityException("Update metadata URL is not the official GitHub API host.")
        }
        val connection = (requested.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PS5-Game-Hub-Android")
        }
        try {
            val response = connection.responseCode
            if (response == 404) {
                throw IllegalStateException("No official app release has been published yet.")
            }
            if (response !in 200..299) {
                throw IllegalStateException("GitHub API returned HTTP $response.")
            }
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 2 * 1024 * 1024) {
                        throw IllegalStateException("GitHub release metadata exceeds the size limit.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun installedVersionName(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"

    private fun setBusy(busy: Boolean, message: String) {
        if (!::status.isInitialized || !::updateButton.isInitialized || !::progress.isInitialized) return
        updateButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.text = message
    }

    private companion object {
        const val MAX_APK_BYTES = 700L * 1024L * 1024L
        const val MAX_REDIRECTS = 5
        const val UPDATE_DIRECTORY = "update"
        const val UPDATE_TEMP_FILE = "app-update.apk.part"
        const val UPDATE_APK_FILE = "app-update.apk"
        const val STATE_PENDING_INSTALL_FILE = "pending_install_file"
        const val RELEASES_API =
            "https://api.github.com/repos/loudply0-eng/PS5-Game-Hub-Android/releases/latest"
    }
}
