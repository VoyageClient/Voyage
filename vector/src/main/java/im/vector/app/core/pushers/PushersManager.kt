/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import im.vector.app.core.device.GetDeviceInfoUseCase
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.AppNameProvider
import im.vector.app.core.resources.LocaleProvider
import im.vector.app.core.resources.StringProvider
import org.matrix.android.sdk.api.session.pushers.HttpPusher
import org.matrix.android.sdk.api.session.pushers.Pusher
import org.matrix.android.sdk.api.session.pushers.PusherState
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

internal const val DEFAULT_PUSHER_FILE_TAG = "mobile"

class PushersManager @Inject constructor(
        private val unifiedPushHelper: UnifiedPushHelper,
        private val activeSessionHolder: ActiveSessionHolder,
        private val localeProvider: LocaleProvider,
        private val stringProvider: StringProvider,
        private val appNameProvider: AppNameProvider,
        private val getDeviceInfoUseCase: GetDeviceInfoUseCase,
) {
    suspend fun testPush() {
        val currentSession = activeSessionHolder.getActiveSession()

        currentSession.pushersService().testPush(
                unifiedPushHelper.getPushGateway() ?: return,
                stringProvider.getString(im.vector.app.config.R.string.pusher_app_id),
                unifiedPushHelper.getEndpointOrToken().orEmpty(),
                TEST_EVENT_ID
        )
    }

    /**
     * Register the pusher and wait for the homeserver to take it, so the caller can tell whether the
     * endpoint it holds is actually live.
     */
    suspend fun registerPusher(pushKey: String, gateway: String): Result<Unit> {
        return runCatching {
            activeSessionHolder.getActiveSession().pushersService().addHttpPusher(createHttpPusher(pushKey, gateway))
        }
    }

    suspend fun enqueueRegisterPusher(
            pushKey: String,
            gateway: String
    ): UUID {
        val currentSession = activeSessionHolder.getActiveSession()
        val pusher = createHttpPusher(pushKey, gateway)
        return currentSession.pushersService().enqueueAddHttpPusher(pusher)
    }

    private suspend fun createHttpPusher(
            pushKey: String,
            gateway: String
    ) = HttpPusher(
            pushkey = pushKey,
            appId = stringProvider.getString(im.vector.app.config.R.string.pusher_app_id),
            profileTag = DEFAULT_PUSHER_FILE_TAG + "_" + abs(activeSessionHolder.getActiveSession().myUserId.hashCode()),
            lang = localeProvider.current().language,
            appDisplayName = appNameProvider.getAppName(),
            deviceDisplayName = getDeviceInfoUseCase.execute().displayName().orEmpty(),
            url = gateway,
            enabled = true,
            deviceId = activeSessionHolder.getActiveSession().sessionParams.deviceId,
            append = false,
            withEventIdOnly = true,
    )

    suspend fun registerEmailForPush(email: String) {
        val currentSession = activeSessionHolder.getActiveSession()
        val appName = appNameProvider.getAppName()
        currentSession.pushersService().addEmailPusher(
                email = email,
                lang = localeProvider.current().language,
                emailBranding = appName,
                appDisplayName = appName,
                deviceDisplayName = currentSession.sessionParams.deviceId
        )
    }

    fun isPusherRegisteredFor(pushKey: String): Boolean {
        val session = activeSessionHolder.getSafeActiveSession() ?: return false
        return session.pushersService().getPushers().any {
            it.pushKey == pushKey && it.state in setOf(PusherState.REGISTERED, PusherState.REGISTERING)
        }
    }

    fun getPusherForCurrentSession(): Pusher? {
        val session = activeSessionHolder.getSafeActiveSession() ?: return null
        val deviceId = session.sessionParams.deviceId
        return session.pushersService().getPushers().firstOrNull { it.deviceId == deviceId }
    }

    suspend fun unregisterEmailPusher(email: String) {
        val currentSession = activeSessionHolder.getSafeActiveSession() ?: return
        currentSession.pushersService().removeEmailPusher(email)
    }

    suspend fun unregisterPusher(pushKey: String) {
        val currentSession = activeSessionHolder.getSafeActiveSession() ?: return
        currentSession.pushersService().removeHttpPusher(pushKey, stringProvider.getString(im.vector.app.config.R.string.pusher_app_id))
    }

    companion object {
        const val TEST_EVENT_ID = "\$THIS_IS_A_FAKE_EVENT_ID"
    }
}
