/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.sessionId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SignedOutNotice(
        val userId: String,
        val displayName: String?,
)

/**
 * Remembers an account the homeserver signed out remotely until the user has acknowledged it by
 * picking or adding an account, so the "You've been signed out" screen survives app restarts.
 * Its own prefs file and filesDir location keep it clear of the sign-out cleanup.
 */
@Singleton
class SignedOutNoticeStore @Inject constructor(
        @ApplicationContext private val context: Context,
        private val authenticationService: AuthenticationService,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val avatarFile: File
        get() = File(context.filesDir, AVATAR_FILE_NAME)

    fun get(): SignedOutNotice? {
        if (!isPending()) return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return SignedOutNotice(userId, prefs.getString(KEY_DISPLAY_NAME, null))
    }

    fun isPending(): Boolean {
        if (!prefs.contains(KEY_USER_ID)) return false
        // A session that didn't exist at sign-out time means the user has since signed in
        // (any login path, SSO included), which answers the notice.
        val known = prefs.getStringSet(KEY_KNOWN_SESSION_IDS, null).orEmpty()
        if (currentSessionIds().any { it !in known }) {
            clear()
            return false
        }
        return true
    }

    /** Call after the account's credentials are gone; takes ownership of [cachedAvatar]. */
    fun set(userId: String, displayName: String?, cachedAvatar: File?) {
        avatarFile.delete()
        if (cachedAvatar != null && cachedAvatar.length() > 0 && !cachedAvatar.renameTo(avatarFile)) {
            runCatching { cachedAvatar.copyTo(avatarFile, overwrite = true) }.onFailure { avatarFile.delete() }
        }
        prefs.edit(commit = true) {
            putString(KEY_USER_ID, userId)
            if (displayName == null) remove(KEY_DISPLAY_NAME) else putString(KEY_DISPLAY_NAME, displayName)
            putStringSet(KEY_KNOWN_SESSION_IDS, currentSessionIds().toSet())
        }
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
        avatarFile.delete()
    }

    private fun currentSessionIds() = authenticationService.getAllSessionParams().map { it.credentials.sessionId() }

    companion object {
        private const val PREFS_NAME = "signed_out_notice_prefs"
        private const val KEY_USER_ID = "userId"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_KNOWN_SESSION_IDS = "knownSessionIds"
        private const val AVATAR_FILE_NAME = "signed_out_avatar.bin"
    }
}
