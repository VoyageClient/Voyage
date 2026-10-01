/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class EditHistoryTest {

    private val history = EditHistory<Int>(capacity = 3).apply { reset(0) }

    @Test
    fun `a fresh history has nothing to undo or redo`() {
        history.canUndo shouldBeEqualTo false
        history.canRedo shouldBeEqualTo false
        history.undo().shouldBeNull()
    }

    @Test
    fun `undo and redo walk the committed states`() {
        history.commit(1)
        history.commit(2)

        history.undo() shouldBeEqualTo 1
        history.undo() shouldBeEqualTo 0
        history.canUndo shouldBeEqualTo false
        history.redo() shouldBeEqualTo 1
        history.redo() shouldBeEqualTo 2
        history.canRedo shouldBeEqualTo false
    }

    @Test
    fun `a new commit drops what could have been redone`() {
        history.commit(1)
        history.commit(2)
        history.undo()

        history.commit(5)

        history.canRedo shouldBeEqualTo false
        history.undo() shouldBeEqualTo 1
    }

    @Test
    fun `committing the current state records nothing`() {
        history.commit(1) shouldBeEqualTo true
        history.commit(1) shouldBeEqualTo false

        history.undo() shouldBeEqualTo 0
    }

    @Test
    fun `the oldest state falls off past the capacity`() {
        history.commit(1)
        history.commit(2)
        history.commit(3)

        history.undo() shouldBeEqualTo 2
        history.undo() shouldBeEqualTo 1
        history.canUndo shouldBeEqualTo false
    }
}
