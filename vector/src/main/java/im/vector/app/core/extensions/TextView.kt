/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings

/**
 * Set a text in the TextView, or set visibility to GONE if the text is null.
 */
fun TextView.setTextOrHide(newText: CharSequence?, hideWhenBlank: Boolean = true, vararg relatedViews: View = emptyArray()) {
    if (newText == null ||
            (newText.isBlank() && hideWhenBlank)) {
        isVisible = false
        relatedViews.forEach { it.isVisible = false }
    } else {
        this.text = newText
        isVisible = true
        relatedViews.forEach { it.isVisible = true }
    }
}

/**
 * Set text with a colored part.
 * @param fullTextRes the resource id of the full text. Value MUST contains a parameter for string, which will be replaced by the colored part
 * @param coloredTextRes the resource id of the colored part of the text
 * @param colorAttribute attribute of the color. Default to colorPrimary
 * @param underline true to also underline the text. Default to false
 * @param onClick attributes to handle click on the colored part if needed
 */
fun TextView.setTextWithColoredPart(
        @StringRes fullTextRes: Int,
        @StringRes coloredTextRes: Int,
        @AttrRes colorAttribute: Int = com.google.android.material.R.attr.colorPrimary,
        underline: Boolean = false,
        onClick: (() -> Unit)? = null
) {
    val coloredPart = resources.getString(coloredTextRes)
    // Insert colored part into the full text
    val fullText = resources.getString(fullTextRes, coloredPart)

    setTextWithColoredPart(fullText, coloredPart, colorAttribute, underline, onClick)
}

/**
 * Set text with a colored part.
 * @param fullText The full text.
 * @param coloredPart The colored part of the text
 * @param colorAttribute attribute of the color. Default to colorPrimary
 * @param underline true to also underline the text. Default to false
 * @param onClick attributes to handle click on the colored part if needed
 */
fun TextView.setTextWithColoredPart(
        fullText: String,
        coloredPart: String,
        @AttrRes colorAttribute: Int = com.google.android.material.R.attr.colorPrimary,
        underline: Boolean = true,
        onClick: (() -> Unit)? = null
) {
    val color = ThemeUtils.getColor(context, colorAttribute)

    val foregroundSpan = ForegroundColorSpan(color)

    val index = fullText.indexOf(coloredPart)

    text = SpannableString(fullText)
            .apply {
                setSpan(foregroundSpan, index, index + coloredPart.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (onClick != null) {
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onClick()
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = color
                        }
                    }
                    setSpan(clickableSpan, index, index + coloredPart.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    movementMethod = LinkMovementMethod.getInstance()
                }
                if (underline) {
                    setSpan(UnderlineSpan(), index, index + coloredPart.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
}

fun TextView.setLeftDrawable(@DrawableRes iconRes: Int, @AttrRes tintColor: Int? = null) {
    // AppCompatResources, not ContextCompat: these are <vector> assets, which the framework loader
    // cannot inflate below API 21.
    val icon = if (tintColor != null) {
        val tint = ThemeUtils.getColor(context, tintColor)
        AppCompatResources.getDrawable(context, iconRes)?.also {
            DrawableCompat.setTint(it.mutate(), tint)
        }
    } else {
        AppCompatResources.getDrawable(context, iconRes)
    }
    setLeftDrawable(icon)
}

fun TextView.setLeftDrawable(drawable: Drawable?) {
    setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
}

fun TextView.clearDrawables() {
    setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
    }
}

/** Style a preview of a deleted message like [R.layout.item_timeline_event_redacted_stub] does in the timeline. */
fun TextView.setRedactedPreviewStyle() {
    val color = im.vector.lib.ui.styles.R.attr.vctr_content_tertiary
    setTextColor(ThemeUtils.getColor(context, color))
    // AppCompatResources, not ContextCompat: ic_trash_16 is a <vector>, which the framework loader
    // cannot inflate below API 21.
    val icon = AppCompatResources.getDrawable(context, R.drawable.ic_trash_16)
            ?.also { DrawableCompat.setTint(it.mutate(), ThemeUtils.getColor(context, color)) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    } else {
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }
    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
}

/**
 * Set long click listener to copy the current text of the TextView to the clipboard and show a Snackbar.
 * Copies the [setCopySource] value when one was bound, else the displayed text.
 */
fun TextView.copyOnLongClick() {
    setOnLongClickListener { view ->
        (view as? TextView)
                ?.let { tv -> tv.getTag(R.id.copy_on_long_click_source) as? CharSequence ?: tv.text }
                ?.let { text ->
                    copyToClipboard(view.context, text, false)
                    view.showOptimizedSnackbar(view.resources.getString(CommonStrings.copied_to_clipboard))
                }
        true
    }
}

/** The exact text [copyOnLongClick] should copy when the displayed text is a lossy rendering of it
 *  (e.g. direction-override characters neutralized to a placeholder glyph). */
fun TextView.setCopySource(raw: CharSequence?) {
    setTag(R.id.copy_on_long_click_source, raw)
}
