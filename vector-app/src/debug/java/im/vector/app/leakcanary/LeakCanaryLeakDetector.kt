/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.leakcanary

import android.app.Application
import im.vector.app.core.debug.LeakDetector
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import timber.log.Timber
import javax.inject.Inject

// Debug builds only (they never run on the ICS/Dalvik path, where LeakCanary's class count would
// blow the 8 MB LinearAlloc budget).
class LeakCanaryLeakDetector @Inject constructor(
        private val application: Application,
) : LeakDetector {

    // LeakCanary's auto-install ContentProvider is removed in the debug manifest, so it doesn't proactively
    // request POST_NOTIFICATIONS on launch when analysis is off (the default). Install it lazily the first
    // time analysis is enabled.
    private var installed = false

    // AppWatcher.config's setter is deprecated in favour of manualInstall(), but that's install-time
    // only — there's no non-deprecated way to toggle watching at runtime, which is what we need here.
    @Suppress("DEPRECATION")
    override fun enable(enable: Boolean) {
        Timber.v("Enable LeakCanary: $enable")
        if (!enable && !installed) return
        if (enable && !installed) {
            AppWatcher.manualInstall(application)
            installed = true
        }
        // dumpHeap alone only stops heap dumps; AppWatcher keeps watching and firing retained-object
        // notifications/permission prompts, so gate object watching too.
        AppWatcher.config = AppWatcher.config.copy(enabled = enable)
        LeakCanary.config = LeakCanary.config.copy(dumpHeap = enable)
    }
}
