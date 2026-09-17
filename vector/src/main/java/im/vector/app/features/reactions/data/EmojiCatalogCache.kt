/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.emoji.TwemojiProvider
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A category as a picker draws it: its name, the glyph on its tab, and its emoji in order. */
data class EmojiCatalogCategory(val name: String, val tabGlyph: String?, val glyphs: List<String>)

/**
 * The emoji categories as the pickers show them, kept on disk between launches.
 *
 * Drawing a picker needs nothing but these strings, while producing them from the bundled resource
 * costs ~0.9s to parse it and ~0.7s to check every emoji against the sprite set (or the system font)
 * — once per process, which is what made the first open of each launch slow. The full [EmojiData],
 * with the names and keywords only search needs, stays lazy behind it.
 *
 * Not under `cacheDir`: nothing here is account data (the categories ship with the app, and what is
 * renderable depends on the device), so it should survive signing out and clearing the cache. The key
 * covers what its content depends on, so a new app version or a Twemoji switch rebuilds it.
 */
@Singleton
class EmojiCatalogCache @Inject constructor(
        @ApplicationContext private val context: Context,
        private val twemojiProvider: TwemojiProvider,
        private val buildMeta: BuildMeta,
) {

    private val file by lazy { File(context.filesDir, FILE_NAME) }

    private val key: String get() = "$FORMAT_VERSION|twemoji=${twemojiProvider.enabled}|app=${buildMeta.versionName}"

    fun read(): List<EmojiCatalogCategory>? {
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.firstOrNull() != key) return null
            lines.drop(1).mapNotNull { line ->
                val parts = line.split(FIELD_SEPARATOR)
                if (parts.size < 3) return@mapNotNull null
                val glyphs = parts[2].split(GLYPH_SEPARATOR).filter { it.isNotEmpty() }
                if (glyphs.isEmpty()) null else EmojiCatalogCategory(parts[0], parts[1].takeIf { it.isNotEmpty() }, glyphs)
            }.takeIf { it.isNotEmpty() }
        }.onFailure { Timber.w(it, "Unreadable emoji catalog, rebuilding it") }.getOrNull()
    }

    fun write(categories: List<EmojiCatalogCategory>) {
        if (categories.isEmpty()) return
        runCatching {
            file.writeText(
                    buildString {
                        append(key)
                        categories.forEach { category ->
                            append('\n')
                            append(category.name).append(FIELD_SEPARATOR)
                            append(category.tabGlyph.orEmpty()).append(FIELD_SEPARATOR)
                            append(category.glyphs.joinToString(GLYPH_SEPARATOR))
                        }
                    }
            )
        }.onFailure { Timber.w(it, "Could not store the emoji catalog") }
    }

    companion object {
        private const val FILE_NAME = "emoji-catalog.txt"
        private const val FORMAT_VERSION = "v1"

        // Neither occurs in an emoji or a category name, so no escaping is needed.
        private const val FIELD_SEPARATOR = "\t"
        private const val GLYPH_SEPARATOR = " "
    }
}
