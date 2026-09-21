/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import java.util.concurrent.ConcurrentHashMap

/**
 * Ticks a user made but the view model has not reported back yet. Model building and diffing run off
 * the main thread, so a build started before a tap is bound after it and would otherwise paint the
 * pre-tap selection; a tick is held here until a committed selection agrees with it.
 */
class PendingCheckboxSelections {

    private val pending = ConcurrentHashMap<String, Boolean>()

    @Volatile private var committed: Set<String> = emptySet()

    fun isTicked(id: String) = pending[id] ?: committed.contains(id)

    fun toggle(id: String) {
        pending[id] = !isTicked(id)
    }

    fun commit(selection: Set<String>) {
        committed = selection
        pending.entries.removeAll { (id, ticked) -> selection.contains(id) == ticked }
    }

    fun applyTo(selection: Set<String>): Set<String> {
        if (pending.isEmpty()) return selection
        return selection.toMutableSet().apply {
            pending.forEach { (id, ticked) -> if (ticked) add(id) else remove(id) }
        }
    }
}
