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
import android.os.Build
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
        override fun sizeOf(key: String, value: Bitmap) = value.byteCountCompat()
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
            if (codePoint in idx.starters) {
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

    companion object {
        private const val ASSET_DIR = "twemoji"
        private const val SUFFIX = ".png"
        private const val VARIATION_SELECTOR = 0xFE0F

        private fun Bitmap.byteCountCompat(): Int =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) byteCount else rowBytes * height
    }
}
