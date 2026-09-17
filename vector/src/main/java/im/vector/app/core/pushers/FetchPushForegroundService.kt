/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import im.vector.app.R
import im.vector.lib.strings.CommonStrings
import timber.log.Timber

/**
 * Keeps the device awake while a push is turned into a notification.
 *
 * A broadcast receiver's process is killable the moment onReceive returns, and a dozing device will
 * not run the fetch that follows, so the push is simply lost. Holding a wakelock behind a foreground
 * service for the few seconds the fetch needs is what makes delivery reliable.
 */
class FetchPushForegroundService : Service() {

    private val wakelock: PowerManager.WakeLock by lazy {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Timber.w("FetchPushForegroundService: wakelock timeout reached, stopping")
        stopSelf()
    }

    private var isOnForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
                listOf(
                        NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                                .setName(getString(CommonStrings.notification_fetching_title))
                                .setVibrationEnabled(false)
                                .setSound(null, null)
                                .build()
                )
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(CommonStrings.notification_fetching_title))
                .setProgress(0, 0, true)
                .setVibrate(longArrayOf(0))
                .setSound(null)
                .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        isOnForeground = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            true
        } catch (throwable: Throwable) {
            // Android 12+ refuses some background foreground-service starts, and OEMs refuse more.
            Timber.e(throwable, "FetchPushForegroundService: cannot go foreground")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isOnForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        wakelock.acquire(WAKELOCK_TIMEOUT_MS)
        // Android 15 calls onTimeout by itself; below that we have to watch the clock.
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, WAKELOCK_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    /** Short foreground services must stop themselves when the system says time is up (API 34+). */
    override fun onTimeout(startId: Int) {
        Timber.w("FetchPushForegroundService: onTimeout")
        stopSelf()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        if (wakelock.isHeld) {
            wakelock.release()
        }
        if (isOnForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "fetch_push_notification_channel"
        private const val WAKELOCK_TAG = "Voyage:FetchPush"

        // A short foreground service may only live 3 minutes.
        const val WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L
    }
}
