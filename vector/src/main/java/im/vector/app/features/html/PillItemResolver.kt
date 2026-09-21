/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.peeking.PeekResult
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills in a pill the local data can't: a mention outside any room — a biography, a profile note —
 * points at people and rooms we may share nothing with, so the name and avatar have to come from the
 * server. Results are kept for the session (misses included) so the same mention costs one request.
 */
@Singleton
class PillItemResolver @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val coroutineScope: CoroutineScope,
) {

    private data class Profile(val displayName: String?, val avatarUrl: String?)

    private val lock = Any()
    private val resolved = mutableMapOf<String, Profile?>()
    private val pending = mutableMapOf<String, MutableList<(Profile) -> Unit>>()

    /** Calls [onResolved] on the main thread, at most once, and only if the lookup adds something. */
    fun resolve(item: MatrixItem, onResolved: (MatrixItem) -> Unit) {
        val id = item.id
        val callback: (Profile) -> Unit = { profile -> item.mergedWith(profile)?.let(onResolved) }
        synchronized(lock) {
            if (resolved.containsKey(id)) {
                val profile = resolved[id] ?: return
                coroutineScope.launch { callback(profile) }
                return
            }
            pending[id]?.let {
                it.add(callback)
                return
            }
            pending[id] = mutableListOf(callback)
        }
        val session = activeSessionHolder.getSafeActiveSession() ?: run {
            synchronized(lock) { pending.remove(id) }
            return
        }
        coroutineScope.launch {
            val profile = withContext(Dispatchers.IO) {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { tryOrNull { session.fetchProfile(item) } }
            }?.takeIf { !it.displayName.isNullOrBlank() || !it.avatarUrl.isNullOrBlank() }
            val waiting = synchronized(lock) {
                resolved[id] = profile
                pending.remove(id)
            }
            profile ?: return@launch
            waiting?.forEach { it(profile) }
        }
    }

    // getProfileAsUser, not userService().resolveUser: the latter answers from the local store, which is
    // exactly what left the pill without an avatar in the first place.
    private suspend fun Session.fetchProfile(item: MatrixItem): Profile? = when (item) {
        is MatrixItem.UserItem -> profileService().getProfileAsUser(item.id).let { Profile(it.displayName, it.avatarUrl) }
        is MatrixItem.RoomItem,
        is MatrixItem.RoomAliasItem -> (roomService().peekRoom(item.id) as? PeekResult.Success)?.let { Profile(it.name, it.avatarUrl) }
        else -> null
    }

    // The pill keeps the name it was built with unless that was only the id standing in for one.
    private fun MatrixItem.mergedWith(profile: Profile): MatrixItem? {
        val name = displayName?.takeUnless { it.isBlank() || it == id } ?: profile.displayName
        val merged = when (this) {
            is MatrixItem.UserItem -> copy(displayName = name, avatarUrl = profile.avatarUrl, avatarDecryption = null)
            is MatrixItem.RoomItem -> copy(displayName = name, avatarUrl = profile.avatarUrl)
            is MatrixItem.RoomAliasItem -> copy(displayName = name, avatarUrl = profile.avatarUrl)
            else -> null
        }
        return merged?.takeIf { it != this }
    }

    companion object {
        private const val LOOKUP_TIMEOUT_MS = 10_000L
    }
}
