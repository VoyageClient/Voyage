/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.translation.MessageTranslationStore
import im.vector.app.features.translation.TranslationLanguages
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/** The muted markers a message body carries after its text: "(edited)" and the translation note. */
class MessageSuffixFormatter @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val dimensionConverter: DimensionConverter,
) {

    /** [body] followed by each marker that applies; [onEditedClick] makes "(edited)" tappable. */
    fun annotate(
            body: CharSequence,
            edited: Boolean,
            translation: MessageTranslationStore.Translation?,
            onEditedClick: (() -> Unit)? = null,
    ): CharSequence {
        if (!edited && translation == null) return body
        val text = SpannableStringBuilder(body)
        if (edited) appendSuffix(text, stringProvider.getString(CommonStrings.edited_suffix), onEditedClick)
        if (translation != null) appendSuffix(text, translatedSuffix(translation), null)
        return text
    }

    /** The markers alone, for a body drawn elsewhere (a table or code block); null when none apply. */
    fun footer(edited: Boolean, translation: MessageTranslationStore.Translation?, onEditedClick: (() -> Unit)? = null): CharSequence? {
        val annotated = annotate("", edited, translation, onEditedClick)
        return annotated.takeIf { it.isNotEmpty() }?.subSequence(1, annotated.length)
    }

    private fun translatedSuffix(translation: MessageTranslationStore.Translation): String {
        val sourceName = TranslationLanguages.nameOf(translation.sourceLanguage)
        return if (sourceName != null) {
            stringProvider.getString(CommonStrings.message_translated_from_suffix, sourceName)
        } else {
            stringProvider.getString(CommonStrings.message_translated_suffix)
        }
    }

    private fun appendSuffix(text: SpannableStringBuilder, suffix: String, onClick: (() -> Unit)?) {
        text.append(" ")
        val start = text.length
        text.append(suffix)
        val end = text.length
        text.setSpan(ForegroundColorSpan(colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        text.setSpan(AbsoluteSizeSpan(dimensionConverter.spToPx(SUFFIX_TEXT_SIZE_SP)), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (onClick != null) {
            text.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) = onClick()

                        override fun updateDrawState(ds: TextPaint) = Unit
                    },
                    start,
                    end,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
    }

    private companion object {
        const val SUFFIX_TEXT_SIZE_SP = 13
    }
}
