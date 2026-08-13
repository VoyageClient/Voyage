/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import android.content.Context
import androidx.work.WorkManager
import im.vector.app.core.services.AlarmSyncBroadcastReceiver
import im.vector.app.core.services.VectorSyncAndroidService
import timber.log.Timber
import javax.inject.Inject

/**
 * Cancels all queued background work of a session being switched away from, so a non-active
 * account never touches the network (its pending sends/uploads are deliberately dropped).
 */
class CancelSessionWorkUseCase @Inject constructor(
        private val context: Context,
) {
    fun execute(sessionId: String) {
        Timber.i("Cancelling queued work for session $sessionId")
        // Tag mirrors the internal SDK constant (WorkManagerProvider.MATRIX_SDK_TAG_PREFIX + sessionId),
        // unreachable from here without SDK changes
        WorkManager.getInstance(context).cancelAllWorkByTag("MatrixSDK-$sessionId")
        WorkManager.getInstance(context).cancelAllWorkByTag(VectorSyncAndroidService.TAG_RESTART_WHEN_NETWORK_ON)
        AlarmSyncBroadcastReceiver.cancelAlarm(context)
    }
}
