package com.kytyps5.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URL

class UpdatePolicyTest {
    @Test
    fun throttlesAutomaticChecksButAllowsRetryAfterCooldown() {
        val now = 10L * UpdatePolicy.AUTO_UPDATE_CHECK_INTERVAL_MILLIS
        assertEquals(true, UpdatePolicy.shouldRunAutomaticCheck(0L, 0L, now))
        assertEquals(false, UpdatePolicy.shouldRunAutomaticCheck(
            now - UpdatePolicy.AUTO_UPDATE_CHECK_INTERVAL_MILLIS + 1L, 0L, now
        ))
        assertEquals(false, UpdatePolicy.shouldRunAutomaticCheck(
            0L, now - UpdatePolicy.AUTO_UPDATE_RETRY_INTERVAL_MILLIS + 1L, now
        ))
        assertEquals(true, UpdatePolicy.shouldRunAutomaticCheck(
            now - UpdatePolicy.AUTO_UPDATE_CHECK_INTERVAL_MILLIS, 0L, now
        ))
        assertEquals(true, UpdatePolicy.shouldRunAutomaticCheck(0L, now + 1L, now))
    }

    @Test
    fun acceptsOnlyStableThreePartVersions() {
        assertEquals(listOf(1, 2, 3), UpdatePolicy.parseVersion("v1.2.3"))
        assertEquals(listOf(0, 1, 0), UpdatePolicy.parseVersion("0.1.0"))
        assertNull(UpdatePolicy.parseVersion("v1.2"))
        assertNull(UpdatePolicy.parseVersion("v1.2.3-rc.1"))
        assertNull(UpdatePolicy.parseVersion("v01.2.3"))
        assertNull(UpdatePolicy.parseVersion("1.2.3+build"))
        assertNull(UpdatePolicy.parseVersion("999999999999.2.3"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertEquals(1, UpdatePolicy.compareVersions(listOf(1, 10, 0), listOf(1, 9, 9)))
        assertEquals(0, UpdatePolicy.compareVersions(listOf(1, 2, 0), listOf(1, 2)))
        assertEquals(-1, UpdatePolicy.compareVersions(listOf(0, 9, 9), listOf(1, 0, 0)))
    }

    @Test
    fun validatesEveryRedirectTargetIncludingRelativeLocations() {
        val base = URL("https://github.com/owner/repo/releases/download/v1.2.3/app.apk")
        assertNotNull(UpdatePolicy.resolveTrustedRedirect(base, "https://objects.githubusercontent.com/assets/file"))
        assertNotNull(UpdatePolicy.resolveTrustedRedirect(base, "/owner/repo/releases/download/v1.2.3/next.apk"))
        assertNull(UpdatePolicy.resolveTrustedRedirect(base, "https://attacker.example/steal.apk"))
        assertNull(UpdatePolicy.resolveTrustedRedirect(base, "http://github.com/insecure.apk"))
        assertNull(UpdatePolicy.resolveTrustedRedirect(base, "//github.com.evil.example/file.apk"))
    }

    @Test
    fun acceptsOnlyHttpsGitHubDownloadHostsAndDefaultPort() {
        assertNotNull(UpdatePolicy.trustedDownloadUrl("https://github.com/owner/repo/releases/download/v1.2.3/app.apk"))
        assertNotNull(UpdatePolicy.trustedDownloadUrl("https://objects.githubusercontent.com/assets/file"))
        assertNotNull(UpdatePolicy.trustedDownloadUrl("https://release-assets.githubusercontent.com/file"))
        assertNull(UpdatePolicy.trustedDownloadUrl("http://github.com/file.apk"))
        assertNull(UpdatePolicy.trustedDownloadUrl("https://github.com.evil.example/file.apk"))
        assertNull(UpdatePolicy.trustedDownloadUrl("https://user@github.com/file.apk"))
        assertNull(UpdatePolicy.trustedDownloadUrl("https://github.com:8443/file.apk"))
    }
}
