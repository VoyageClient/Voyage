/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import java.util.UUID
import javax.inject.Inject

/**
 * UnifiedPush registrations are per account: each session registers its own instance with the
 * distributor, so the endpoint it gets back, the gateway resolved from it and the pusher on the
 * homeserver all belong to that one session. The instance doubles as the client secret that maps an
 * incoming push back to the account it was meant for.
 */
class UnifiedPushStore @Inject constructor(
        val context: Context,
        val fcmHelper: FcmHelper,
        @DefaultPreferences
        private val defaultPrefs: SharedPreferences,
) {
    /**
     * The instance registered for [sessionId], creating one if this session has none yet.
     *
     * The first session to ask adopts the legacy unnamed registration, so an app that was already
     * receiving pushes keeps its endpoint and pusher across the upgrade.
     */
    fun getOrCreateInstance(sessionId: String): String {
        getInstance(sessionId)?.let { return it }
        val instance = if (defaultPrefs.getString(legacyEndpointKey(), null) != null &&
                defaultPrefs.all.keys.none { it.startsWith(PREFS_INSTANCE_PREFIX) }) {
            migrateLegacyRegistration()
            DEFAULT_INSTANCE
        } else {
            UUID.randomUUID().toString()
        }
        defaultPrefs.edit {
            putString(PREFS_INSTANCE_PREFIX + sessionId, instance)
            putString(PREFS_SESSION_PREFIX + instance, sessionId)
        }
        return instance
    }

    fun getInstance(sessionId: String): String? {
        return defaultPrefs.getString(PREFS_INSTANCE_PREFIX + sessionId, null)
    }

    /** The session an incoming push belongs to, or null if we no longer know this instance. */
    fun getSessionId(instance: String): String? {
        return defaultPrefs.getString(PREFS_SESSION_PREFIX + instance, null)
    }

    fun forgetInstance(instance: String) {
        val sessionId = getSessionId(instance)
        defaultPrefs.edit {
            remove(PREFS_SESSION_PREFIX + instance)
            remove(endpointKey(instance))
            remove(gatewayKey(instance))
            sessionId?.let { remove(PREFS_INSTANCE_PREFIX + it) }
        }
    }

    fun getEndpoint(instance: String): String? {
        return defaultPrefs.getString(endpointKey(instance), null)
    }

    fun storeUpEndpoint(instance: String, endpoint: String?) {
        defaultPrefs.edit {
            putString(endpointKey(instance), endpoint)
        }
    }

    fun getPushGateway(instance: String): String? {
        return defaultPrefs.getString(gatewayKey(instance), null)
    }

    fun storePushGateway(instance: String, gateway: String?) {
        defaultPrefs.edit {
            putString(gatewayKey(instance), gateway)
        }
    }

    private fun migrateLegacyRegistration() {
        val endpoint = defaultPrefs.getString(legacyEndpointKey(), null)
        val gateway = defaultPrefs.getString(legacyGatewayKey(), null)
        defaultPrefs.edit {
            putString(endpointKey(DEFAULT_INSTANCE), endpoint)
            putString(gatewayKey(DEFAULT_INSTANCE), gateway)
            remove(legacyEndpointKey())
            remove(legacyGatewayKey())
        }
    }

    private fun endpointKey(instance: String) = "${legacyEndpointKey()}_$instance"

    private fun gatewayKey(instance: String) = "${legacyGatewayKey()}_$instance"

    private fun legacyEndpointKey() = PREFS_ENDPOINT_OR_TOKEN

    private fun legacyGatewayKey() = PREFS_PUSH_GATEWAY

    companion object {
        /** The instance name the connector used before registrations were per account. */
        const val DEFAULT_INSTANCE = "default"

        private const val PREFS_ENDPOINT_OR_TOKEN = "UP_ENDPOINT_OR_TOKEN"
        private const val PREFS_PUSH_GATEWAY = "PUSH_GATEWAY"
        private const val PREFS_INSTANCE_PREFIX = "UP_INSTANCE_FOR_SESSION_"
        private const val PREFS_SESSION_PREFIX = "UP_SESSION_FOR_INSTANCE_"
    }
}
