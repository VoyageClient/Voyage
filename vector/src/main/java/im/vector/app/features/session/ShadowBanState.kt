/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.session

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Accounts whose homeserver was caught faking sends, kept until a send is genuinely accepted again. */
@Singleton
class ShadowBanState @Inject constructor(
        @DefaultPreferences private val preferences: SharedPreferences,
) {

    private val suspectedUserIds = MutableStateFlow(preferences.getStringSet(KEY, null).orEmpty().toSet())

    fun isSuspected(userId: String): Flow<Boolean> = suspectedUserIds.map { userId in it }.distinctUntilChanged()

    @Synchronized
    fun setSuspected(userId: String, suspected: Boolean) {
        val current = suspectedUserIds.value
        val updated = if (suspected) current + userId else current - userId
        if (updated == current) return
        suspectedUserIds.value = updated
        preferences.edit { putStringSet(KEY, updated) }
    }

    @Synchronized
    fun clearAll() {
        suspectedUserIds.value = emptySet()
        preferences.edit { remove(KEY) }
    }

    private companion object {
        const val KEY = "SHADOW_BAN_SUSPECTED_USER_IDS"
    }
}
