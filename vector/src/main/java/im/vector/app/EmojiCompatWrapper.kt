/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import android.os.Process
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
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

    // Bundled font as a plain Typeface, for views that draw emoji directly (picker / reactions) rather than
    // via EmojiCompat.
    val emojiTypeface: Typeface? by lazy {
        try {
            Typeface.createFromAsset(context.assets, FONT_ASSET)
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Failed to load emoji font")
            null
        }
    }

    fun init() {
        // EmojiCompat.process() is a hard-coded no-op below API 19, so skip it (pre-KitKat emoji is handled
        // separately) to avoid loading the font for nothing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        val config = AssetFontConfig(context.assets)
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

    // Like BundledEmojiCompatConfig, but builds the MetadataRepo from our own (newer) font asset.
    private class AssetFontConfig(assets: AssetManager) : EmojiCompat.Config(AssetMetadataLoader(assets)) {
        private class AssetMetadataLoader(private val assets: AssetManager) : EmojiCompat.MetadataRepoLoader {
            override fun load(loaderCallback: EmojiCompat.MetadataRepoLoaderCallback) {
                Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    try {
                        loaderCallback.onLoaded(MetadataRepo.create(assets, FONT_ASSET))
                    } catch (throwable: Throwable) {
                        loaderCallback.onFailed(throwable)
                    }
                }.apply { isDaemon = true }.start()
            }
        }
    }

    companion object {
        // Bundled by tools/import_emojis.py from androidx.emoji2:emoji2-bundled's font (newer Emoji version).
        private const val FONT_ASSET = "emoji2/NotoColorEmojiCompat.ttf"
    }
}
