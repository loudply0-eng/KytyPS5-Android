package com.kytyps5.android

import com.kytyps5.android.drivers.DriverAdvisor
import com.kytyps5.android.drivers.DriverAdvisor.Verdict

/**
 * Per-game "Auto" driver mode.
 *
 * Chooses only from already-installed packages that DriverAdvisor marks RECOMMENDED
 * for the detected Adreno generation. It never downloads or installs drivers automatically.
 * This is a hardware-compatibility recommendation, not a title-specific benchmark database.
 */
object GameDriverPolicy {
    const val AUTO_DRIVER_ID = "@auto"

    fun selectCompatible(adrenoModel: Int?, installed: List<GpuDriver>): GpuDriver? {
        if (adrenoModel == null) return null
        return installed
            .sortedBy { it.label.lowercase() }
            .firstOrNull { driver ->
                DriverAdvisor.advise(
                    adrenoModel,
                    driver.driverVersion,
                    "${driver.name} ${driver.description}",
                ).verdict == Verdict.RECOMMENDED
            }
    }
}
