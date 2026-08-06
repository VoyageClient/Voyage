/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files kept for redacted media.
 *
 * Deliberately outside Glide's cache and the edited-media directory: clearing the media cache wipes
 * both wholesale, and Glide's disk cache is a hash-keyed LRU with no way to exempt single entries.
 * Preserved media therefore lives here, and is removed only by the account-wide and per-room clear
 * actions, or with the account itself. Laid out one directory per room so the per-room clear is a
 * directory delete rather than a lookup of which room each file belonged to.
 */
@Singleton
class PreservedMediaStore @Inject constructor(
        @ApplicationContext private val context: Context,
        // Lazy: ActiveSessionHolder reaches this class through ConfigureAndStartSessionUseCase.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
) {

    // Namespaced per account, and removed on sign-out: two accounts in the same room would otherwise
    // read and delete each other's copies, and the files would outlive the account entirely.
    private fun accountRoot(userId: String? = currentUserId()): File =
            File(File(context.filesDir, DIRECTORY), (userId ?: "default").toFileName())

    private fun currentUserId(): String? = activeSessionHolder.get().getSafeActiveSession()?.myUserId

    private fun roomDir(roomId: String) = File(accountRoot(), roomId.toFileName())

    /** Path only — no directory is created, so this stays cheap enough for the bind path. */
    fun fileFor(roomId: String, eventId: String): File = File(roomDir(roomId), eventId.toFileName())

    fun has(roomId: String, eventId: String) = fileFor(roomId, eventId).isFile

    suspend fun size(): Long = withContext(Dispatchers.IO) {
        accountRoot().walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        accountRoot().deleteRecursively()
        Unit
    }

    suspend fun clearRoom(roomId: String) = withContext(Dispatchers.IO) {
        roomDir(roomId).deleteRecursively()
        Unit
    }

    /** Sign-out: the account is going away, so its preserved media goes with it. */
    suspend fun clearForUser(userId: String) = withContext(Dispatchers.IO) {
        accountRoot(userId).deleteRecursively()
        Unit
    }

    /** Creates the directory for [roomId]; only the write path needs this. */
    fun prepareRoomDir(roomId: String): File = roomDir(roomId).also { it.mkdirs() }

    // Room, event and user ids contain '$', ':' and '/', none of which are safe in a file name.
    // '_' is escaped too, so an id containing a literal "_36_" can't collide with one containing '$'.
    private fun String.toFileName() = replace(Regex("[^A-Za-z0-9.-]")) { "_${it.value[0].code}_" }

    companion object {
        private const val DIRECTORY = "preserved_media"
    }
}
