package com.kytyps5.android

// Added by the PS5 Game Hub project on 2026-11-24 for strict release-version and HTTPS download validation.

import java.net.URL

internal object UpdatePolicy {
    private val trustedHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    const val AUTO_UPDATE_CHECK_INTERVAL_MILLIS = 12L * 60L * 60L * 1000L
    const val AUTO_UPDATE_RETRY_INTERVAL_MILLIS = 30L * 60L * 1000L

    /** Run on launch at most every 12 hours; after a failed attempt, retry no sooner than 30 minutes. */
    fun shouldRunAutomaticCheck(
        lastSuccessfulCheckAtMillis: Long,
        lastAttemptAtMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (lastSuccessfulCheckAtMillis > 0L && nowMillis >= lastSuccessfulCheckAtMillis &&
            nowMillis - lastSuccessfulCheckAtMillis < AUTO_UPDATE_CHECK_INTERVAL_MILLIS
        ) return false
        if (lastAttemptAtMillis > 0L && nowMillis >= lastAttemptAtMillis &&
            nowMillis - lastAttemptAtMillis < AUTO_UPDATE_RETRY_INTERVAL_MILLIS
        ) return false
        return true
    }

    fun parseVersion(value: String): List<Int>? {
        val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
            .matchEntire(value.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    fun trustedDownloadUrl(address: String): URL? {
        val url = runCatching { URL(address) }.getOrNull() ?: return null
        if (url.protocol != "https" || url.host.lowercase() !in trustedHosts ||
            url.userInfo != null || url.port !in setOf(-1, 443)
        ) return null
        return url
    }

    fun resolveTrustedRedirect(base: URL, location: String): URL? {
        if (location.isBlank()) return null
        val resolved = runCatching { URL(base, location) }.getOrNull() ?: return null
        return trustedDownloadUrl(resolved.toString())
    }
}


/** User-facing updater failures intentionally omit exception text, URLs, hosts and local paths. */
internal object UpdateErrorMessages {
    const val CHECK_FAILED = "Couldn't check for updates. Try again later."
    const val DOWNLOAD_FAILED = "The update couldn't be downloaded or verified."
    const val INSTALLER_FAILED = "Couldn't open the Android installer."
}
