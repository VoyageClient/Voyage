/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.net.Uri
import android.util.LruCache
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Peaks already read off a file, keyed by where they came from. Extracting them decodes the whole
 * file, so the preview page, the editor, the timeline and the send all work from whichever pass
 * happened first — and from a copy on disk on the next run, rather than reading it all again.
 */
object WaveformCache {

    /** Peaks cost about 2.5 KB a minute, so a handful of files is nothing to hold. */
    private val cache = LruCache<String, FloatArray>(8)

    fun get(source: Uri): FloatArray? = cache.get(source.toString())

    /** The kept copy, from memory or from the last run — under this name or these bytes. */
    fun get(context: Context, source: Uri): FloatArray? {
        val key = source.toString()
        cache.get(key)?.let { return it }
        readFromDisk(context, key)?.let {
            cache.put(key, it)
            return it
        }
        val fingerprint = AudioDetails.fingerprintOf(context, source) ?: return null
        cache.get(fingerprint)?.let {
            cache.put(key, it)
            return it
        }
        return readFromDisk(context, fingerprint)?.also { cache.put(key, it) }
    }

    fun put(context: Context, source: Uri, levels: FloatArray) {
        if (levels.isEmpty()) return
        cache.put(source.toString(), levels)
        writeToDisk(context, source.toString(), levels)
        // Kept against the bytes too, so the message this file becomes finds it without reading
        // the whole thing again.
        AudioDetails.fingerprintOf(context, source)?.let {
            cache.put(it, levels)
            writeToDisk(context, it, levels)
        }
    }

    private fun readFromDisk(context: Context, key: String): FloatArray? = runCatching {
        val file = fileFor(context, key).takeIf { it.isFile } ?: return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val count = input.readInt()
            if (count <= 0 || count > MAX_SLICES) return null
            FloatArray(count) { input.readFloat() }
        }
    }.onFailure { Timber.w(it, "Waveform: cannot read the kept peaks for $key") }.getOrNull()

    private fun writeToDisk(context: Context, key: String, levels: FloatArray) {
        runCatching {
            val file = fileFor(context, key)
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { output ->
                output.writeInt(levels.size)
                levels.forEach { output.writeFloat(it) }
            }
        }.onFailure { Timber.w(it, "Waveform: cannot keep the peaks for $key") }
    }

    private fun fileFor(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "waveforms"), "$name.peaks")
    }

    /**
     * The peaks as a voice message carries them: MSC1767 wants 30 to 120 values from 0 to 1024,
     * so this is what the SDK's own sanitiser would have kept anyway.
     */
    fun asMessageWaveform(levels: FloatArray): List<Int>? {
        if (levels.isEmpty()) return null
        val buckets = levels.size.coerceAtMost(MESSAGE_WAVEFORM_VALUES)
        return List(buckets) { bucket ->
            val from = bucket * levels.size / buckets
            val to = ((bucket + 1) * levels.size / buckets).coerceAtLeast(from + 1)
            var peak = 0f
            for (index in from until to.coerceAtMost(levels.size)) peak = maxOf(peak, levels[index])
            (peak * MESSAGE_WAVEFORM_MAX).toInt().coerceIn(0, MESSAGE_WAVEFORM_MAX)
        }
    }

    /** Twelve hours of sound at 20ms a slice; anything larger is a broken file, not a long one. */
    private const val MAX_SLICES = 2_160_000

    private const val MESSAGE_WAVEFORM_VALUES = 100
    private const val MESSAGE_WAVEFORM_MAX = 1024
}
