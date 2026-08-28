/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import org.matrix.android.sdk.api.extensions.tryOrNull
import java.io.File

object DeviceCapabilities {

    // Rough capability check: single/dual-core or low-clocked CPUs count as low-power. Cached, since
    // nothing here changes while the process lives and the reads hit sysfs.
    val isLowPerformanceHardware: Boolean by lazy {
        val cores = physicalCoreCount()
        val maxFreqGhz = maxCpuFreqGhz()
        when {
            cores <= 2 -> true
            maxFreqGhz in 0.01..1.3 -> true
            else -> false
        }
    }

    // availableProcessors() reports only online cores (old kernels hot-unplug them), so count the
    // physical cpuN entries in sysfs; fall back to the runtime value if that read fails.
    private fun physicalCoreCount(): Int {
        return tryOrNull {
            File("/sys/devices/system/cpu/")
                    .listFiles { file -> file.name.matches(Regex("cpu[0-9]+")) }
                    ?.size
                    ?.takeIf { it > 0 }
        } ?: Runtime.getRuntime().availableProcessors()
    }

    // Max clock of cpu0 in GHz, or 0.0 if sysfs is unreadable (some devices restrict it).
    private fun maxCpuFreqGhz(): Double {
        val khz = tryOrNull {
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        } ?: return 0.0
        return khz / 1_000_000.0
    }
}
