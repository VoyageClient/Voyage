/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

/**
 * True if [text] is non-blank and consists solely of emoji and MSC2545 custom emotes (plus the
 * joiners/modifiers/whitespace that make up emoji sequences), with at most [maxUnits] visual glyphs.
 * Custom emotes are supplied as [emoteRanges] — half-open `start until end` index ranges of their image
 * spans — since this core util can't reference the app's span types. Used to render emoji/emote-only (and
 * mixed) messages larger. Code-point heuristic over the emoji Unicode blocks — no external library, works
 * down to API 19.
 */
fun containsOnlyEmojisAndEmotes(text: CharSequence, emoteRanges: List<IntRange>, maxUnits: Int): Boolean {
    if (text.isEmpty()) return false
    var units = 0
    var sawContent = false
    var i = 0
    while (i < text.length) {
        val emoteEnd = emoteRanges.firstOrNull { i in it }?.let { it.last + 1 }
        if (emoteEnd != null) {
            units++
            sawContent = true
            if (units > maxUnits) return false
            i = emoteEnd
            continue
        }
        val cp = Character.codePointAt(text, i)
        i += Character.charCount(cp)
        when {
            Character.isWhitespace(cp) -> Unit
            isEmojiRelatedCodePoint(cp) -> {
                sawContent = true
                // Modifiers/joiners attach to the preceding base, so they don't add a glyph of their own.
                if (!isEmojiComponentCodePoint(cp)) {
                    units++
                    if (units > maxUnits) return false
                }
            }
            else -> return false
        }
    }
    return sawContent && units in 1..maxUnits
}

private fun isEmojiComponentCodePoint(cp: Int): Boolean {
    return cp == 0x200D ||                    // zero-width joiner
            cp == 0xFE0F || cp == 0xFE0E ||   // variation selectors
            cp == 0x20E3 ||                   // combining enclosing keycap
            cp in 0x1F3FB..0x1F3FF ||         // skin-tone modifiers
            cp in 0xE0020..0xE007F            // tag characters
}

private fun isEmojiRelatedCodePoint(cp: Int): Boolean {
    return cp == 0x200D ||                      // zero-width joiner
            cp == 0xFE0F || cp == 0xFE0E ||     // variation selectors
            cp == 0x20E3 ||                     // combining enclosing keycap
            cp in 0x1F3FB..0x1F3FF ||           // skin-tone modifiers
            cp in 0x1F1E6..0x1F1FF ||           // regional indicators (flags)
            cp == 0x00A9 || cp == 0x00AE ||     // © ®
            cp == 0x203C || cp == 0x2049 ||     // ‼ ⁉
            cp in 0x2100..0x214F ||             // letterlike (™ ℹ …)
            cp in 0x2190..0x21FF ||             // arrows
            cp in 0x2300..0x23FF ||             // technical (⌚ ⏰ …)
            cp in 0x2460..0x24FF ||             // enclosed alphanumerics (Ⓜ …)
            cp in 0x25A0..0x27BF ||             // shapes, misc symbols, dingbats
            cp in 0x2900..0x297F ||             // supplemental arrows-B
            cp in 0x2B00..0x2BFF ||             // misc symbols & arrows (★ …)
            cp in 0x3000..0x303F ||             // CJK symbols (〰 〽)
            cp == 0x3297 || cp == 0x3299 ||     // ㊗ ㊙
            cp in 0x1F000..0x1FAFF ||           // pictographic emoji blocks
            cp in 0xE0020..0xE007F              // tag characters
}

/**
 * Same as split, but considering emojis.
 */
fun CharSequence.splitEmoji(): List<CharSequence> {
    val result = mutableListOf<CharSequence>()

    var index = 0

    while (index < length) {
        val firstChar = get(index)

        if (firstChar.code == 0x200e) {
            // Left to right mark. What should I do with it?
        } else if (firstChar.code in 0xD800..0xDBFF && index + 1 < length) {
            // We have the start of a surrogate pair
            val secondChar = get(index + 1)

            if (secondChar.code in 0xDC00..0xDFFF) {
                // We have an emoji
                result.add("$firstChar$secondChar")
                index++
            } else {
                // Not sure what we have here...
                result.add("$firstChar")
            }
        } else {
            // Regular char
            result.add("$firstChar")
        }

        index++
    }

    return result
}
