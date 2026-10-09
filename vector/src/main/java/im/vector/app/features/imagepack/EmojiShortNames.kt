/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import android.content.res.Resources
import com.squareup.moshi.Moshi
import im.vector.app.R
import im.vector.app.features.reactions.data.EmojiData
import im.vector.app.features.reactions.data.EmojiItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emoji ↔ names: the `:shortcode:`s that resolve to each emoji, and the id-style names (😀 → grinning_face)
 * packs are named after and searched with. Reads the bundled emoji data directly rather than through
 * EmojiDataSource, which drops emoji the device can't render and would give different names per device.
 */
@Singleton
class EmojiShortNames @Inject constructor(private val resources: Resources) {

    private val emojis: Map<String, EmojiItem> by lazy {
        val data = resources.openRawResource(R.raw.emoji_picker_datasource).use { input ->
            Moshi.Builder().build().adapter(EmojiData::class.java).fromJson(input.bufferedReader().readText())
        }
        data?.emojis.orEmpty()
    }

    private val namesByCodepoints: Map<String, String> by lazy {
        emojis.entries.associate { (key, item) -> normalizeHex(item.unicode) to key.replace('-', '_') }
    }

    private val emojisByCodepoints: Map<String, EmojiItem> by lazy {
        emojis.values.associateBy { normalizeHex(it.unicode) }
    }

    private val emojisByShortcode: Map<String, EmojiItem> by lazy {
        emojis.values.flatMap { item -> item.shortcodes.map { it to item } }.toMap()
    }

    private val longestSequence: Int by lazy { namesByCodepoints.keys.maxOfOrNull { it.count { c -> c == '-' } + 1 } ?: 1 }

    /** Parses the emoji data on first use; call this off the main thread before searching or resolving. */
    fun prime() {
        namesByCodepoints.size
        emojisByCodepoints.size
        emojisByShortcode.size
    }

    fun nameFor(emoji: String?): String? = emoji?.takeIf { it.isNotEmpty() }?.let { namesByCodepoints[codepointKey(it)] }

    /** The emoji a typed `:shortcode:` (without the colons) stands for, also in its `name@emoji` form. */
    fun emojiFor(shortcode: String): String? = emojisByShortcode[shortcode.removeSuffix(UNICODE_SUFFIX)]?.emoji

    fun isEmojiShortcode(shortcode: String): Boolean = shortcode in emojisByShortcode

    /** [emoji] as the bundled data spells it, so variation-selector variants of one emoji compare equal. */
    fun canonical(emoji: String): String = emojisByCodepoints[codepointKey(emoji)]?.emoji ?: emoji

    /** The emoji in [text], each once, in the order they first appear. */
    fun emojisIn(text: String): List<String> {
        if (text.all { it.code < 0x80 }) return emptyList()
        return emojiKeysIn(text).distinct().mapNotNull { emojisByCodepoints[it]?.emoji }
    }

    /**
     * What to match image shortcodes/bodies against for [query]: the query itself, plus the name of every
     * emoji in it, so typing 😠 finds `angry_face`.
     */
    fun searchTerms(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.all { it.code < 0x80 }) return listOf(trimmed)
        return listOf(trimmed) + emojiKeysIn(trimmed).mapNotNull { namesByCodepoints[it] }
    }

    /** Names of the emoji in [text] (an image's shortcode or body), so `angry` finds an image whose body is 😠. */
    fun namesIn(text: String?): List<String> {
        if (text.isNullOrEmpty() || text.all { it.code < 0x80 }) return emptyList()
        return emojiKeysIn(text).mapNotNull { namesByCodepoints[it] }
    }

    // Longest match first at each position, so ❤️‍🔥 is heart_on_fire rather than red_heart + fire.
    private fun emojiKeysIn(text: String): List<String> {
        val codepoints = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAt(index)
            codepoints += codepoint
            index += Character.charCount(codepoint)
        }
        val keys = mutableListOf<String>()
        var start = 0
        while (start < codepoints.size) {
            var matched = 0
            for (length in minOf(longestSequence, codepoints.size - start) downTo 1) {
                val key = keyOf(codepoints.subList(start, start + length))
                if (key !in emojisByCodepoints) continue
                keys += key
                matched = length
                break
            }
            start += if (matched > 0) matched else 1
        }
        return keys
    }

    companion object {
        private const val VARIATION_SELECTOR = 0xFE0F

        /**
         * Unicode emoji take part in shortcode disambiguation as if they were one more image pack, ranked
         * last: an emote sharing an emoji's shortcode is suffixed like any duplicate, and the emoji becomes
         * `name@emoji`. Like `@personal`, it can't collide with a room pack's `name/slug` forms.
         */
        const val UNICODE_SUFFIX = "@emoji"

        // Sources disagree on whether U+FE0F is present, so it never takes part in matching.
        fun codepointKey(emoji: String): String {
            val codepoints = mutableListOf<Int>()
            var index = 0
            while (index < emoji.length) {
                val codepoint = emoji.codePointAt(index)
                codepoints += codepoint
                index += Character.charCount(codepoint)
            }
            return keyOf(codepoints)
        }

        private fun keyOf(codepoints: List<Int>): String =
                codepoints.filter { it != VARIATION_SELECTOR }.joinToString("-") { Integer.toHexString(it).uppercase() }

        fun normalizeHex(unicode: String): String = unicode.uppercase().split('-').filter { it != "FE0F" }.joinToString("-")

        /** Whether [text] contains [term] the way pack search compares names: case- and -/_-insensitive. */
        fun nameContains(text: String?, term: String): Boolean {
            if (text == null || term.isEmpty()) return false
            return text.replace('-', '_').contains(term.replace('-', '_'), ignoreCase = true)
        }
    }
}
