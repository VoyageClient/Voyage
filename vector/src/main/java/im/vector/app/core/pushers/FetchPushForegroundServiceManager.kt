/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reference-counted owner of [FetchPushForegroundService]: pushes arrive in bursts, and the first
 * one to finish must not drop the wakelock the others are still relying on.
 */
@Singleton
class FetchPushForegroundServiceManager @Inject constructor(
        @ApplicationContext private val context: Context,
) {
    private val pendingPushes = AtomicInteger(0)

    fun start() {
        if (pendingPushes.getAndIncrement() > 0) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractiveCompat() == true) {
            // The screen is on, so nothing is going to suspend the fetch.
            Timber.d("FetchPush: device interactive, no wakelock needed")
            return
        }

        val intent = Intent(context, FetchPushForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (throwable: Throwable) {
            Timber.e(throwable, "FetchPush: cannot start the foreground service")
        }
    }

    fun stop() {
        if (pendingPushes.decrementAndGet() > 0) return
        stopAll()
    }

    /** The worker took over and holds its own wakelock, so nothing is waiting on this one. */
    fun stopAll() {
        pendingPushes.set(0)
        try {
            context.stopService(Intent(context, FetchPushForegroundService::class.java))
        } catch (throwable: Throwable) {
            Timber.e(throwable, "FetchPush: cannot stop the foreground service")
        }
    }

    @Suppress("DEPRECATION")
    private fun PowerManager.isInteractiveCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) isInteractive else isScreenOn
    }
}
