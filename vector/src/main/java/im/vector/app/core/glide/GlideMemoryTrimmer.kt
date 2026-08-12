/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import com.bumptech.glide.Glide
import timber.log.Timber

object GlideMemoryTrimmer {

    /**
     * GuardAndroidService keeps a foreground service up, so the system treats this process as
     * foreground forever and never delivers TRIM_MEMORY_BACKGROUND/COMPLETE — the levels where
     * Glide would drop its bitmap cache. Without this, decoded timeline images stay resident for
     * as long as the app is backgrounded.
     */
    fun onAppBackgrounded(context: Context) {
        Glide.get(context).clearMemory()
        Timber.i("Cleared Glide memory cache on entering background")
    }
}
