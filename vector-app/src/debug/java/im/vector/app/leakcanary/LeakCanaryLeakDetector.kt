/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.leakcanary

import im.vector.app.core.debug.LeakDetector
import javax.inject.Inject

// LeakCanary is omitted from this fork: its classes blow the Dalvik 8 MB LinearAlloc budget on the
// API-14 target. This keeps the debug DI binding intact as a no-op.
class LeakCanaryLeakDetector @Inject constructor() : LeakDetector {
    override fun enable(enable: Boolean) {
        // no-op
    }
}
