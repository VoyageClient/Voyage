/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import im.vector.app.features.emoji.TwemojiProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

fun interface EmojiSpanify {
    fun spanify(sequence: CharSequence): CharSequence
}

@Singleton
class EmojiCompatWrapper @Inject constructor(
        private val context: Context,
        private val twemojiProvider: TwemojiProvider,
) : EmojiSpanify {

    private var initialized = false

    /**
     * The bundled NotoColorEmojiCompat font (from androidx.emoji2:emoji2-bundled) as a plain Typeface,
     * for views that draw emoji directly (emoji picker / reactions) rather than going through
     * EmojiCompat. Available on every API level; loaded from the same asset the bundled config uses.
     */
    val emojiTypeface: Typeface? by lazy {
        try {
            Typeface.createFromAsset(context.assets, BUNDLED_FONT_ASSET)
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Failed to load bundled emoji font")
            null
        }
    }

    fun init() {
        // EmojiCompat hard-gates to API 19 internally: below 19 it installs a no-op helper whose
        // process() returns the text unchanged, so running it on pre-KitKat does nothing. Skip it there
        // (pre-KitKat emoji rendering is handled separately) to avoid loading the bundled font for nothing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val config = BundledEmojiCompatConfig(context)
                // Replace all emojis with the bundled font so rendering is consistent across devices.
                .setReplaceAll(true)
        EmojiCompat.init(config)
                .registerInitCallback(object : EmojiCompat.InitCallback() {
                    override fun onInitialized() {
                        Timber.v("Emoji compat onInitialized success")
                        initialized = true
                    }

                    override fun onFailed(throwable: Throwable?) {
                        Timber.e(throwable, "Failed to init EmojiCompat")
                    }
                })
    }

    override fun spanify(sequence: CharSequence): CharSequence {
        if (twemojiProvider.enabled) {
            return twemojiProvider.spanify(sequence)
        }
        if (initialized) {
            try {
                return EmojiCompat.get().process(sequence) ?: sequence
            } catch (throwable: Throwable) {
                // Defensive coding against error (should not happen as it is initialized)
                Timber.e(throwable, "Failed to process with EmojiCompat")
                return sequence
            }
        } else {
            return sequence
        }
    }

    companion object {
        // Asset name shipped by androidx.emoji2:emoji2-bundled (merged into the app assets).
        private const val BUNDLED_FONT_ASSET = "NotoColorEmojiCompat.ttf"
    }
}
