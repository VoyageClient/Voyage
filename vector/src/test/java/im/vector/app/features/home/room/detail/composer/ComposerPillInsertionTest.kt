/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Editable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The autocomplete inserts a display name that may itself read as a mention (a member with no
 * display name is inserted as their id) and spans it with its own pill afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComposerPillInsertionTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private fun editText(collapsed: MutableList<String>) = ComposerEditText(context).apply {
        onMentionCompleted = { editable: Editable, start: Int, end: Int ->
            collapsed.add(editable.substring(start, end))
            editable.replace(start, end, "￼")
        }
    }

    @Test
    fun `a typed out mention is pilled once it is finished`() {
        val collapsed = mutableListOf<String>()
        val editText = editText(collapsed)

        editText.setText("hi @bob:matrix.org ")

        assertEquals(listOf("@bob:matrix.org"), collapsed)
        assertEquals("hi ￼ ", editText.text.toString())
    }

    @Test
    fun `the text the autocomplete inserts is left for it to pill itself`() {
        val collapsed = mutableListOf<String>()
        val editText = editText(collapsed)
        editText.setText("hi @b")

        val editable = editText.editableText
        editText.insertingPill { editable.replace(3, editable.length, "@bob:matrix.org ") }

        assertEquals(emptyList<String>(), collapsed)
        assertEquals("hi @bob:matrix.org ", editable.toString())
    }
}
