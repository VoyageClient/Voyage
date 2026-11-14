/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Rooms delivered with `timeline_limit` 0 since their last messages. Synapse only records a lowered limit
 * on a `limited` response, so raising it again can resume from the last delivery and silently skip what
 * came in between; each room here gets its next timeline read as limited, leaving a gap to back-paginate.
 * With no server unread counts over sliding sync, a rising `bump_stamp` is also what marks it unread.
 *
 * Staged until [commit] so a failed import replays against the same state. The lock is never held across
 * database access, since callers come from inside transactions the driver serializes.
 */
@SessionScope
internal class UnsyncedRoomTracker @Inject constructor(
        private val stores: SessionStores,
) {
    private data class Entry(val bumpStamp: Long?, val activityTs: Long)

    private val lock = Any()
    private var committed: Map<String, Entry>? = null
    private var pending: MutableMap<String, Entry> = HashMap()

    // Set when the committed state changed without a sync response, so the next commit writes it.
    private var dirty = false

    fun onDeliveredWithoutTimeline(roomId: String, bumpStamp: Long?, now: Long) {
        ensureLoaded()
        synchronized(lock) {
            val previous = pending[roomId]
            // Without a previous stamp there is nothing to compare against: the room has only just stopped
            // getting messages, or the connection is new. Treating that as news would mark every busy room
            // unread the moment the scope is narrowed.
            val isNews = bumpStamp != null && previous?.bumpStamp != null && bumpStamp > previous.bumpStamp
            pending[roomId] = Entry(
                    bumpStamp = bumpStamp ?: previous?.bumpStamp,
                    activityTs = if (isNews) now else previous?.activityTs ?: 0L,
            )
        }
    }

    /** @return whether messages may have been left out since the room's last timeline. */
    fun onDeliveredWithTimeline(roomId: String): Boolean {
        ensureLoaded()
        return synchronized(lock) { pending.remove(roomId) != null }
    }

    /** When the room last had messages this device never received, or null if it has none pending. */
    fun unseenActivityTs(roomId: String): Long? {
        ensureLoaded()
        return synchronized(lock) { pending[roomId]?.activityTs?.takeIf { it > 0 } }
    }

    /** The room was marked read: its unseen messages count as read too. Persisted with the next commit. */
    fun clearActivity(roomId: String) {
        ensureLoaded()
        synchronized(lock) {
            pending[roomId]?.takeIf { it.activityTs > 0 }?.let { pending[roomId] = it.copy(activityTs = 0) } ?: return
            committed?.get(roomId)?.let { committed = committed.orEmpty() + (roomId to it.copy(activityTs = 0)) }
            dirty = true
        }
    }

    fun commit() {
        val snapshot = synchronized(lock) {
            if (committed == null || (!dirty && committed == pending)) return
            dirty = false
            HashMap(pending).also { committed = it }
        }
        persist(snapshot)
    }

    fun rollback() {
        synchronized(lock) {
            pending = HashMap(committed ?: return)
        }
    }

    private fun ensureLoaded() {
        if (synchronized(lock) { committed != null }) return
        val loaded = parse(stores.syncToken.getUnsyncedRooms())
        synchronized(lock) {
            if (committed == null) {
                committed = loaded
                pending = HashMap(loaded)
            }
        }
    }

    private fun persist(entries: Map<String, Entry>) {
        stores.syncToken.setUnsyncedRooms(
                entries.entries.joinToString("\n") { (roomId, entry) -> "$roomId\t${entry.bumpStamp ?: ""}\t${entry.activityTs}" }
                        .takeIf { it.isNotEmpty() }
        )
    }

    private fun parse(serialized: String?): Map<String, Entry> {
        if (serialized.isNullOrEmpty()) return emptyMap()
        return serialized.lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            val roomId = fields.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            roomId to Entry(
                    bumpStamp = fields.getOrNull(1)?.toLongOrNull(),
                    activityTs = fields.getOrNull(2)?.toLongOrNull() ?: 0L,
            )
        }.toMap()
    }
}
