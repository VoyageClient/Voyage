/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.selection

object BulkCopyFormatter {

    data class Entry(val senderId: String, val senderName: String, val body: String)

    /**
     * Joins [entries] (oldest first) for the clipboard. A "Name:" line is written only when the sender
     * changes, and not at all for a single message or when every message is [myUserId]'s.
     */
    fun format(entries: List<Entry>, myUserId: String): String {
        val withNames = entries.size > 1 && entries.any { it.senderId != myUserId }
        val out = StringBuilder()
        var previous: Entry? = null
        entries.forEach { entry ->
            if (out.isNotEmpty()) out.append("\n\n")
            if (withNames && (previous?.senderId != entry.senderId || previous?.senderName != entry.senderName)) {
                out.append(entry.senderName).append(":\n")
            }
            out.append(entry.body)
            previous = entry
        }
        return out.toString()
    }
}
