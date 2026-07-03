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
import im.vector.app.features.emoji.CustomEmojiFontStore
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.settings.VectorPreferences
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

interface EmojiSpanify {
    fun spanify(sequence: CharSequence): CharSequence

    /** Apply emoji rendering in place to a live editable (an input field), for use from a TextWatcher so
     *  emoji typed/pasted after the initial value also render. No-op when emoji rendering is off. */
    fun applyLive(editable: android.text.Editable) {}
}

@Singleton
class EmojiCompatWrapper @Inject constructor(
        private val context: Context,
        private val twemojiProvider: TwemojiProvider,
        private val vectorPreferences: VectorPreferences,
        private val customEmojiFontStore: CustomEmojiFontStore,
) : EmojiSpanify {

    private var initialized = false

    // Emoji font as a plain Typeface, for views that draw emoji directly (picker / reactions) rather than
    // via EmojiCompat: the imported custom font if any, else the bundled one (null = system font).
    val emojiTypeface: Typeface? by lazy {
        try {
            when {
                vectorPreferences.useSystemEmojiFont() -> null
                customEmojiFontStore.isAvailable() -> Typeface.createFromFile(customEmojiFontStore.fontFile)
                else -> Typeface.createFromAsset(context.assets, FONT_ASSET)
            }
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Failed to load emoji font")
            null
        }
    }

    fun init() {
        // EmojiCompat.process() is a hard-coded no-op below API 19, so skip it (pre-KitKat emoji is handled
        // separately) to avoid loading the font for nothing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        // System-font mode: don't apply emoji2 at all, so the device's emoji font (incl. a custom one) shows.
        if (vectorPreferences.useSystemEmojiFont()) return
        val customFont = customEmojiFontStore.fontFile.takeIf { customEmojiFontStore.isAvailable() }
        val config = EmojiFontConfig(context.assets, customFont)
                // Replace all emojis with the bundled (or imported) font so rendering is consistent across devices.
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

    override fun applyLive(editable: android.text.Editable) {
        if (twemojiProvider.enabled) {
            twemojiProvider.applyTo(editable)
            return
        }
        if (initialized) {
            try {
                EmojiCompat.get().process(editable)
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Failed to process editable with EmojiCompat")
            }
        }
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

    // Like BundledEmojiCompatConfig, but builds the MetadataRepo from our own (newer) bundled font asset,
    // or from a user-imported emoji2 font file when one is set.
    private class EmojiFontConfig(assets: AssetManager, customFont: File?) :
            EmojiCompat.Config(FontMetadataLoader(assets, customFont)) {
        private class FontMetadataLoader(private val assets: AssetManager, private val customFont: File?) : EmojiCompat.MetadataRepoLoader {
            override fun load(loaderCallback: EmojiCompat.MetadataRepoLoaderCallback) {
                Thread {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    try {
                        val repo = if (customFont != null) {
                            FileInputStream(customFont).use { MetadataRepo.create(Typeface.createFromFile(customFont), it) }
                        } else {
                            MetadataRepo.create(assets, FONT_ASSET)
                        }
                        loaderCallback.onLoaded(repo)
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
