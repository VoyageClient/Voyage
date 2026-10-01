/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

/** Linear undo/redo over whole editor states. States must be immutable and compare by value. */
class EditHistory<T>(private val capacity: Int = DEFAULT_CAPACITY) {

    private val states = ArrayList<T>()
    private var index = -1

    val current: T? get() = states.getOrNull(index)
    val canUndo get() = index > 0
    val canRedo get() = index in 0 until states.lastIndex

    fun reset(initial: T) {
        states.clear()
        states.add(initial)
        index = 0
    }

    /** Returns false when [state] is what is already current, so nothing was recorded. */
    fun commit(state: T): Boolean {
        if (index >= 0 && states[index] == state) return false
        while (states.lastIndex > index) states.removeAt(states.lastIndex)
        states.add(state)
        if (states.size > capacity) states.removeAt(0)
        index = states.lastIndex
        return true
    }

    fun undo(): T? {
        if (!canUndo) return null
        index--
        return states[index]
    }

    fun redo(): T? {
        if (!canRedo) return null
        index++
        return states[index]
    }

    fun copy() = EditHistory<T>(capacity).also {
        it.states.addAll(states)
        it.index = index
    }

    companion object {
        const val DEFAULT_CAPACITY = 50
    }
}
