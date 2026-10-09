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
 * Replaces literal `:shortcode:` occurrences in typed text with emote spans, so that typed emotes are
 * sent as custom emoticons, or else with the unicode emoji of that name (`:grinning:` → 😀).
 * Already-spanned emotes and shortcodes inside inline code are left untouched.
 * A null [roomId] resolves against the packs that apply everywhere (personal and globally-enabled ones),
 * which is what text outside a room — a profile biography — can use.
 */
class EmoteShortcodeProcessor @Inject constructor(
        private val imagePackProvider: ImagePackProvider,
        private val emojiShortNames: EmojiShortNames,
) {

    // Base shortcode grammar (plus '+', for :+1:) and the optional `/<pack-slug>`, `@<room/personal/emoji>` suffixes.
    private val shortcodeRegex = Regex(":([A-Za-z0-9_+-]{1,100}(?:[/@][A-Za-z0-9_-]+)*):")

    fun process(roomId: String?, text: CharSequence, cachedOnly: Boolean = false): CharSequence {
        if (!shortcodeRegex.containsMatchIn(text)) return text
        // The composer calls this inline on its single-slot send-preparation lane, so it must never do the
        // synchronous space-hierarchy DB walk of getEmoticons() — under DB contention (e.g. a /join
        // bringing a large room via sync) that read stalls for seconds and, holding the only lane
        // slot, blocks every following send. Use the in-memory cache the open room's live flow keeps
        // warm; only a cold cache falls back to the synchronous read.
        val cached = imagePackProvider.cachedEmoticons(roomId)
        val emoticons = if (cachedOnly) cached else cached.ifEmpty { imagePackProvider.getEmoticons(roomId) }
        // Resolve the typed token by its exact (possibly disambiguated) shortcode first; otherwise by its
        // base, which covers a bare `:foo:` that now only exists disambiguated (→ best candidate) AND a stale
        // `:foo/old-pack:` whose duplicate has since gone (→ the now-unique `foo`).
        val byShortcode = emoticons.associateBy { it.shortcode }
        val byBase = emoticons.groupBy { baseOf(it.shortcode) }

        // Unicode emoji rank below every pack, so they only win a bare `:foo:` that no emote claims.
        fun resolve(typed: String): ResolvedImage? {
            if (typed.endsWith(EmojiShortNames.UNICODE_SUFFIX) && emojiShortNames.emojiFor(typed) != null) return null
            return byShortcode[typed] ?: byBase[baseOf(typed)]?.firstOrNull()
        }

        val matches = shortcodeRegex.findAll(text)
                .filter { resolve(it.groupValues[1]) != null || emojiShortNames.emojiFor(it.groupValues[1]) != null }
                .filterNot { isInsideInlineCode(text, it.range.first) }
                .toList()
        if (matches.isEmpty()) return text

        val spannable = SpannableStringBuilder(text)
        val emojiMatches = mutableListOf<Pair<MatchResult, String>>()
        matches.forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            // Don't double-tag a range that already carries an emote span (e.g. from autocomplete).
            if (spannable.getSpans(start, end, MatrixEmoteSpan::class.java).isNotEmpty()) return@forEach
            val image = resolve(match.groupValues[1])
            if (image == null) {
                emojiShortNames.emojiFor(match.groupValues[1])?.let { emojiMatches += match to it }
                return@forEach
            }
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
        // Last to first, so swapping a shortcode for its shorter emoji leaves the earlier ranges valid.
        emojiMatches.asReversed().forEach { (match, emoji) -> spannable.replace(match.range.first, match.range.last + 1, emoji) }
        return spannable
    }

    companion object {
        // The base shortcode is everything before the first disambiguation delimiter ('/' or '@').
        fun baseOf(shortcode: String) = shortcode.takeWhile { it != '/' && it != '@' }
    }

    private fun isInsideInlineCode(text: CharSequence, index: Int): Boolean {
        var backticks = 0
        for (i in 0 until index) {
            if (text[i] == '`') backticks++
        }
        return backticks % 2 == 1
    }
}
