/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.flexbox.FlexboxLayout
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.glide.GlideApp

@EpoxyModelClass
abstract class ImagePackEditItem : VectorEpoxyModel<ImagePackEditItem.Holder>(R.layout.item_image_pack_edit) {

    @EpoxyAttribute lateinit var image: EditableImage
    @EpoxyAttribute var resolvedUrl: String? = null
    @EpoxyAttribute var editable: Boolean = true
    @EpoxyAttribute var showUsageToggles: Boolean = true
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onDeleteClick: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onEdited: (() -> Unit)? = null

    override fun bind(holder: Holder) {
        super.bind(holder)
        // dontAnimate + fixed size: animated stickers (APNG/animated WebP) are what makes a large pack's
        // editor list janky to open and scroll; a static thumbnail is all we need here.
        GlideApp.with(holder.thumb).load(resolvedUrl).dontAnimate().override(96, 96).into(holder.thumb)

        // Inline, live-editable shortcode (mutates the model directly; no dialog).
        holder.shortcode.removeTextChangedListener(holder.watcher)
        holder.shortcode.filters = SHORTCODE_FILTERS
        if (holder.shortcode.text.toString() != image.shortcode) {
            holder.shortcode.setText(image.shortcode)
        }
        holder.shortcode.isEnabled = editable
        val boundImage = image
        holder.watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Keep the current name while the field is empty (the user is mid-edit / clearing it).
                s?.toString()?.takeIf { it.isNotBlank() }?.let { boundImage.shortcode = it }
                resizeToContent(holder.shortcode)
                onEdited?.invoke()
            }
        }
        holder.shortcode.addTextChangedListener(holder.watcher)
        resizeToContent(holder.shortcode)
        holder.shortcode.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                (v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else {
                false
            }
        }

        holder.usageRow.isVisible = showUsageToggles
        // FlexboxLayout isn't a RadioGroup, so drive the single-selection state ourselves.
        holder.syncUsage(image.emoticon, image.sticker)
        holder.emoticon.isEnabled = editable
        holder.sticker.isEnabled = editable
        holder.both.isEnabled = editable
        holder.delete.isVisible = editable
        if (editable) {
            val select = { emoticon: Boolean, sticker: Boolean ->
                boundImage.emoticon = emoticon
                boundImage.sticker = sticker
                holder.syncUsage(emoticon, sticker)
                onEdited?.invoke()
            }
            holder.emoticon.setOnClickListener { select(true, false) }
            holder.sticker.setOnClickListener { select(false, true) }
            holder.both.setOnClickListener { select(true, true) }
            holder.delete.onClick(onDeleteClick)
        }
    }

    // Size the field tightly to its text (measureText, not wrap_content which leaves a small trailing gap) so
    // the trailing ":" hugs the shortcode, but cap it at the space left in the row so a long shortcode scrolls
    // horizontally with the cursor instead of overrunning into the ":" / delete button.
    private fun resizeToContent(editText: EditText) {
        val text = editText.text?.toString().orEmpty()
        val toMeasure = text.ifEmpty { editText.hint?.toString().orEmpty() }
        val desired = editText.paint.measureText(toMeasure).toInt() + editText.compoundPaddingLeft + editText.compoundPaddingRight
        val available = availableWidth(editText)
        if (available == null) {
            // Row not laid out yet (first bind): retry once it has a width so we can compute the cap.
            editText.post { resizeToContent(editText) }
            return
        }
        val width = desired.coerceAtMost(available)
        if (editText.layoutParams.width != width) {
            editText.layoutParams = editText.layoutParams.apply { this.width = width }
        }
    }

    private fun availableWidth(editText: EditText): Int? {
        val row = editText.parent as? android.view.ViewGroup ?: return null
        if (row.width == 0) return null
        // Use each sibling's intrinsic width, not its current one: a long field squeezes the trailing ":" to
        // 0 width, and reading that 0 back would keep handing the field the whole row (hiding the ":" forever).
        val unspecified = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        var siblings = 0
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child !== editText) {
                child.measure(unspecified, unspecified)
                siblings += child.measuredWidth
            }
        }
        return (row.width - row.paddingLeft - row.paddingRight - siblings).takeIf { it > 0 }
    }

    override fun unbind(holder: Holder) {
        holder.shortcode.removeTextChangedListener(holder.watcher)
        holder.watcher = null
        GlideApp.with(holder.thumb.context.applicationContext).clear(holder.thumb)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val thumb by bind<ImageView>(R.id.imagePackEditThumb)
        val shortcode by bind<EditText>(R.id.imagePackEditShortcode)
        val usageRow by bind<FlexboxLayout>(R.id.imagePackEditUsageRow)
        val emoticon by bind<RadioButton>(R.id.imagePackEditEmoticon)
        val sticker by bind<RadioButton>(R.id.imagePackEditSticker)
        val both by bind<RadioButton>(R.id.imagePackEditBoth)
        val delete by bind<ImageButton>(R.id.imagePackEditDelete)
        var watcher: TextWatcher? = null

        fun syncUsage(emoticonUsage: Boolean, stickerUsage: Boolean) {
            emoticon.isChecked = emoticonUsage && !stickerUsage
            sticker.isChecked = stickerUsage && !emoticonUsage
            both.isChecked = emoticonUsage && stickerUsage
        }
    }

    companion object {
        // MSC2545 shortcode grammar: ASCII [a-zA-Z0-9-_] only (not Unicode letters), max 100 bytes — which
        // for this ASCII-only set is the same as 100 chars.
        private fun isShortcodeChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'

        private val SHORTCODE_FILTERS = arrayOf<InputFilter>(
                InputFilter.LengthFilter(100),
                InputFilter { source, start, end, _, _, _ ->
                    val filtered = (start until end).filter { isShortcodeChar(source[it]) }
                    if (filtered.size == end - start) null else filtered.map { source[it] }.joinToString("")
                },
        )
    }
}
