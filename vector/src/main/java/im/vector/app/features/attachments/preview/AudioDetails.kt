/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.drawable.RoundedBitmapDrawable
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.vanniktech.blurhash.BlurHash
import im.vector.app.core.extensions.useCompat
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.session.content.AudioCoverArt
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/** Tags, embedded cover art, and a backdrop for the preview or timeline. */
object AudioDetails {

    /** Rounded as a space's avatar is: a fraction of the shorter side, so any cover looks alike. */
    fun roundedArt(context: Context, art: Bitmap): RoundedBitmapDrawable =
            RoundedBitmapDrawableFactory.create(context.resources, art).apply {
                cornerRadius = minOf(art.width, art.height) * AvatarRenderer.ROUNDED_CORNER_PERCENT
            }

    class Details(
            val title: String?,
            val artist: String?,
            val album: String?,
            val art: Bitmap?,
            val backdrop: Bitmap?,
    ) {
        val isEmpty get() = title == null && artist == null && album == null && art == null

        /**
         * Who made it and what it is from, as a player shows them: "Artist / Album". A single by
         * its own name says nothing twice.
         */
        val credits: String?
            get() = listOfNotNull(artist, album?.takeIf { !it.equals(artist, ignoreCase = true) })
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" / ")
    }

    /** Enough for a screenful of messages and then some, so scrolling back shows art at once. */
    private val cache = LruCache<String, Details>(32)

    fun cached(source: Uri, forTimeline: Boolean = false): Details? = cache.get(cacheKey(source.toString(), forTimeline))

    private fun cacheKey(source: String, forTimeline: Boolean) = if (forTimeline) "timeline:$source" else source

    fun fingerprintOf(context: Context, source: Uri): String? = runCatching {
        val length = lengthOf(context, source)
        val window = ByteArray(FINGERPRINT_WINDOW)
        var read = 0
        context.contentResolver.openInputStream(source)?.use { input ->
            while (read < FINGERPRINT_WINDOW) {
                val count = input.read(window, read, FINGERPRINT_WINDOW - read)
                if (count <= 0) break
                read += count
            }
        } ?: return null
        if (read <= 0) return null
        val digest = MessageDigest.getInstance("SHA-1").apply { update(window, 0, read) }
        "$length-" + digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun lengthOf(context: Context, source: Uri): Long {
        source.path?.takeIf { source.scheme == "file" }?.let { return File(it).length() }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(source, "r")?.useCompat { it.length }
        }.getOrNull()?.takeIf { it >= 0 } ?: UNKNOWN_LENGTH
    }

    fun load(context: Context, source: Uri, forTimeline: Boolean = false): Details {
        val key = cacheKey(source.toString(), forTimeline)
        cache.get(key)?.let { return it }
        readFromDisk(context, key)?.let {
            cache.put(key, it)
            return it
        }
        val fingerprint = fingerprintOf(context, source)?.let { cacheKey(it, forTimeline) }
        fingerprint?.let { print ->
            cache.get(print)?.let { keep(key, print, it); return it }
            readFromDisk(context, print)?.let { keep(key, print, it); return it }
        }
        val details = read(context, source, forTimeline)
        keep(key, fingerprint, details)
        writeToDisk(context, key, details)
        fingerprint?.let { writeToDisk(context, it, details) }
        return details
    }

    private fun keep(key: String, fingerprint: String?, details: Details) {
        cache.put(key, details)
        fingerprint?.let { cache.put(it, details) }
    }

    private fun readFromDisk(context: Context, key: String): Details? = runCatching {
        val directory = directoryFor(context, key).takeIf { it.isDirectory } ?: return null
        val tags = File(directory, TAGS_FILE).takeIf { it.isFile }?.readLines().orEmpty()
        val art = File(directory, ART_FILE).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        val backdrop = File(directory, BACKDROP_FILE).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        if (art != null && backdrop == null) return null
        Details(
                title = tags.getOrNull(0)?.takeIf { it.isNotBlank() },
                artist = tags.getOrNull(1)?.takeIf { it.isNotBlank() },
                album = tags.getOrNull(2)?.takeIf { it.isNotBlank() },
                art = art,
                backdrop = backdrop,
        )
    }.onFailure { Timber.w(it, "AudioDetails: cannot read what was kept for $key") }.getOrNull()

    private fun writeToDisk(context: Context, key: String, details: Details) {
        runCatching {
            val directory = directoryFor(context, key).also { it.mkdirs() }
            // Written even when there is nothing to find, so a tagless file is only scanned once.
            File(directory, TAGS_FILE).writeText(
                    listOf(details.title, details.artist, details.album)
                            .joinToString("\n") { it.orEmpty().singleLine() }
            )
            details.art?.let { art ->
                File(directory, ART_FILE).outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            details.backdrop?.let { backdrop ->
                File(directory, BACKDROP_FILE).outputStream().use { backdrop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }.onFailure { Timber.w(it, "AudioDetails: cannot keep what was read from $key") }
    }

    private fun String.singleLine() = replace('\n', ' ')

    private fun directoryFor(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(File(context.cacheDir, "audio-details-v4"), digest.joinToString("") { "%02x".format(it) })
    }

    private fun MediaMetadataRetriever.tag(key: Int) = extractMetadata(key)?.takeIf { it.isNotBlank() }

    private fun read(context: Context, source: Uri, forTimeline: Boolean): Details {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, source)
            val picture = retriever.embeddedPicture
            val art = picture?.let { decodeScaled(it) }
            Details(
                    title = retriever.tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: retriever.tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    album = retriever.tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    art = art,
                    backdrop = if (forTimeline) {
                        picture?.let { AudioCoverArt.encode(it) }?.let { coverArtBackdrop(it) }
                    } else {
                        art?.let { blur(it) }
                    },
            )
        } catch (error: Exception) {
            Timber.w(error, "AudioDetails: cannot read $source")
            Details(null, null, null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ART_DIMENSION) sample *= 2
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.onFailure { Timber.w(it, "AudioDetails: cannot decode the artwork") }.getOrNull()
    }

    private fun blur(art: Bitmap): Bitmap {
        val width = BACKDROP_DIMENSION
        val height = (art.height.toFloat() / art.width * width).toInt().coerceAtLeast(1)
        val small = runCatching { Bitmap.createScaledBitmap(art, width, height, true) }.getOrNull() ?: return art
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(BLUR_PASSES) {
            boxBlur(pixels, width, height, BLUR_RADIUS)
            boxBlurTransposed(pixels, width, height, BLUR_RADIUS)
        }
        small.setPixels(pixels, 0, width, 0, 0, width, height)
        return small
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val row = IntArray(width)
        for (y in 0 until height) {
            val offset = y * width
            System.arraycopy(pixels, offset, row, 0, width)
            var red = 0
            var green = 0
            var blue = 0
            var count = 0
            for (x in 0 until minOf(radius, width)) {
                red += row[x] shr 16 and 0xFF
                green += row[x] shr 8 and 0xFF
                blue += row[x] and 0xFF
                count++
            }
            for (x in 0 until width) {
                pixels[offset + x] = (0xFF shl 24) or (red / count shl 16) or (green / count shl 8) or (blue / count)
                val leaving = x - radius
                val entering = x + radius
                if (entering < width) {
                    red += row[entering] shr 16 and 0xFF
                    green += row[entering] shr 8 and 0xFF
                    blue += row[entering] and 0xFF
                    count++
                }
                if (leaving >= 0) {
                    red -= row[leaving] shr 16 and 0xFF
                    green -= row[leaving] shr 8 and 0xFF
                    blue -= row[leaving] and 0xFF
                    count--
                }
            }
        }
    }

    private fun boxBlurTransposed(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val transposed = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) transposed[x * height + y] = pixels[y * width + x]
        }
        boxBlur(transposed, height, width, radius)
        for (y in 0 until height) {
            for (x in 0 until width) pixels[y * width + x] = transposed[x * height + y]
        }
    }

    private val coverArtBackdrops = LruCache<String, Bitmap>(32)

    fun coverArtBackdrop(hash: String): Bitmap? {
        coverArtBackdrops.get(hash)?.let { return it }
        // The library's cosine-table cache is not thread-safe.
        val backdrop = runCatching { BlurHash.decode(hash, COVER_ART_DIMENSION, COVER_ART_DIMENSION, useCache = false) }
                .onFailure { Timber.w(it, "Cannot decode a cover art hash") }
                .getOrNull() ?: return null
        coverArtBackdrops.put(hash, backdrop)
        return backdrop
    }

    /** Enough of a file to tell it apart from another, without reading all of it. */
    private const val FINGERPRINT_WINDOW = 64 * 1024

    /** A source that will not say how long it is; the bytes read still tell files apart. */
    private const val UNKNOWN_LENGTH = -1L

    private const val TAGS_FILE = "tags"
    private const val ART_FILE = "art.png"
    private const val BACKDROP_FILE = "backdrop.png"

    private const val COVER_ART_DIMENSION = 48
    private const val BACKDROP_DIMENSION = 192
    private const val BLUR_RADIUS = 8
    private const val BLUR_PASSES = 3
    private const val MAX_ART_DIMENSION = 512
}
