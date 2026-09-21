/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.widget.CheckBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectionAwareRelativeLayoutTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private class CountingRow(context: Context) : SelectionAwareRelativeLayout(context) {
        var merges = 0
        override fun onCreateDrawableState(extraSpace: Int): IntArray {
            merges++
            return super.onCreateDrawableState(extraSpace)
        }
    }

    private fun aRow(childCount: Int = 8) = CountingRow(context).apply {
        setAddStatesFromChildren(true)
        repeat(childCount) { addView(CheckBox(context)) }
        drawableState
        merges = 0
    }

    @Test
    fun `a window focus change costs the same merges whatever the row holds`() {
        val small = aRow(childCount = 4)
        val large = aRow(childCount = 32)

        small.dispatchWindowFocusChanged(true)
        large.dispatchWindowFocusChanged(true)

        assertEquals(small.merges, large.merges)
        assertTrue("merged ${large.merges} times", large.merges <= 2)
    }

    @Test
    fun `a child state change outside a focus dispatch still reaches the row`() {
        val row = aRow(childCount = 2)

        (row.getChildAt(0) as CheckBox).isPressed = true

        assertTrue(row.merges > 0)
    }

    @Test
    fun `the row keeps its own pressed state through a focus dispatch`() {
        val row = aRow(childCount = 2)

        row.setDescendantPressed(true, 0f, 0f)
        row.dispatchWindowFocusChanged(true)

        assertTrue(row.drawableState.contains(android.R.attr.state_pressed))
    }

    @Test
    fun `the row drops the states a held text selection merges up`() {
        val row = aRow(childCount = 1)
        val child = row.getChildAt(0) as CheckBox
        child.isSelected = true
        child.isActivated = true

        val state = row.drawableState

        assertFalse(state.contains(android.R.attr.state_selected))
        assertFalse(state.contains(android.R.attr.state_activated))
        assertFalse(state.contains(android.R.attr.state_focused))
    }
}
