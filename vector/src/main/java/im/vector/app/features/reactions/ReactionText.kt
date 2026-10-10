/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.text.SpannableStringBuilder
import android.text.Spanned
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import javax.inject.Inject

class ReactionText @Inject constructor(private val shortcodeProcessor: EmoteShortcodeProcessor) {

    fun resolveShortcodes(roomId: String?, text: String): String {
        val protectedText = SpannableStringBuilder(text)
        imageUrls.findAll(text).forEach { match ->
            // Existing URLs must bypass shortcode matching, including colon segments in server names.
            protectedText.setSpan(
                    object : MatrixEmoteSpan {
                        override val shortcode = ""
                        override val mxcUrl = match.value
                        override val body: String? = null
                    },
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val processed = shortcodeProcessor.process(roomId, protectedText)
        val spanned = processed as? Spanned ?: return processed.toString()
        val result = SpannableStringBuilder(processed)
        val emotes = spanned.getSpans(0, spanned.length, MatrixEmoteSpan::class.java)
        emotes.sortedByDescending { spanned.getSpanStart(it) }
                .forEach { span ->
                    val end = spanned.getSpanEnd(span)
                    // A separator keeps adjacent text from becoming part of the opaque media ID.
                    val needsSeparator = end < processed.length &&
                            (processed[end].toString().matches(mediaIdCharacter) || emotes.any { spanned.getSpanStart(it) == end })
                    val suffix = if (needsSeparator) " " else ""
                    result.replace(spanned.getSpanStart(span), end, span.mxcUrl + suffix)
                }
        return result.toString()
    }

    companion object {
        private val mediaIdCharacter = Regex("[A-Za-z0-9_-]")
        private val imageUrls = Regex("mxc://[^\\s/<>\"'`]+/[A-Za-z0-9_-]+")

        fun containsImages(text: String): Boolean = imageUrls.containsMatchIn(text)

        fun singleImageUrl(text: String): String? = imageUrls.matchEntire(text)?.value

        fun renderImages(text: String, blockImages: Boolean, resolveUrl: (String) -> String?): CharSequence {
            if (!containsImages(text)) return text
            val result = SpannableStringBuilder()
            var start = 0
            imageUrls.findAll(text).forEach { match ->
                result.append(text.substring(start, match.range.first))
                val resolved = if (blockImages) null else resolveUrl(match.value)
                if (resolved == null) {
                    result.append("❓")
                } else {
                    val spanStart = result.length
                    result.append("\uFFFC")
                    result.setSpan(
                            EmoteImageSpan("", match.value, null, resolved),
                            spanStart,
                            result.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                start = match.range.last + 1
            }
            result.append(text.substring(start))
            return result
        }
    }
}
