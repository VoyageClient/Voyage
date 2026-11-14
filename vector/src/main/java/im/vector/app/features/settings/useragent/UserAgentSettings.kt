/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.di.DefaultPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent store for User-Agent spoofing, scoped per account: each signed-in session has its own
 * spoof choice, so switching accounts changes the UA. The interceptor reads the active session's
 * config. Field values are further namespaced per client. (The downloaded option data is separate and
 * global — see [im.vector.app.features.settings.useragent.data.UaDataRepository].)
 */
@Singleton
class UserAgentSettings @Inject constructor(
        @DefaultPreferences private val preferences: SharedPreferences,
        private val activeSessionDataSource: ActiveSessionDataSource,
) {

    /**
     * When set (by the pre-login screen), editing/reading targets [DEFAULT_SCOPE] instead of the
     * active account — so a spoof configured before signing in is never written to the current account.
     * The interceptor deliberately ignores this and always uses [sessionScope].
     */
    @Volatile var editScopeOverride: String? = null

    // Interceptor reads must not initialize sessions while an account switch is tearing one down.
    fun sessionScope(): String = activeSessionDataSource.currentValue?.orNull()?.myUserId ?: DEFAULT_SCOPE

    /** The scope the settings UI reads/writes: the pre-login override if set, else the session scope. */
    fun editScope(): String = editScopeOverride ?: sessionScope()

    var selectedClient: UaSpoofClient
        get() = selectedClientFor(editScope())
        set(value) = preferences.edit { putString(clientKey(editScope()), value.id) }

    fun selectedClientFor(scope: String): UaSpoofClient = UaSpoofClient.fromId(preferences.getString(clientKey(scope), null))

    /** Remembered choice for the version picker: sort by latest instead of by usage share. */
    var sortVersionsByLatest: Boolean
        get() = preferences.getBoolean(KEY_SORT_BY_LATEST, false)
        set(value) = preferences.edit { putBoolean(KEY_SORT_BY_LATEST, value) }

    /** Per-client: when on, that client's software version fields always resolve to the newest available. */
    fun autoUpgradeFor(client: UaSpoofClient, scope: String = editScope()): Boolean =
            preferences.getBoolean(autoUpgradeKey(client, scope), false)

    fun setAutoUpgradeFor(client: UaSpoofClient, value: Boolean, scope: String = editScope()) {
        preferences.edit { putBoolean(autoUpgradeKey(client, scope), value) }
    }

    private fun autoUpgradeKey(client: UaSpoofClient, scope: String) = "${KEY_AUTO_UPGRADE}_${scope}_${client.id}"
    private fun clientKey(scope: String) = "${KEY_CLIENT}_${scope}"
    private fun valueKey(client: UaSpoofClient, field: UaField, scope: String) = "${field.prefKey}_${scope}_${client.id}"

    /** Stored value for a field, or null if the user hasn't set one (caller falls back to the default). */
    fun storedValue(client: UaSpoofClient, field: UaField, scope: String = editScope()): String? =
            preferences.getString(valueKey(client, field, scope), null)

    fun setValue(client: UaSpoofClient, field: UaField, value: String?, scope: String = editScope()) {
        preferences.edit {
            if (value.isNullOrEmpty()) remove(valueKey(client, field, scope)) else putString(valueKey(client, field, scope), value)
        }
    }

    /** Drop every user-set field for a client, reverting it to the live (most-popular) defaults. */
    fun clearClientValues(client: UaSpoofClient, scope: String = editScope()) {
        preferences.edit {
            UaField.entries.forEach { remove(valueKey(client, it, scope)) }
        }
    }

    /** One-off: earlier builds auto-stored field values; the model now only stores real user picks. */
    fun migrateClearLegacyValues() {
        if (preferences.getBoolean(KEY_MIGRATED, false)) return
        preferences.edit {
            preferences.all.keys.filter { it.startsWith("SETTINGS_UA_FIELD_") }.forEach { remove(it) }
            putBoolean(KEY_MIGRATED, true)
        }
    }

    /** Resolved rust-sdk sha for an app version (global — the sha is objective, not per-account). */
    fun sdkShaFor(appVersion: String): String? =
            appVersion.takeIf { it.isNotEmpty() }?.let { preferences.getString("SETTINGS_UA_SDKSHA_$it", null) }

    fun setSdkShaFor(appVersion: String, sha: String) {
        preferences.edit { putString("SETTINGS_UA_SDKSHA_$appVersion", sha) }
    }

    /** Effective surfaces for a client: the user's override if set, else the client's default. */
    fun surfaces(client: UaSpoofClient, scope: String = editScope()): Set<UaSurface> {
        val stored = preferences.getStringSet(surfaceKey(client, scope), null) ?: return client.defaultSurfaces
        return stored.mapNotNull { name -> UaSurface.entries.firstOrNull { it.name == name } }.toSet()
    }

    fun setSurfaces(client: UaSpoofClient, surfaces: Set<UaSurface>?, scope: String = editScope()) {
        preferences.edit {
            if (surfaces == null) remove(surfaceKey(client, scope)) else putStringSet(surfaceKey(client, scope), surfaces.map { it.name }.toSet())
        }
    }

    private fun surfaceKey(client: UaSpoofClient, scope: String) = "SETTINGS_UA_SURFACES_${scope}_${client.id}"

    /**
     * Seed a newly signed-in account with an exact copy of the [DEFAULT_SCOPE] config, replacing whatever
     * it kept from an earlier sign-in, so the UA doesn't change at the moment sign-in completes.
     */
    fun applyDefaultTo(newScope: String) {
        if (newScope == DEFAULT_SCOPE) return
        if (preferences.getString(clientKey(DEFAULT_SCOPE), null) == null) return
        val defaults = scopedEntries(DEFAULT_SCOPE)
        val stale = scopedEntries(newScope).keys
        preferences.edit {
            stale.forEach { remove(it) }
            defaults.forEach { (key, value) ->
                val target = when {
                    key.contains("_${DEFAULT_SCOPE}_") -> key.replaceFirst("_${DEFAULT_SCOPE}_", "_${newScope}_")
                    else -> key.removeSuffix("_$DEFAULT_SCOPE") + "_$newScope"
                }
                when (value) {
                    is String -> putString(target, value)
                    is Boolean -> putBoolean(target, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(target, value as Set<String>)
                }
            }
        }
    }

    private fun scopedEntries(scope: String): Map<String, Any?> {
        val infix = "_${scope}_"
        val suffix = "_$scope"
        return preferences.all.filterKeys { key ->
            key.startsWith(KEY_PREFIX) && (key.contains(infix) || key.endsWith(suffix))
        }
    }

    companion object {
        /** All UA-spoofing keys share this prefix; kept across logout (a device choice, not account data). */
        const val KEY_PREFIX = "SETTINGS_UA_"

        /** Scope of the pre-sign-in config: used while no account is active, and copied into each new sign-in. */
        const val DEFAULT_SCOPE = "default"

        private const val KEY_CLIENT = "SETTINGS_UA_CLIENT"
        private const val KEY_SORT_BY_LATEST = "SETTINGS_UA_SORT_BY_LATEST"
        private const val KEY_AUTO_UPGRADE = "SETTINGS_UA_AUTO_UPGRADE"
        private const val KEY_MIGRATED = "SETTINGS_UA_MIGRATED_V2"
    }
}
