/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
internal data class StoredImagePacks(val version: Int, val packs: List<ResolvedImagePack>)

/**
 * Last known packs of a (session, room), kept across restarts so a picker has something to draw before
 * the aggregation — a walk of account data, room state and parent-space state — has run.
 *
 * Lives under `cacheDir`: signing out deletes that wholesale (MainActivity.doLocalCleanup), and an
 * eviction by the platform only costs the next open the load it used to do every time.
 */
@Singleton
class ImagePackDiskCache @Inject constructor(
        @ApplicationContext private val context: Context,
) {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(StoredImagePacks::class.java)
    private val directory by lazy { File(context.cacheDir, DIRECTORY_NAME) }

    fun read(key: String): List<ResolvedImagePack>? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { adapter.fromJson(file.readText()) }
                .onFailure { Timber.w(it, "Unreadable image-pack cache, dropping it") }
                .getOrNull()
                ?.takeIf { it.version == FORMAT_VERSION }
                ?.packs
    }

    fun write(key: String, packs: List<ResolvedImagePack>) {
        runCatching {
            directory.mkdirs()
            fileFor(key).writeText(adapter.toJson(StoredImagePacks(FORMAT_VERSION, packs)))
        }.onFailure { Timber.w(it, "Could not store the image-pack cache") }
    }

    // The key holds a user id and a room id; hash it rather than sanitising either into a filename.
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    companion object {
        private const val DIRECTORY_NAME = "imagepacks"
        private const val FORMAT_VERSION = 1
    }
}
