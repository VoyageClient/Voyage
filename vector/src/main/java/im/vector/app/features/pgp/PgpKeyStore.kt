/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.pgp

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Per-account PGP settings (own signing key, auto-decrypt, per-contact key overrides) and the
 * per-room "encrypt my messages" toggle. Everything is namespaced by the active account's user
 * id so multiple logged-in accounts don't share keys/toggles.
 */
@Singleton
class PgpKeyStore @Inject constructor(
        @DefaultPreferences private val prefs: SharedPreferences,
        // Provider (not a direct injection) to break a Dagger cycle: ActiveSessionHolder ->
        // PushRuleTriggerListener -> NotifiableEventResolver -> DisplayableEventFormatter ->
        // PgpDecryptor -> PgpKeyStore -> ActiveSessionHolder.
        private val activeSessionHolder: Provider<ActiveSessionHolder>,
) {
    private fun scope(): String = activeSessionHolder.get().getSafeActiveSession()?.myUserId ?: "default"
    private fun key(name: String) = "SETTINGS_PGP_${name}_${scope()}"

    // Emits whenever the global enable or a per-room toggle changes, so the lock indicators
    // (timeline toolbar, room profile, room list) can re-render immediately instead of waiting for
    // an unrelated Matrix state change.
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes

    var isEnabled: Boolean
        get() = prefs.getBoolean(key("ENABLED"), false)
        set(value) {
            prefs.edit { putBoolean(key("ENABLED"), value) }
            _changes.tryEmit(Unit)
        }

    var myKeyId: Long
        get() = prefs.getLong(key("MY_KEY_ID"), 0L)
        set(value) = prefs.edit { putLong(key("MY_KEY_ID"), value) }

    fun hasMyKey(): Boolean = myKeyId != 0L

    // ---- per-room send toggle ----

    fun isRoomPgpEnabled(roomId: String): Boolean =
            prefs.getStringSet(key("ROOMS"), emptySet()).orEmpty().contains(roomId)

    fun setRoomPgpEnabled(roomId: String, enabled: Boolean) {
        val set = prefs.getStringSet(key("ROOMS"), emptySet()).orEmpty().toMutableSet()
        if (enabled) set.add(roomId) else set.remove(roomId)
        prefs.edit { putStringSet(key("ROOMS"), set) }
        _changes.tryEmit(Unit)
    }

    // Recipient key ids resolved once via OpenKeychain and cached per room so we don't re-prompt
    // on every send. Cleared when the room toggle flips.

    fun getRoomRecipientKeyIds(roomId: String): LongArray {
        val csv = prefs.getString(key("RECIPIENTS_$roomId"), null) ?: return LongArray(0)
        return csv.split(",").mapNotNull { it.toLongOrNull() }.toLongArray()
    }

    fun setRoomRecipientKeyIds(roomId: String, keyIds: LongArray) {
        prefs.edit { putString(key("RECIPIENTS_$roomId"), keyIds.joinToString(",")) }
    }

    fun clearRoomRecipientKeyIds(roomId: String) {
        prefs.edit { remove(key("RECIPIENTS_$roomId")) }
    }

    // ---- per-contact key overrides (Matrix userId -> OpenKeychain lookup address) ----
    // Used when the auto-derived "user@server" doesn't match the imported key's UID email.
    // Stored as a string set of "userId;address" entries; ';' can't appear in a Matrix id, so
    // the first ';' is always the separator.

    fun getOverrides(): Map<String, String> {
        return prefs.getStringSet(key("OVERRIDES"), emptySet()).orEmpty().mapNotNull { entry ->
            val sep = entry.indexOf(OVERRIDE_SEPARATOR)
            if (sep <= 0 || sep == entry.length - 1) return@mapNotNull null
            entry.substring(0, sep) to entry.substring(sep + 1)
        }.toMap()
    }

    fun getOverride(userId: String): String? = getOverrides()[userId]

    fun setOverride(userId: String, address: String) {
        persistOverrides(getOverrides().toMutableMap().apply { put(userId, address) })
    }

    fun removeOverride(userId: String) {
        persistOverrides(getOverrides().toMutableMap().apply { remove(userId) })
    }

    private fun persistOverrides(map: Map<String, String>) {
        val set = map.map { "${it.key}$OVERRIDE_SEPARATOR${it.value}" }.toSet()
        prefs.edit { putStringSet(key("OVERRIDES"), set) }
    }

    companion object {
        private const val OVERRIDE_SEPARATOR = ';'
    }
}
