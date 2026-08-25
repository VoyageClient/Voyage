/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.Editable
import android.text.method.MovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.extensions.hasClickableSpanAt
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.features.html.bindEmoteImageSpans

/**
 * Discord-style inline profile note: renders the formatted note, tapping it switches to a plain
 * multiline EditText holding the markdown source; dismissing the keyboard commits the edit and
 * returns to the rendered view.
 */
@EpoxyModelClass
abstract class PersonalNoteItem : VectorEpoxyModel<PersonalNoteItem.Holder>(R.layout.item_personal_note) {

    /** The markdown source of the note, i.e. what is edited. */
    @EpoxyAttribute
    var noteSource: String = ""

    /** The rendered representation shown outside of edit mode. */
    @EpoxyAttribute
    var renderedNote: CharSequence? = null

    /** Bumped by the screen to force a rebind that re-syncs the editor with [noteSource]. */
    @EpoxyAttribute
    var generation: Long = 0

    // Lets links, spoilers and other interactive spans handle their own taps; taps on plain text
    // still reach the click listener that opens the editor.
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var movementMethod: MovementMethod? = null

    // Edit mode and the in-progress draft are held by the screen so they survive the view being
    // recycled on scroll. Read through providers at bind time: they change without a model rebuild,
    // so a plain attribute would hand a recycled view stale values.
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var editingProvider: (() -> Boolean)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var draftProvider: (() -> String?)? = null

