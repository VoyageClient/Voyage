/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.flexbox.FlexboxLayout
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.media.ImageContentRenderer
import im.vector.lib.strings.CommonStrings

@EpoxyModelClass
abstract class ImagePackEditItem : VectorEpoxyModel<ImagePackEditItem.Holder>(R.layout.item_image_pack_edit) {

    @EpoxyAttribute lateinit var image: EditableImage
    @EpoxyAttribute var highlighted: Boolean = false
    @EpoxyAttribute var removed: Boolean = false

    // An http url for uploaded images, the local File for ones not uploaded yet.
    @EpoxyAttribute var thumbSource: Any? = null
    @EpoxyAttribute var editable: Boolean = true
    @EpoxyAttribute var showUsageToggles: Boolean = true
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onDeleteClick: ClickListener? = null
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash) var onEdited: (() -> Unit)? = null

    // A row losing its highlight while on screen (the pack was just applied) fades it out instead of snapping.
    override fun bind(holder: Holder, previouslyBoundModel: EpoxyModel<*>) {
        super.bind(holder, previouslyBoundModel)
        val previous = previouslyBoundModel as? ImagePackEditItem
        if (previous != null && (previous.highlighted || previous.removed) && !highlighted && !removed) {
            holder.highlight.isVisible = true
            holder.highlight.setBackgroundColor(highlightColor(holder.highlight.context, previous.removed))
            ViewCompat.animate(holder.highlight)
                    .alpha(0f)
                    .setDuration(ImageContentRenderer.CROSSFADE_MS.toLong())
                    .withEndAction { holder.highlight.isVisible = false }
        }
    }

    override fun bind(holder: Holder) {
        super.bind(holder)
        ViewCompat.animate(holder.highlight).cancel()
        holder.highlight.alpha = 1f
        holder.highlight.isVisible = highlighted || removed
        holder.highlight.setBackgroundColor(highlightColor(holder.highlight.context, removed))
        // dontAnimate + fixed size: animated stickers (APNG/animated WebP) are what makes a large pack's
        // editor list janky to open and scroll; a static thumbnail is all we need here.
        GlideApp.with(holder.thumb).load(thumbSource).dontAnimate().override(96, 96).into(holder.thumb)
        holder.thumb.alpha = if (removed) REMOVED_ALPHA else 1f

        // Inline, live-editable shortcode (mutates the model directly; no dialog).
        holder.shortcode.removeTextChangedListener(holder.watcher)
        holder.shortcode.filters = SHORTCODE_FILTERS
        if (holder.shortcode.text.toString() != image.shortcode) {
            holder.shortcode.setText(image.shortcode)
        }
        holder.shortcode.isEnabled = editable && !removed
        val boundImage = image
        holder.watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Keep the current name while the field is empty (the user is mid-edit / clearing it).
                s?.toString()?.takeIf { it.isNotBlank() }?.let {
                    boundImage.shortcode = it
                    // The body names a sent sticker, and nothing else here edits it, so it follows the rename.
                    boundImage.body = it
                }
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
        holder.emoticon.isEnabled = editable && !removed
        holder.sticker.isEnabled = editable && !removed
        holder.both.isEnabled = editable && !removed
        holder.delete.isVisible = editable
        holder.delete.setImageDrawable(AppCompatResources.getDrawable(holder.delete.context, if (removed) R.drawable.ic_editor_undo else R.drawable.ic_close_24dp))
        holder.delete.contentDescription = holder.delete.context.getString(
                if (removed) CommonStrings.image_pack_keep_image else CommonStrings.image_pack_remove_image
        )
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

    private fun highlightColor(context: Context, removed: Boolean) = ContextCompat.getColor(
            context,
            if (removed) im.vector.lib.ui.styles.R.color.image_pack_removed_highlight else im.vector.lib.ui.styles.R.color.image_pack_imported_highlight
    )

    // Size the field tightly to its text (measureText, not wrap_content which leaves a small trailing gap) so
    // the trailing ":" hugs the shortcode, but cap it at the space left in the row so a long shortcode scrolls
    // horizontally with the cursor instead of overrunning into the ":" / delete button.
    private fun resizeToContent(editText: EditText) {
        val text = editText.text?.toString().orEmpty()
        val toMeasure = text.ifEmpty { editText.hint?.toString().orEmpty() }
        val desired = editText.paint.measureText(toMeasure).toInt() + editText.compoundPaddingLeft + editText.compoundPaddingRight
        val available = availableWidth(editText)
        if (available == null) {
            // Row not laid out yet (first bind): size to the text now, uncapped — leaving wrap_content here
            // measures the HINT ("shortcode"), parking the trailing ":" way out until the retry runs — and
            // re-run once laid out to apply the cap.
            if (editText.layoutParams.width != desired) {
                editText.layoutParams = editText.layoutParams.apply { this.width = desired }
            }
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
        ViewCompat.animate(holder.highlight).cancel()
        holder.highlight.alpha = 1f
        holder.highlight.isVisible = false
        GlideApp.with(holder.thumb.context.applicationContext).clear(holder.thumb)
        super.unbind(holder)
    }

    class Holder : VectorEpoxyHolder() {
        val highlight by bind<View>(R.id.imagePackEditHighlight)
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
        private const val REMOVED_ALPHA = 0.4f

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
