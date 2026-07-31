/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.matrix.android.sdk.internal.util.BackgroundDetectionObserver

/**
 * Feeds Android process-lifecycle transitions into the platform-neutral
 * [BackgroundDetectionObserver]. A desktop app instead calls onAppForeground() once at startup.
 */
internal class AndroidAppStateDriver(
        private val observer: BackgroundDetectionObserver,
) : DefaultLifecycleObserver {

    fun start() {
        // addObserver must run on the main thread
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStart(owner: LifecycleOwner) = observer.onAppForeground()

    override fun onStop(owner: LifecycleOwner) = observer.onAppBackground()
}