    // Cursor/selection survives recycling the same way the draft does
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var selectionProvider: (() -> Pair<Int, Int>?)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onSelectionStashed: ((Int, Int) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onNoteChanged: ((String) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onEditingChanged: ((Boolean) -> Unit)? = null

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onDraftChanged: ((String) -> Unit)? = null

    /**
     * The editor scrolled off screen mid-edit. The host moves focus to an off-list proxy input so
     * the IME keeps a live connection with the same input type — otherwise it re-targets a fallback
     * and morphs its layout (e.g. grows a number row) — and typing keeps feeding the draft.
     */
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var onDetachedWhileEditing: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun bind(holder: Holder) {
        super.bind(holder)
        if (!holder.listenersWired) {
            holder.listenersWired = true
            holder.edit.setupLiveEmojiInput()
            holder.edit.addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable) {
                    holder.draftListener?.invoke(s.toString())
                }
            })
        }
        holder.rendered.movementMethod = movementMethod
        holder.rendered.text = renderedNote
        // Async emote images can't surface through a span's invalidate(); they need the TextView bound
        holder.rendered.bindEmoteImageSpans()
        val isEditing = editingProvider?.invoke() == true || holder.edit.hasFocus()
        if (!holder.edit.hasFocus()) {
            holder.edit.setText(draftProvider?.invoke() ?: noteSource)
        }
        // After the setText above, which would otherwise stash a phantom draft on every bind
        holder.draftListener = onDraftChanged
        setMode(holder, isEditing)
        var lastTapHitSpan = false
        var lastTapX = 0f
        var lastTapY = 0f
        holder.rendered.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                lastTapHitSpan = (v as TextView).hasClickableSpanAt(event)
                lastTapX = event.x
                lastTapY = event.y
            }
            false
        }
        holder.rendered.setOnClickListener {
            // A tap on an interactive span (link, spoiler…) is the span's, not the editor's
            if (lastTapHitSpan) {
                lastTapHitSpan = false
                return@setOnClickListener
            }
            setMode(holder, editing = true)
            onEditingChanged?.invoke(true)
            holder.edit.setSelection(sourceOffsetForTap(holder, lastTapX, lastTapY))
            holder.edit.requestFocus()
            startWatchingKeyboard(holder)
            val imm = holder.edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(holder.edit, InputMethodManager.SHOW_IMPLICIT)
        }
        // Multiline editing: Enter inserts newlines; dismissing the keyboard is what saves.
        // No exit on focus loss: scrolling the item off screen steals focus, and edit mode
        // must survive that (the editing/draft providers restore it on rebind).
        holder.edit.onImeBack = { finishEditing(holder) }
        if (isEditing && holder.keyboardWatcher == null) startWatchingKeyboard(holder)
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        if (editingProvider?.invoke() == true) {
            // Stash before the handoff so the proxy resumes at the same cursor position
            onSelectionStashed?.invoke(holder.edit.selectionStart, holder.edit.selectionEnd)
            onDetachedWhileEditing?.invoke()
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        if (editingProvider?.invoke() != true) return
        if (holder.keyboardWatcher == null) startWatchingKeyboard(holder)
        // Scrolled back into view mid-edit: recycling stripped the focus and the IME connection,
        // so hand them back rather than leaving a dead editor.
        if (!holder.edit.hasFocus()) {
            holder.edit.post {
                if (editingProvider?.invoke() != true || !holder.edit.isShown) return@post
                // Re-sync: the draft may have advanced through the off-list proxy since bind
                holder.edit.setText(draftProvider?.invoke() ?: noteSource)
                holder.edit.requestFocus()
                val length = holder.edit.text?.length ?: 0
                val selection = selectionProvider?.invoke()
                if (selection != null) {
                    holder.edit.setSelection(selection.first.coerceIn(0, length), selection.second.coerceIn(0, length))
                } else {
                    holder.edit.setSelection(length)
                }
                val imm = holder.edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(holder.edit, InputMethodManager.SHOW_IMPLICIT)
                if (holder.keyboardWatcher == null) startWatchingKeyboard(holder)
            }
        }
    }

    override fun unbind(holder: Holder) {
        // No commit: a recycled mid-edit view keeps its draft in the host and resumes on rebind
        if (editingProvider?.invoke() == true) {
            onSelectionStashed?.invoke(holder.edit.selectionStart, holder.edit.selectionEnd)
        }
        stopWatchingKeyboard(holder)
        holder.draftListener = null
        holder.edit.onImeBack = null
        holder.rendered.setOnClickListener(null)
        holder.rendered.setOnTouchListener(null)
        super.unbind(holder)
    }

    // The IME's own hide button dismisses the keyboard without any back-key event, so watch the
    // visible window frame while editing: once the keyboard has been up and goes away, commit.
    private fun startWatchingKeyboard(holder: Holder) {
        stopWatchingKeyboard(holder)
        // Only on an attached view: a detached view's rootView is the view itself, whose bogus
        // frame math would register a phantom open→closed transition and exit editing on reattach.
        if (holder.view.windowToken == null) return
        val root = holder.view.rootView ?: return
        var keyboardWasOpen = false
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val frame = Rect()
            root.getWindowVisibleDisplayFrame(frame)
            if (frame.height() <= 0 || root.height <= 0) return@OnGlobalLayoutListener
            // App-switching closes the keyboard too; only a dismissal in the focused window is a save gesture
            if (!root.hasWindowFocus()) return@OnGlobalLayoutListener
            val keyboardOpen = root.height - frame.height() > root.height * 0.15
            if (keyboardOpen) {
                keyboardWasOpen = true
            } else if (keyboardWasOpen) {
                // Only a dismissal while the editor is actually on screen counts: scroll-recycling
                // detaches the focused view and can close the IME, which is not a save gesture.
                if (holder.edit.hasFocus() && holder.edit.isShown) {
                    finishEditing(holder)
                } else {
                    stopWatchingKeyboard(holder)
                }
            }
        }
        holder.keyboardWatcher = listener
        holder.watchedRoot = root
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun stopWatchingKeyboard(holder: Holder) {
        val listener = holder.keyboardWatcher ?: return
        holder.watchedRoot?.viewTreeObserver?.let { observer ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                observer.removeOnGlobalLayoutListener(listener)
            } else {
                @Suppress("DEPRECATION")
                observer.removeGlobalOnLayoutListener(listener)
            }
        }
        holder.keyboardWatcher = null
        holder.watchedRoot = null
    }

    private fun finishEditing(holder: Holder) {
        stopWatchingKeyboard(holder)
        commit(holder)
        holder.edit.clearFocus()
        setMode(holder, editing = false)
        onEditingChanged?.invoke(false)
        // Until the save round-trips into new state, show the plain source rather than a stale rendering
        val text = holder.edit.text?.toString().orEmpty()
        if (text != noteSource) holder.rendered.text = text
    }

    /**
     * The cursor position in the markdown source matching a tap on the rendered note. Exact when
     * the note is plain (rendered == source); for formatted notes, the literal text around the tap
     * is looked up in the source, falling back to a proportional position.
     */
    private fun sourceOffsetForTap(holder: Holder, tapX: Float, tapY: Float): Int {
        val source = holder.edit.text?.toString().orEmpty()
        val textView = holder.rendered
        val rendered = textView.text?.toString().orEmpty()
        val layout = textView.layout ?: return source.length
        val x = tapX.toInt() - textView.totalPaddingLeft + textView.scrollX
        val y = tapY.toInt() - textView.totalPaddingTop + textView.scrollY
        val line = layout.getLineForVertical(y)
        val renderedOffset = if (x > layout.getLineRight(line)) {
            layout.getLineEnd(line)
        } else {
            layout.getOffsetForHorizontal(line, x.toFloat().coerceAtLeast(layout.getLineLeft(line)))
        }.coerceIn(0, rendered.length)
        if (source == rendered) return renderedOffset
        for (chunkLength in intArrayOf(12, 8, 5, 3)) {
            val end = (renderedOffset + chunkLength).coerceAtMost(rendered.length)
            if (end - renderedOffset < 3) continue
            val chunk = rendered.substring(renderedOffset, end)
            if (chunk.isBlank()) continue
            val index = source.indexOf(chunk)
            if (index >= 0) return index
        }
        return if (rendered.isEmpty()) source.length else (renderedOffset.toLong() * source.length / rendered.length).toInt().coerceIn(0, source.length)
    }

    private fun commit(holder: Holder) {
        val text = holder.edit.text?.toString().orEmpty()
        if (text.trim() != noteSource.trim()) {
            onNoteChanged?.invoke(text)
        }
    }

    private fun setMode(holder: Holder, editing: Boolean) {
        holder.rendered.isVisible = !editing
        holder.edit.isVisible = editing
    }

    class Holder : VectorEpoxyHolder() {
        val rendered by bind<TextView>(R.id.personalNoteRendered)
        val edit by bind<PersonalNoteEditText>(R.id.personalNoteEdit)
        var listenersWired = false
        var draftListener: ((String) -> Unit)? = null

        // On the holder, not the model: rebinds swap in a fresh model instance mid-edit
        var keyboardWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null
        var watchedRoot: View? = null
    }
}
