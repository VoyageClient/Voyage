/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.emoji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.LruCache
import im.vector.app.features.settings.VectorPreferences
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders Unicode emoji from bundled Twemoji colour sprites (assets/twemoji/<codepoints>.png) instead
 * of relying on the platform font. This is the only way to show emoji below KitKat (no system emoji
 * font, and EmojiCompat is a no-op there); on KitKat+ it's an opt-in for a consistent colour look.
 *
 * Asset names are the emoji's codepoints, lowercase hex, '-' joined, with U+FE0F stripped — the same
 * convention the Twemoji project uses and that tools/import_twemoji.py downloads, so detection here
 * and generation there always agree.
 */
@Singleton
class TwemojiProvider @Inject constructor(
        private val context: Context,
        vectorPreferences: VectorPreferences,
) {

    val enabled: Boolean = vectorPreferences.useTwemoji()

    private class Index(val names: Set<String>, val starters: Set<Int>, val maxCodepoints: Int)

    private val index: Index by lazy { loadIndex() }

    // ~1/16 of the heap, never more than 8MB. Each 72x72 sprite is ~20KB, so this holds a few hundred.
    private val cache = object : LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(8L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun loadIndex(): Index {
        val names = try {
            context.assets.list(ASSET_DIR)
                    ?.mapNotNull { if (it.endsWith(SUFFIX)) it.dropLast(SUFFIX.length) else null }
                    ?.toHashSet()
                    .orEmpty()
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Failed to list Twemoji assets")
            emptySet<String>()
        }
        val starters = HashSet<Int>()
        var maxCodepoints = 1
        for (name in names) {
            val segments = name.split('-')
            maxCodepoints = maxOf(maxCodepoints, segments.size)
            segments.firstOrNull()?.toIntOrNull(16)?.let { starters.add(it) }
        }
        Timber.v("Twemoji index: ${names.size} sprites, maxCodepoints=$maxCodepoints")
        return Index(names, starters, maxCodepoints)
    }

    /** Build the asset index ahead of the first use so it isn't done on the main thread. */
    fun warmUp() {
        if (enabled) index
    }

    fun spanify(text: CharSequence): CharSequence {
        if (!enabled || text.isEmpty()) return text
        val idx = index
        if (idx.names.isEmpty()) return text

        var builder: SpannableStringBuilder? = null
        scan(text, idx) { start, end, bitmap ->
            val b = builder ?: SpannableStringBuilder(text).also { builder = it }
            b.setSpan(TwemojiSpan(bitmap), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder ?: text
    }

    /** Apply Twemoji spans to a live editable in place (the composer); clears stale spans first. */
    fun applyTo(spannable: Spannable) {
        if (!enabled) return
        spannable.getSpans(0, spannable.length, TwemojiSpan::class.java).forEach { spannable.removeSpan(it) }
        val idx = index
        if (idx.names.isEmpty()) return
        scan(spannable, idx) { start, end, bitmap ->
            spannable.setSpan(TwemojiSpan(bitmap), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inline fun scan(text: CharSequence, idx: Index, onMatch: (start: Int, end: Int, bitmap: Bitmap) -> Unit) {
        val length = text.length
        var i = 0
        while (i < length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint in idx.starters && hasEmojiPresentation(text, i, codePoint)) {
                val match = longestMatch(text, i, idx)
                if (match != null) {
                    getBitmap(match.name)?.let { onMatch(i, match.end, it) }
                    i = match.end
                    continue
                }
            }
            i += Character.charCount(codePoint)
        }
    }

    /**
     * Codepoints whose Unicode default is text presentation (™, ©, ®, ↔, ❤, …) only count as emoji when
     * the author asked for it with U+FE0F or a skin tone modifier; otherwise they are ordinary punctuation
     * in running text and must not become sprites.
     */
    private fun hasEmojiPresentation(text: CharSequence, start: Int, codePoint: Int): Boolean {
        if (!isTextPresentationDefault(codePoint)) return true
        val next = start + Character.charCount(codePoint)
        if (next >= text.length) return false
        val following = Character.codePointAt(text, next)
        return following == VARIATION_SELECTOR || following == ZERO_WIDTH_JOINER || following in SKIN_TONE_FIRST..SKIN_TONE_LAST
    }

    fun hasEmoji(emoji: String): Boolean = nameForEmoji(emoji) != null

    fun bitmapForEmoji(emoji: String): Bitmap? = nameForEmoji(emoji)?.let { getBitmap(it) }

    private class Match(val end: Int, val name: String)

    private fun longestMatch(text: CharSequence, start: Int, idx: Index): Match? {
        val hex = StringBuilder()
        var bestEnd = -1
        var bestName: String? = null
        var codePointCount = 0
        var j = start
        val length = text.length
        // Bound the look-ahead: matched codepoints + room for interleaved variation selectors.
        val charLimit = start + idx.maxCodepoints * 3
        while (j < length && codePointCount < idx.maxCodepoints && j < charLimit) {
            val codePoint = Character.codePointAt(text, j)
            j += Character.charCount(codePoint)
            if (codePoint == VARIATION_SELECTOR) continue
            if (hex.isNotEmpty()) hex.append('-')
            hex.append(Integer.toHexString(codePoint))
            codePointCount++
            val name = hex.toString()
            if (name in idx.names) {
                bestEnd = j
                bestName = name
            }
        }
        if (bestName == null) return null
        // Absorb a trailing variation selector into the span so it isn't left dangling.
        var end = bestEnd
        while (end < length && text[end].code == VARIATION_SELECTOR) end++
        return Match(end, bestName)
    }

    private fun nameForEmoji(emoji: String): String? {
        if (emoji.isEmpty()) return null
        val hex = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val codePoint = Character.codePointAt(emoji, i)
            i += Character.charCount(codePoint)
            if (codePoint == VARIATION_SELECTOR) continue
            if (hex.isNotEmpty()) hex.append('-')
            hex.append(Integer.toHexString(codePoint))
        }
        val name = hex.toString()
        return if (name in index.names) name else null
    }

    private fun getBitmap(name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val bitmap = try {
            context.assets.open("$ASSET_DIR/$name$SUFFIX").use { BitmapFactory.decodeStream(it) }
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Failed to decode Twemoji $name")
            null
        }
        if (bitmap != null) cache.put(name, bitmap)
        return bitmap
    }

    private fun isTextPresentationDefault(codePoint: Int): Boolean {
        var low = 0
        var high = TEXT_PRESENTATION_RANGES.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codePoint < TEXT_PRESENTATION_RANGES[mid * 2] -> high = mid - 1
                codePoint > TEXT_PRESENTATION_RANGES[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        private const val ASSET_DIR = "twemoji"
        private const val SUFFIX = ".png"
        private const val VARIATION_SELECTOR = 0xFE0F
        private const val ZERO_WIDTH_JOINER = 0x200D
        private const val SKIN_TONE_FIRST = 0x1F3FB
        private const val SKIN_TONE_LAST = 0x1F3FF

        // Sorted inclusive [start, end] pairs: Emoji=Yes, Emoji_Presentation=No in Unicode's emoji-data.txt,
        // limited to the codepoints we ship a sprite for.
        private val TEXT_PRESENTATION_RANGES = intArrayOf(
                0x00A9, 0x00A9, 0x00AE, 0x00AE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
                0x2194, 0x2199, 0x21A9, 0x21AA, 0x2328, 0x2328, 0x23CF, 0x23CF, 0x23ED, 0x23EF, 0x23F1, 0x23F2,
                0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FC,
                0x2600, 0x2604, 0x260E, 0x260E, 0x2611, 0x2611, 0x2618, 0x2618, 0x261D, 0x261D, 0x2620, 0x2620,
                0x2622, 0x2623, 0x2626, 0x2626, 0x262A, 0x262A, 0x262E, 0x262F, 0x2638, 0x263A, 0x2640, 0x2640,
                0x2642, 0x2642, 0x265F, 0x2660, 0x2663, 0x2663, 0x2665, 0x2666, 0x2668, 0x2668, 0x267B, 0x267B,
                0x267E, 0x267E, 0x2692, 0x2692, 0x2694, 0x2697, 0x2699, 0x2699, 0x269B, 0x269C, 0x26A0, 0x26A0,
                0x26A7, 0x26A7, 0x26B0, 0x26B1, 0x26C8, 0x26C8, 0x26CF, 0x26CF, 0x26D1, 0x26D1, 0x26D3, 0x26D3,
                0x26E9, 0x26E9, 0x26F0, 0x26F1, 0x26F4, 0x26F4, 0x26F7, 0x26F9, 0x2702, 0x2702, 0x2708, 0x2709,
                0x270C, 0x270D, 0x270F, 0x270F, 0x2712, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D,
                0x2721, 0x2721, 0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x2763, 0x2764, 0x27A1, 0x27A1,
                0x2934, 0x2935, 0x2B05, 0x2B07, 0x3030, 0x3030, 0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299,
                0x1F170, 0x1F171, 0x1F17E, 0x1F17F, 0x1F202, 0x1F202, 0x1F237, 0x1F237, 0x1F321, 0x1F321,
                0x1F324, 0x1F32C, 0x1F336, 0x1F336, 0x1F37D, 0x1F37D, 0x1F396, 0x1F397, 0x1F399, 0x1F39B,
                0x1F39E, 0x1F39F, 0x1F3CB, 0x1F3CE, 0x1F3D4, 0x1F3DF, 0x1F3F3, 0x1F3F3, 0x1F3F5, 0x1F3F5,
                0x1F3F7, 0x1F3F7, 0x1F43F, 0x1F43F, 0x1F441, 0x1F441, 0x1F4FD, 0x1F4FD, 0x1F549, 0x1F54A,
                0x1F56F, 0x1F570, 0x1F573, 0x1F579, 0x1F587, 0x1F587, 0x1F58A, 0x1F58D, 0x1F590, 0x1F590,
                0x1F5A5, 0x1F5A5, 0x1F5A8, 0x1F5A8, 0x1F5B1, 0x1F5B2, 0x1F5BC, 0x1F5BC, 0x1F5C2, 0x1F5C4,
                0x1F5D1, 0x1F5D3, 0x1F5DC, 0x1F5DE, 0x1F5E1, 0x1F5E1, 0x1F5E3, 0x1F5E3, 0x1F5E8, 0x1F5E8,
                0x1F5EF, 0x1F5EF, 0x1F5F3, 0x1F5F3, 0x1F5FA, 0x1F5FA, 0x1F6CB, 0x1F6CB, 0x1F6CD, 0x1F6CF,
                0x1F6E0, 0x1F6E5, 0x1F6E9, 0x1F6E9, 0x1F6F0, 0x1F6F0, 0x1F6F3, 0x1F6F3,
        )
    }
}
