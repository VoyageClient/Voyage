/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.emoji

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.emoji2.text.MetadataRepo
import im.vector.app.features.settings.VectorPreferences
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores an optional user-imported emoji2 font that replaces the bundled NotoColorEmojiCompat for
 * EmojiCompat. Only fonts EmojiCompat can actually use (i.e. carrying a parseable emoji 'meta' table)
 * are accepted; plain colour-emoji TTFs are rejected.
 */
@Singleton
class CustomEmojiFontStore @Inject constructor(
        private val context: Context,
        private val vectorPreferences: VectorPreferences,
) {

    val fontFile: File get() = File(context.filesDir, CUSTOM_EMOJI_FONT_FILE)

    fun isAvailable(): Boolean = vectorPreferences.customEmojiFontName() != null && fontFile.exists()

    fun displayName(): String? = vectorPreferences.customEmojiFontName()

    /**
     * Validate [uri] is an EmojiCompat-compatible font (MetadataRepo can parse its emoji 'meta'
     * table), copy it into place and remember its name. Returns the display name on success.
     */
    fun import(uri: Uri): Result<String> = runCatching {
        val name = queryDisplayName(uri)
        val tmp = File(context.cacheDir, "emoji_font_import.ttf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: error("Cannot open the selected file")
        try {
            // Throws if the font isn't a valid emoji2 font (missing/invalid emoji metadata).
            FileInputStream(tmp).use { MetadataRepo.create(Typeface.createFromFile(tmp), it) }
            tmp.copyTo(fontFile, overwrite = true)
            vectorPreferences.setCustomEmojiFontName(name)
            name
        } finally {
            tmp.delete()
        }
    }.onFailure { Timber.w(it, "Failed to import custom emoji font") }

    fun reset() {
        fontFile.delete()
        vectorPreferences.setCustomEmojiFontName(null)
    }

    private fun queryDisplayName(uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }
                        ?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        ?.takeIf { it >= 0 }
                        ?.let { cursor.getString(it) }
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: "custom.ttf"
    }

    companion object {
        private const val CUSTOM_EMOJI_FONT_FILE = "custom_emoji.ttf"
    }
}
