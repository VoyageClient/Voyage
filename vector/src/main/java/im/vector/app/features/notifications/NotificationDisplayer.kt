/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber
import javax.inject.Inject

class NotificationDisplayer @Inject constructor(
        private val context: Context,
) {

    private val notificationManager = NotificationManagerCompat.from(context)

    fun canShowNotifications(): Boolean =
            (Build.VERSION.SDK_INT < 33 ||
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                    notificationManager.areNotificationsEnabled()

    fun showNotificationMessage(tag: String?, id: Int, notification: Notification) {
        if (!canShowNotifications()) {
            Timber.w("Not allowed to notify.")
        } else {
            try {
                notificationManager.notify(tag, id, notification)
            } catch (exception: SecurityException) {
                Timber.w(exception, "Not allowed to notify.")
            }
        }
    }

    fun cancelNotificationMessage(tag: String?, id: Int) {
        notificationManager.cancel(tag, id)
    }

    fun cancelAllNotifications() {
        // Keep this try catch (reported by GA)
        try {
            notificationManager.cancelAll()
        } catch (e: Exception) {
            Timber.e(e, "## cancelAllNotifications() failed")
        }
    }
}
