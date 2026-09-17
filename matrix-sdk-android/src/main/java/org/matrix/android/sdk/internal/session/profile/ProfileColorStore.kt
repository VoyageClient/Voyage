/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.internal.di.FilesDirectory
import org.matrix.android.sdk.internal.di.MatrixScope
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject

/** A bounded cache of explicit user colors, shared across accounts because colors belong to users. */
@MatrixScope
internal class ProfileColorStore @Inject constructor(
        @FilesDirectory private val filesDirectory: File,
) {

    private val file by lazy { File(filesDirectory, FILE_NAME) }

    // Access-ordered so eviction drops whoever was read longest ago, not whoever was stored first.
    private val entries = object : LinkedHashMap<String, ColorPreference>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ColorPreference>) = size > MAX_ENTRIES
    }

    private var loaded = false

    /** Preload off the main thread at session start; an earlier read still loads synchronously. */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        tryOrNull {
            file.takeIf { it.exists() }
                    ?.inputStream()
                    ?.use { input -> InflaterInputStream(input).bufferedReader().use { it.readLines() } }
                    ?.forEach { line ->
                val parts = line.split('\t')
                val color = when (parts.size) {
                    // One value means it is the same on both themes, which is what a single-color pick
                    // stores and by far the common case.
                    2 -> hex(parts[1])?.let { ColorPreference(onLight = it, onDark = it) }
                    3 -> ColorPreference(onLight = hex(parts[1]), onDark = hex(parts[2]))
                    else -> null
                }
                if (color?.isEmpty() == false) entries[parts[0]] = color
            }
        }
    }

    @Synchronized
    fun get(userId: String): ColorPreference? {
        ensureLoaded()
        return entries[userId]
    }

    @Synchronized
    fun put(userId: String, color: ColorPreference?) {
        ensureLoaded()
        val kept = color?.takeIf { !it.isEmpty() }
        if (kept == null) {
            if (entries.remove(userId) != null) persist()
            return
        }
        // Rewriting the whole file suits how rarely this changes: only a user who actually set a color,
        // and only when that color is new to us.
        if (entries.put(userId, kept) != kept) persist()
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        entries.clear()
        tryOrNull { file.delete() }
    }

    private fun hex(raw: String) = ColorPreference.normalizeHex(if (raw.startsWith("#")) raw else "#$raw")

    private fun persist() {
        tryOrNull {
            filesDirectory.mkdirs()
            val text = entries.entries.joinToString("\n") { (userId, color) ->
                // The "#" is implied, and the second value only written when the themes differ.
                val light = color.onLight?.removePrefix("#").orEmpty()
                val dark = color.onDark?.removePrefix("#").orEmpty()
                if (light == dark) "$userId\t$light" else "$userId\t$light\t$dark"
            }
            // Repeated server names compress well. Explicitly free the caller-owned deflater
            // because closing its stream does not release its native buffers.
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                file.outputStream().use { out ->
                    DeflaterOutputStream(out, deflater).use { it.write(text.toByteArray()) }
                }
            } finally {
                deflater.end()
            }
        }
    }

    private companion object {
        private const val FILE_NAME = "profile_colors.z"
        private const val MAX_ENTRIES = 1024
    }
}
