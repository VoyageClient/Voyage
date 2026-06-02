/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LastActiveSessionStore @Inject constructor(
        @ApplicationContext context: Context,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    fun get(): String? = prefs.getString(KEY, null)

    fun set(sessionId: String?) {
        prefs.edit { if (sessionId == null) remove(KEY) else putString(KEY, sessionId) }
    }

    companion object {
        private const val KEY = "SETTINGS_LAST_ACTIVE_SESSION_ID"
    }
}
