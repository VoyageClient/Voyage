/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.Selection
import android.util.AttributeSet
import android.view.ActionMode
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import im.vector.app.core.extensions.removeParagraphLayoutSpans
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.features.home.room.detail.composer.images.UriContentListener
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.html.pillsToCopyText
import im.vector.lib.core.utils.text.copyRawSelection
import timber.log.Timber

class ComposerEditText @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    interface Callback {
        fun onRichContentSelected(contentUri: Uri): Boolean
        fun onTextChanged(text: CharSequence)
    }

    var callback: Callback? = null

    /**
     * Turns the mention at [start, end) into a pill. Set by the composer, which owns the room/member
     * lookup a pill needs; left null, mentions are kept as typed.
     */
    var onMentionCompleted: ((Editable, Int, Int) -> Unit)? = null

    // Set while the field rewrites its own text (a pill collapsing or expanding), so the mention
    // handling below doesn't act on a change it made itself.
    private var rewriting = false
    private var pasting = false
    private var pillToRestore: PillImageSpan? = null
    private var pillRestorePosition = -1

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        var ic = super.onCreateInputConnection(editorInfo) ?: return null
        val mimeTypes = ViewCompat.getOnReceiveContentMimeTypes(this) ?: arrayOf("image/*")

        EditorInfoCompat.setContentMimeTypes(editorInfo, mimeTypes)
        ic = InputConnectionCompat.createWrapper(this, ic, editorInfo)

        ViewCompat.setOnReceiveContentListener(
                this,
                mimeTypes,
                UriContentListener { callback?.onRichContentSelected(it) }
        )

        return ic
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (copyRawSelection(id) { it.pillsToCopyText() }) return true
        // A mention arrives complete when it is pasted, so it pills without waiting for a terminator.
        pasting = id == android.R.id.paste ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && id == android.R.id.pasteAsPlainText)
        return try {
            super.onTextContextMenuItem(id)
        } finally {
            pasting = false
        }
    }

    // Some Android 4.x (TouchWiz) builds throw ArithmeticException: divide by zero inside
    // Editor.updateShowAsAction while creating the text-selection action mode on long-press. Swallow it
    // so the selection menu just fails to open instead of crashing the app.
    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return try {
            super.startActionMode(callback)
        } catch (e: ArithmeticException) {
            Timber.w(e, "Suppressed selection ActionMode crash (framework bug)")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return try {
            super.startActionMode(callback, type)
        } catch (e: ArithmeticException) {
            Timber.w(e, "Suppressed selection ActionMode crash (framework bug)")
            null
        }
    }

    /** Set whether the keyboard should disable personalized learning. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun setUseIncognitoKeyboard(useIncognitoKeyboard: Boolean) {
        imeOptions = if (useIncognitoKeyboard) {
            imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        }
    }

    /** Set whether enter should send the message or add a new line. */
    fun setSendMessageWithEnter(sendMessageWithEnter: Boolean) {
        if (sendMessageWithEnter) {
            inputType = inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE.inv()
            imeOptions = imeOptions or EditorInfo.IME_ACTION_SEND
        } else {
            inputType = inputType or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = imeOptions and EditorInfo.IME_ACTION_SEND.inv()
        }
    }

    init {
        addTextChangedListener(
                object : SimpleTextWatcher() {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                        if (rewriting || count != 1 || after != 0) return
                        // Backspace onto a pill — or onto the space that finished the mention off and
                        // made it one — puts the mention back as editable text rather than swallowing
                        // it, so it can be corrected (and pilled again).
                        pillToRestore = if (start > 0 && s.getOrNull(start)?.isWhitespace() == true) {
                            editableText.getSpans(start - 1, start, PillImageSpan::class.java)
                                    .firstOrNull { editableText.getSpanEnd(it) == start }
                        } else {
                            editableText.getSpans(start, start + 1, PillImageSpan::class.java)
                                    .firstOrNull { editableText.getSpanStart(it) == start && editableText.getSpanEnd(it) == start + 1 }
                        }
                        pillToRestore?.let { pillRestorePosition = editableText.getSpanStart(it) }
                    }

                    override fun afterTextChanged(s: Editable) {
                        if (rewriting) return
                        rewriting = true
                        try {
                            if (!restorePillText(s)) pillifyCompletedMentions(s)
                        } finally {
                            rewriting = false
                        }
                        if (s.removeParagraphLayoutSpans()) {
                            Timber.d("Composer: dropped indent/alignment spans carried in by a rich-text paste")
                        }
                        callback?.onTextChanged(s.toString())
                    }
                }
        )
    }

    private fun restorePillText(editable: Editable): Boolean {
        val span = pillToRestore ?: return false
        pillToRestore = null
        val text = span.copyText
        val start = editable.getSpanStart(span)
        val end = editable.getSpanEnd(span)
        editable.removeSpan(span)
        // The pill survived the deletion (its terminator went instead), so its own char is replaced;
        // otherwise the deletion took it and the text goes back where it stood.
        val at = if (start >= 0 && end > start) {
            editable.replace(start, end, text)
            start
        } else {
            (pillRestorePosition.takeIf { it in 0..editable.length } ?: return false).also { editable.insert(it, text) }
        }
        Selection.setSelection(editable, at + text.length)
        return true
    }

    private fun pillifyCompletedMentions(editable: Editable) {
        val pillify = onMentionCompleted ?: return
        // Later ones first: pilling a mention collapses it to a single char, moving what follows.
        findMentions(editable, selectionEnd, requireTerminator = !pasting).asReversed().forEach { range ->
            pillify(editable, range.first, range.last + 1)
        }
    }
}
