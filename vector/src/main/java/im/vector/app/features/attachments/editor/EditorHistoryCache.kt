/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

/**
 * Each attachment's undo history for the life of the process, so reopening the editor on an
 * attachment can still undo what was done before it was saved. Kept in memory rather than passed
 * through the intent: strokes make it too large for a binder transaction.
 */
object EditorHistoryCache {

    private const val MAX_ENTRIES = 8

    private class Entry(val edits: AttachmentEdits?, val history: EditHistory<*>)

    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }

    /** Records [history] as the one that produced [edits] of the original at [key]. */
    @Synchronized
    fun put(key: String, edits: AttachmentEdits?, history: EditHistory<*>) {
        entries[key] = Entry(edits, history.copy())
    }

    /**
     * The history to resume when the editor reopens [key] on [edits]; null when those are not the
     * edits it was saved with, since a stale history would undo into something else.
     */
    @Synchronized
    fun find(key: String, edits: AttachmentEdits?): EditHistory<*>? {
        val entry = entries[key] ?: return null
        // Undoing everything hands the original back, which reopens with no edits at all.
        val matches = entry.edits == edits || (edits == null && entry.edits?.hasChanges != true)
        return if (matches) entry.history.copy() else null
    }
}
