package com.kytyps5.android

import com.kytyps5.android.drivers.DriverAdvisor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameDriverPolicyTest {
    private fun driver(id: String, name: String, version: String, description: String = "") =
        GpuDriver(id, name, version, description, "test", "libvulkan_freedreno.so", File("/tmp/$id"))

    @Test
    fun adreno7xxAutoSelectsAnInstalledA7xxCompatibleDriverAndRejectsGen8() {
        val gen8 = driver("gen8", "Turnip_Gen8_V37", "V37", "gen8")
        val a7xx = driver("a7xx", "Turnip v26.2 A7XX", "v26.2.0", "a7xx")
        assertEquals("a7xx", GameDriverPolicy.selectCompatible(725, listOf(gen8, a7xx))?.id)
    }

    @Test
    fun adreno8xxAutoSelectsGen8CompatibleDriver() {
        val gen7 = driver("gen7", "Turnip v26 A7XX", "v26.0.0", "a7xx")
        val gen8 = driver("gen8", "Turnip Gen8 V37", "V37", "gen8")
        assertEquals("gen8", GameDriverPolicy.selectCompatible(830, listOf(gen7, gen8))?.id)
    }

    @Test
    fun unknownGpuOrNoRecommendedInstalledDriverReturnsNull() {
        val gen8 = driver("gen8", "Turnip Gen8", "V37", "gen8")
        assertNull(GameDriverPolicy.selectCompatible(null, listOf(gen8)))
        assertNull(GameDriverPolicy.selectCompatible(725, listOf(gen8)))
    }
}
