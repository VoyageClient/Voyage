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
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
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
            DebugLog.i { "NOTIFDBG post-suppressed reason=permission-or-system-setting id=$id tag=${OperationDiagnostics.id(tag)}" }
            Timber.w("Not allowed to notify.")
        } else {
            try {
                notificationManager.notify(tag, id, notification)
                DebugLog.i { "NOTIFDBG posted id=$id tag=${OperationDiagnostics.id(tag)}" }
            } catch (exception: SecurityException) {
                DebugLog.w { "NOTIFDBG post-failed reason=permission-denied id=$id tag=${OperationDiagnostics.id(tag)}" }
                Timber.w(exception, "Not allowed to notify.")
            }
        }
    }

    fun cancelNotificationMessage(tag: String?, id: Int) {
        notificationManager.cancel(tag, id)
        DebugLog.i { "NOTIFDBG canceled id=$id tag=${OperationDiagnostics.id(tag)}" }
    }

    fun cancelAllNotifications() {
        // Keep this try catch (reported by GA)
        try {
            notificationManager.cancelAll()
            DebugLog.i { "NOTIFDBG canceled-all" }
        } catch (e: Exception) {
            Timber.e(e, "## cancelAllNotifications() failed")
        }
    }
}
