/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import im.vector.lib.core.utils.text.DirectionOverridesTransformation
import im.vector.lib.core.utils.text.SingleLineDirectionOverridesTransformation
import im.vector.lib.core.utils.text.neutralizeDirectionOverridesInput
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DirectionOverridesInputTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val hostile = "abc‮def\nghi"

    private fun EditText.displayed() = transformationMethod.getTransformation(text, this).toString()

    @Test
    fun `single-line field keeps drawing newlines as spaces`() {
        val editText = EditText(context).apply { setSingleLine() }
        editText.setText(hostile)

        editText.neutralizeDirectionOverridesInput()

        editText.transformationMethod shouldBe SingleLineDirectionOverridesTransformation
        editText.displayed() shouldBeEqualTo "abc﷐def ghi"
        editText.text.toString() shouldBeEqualTo hostile
    }

    @Test
    fun `multi-line field keeps its newlines`() {
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        editText.setText(hostile)

        editText.neutralizeDirectionOverridesInput()

        editText.transformationMethod shouldBe DirectionOverridesTransformation
        editText.displayed() shouldBeEqualTo "abc﷐def\nghi"
    }

    @Test
    fun `password mask is left alone`() {
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        editText.neutralizeDirectionOverridesInput()

        (editText.transformationMethod is PasswordTransformationMethod) shouldBe true
    }

    @Test
    fun `copy from the selection toolbar puts the raw text on the clipboard`() {
        val editText = EditText(context).apply { setSingleLine() }
        editText.setText(hostile)
        editText.neutralizeDirectionOverridesInput()
        editText.setSelection(0, 7)
        val mode = mockk<ActionMode>(relaxed = true)
        val copy = mockk<MenuItem> { every { itemId } returns android.R.id.copy }

        val handled = editText.customSelectionActionModeCallback.onActionItemClicked(mode, copy)

        handled shouldBe true
        verify { mode.finish() }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.text.toString() shouldBeEqualTo "abc‮def"
    }

    @Test
    fun `an existing selection callback is wrapped once and still consulted first`() {
        val existing = mockk<ActionMode.Callback>(relaxed = true)
        val editText = EditText(context).apply { customSelectionActionModeCallback = existing }
        editText.neutralizeDirectionOverridesInput()
        val wrapper = editText.customSelectionActionModeCallback

        editText.neutralizeDirectionOverridesInput()
        editText.customSelectionActionModeCallback shouldBe wrapper

        val mode = mockk<ActionMode>(relaxed = true)
        val menu = mockk<Menu>(relaxed = true)
        every { existing.onCreateActionMode(mode, menu) } returns false
        wrapper.onCreateActionMode(mode, menu) shouldBe false
    }

    @Test
    fun `views that copy raw text themselves get no selection callback`() {
        val editText = RawCopyEditText(context)

        editText.neutralizeDirectionOverridesInput()

        editText.customSelectionActionModeCallback shouldBe null
    }
}
