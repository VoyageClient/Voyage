/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Editable
import android.text.Spannable
import im.vector.app.features.html.PILL_PLACEHOLDER
import im.vector.app.features.html.PillImageSpan
import io.mockk.every
import io.mockk.mockk
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

    private fun editTextWithPill(text: String): ComposerEditText {
        val editText = ComposerEditText(context)
        val pill = mockk<PillImageSpan>(relaxed = true) {
            every { copyText } returns "@bob:matrix.org"
        }
        editText.setText(text)
        val at = text.indexOf(PILL_PLACEHOLDER)
        editText.editableText.setSpan(pill, at, at + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return editText
    }

    private fun ComposerEditText.pillCount() = editableText.getSpans(0, length(), PillImageSpan::class.java).size

    @Test
    fun `typing over the space after a pill puts the mention back as text`() {
        val editText = editTextWithPill("hi $PILL_PLACEHOLDER there")

        editText.setSelection(4, 5)
        editText.editableText.replace(4, 5, "b")

        assertEquals("hi @bob:matrix.orgbthere", editText.text.toString())
        assertEquals(0, editText.pillCount())
        assertEquals("hi @bob:matrix.orgb".length, editText.selectionEnd)
    }

    @Test
    fun `typing straight onto a pill's end puts the mention back as text`() {
        val editText = editTextWithPill("hi $PILL_PLACEHOLDER there")

        editText.setSelection(4)
        editText.editableText.insert(4, "b")

        assertEquals("hi @bob:matrix.orgb there", editText.text.toString())
        assertEquals(0, editText.pillCount())
        assertEquals("hi @bob:matrix.orgb".length, editText.selectionEnd)
    }

    @Test
    fun `punctuation typed onto a pill's end keeps the pill`() {
        val editText = editTextWithPill("hi $PILL_PLACEHOLDER there")

        editText.editableText.replace(4, 5, ",")

        assertEquals("hi $PILL_PLACEHOLDER,there", editText.text.toString())
        assertEquals(1, editText.pillCount())
    }

    @Test
    fun `programmatically clearing a pill does not restore its copy text`() {
        val editText = ComposerEditText(context)
        val pill = mockk<PillImageSpan> {
            every { copyText } returns "@bob:matrix.org"
        }
        editText.setText(PILL_PLACEHOLDER)
        editText.editableText.setSpan(pill, 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        editText.setTextProgrammatically("")

        assertEquals("", editText.text.toString())
    }
}
