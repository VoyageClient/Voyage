/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Account-wide redaction-preservation settings, each overridable per room.
 *
 * Kept in the same [SharedPreferences] as the rest of the app's settings and namespaced by account,
 * so signing a different account in on the same device doesn't inherit the previous one's choices.
 */
@Singleton
class RedactionPreservationSettings @Inject constructor(
        @DefaultPreferences private val preferences: SharedPreferences,
        // Provider, not a direct injection: ActiveSessionHolder builds ConfigureAndStartSessionUseCase,
        // which reaches this class.
        private val activeSessionHolder: Provider<ActiveSessionHolder>,
) {

    private fun scope(): String = activeSessionHolder.get().getSafeActiveSession()?.myUserId ?: "default"

    private fun key(name: String) = "SETTINGS_REDACTION_${name}_${scope()}"

    private fun roomKey(name: String, roomId: String) = "${key(name)}_$roomId"

    // -- Preservation ---------------------------------------------------------------------------

    /**
     * Whether a redaction here triggers an MSC2815 fetch of the original, and whether redacted
     * messages then show that content without being revealed one by one.
     */
    var globalPreserveRedacted: Boolean
        get() = preferences.getBoolean(key(PRESERVE), false)
        set(value) {
            preferences.edit { putBoolean(key(PRESERVE), value) }
        }

    /** null = inherit the account-wide value. */
    fun roomPreserveRedactedOverride(roomId: String): Boolean? =
            if (preferences.contains(roomKey(PRESERVE, roomId))) preferences.getBoolean(roomKey(PRESERVE, roomId), false) else null

    fun setRoomPreserveRedactedOverride(roomId: String, value: Boolean?) {
        preferences.edit {
            if (value == null) remove(roomKey(PRESERVE, roomId)) else putBoolean(roomKey(PRESERVE, roomId), value)
        }
    }

    fun preserveRedactedFor(roomId: String): Boolean = roomPreserveRedactedOverride(roomId) ?: globalPreserveRedacted

    // -- What gets preserved. Each has a per-room override; null means inherit the account value. ---

    var globalPreserveMedia: Boolean
        get() = preferences.getBoolean(key(PRESERVE_MEDIA), true)
        set(value) {
            preferences.edit { putBoolean(key(PRESERVE_MEDIA), value) }
        }

    fun roomPreserveMediaOverride(roomId: String): Boolean? =
            if (preferences.contains(roomKey(PRESERVE_MEDIA, roomId))) preferences.getBoolean(roomKey(PRESERVE_MEDIA, roomId), true) else null

    fun setRoomPreserveMediaOverride(roomId: String, value: Boolean?) {
        preferences.edit {
            if (value == null) remove(roomKey(PRESERVE_MEDIA, roomId)) else putBoolean(roomKey(PRESERVE_MEDIA, roomId), value)
        }
    }

    fun preserveMediaFor(roomId: String): Boolean = roomPreserveMediaOverride(roomId) ?: globalPreserveMedia

    /** Bytes; 0 means no cap. Caps downloads of media that isn't already in the cache. */
    var globalMaxMediaSize: Long
        get() = preferences.getLong(key(MAX_MEDIA_SIZE), DEFAULT_MAX_MEDIA_SIZE)
        set(value) {
            preferences.edit { putLong(key(MAX_MEDIA_SIZE), value) }
        }

    fun roomMaxMediaSizeOverride(roomId: String): Long? =
            if (preferences.contains(roomKey(MAX_MEDIA_SIZE, roomId))) preferences.getLong(roomKey(MAX_MEDIA_SIZE, roomId), 0) else null

    fun setRoomMaxMediaSizeOverride(roomId: String, value: Long?) {
        preferences.edit {
            if (value == null) remove(roomKey(MAX_MEDIA_SIZE, roomId)) else putLong(roomKey(MAX_MEDIA_SIZE, roomId), value)
        }
    }

    fun maxMediaSizeFor(roomId: String): Long = roomMaxMediaSizeOverride(roomId) ?: globalMaxMediaSize

    var globalWifiOnly: Boolean
        get() = preferences.getBoolean(key(WIFI_ONLY), true)
        set(value) {
            preferences.edit { putBoolean(key(WIFI_ONLY), value) }
        }

    fun roomWifiOnlyOverride(roomId: String): Boolean? =
            if (preferences.contains(roomKey(WIFI_ONLY, roomId))) preferences.getBoolean(roomKey(WIFI_ONLY, roomId), true) else null

    fun setRoomWifiOnlyOverride(roomId: String, value: Boolean?) {
        preferences.edit {
            if (value == null) remove(roomKey(WIFI_ONLY, roomId)) else putBoolean(roomKey(WIFI_ONLY, roomId), value)
        }
    }

    fun wifiOnlyFor(roomId: String): Boolean = roomWifiOnlyOverride(roomId) ?: globalWifiOnly

    // -- Redaction cache ------------------------------------------------------------------------

    /** Whether the app's own Clear cache also empties the redaction cache. */
    var clearRedactionCacheWithAppCache: Boolean
        get() = preferences.getBoolean(key(CLEAR_WITH_APP_CACHE), true)
        set(value) {
            preferences.edit { putBoolean(key(CLEAR_WITH_APP_CACHE), value) }
        }

    /** null = inherit [clearRedactionCacheWithAppCache]. */
    fun roomClearWithAppCacheOverride(roomId: String): Boolean? =
            if (preferences.contains(roomKey(CLEAR_INCLUDED, roomId))) preferences.getBoolean(roomKey(CLEAR_INCLUDED, roomId), true) else null

    fun setRoomClearWithAppCacheOverride(roomId: String, value: Boolean?) {
        preferences.edit {
            if (value == null) remove(roomKey(CLEAR_INCLUDED, roomId)) else putBoolean(roomKey(CLEAR_INCLUDED, roomId), value)
        }
    }

    fun clearsWithAppCache(roomId: String): Boolean = roomClearWithAppCacheOverride(roomId) ?: clearRedactionCacheWithAppCache

    companion object {
        private const val PRESERVE = "PRESERVE"
        private const val PRESERVE_MEDIA = "PRESERVE_MEDIA"
        private const val MAX_MEDIA_SIZE = "MAX_MEDIA_SIZE"
        private const val WIFI_ONLY = "WIFI_ONLY"
        private const val CLEAR_WITH_APP_CACHE = "CLEAR_WITH_APP_CACHE"
        private const val CLEAR_INCLUDED = "CLEAR_INCLUDED"

        const val DEFAULT_MAX_MEDIA_SIZE = 10L * 1024 * 1024

        // Preference keys (the values are not persisted under these; the fragment reads/writes this class).
        const val SETTINGS_REDACTION_PRESERVE_KEY = "SETTINGS_REDACTION_PRESERVE_KEY"
        const val SETTINGS_REDACTION_PRESERVE_MEDIA_KEY = "SETTINGS_REDACTION_PRESERVE_MEDIA_KEY"
        const val SETTINGS_REDACTION_MAX_MEDIA_SIZE_KEY = "SETTINGS_REDACTION_MAX_MEDIA_SIZE_KEY"
        const val SETTINGS_REDACTION_WIFI_ONLY_KEY = "SETTINGS_REDACTION_WIFI_ONLY_KEY"
        const val SETTINGS_REDACTION_CLEAR_WITH_APP_CACHE_KEY = "SETTINGS_REDACTION_CLEAR_WITH_APP_CACHE_KEY"
        const val SETTINGS_REDACTION_CLEAR_MEDIA_CACHE_KEY = "SETTINGS_REDACTION_CLEAR_MEDIA_CACHE_KEY"
        const val SETTINGS_REDACTIONS_CATEGORY_KEY = "SETTINGS_REDACTIONS_CATEGORY_KEY"
    }
}
