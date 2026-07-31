/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import android.text.Spannable
import android.text.SpannableStringBuilder
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import javax.inject.Inject

/**
 * Non-visual span tagging a stretch of text as an MSC2545 custom emote so the SDK serializes it to an
 * `<img data-mx-emoticon>` on send. Used for literal `:shortcode:` text the user typed without picking
 * from the autocomplete popup.
 */
private class SendableEmoteSpan(
        override val shortcode: String,
        override val mxcUrl: String,
        override val body: String?,
) : MatrixEmoteSpan

/**
 * Replaces literal `:shortcode:` occurrences in composer text with emote spans, so that typed emotes are
 * sent as custom emoticons. Already-spanned emotes and shortcodes inside inline code are left untouched.
 */
class EmoteShortcodeProcessor @Inject constructor(
        private val imagePackProvider: ImagePackProvider,
) {

    // Base shortcode grammar plus the optional `/<pack-slug>`, `@<room/personal>` disambiguation suffixes.
    private val shortcodeRegex = Regex(":([A-Za-z0-9_-]{1,100}(?:[/@][A-Za-z0-9_-]+)*):")

    // The base shortcode is everything before the first disambiguation delimiter ('/' or '@').
    private fun baseOf(shortcode: String) = shortcode.takeWhile { it != '/' && it != '@' }

    fun process(roomId: String, text: CharSequence): CharSequence {
        // Runs inline on the composer's single-slot send-preparation lane, so it must never do the
        // synchronous space-hierarchy DB walk of getEmoticons() — under DB contention (e.g. a /join
        // bringing a large room via sync) that read stalls for seconds and, holding the only lane
        // slot, blocks every following send. Use the in-memory cache the open room's live flow keeps
        // warm; only a never-opened room (cold cache) falls back to the synchronous read.
        val emoticons = imagePackProvider.cachedEmoticons(roomId).ifEmpty { imagePackProvider.getEmoticons(roomId) }
        if (emoticons.isEmpty()) return text
        // Resolve the typed token by its exact (possibly disambiguated) shortcode first; otherwise by its
        // base, which covers a bare `:foo:` that now only exists disambiguated (→ best candidate) AND a stale
        // `:foo/old-pack:` whose duplicate has since gone (→ the now-unique `foo`).
        val byShortcode = emoticons.associateBy { it.shortcode }
        val byBase = emoticons.groupBy { baseOf(it.shortcode) }
        fun resolve(typed: String) = byShortcode[typed] ?: byBase[baseOf(typed)]?.firstOrNull()

        val matches = shortcodeRegex.findAll(text)
                .filter { resolve(it.groupValues[1]) != null }
                .filterNot { isInsideInlineCode(text, it.range.first) }
                .toList()
        if (matches.isEmpty()) return text

        val spannable = SpannableStringBuilder(text)
        matches.forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            // Don't double-tag a range that already carries an emote span (e.g. from autocomplete).
            if (spannable.getSpans(start, end, MatrixEmoteSpan::class.java).isNotEmpty()) return@forEach
            val image = resolve(match.groupValues[1]) ?: return@forEach
            spannable.setSpan(
                    // Serialize with the resolved emote's current shortcode: disambiguate() already made it
                    // plain when the shortcode is unique and `name/pack-slug` only when it's a duplicate, so
                    // the wire shortcode keeps the suffix exactly when it's needed to identify the emote.
                    SendableEmoteSpan(image.shortcode, image.mxcUrl, image.body),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun isInsideInlineCode(text: CharSequence, index: Int): Boolean {
        var backticks = 0
        for (i in 0 until index) {
            if (text[i] == '`') backticks++
        }
        return backticks % 2 == 1
    }
}
