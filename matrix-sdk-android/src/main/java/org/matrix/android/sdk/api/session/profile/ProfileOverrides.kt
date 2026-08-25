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
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes

/**
 * Client-side per-user profile overrides, backed by the MSC4529 `m.profile_overrides` user account
 * data event: a map of userId to a map of profile fields (`displayname`, `avatar_url`, or any other
 * profile field) that replace the user's own values everywhere they are displayed.
 *
 * Held statically so the non-injectable mappers/resolvers can consult it on every read.
 */
object ProfileOverrides {

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
    // when the event is persisted and before the overrides are applied.
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

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
        overrides = newOverrides
        generation++
        _changes.tryEmit(Unit)
    }

    fun fieldsFor(userId: String?): Map<String, Any?>? =
            userId?.let { overrides[it] }?.takeIf { it.isNotEmpty() }

    fun displayNameFor(userId: String?): String? =
            (fieldsFor(userId)?.get(FIELD_DISPLAY_NAME) as? String)?.takeIf { it.isNotBlank() }

    fun avatarUrlFor(userId: String?): String? =
            (fieldsFor(userId)?.get(FIELD_AVATAR_URL) as? String)?.takeIf { it.isNotBlank() }

    /** [profile] with this user's overrides merged over it: an override replaces (or adds) the field, a null override removes it. */
    fun mergedOver(userId: String, profile: Map<String, Any>): Map<String, Any> {
        val overrides = fieldsFor(userId) ?: return profile
        val merged = profile.toMutableMap()
        overrides.forEach { (key, value) ->
            if (value == null) merged.remove(key) else merged[key] = value
        }
        return merged
    }

    fun parse(content: Map<String, Any?>?): Map<String, Map<String, Any?>> =
            content.orEmpty().entries.mapNotNull { (userId, fields) ->
                if (!userId.startsWith("@")) return@mapNotNull null
                (fields as? Map<*, *>)
                        ?.entries
                        ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                        ?.toMap()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { userId to it }
            }.toMap()
}
