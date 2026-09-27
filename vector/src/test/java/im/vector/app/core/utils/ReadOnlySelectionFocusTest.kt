/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadOnlySelectionFocusTest {

    private class MessageView(context: Context) : TextView(context), SelectionFocusHost {
        override val selectionFocus = ReadOnlySelectionFocus(this)

        init {
            setTextIsSelectable(true)
        }
    }

    @Test
    fun `ending successive message selections restores the original editor`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity)
        val composer = EditText(activity)
        val first = MessageView(activity)
        val second = MessageView(activity)
        val third = MessageView(activity)
        listOf(composer, first, second, third).forEach { root.addView(it) }
        activity.setContentView(root)
        assertTrue(composer.requestFocus())

        select(first)
        select(second)
        first.selectionFocus.endSelection()
        assertTrue(second.isFocused)
        select(third)
        second.selectionFocus.endSelection()
        assertTrue(third.isFocused)
        third.selectionFocus.endSelection()

        assertTrue(composer.isFocused)
    }

    private fun select(message: MessageView) {
        val event = MotionEvent.obtain(1000L, 1000L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        try {
            message.selectionFocus.beforeTouch(event)
        } finally {
            event.recycle()
        }
        message.selectionFocus.beforeLongClick()
        assertTrue(message.requestFocus())
    }
}
