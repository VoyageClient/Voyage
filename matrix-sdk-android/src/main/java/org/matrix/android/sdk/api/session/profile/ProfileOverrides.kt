/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.events.model.toModel

/**
 * Client-side per-user profile overrides, backed by the MSC4529 `m.profile_overrides` user account
 * data event: a map of userId to a map of profile fields (`displayname`, `avatar_url`, or any other
 * profile field) that replace the user's own values everywhere they are displayed.
 *
 * Held statically so the non-injectable mappers/resolvers can consult it on every read.
 */
object ProfileOverrides {

    data class ProfileMedia(
            val url: String,
            val encryptedFile: EncryptedFileInfo? = null,
    )

    const val FIELD_DISPLAY_NAME = "displayname"
    const val FIELD_AVATAR_URL = "avatar_url"

    /** Stable first: the MSC says to prefer it where both are present. */
    val ACCOUNT_DATA_TYPES = listOf(UserAccountDataTypes.TYPE_PROFILE_OVERRIDES, UserAccountDataTypes.TYPE_PROFILE_OVERRIDES_UNSTABLE)

    fun isAccountDataType(type: String): Boolean = type in ACCOUNT_DATA_TYPES

    @Volatile
    var overrides: Map<String, Map<String, Any?>> = emptyMap()
        private set

    /** Bumped on every change so read-side memo caches (e.g. the timeline mapper) can invalidate. */
    @Volatile
    var generation: Long = 0L
        private set

    // Fires once the map has actually been swapped, unlike the account-data live flow, which emits
    // when the event is persisted and before the overrides are applied. Carries the users whose
    // overrides changed, so readers can re-resolve just those instead of everything they hold.
    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    // The static is shared by every session in the process; ownership gating keeps a
    // backgrounded (not-yet-released) session's sync from clobbering the active account's map.
    @Volatile
    private var ownerSessionId: String? = null

    @Synchronized
    fun claim(sessionId: String) {
        if (ownerSessionId == sessionId) return
        ownerSessionId = sessionId
        applyLocked(emptyMap())
    }

    @Synchronized
    fun release(sessionId: String) {
        if (ownerSessionId != sessionId) return
        ownerSessionId = null
        applyLocked(emptyMap())
    }

    /** Applies only when [sessionId] is the owning (active) session. Returns whether it applied. */
    @Synchronized
    fun set(sessionId: String, newOverrides: Map<String, Map<String, Any?>>): Boolean {
        if (sessionId != ownerSessionId) return false
        applyLocked(newOverrides)
        return true
    }

    private fun applyLocked(newOverrides: Map<String, Map<String, Any?>>) {
        if (newOverrides == overrides) return
        val changedUsers = (overrides.keys + newOverrides.keys).filterTo(HashSet()) { overrides[it] != newOverrides[it] }
        overrides = newOverrides
        generation++
        _changes.tryEmit(changedUsers)
    }

    fun fieldsFor(userId: String?): Map<String, Any?>? =
            userId?.let { overrides[it] }?.takeIf { it.isNotEmpty() }

    fun displayNameFor(userId: String?): String? =
            fieldsFor(userId)?.get(FIELD_DISPLAY_NAME) as? String

    fun displayNameOr(userId: String?, fallback: String?): String? =
            fieldsFor(userId)?.let { if (FIELD_DISPLAY_NAME in it) displayNameFor(userId) else fallback } ?: fallback

    fun avatarUrlFor(userId: String?): String? = mediaOverrideFor(userId, FIELD_AVATAR_URL)?.url

    fun avatarUrlOr(userId: String?, fallback: String?): String? =
            fieldsFor(userId)?.let { if (FIELD_AVATAR_URL in it) avatarUrlFor(userId) else fallback } ?: fallback

    fun mediaOr(userId: String?, fields: List<String>, fallback: String?): ProfileMedia? {
        val overrides = fieldsFor(userId)
        val overriddenField = fields.firstOrNull { it in overrides.orEmpty() }
        return if (overriddenField != null) mediaOverrideFor(userId, overriddenField) else fallback?.let(::ProfileMedia)
    }

    private fun mediaOverrideFor(userId: String?, field: String): ProfileMedia? {
        return when (val value = fieldsFor(userId)?.get(field)) {
            is String -> value.takeIf { it.isNotBlank() }?.let(::ProfileMedia)
            is EncryptedFileInfo -> value.url?.takeIf { it.startsWith("mxc://") }?.let { ProfileMedia(it, value) }
            else -> null
        }
    }

    /** How to decrypt this user's avatar override, when it is an encrypted file. */
    fun avatarDecryptionFor(userId: String?): ElementToDecrypt? =
            mediaOverrideFor(userId, FIELD_AVATAR_URL)?.encryptedFile?.toElementToDecrypt()

