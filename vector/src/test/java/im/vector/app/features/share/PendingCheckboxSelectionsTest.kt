/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PendingCheckboxSelectionsTest {

    private val selections = PendingCheckboxSelections()

    @Test
    fun `a tick is visible before it is committed`() {
        selections.toggle("a")

        selections.isTicked("a") shouldBeEqualTo true
        selections.applyTo(emptySet()) shouldBeEqualTo setOf("a")
    }

    @Test
    fun `a stale selection does not undo a tick`() {
        selections.commit(setOf("a"))
        selections.toggle("b")

        selections.commit(setOf("a"))

        selections.isTicked("b") shouldBeEqualTo true
        selections.applyTo(setOf("a")) shouldBeEqualTo setOf("a", "b")
    }

    @Test
    fun `an untick survives a selection that still holds the room`() {
        selections.commit(setOf("a", "b"))
        selections.toggle("b")

        selections.isTicked("b") shouldBeEqualTo false
        selections.applyTo(setOf("a", "b")) shouldBeEqualTo setOf("a")
    }

    @Test
    fun `a committed selection retires the tick it agrees with`() {
        selections.toggle("a")
        selections.commit(setOf("a"))

        selections.isTicked("a") shouldBeEqualTo true
        selections.applyTo(emptySet()) shouldBeEqualTo emptySet()
    }

    @Test
    fun `toggling twice returns to the committed value`() {
        selections.commit(setOf("a"))
        selections.toggle("a")
        selections.toggle("a")

        selections.isTicked("a") shouldBeEqualTo true
        selections.applyTo(setOf("a")) shouldBeEqualTo setOf("a")
    }
}
