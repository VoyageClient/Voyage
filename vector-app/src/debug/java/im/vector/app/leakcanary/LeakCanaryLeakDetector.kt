/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.leakcanary

import im.vector.app.core.debug.LeakDetector
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import timber.log.Timber
import javax.inject.Inject

// Debug builds only (they never run on the ICS/Dalvik path, where LeakCanary's class count would
// blow the 8 MB LinearAlloc budget).
class LeakCanaryLeakDetector @Inject constructor() : LeakDetector {
    override fun enable(enable: Boolean) {
        Timber.v("Enable LeakCanary: $enable")
        // dumpHeap alone only stops heap dumps; AppWatcher keeps watching and firing retained-object
        // notifications/permission prompts, so gate object watching too.
        AppWatcher.config = AppWatcher.config.copy(enabled = enable)
        LeakCanary.config = LeakCanary.config.copy(dumpHeap = enable)
    }
}