    /**
     * The same, found by url alone. For values that reach a renderer through a plain string (a room
     * summary's avatar column, say) and so arrive without the user they came from.
     */
    fun avatarDecryptionForUrl(url: String?): ElementToDecrypt? {
        if (url == null || overrides.isEmpty()) return null
        return overrides.keys.firstNotNullOfOrNull { userId ->
            mediaOverrideFor(userId, FIELD_AVATAR_URL)?.encryptedFile?.takeIf { it.url == url }?.toElementToDecrypt()
        }
    }

    /** [profile] with this user's overrides merged over it: an override replaces (or adds) the field, a null override removes it. */
    fun mergedOver(userId: String, profile: Map<String, Any>): Map<String, Any> {
        val overrides = fieldsFor(userId) ?: return profile
        val merged = profile.toMutableMap()
        overrides.forEach { (key, value) ->
            if (value == null) merged.remove(key) else merged[key] = value
        }
        return merged
    }

    fun parse(content: Map<String, Any?>?, encrypted: Boolean = false): Map<String, Map<String, Any?>> =
            content.orEmpty().entries.mapNotNull { (userId, fields) ->
                if (!MatrixPatterns.isUserId(userId)) return@mapNotNull null
                (fields as? Map<*, *>)
                        ?.entries
                        ?.mapNotNull { (key, value) -> (key as? String)?.let { parseField(it, value, encrypted) } }
                        ?.toMap()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { userId to it }
            }.toMap()

    private fun parseField(key: String, value: Any?, encrypted: Boolean): Pair<String, Any?>? {
        val encryptedFile = when (value) {
            is EncryptedFileInfo -> value
            is Map<*, *> -> encryptedFile(value)
            else -> null
        }?.takeIf { it.isValid() && it.url?.startsWith("mxc://") == true }
        if (encryptedFile != null) {
            // A file key in a plaintext event is not a secret worth honoring, and no text field
            // legitimately holds one — either way it is not an override we can use.
            return if (!encrypted || key in KNOWN_NON_MEDIA_FIELDS) null else key to encryptedFile
        }
        if (value == null) return key to null
        val valid = when (key) {
            FIELD_DISPLAY_NAME, ProfileKeys.TIMEZONE, ProfileKeys.TIMEZONE_UNSTABLE, ProfileKeys.STATUS_COMMET,
            ProfileKeys.BIOGRAPHY_SABLE, ProfileKeys.COLOR_SABLE_ON_LIGHT, ProfileKeys.COLOR_SABLE_ON_DARK,
            ProfileKeys.COLOR_SABLE -> value is String
            FIELD_AVATAR_URL, ProfileKeys.BANNER_URL, ProfileKeys.BANNER_URL_UNSTABLE ->
                value is String && value.startsWith("mxc://")
            ProfileKeys.PRONOUNS, ProfileKeys.PRONOUNS_UNSTABLE -> value is List<*>
            ProfileKeys.STATUS, ProfileKeys.STATUS_UNSTABLE, ProfileKeys.BIOGRAPHY, ProfileKeys.BIOGRAPHY_UNSTABLE,
            ProfileKeys.BIOGRAPHY_COMMET, ProfileKeys.COLOR_PREFERENCE, ProfileKeys.COLOR_PREFERENCE_UNSTABLE,
            ProfileKeys.COLOR_COMMET -> value is Map<*, *>
            else -> true
        }
        return if (valid) key to value else null
    }

    private fun encryptedFile(value: Map<*, *>): EncryptedFileInfo? {
        val content: Map<String, Any> = value.entries.mapNotNull { (key, field) ->
            (key as? String)?.let { name -> field?.let { name to it } }
        }.toMap()
        return content.toModel()
    }

    private val KNOWN_NON_MEDIA_FIELDS = setOf(
            FIELD_DISPLAY_NAME,
            ProfileKeys.PRONOUNS, ProfileKeys.PRONOUNS_UNSTABLE,
            ProfileKeys.TIMEZONE, ProfileKeys.TIMEZONE_UNSTABLE,
            ProfileKeys.STATUS, ProfileKeys.STATUS_UNSTABLE, ProfileKeys.STATUS_COMMET,
            ProfileKeys.BIOGRAPHY, ProfileKeys.BIOGRAPHY_UNSTABLE, ProfileKeys.BIOGRAPHY_COMMET, ProfileKeys.BIOGRAPHY_SABLE,
            ProfileKeys.COLOR_PREFERENCE, ProfileKeys.COLOR_PREFERENCE_UNSTABLE,
            ProfileKeys.COLOR_SABLE_ON_LIGHT, ProfileKeys.COLOR_SABLE_ON_DARK, ProfileKeys.COLOR_SABLE, ProfileKeys.COLOR_COMMET,
    )
}
