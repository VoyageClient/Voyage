/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the lightweight list of a user's known image packs (one entry per pack: room, state key,
 * name, avatar, image count) so the settings Image Packs screen can render instantly on a fresh launch,
 * before the (potentially slow) all-rooms scan finishes. The scan then reconciles and rewrites this.
 */
@Singleton
class ImagePackDiskCache @Inject constructor(
        @ApplicationContext private val context: Context,
) {

    data class Entry(
            val roomId: String,
            val stateKey: String,
            val displayName: String?,
            val avatarUrl: String?,
            val firstImageUrl: String?,
            val imageCount: Int,
    )

    fun load(userId: String): List<Entry>? {
        return runCatching {
            val file = fileFor(userId)
            if (!file.exists()) return null
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Entry(
                        roomId = o.getString("roomId"),
                        stateKey = o.getString("stateKey"),
                        displayName = o.optStringOrNull("displayName"),
                        avatarUrl = o.optStringOrNull("avatarUrl"),
                        firstImageUrl = o.optStringOrNull("firstImageUrl"),
                        imageCount = o.getInt("imageCount"),
                )
            }
        }.getOrNull()
    }

    fun save(userId: String, entries: List<Entry>) {
        runCatching {
            val array = JSONArray()
            entries.forEach { e ->
                array.put(
                        JSONObject()
                                .put("roomId", e.roomId)
                                .put("stateKey", e.stateKey)
                                .put("displayName", e.displayName ?: JSONObject.NULL)
                                .put("avatarUrl", e.avatarUrl ?: JSONObject.NULL)
                                .put("firstImageUrl", e.firstImageUrl ?: JSONObject.NULL)
                                .put("imageCount", e.imageCount)
                )
            }
            fileFor(userId).writeText(array.toString())
        }
    }

    private fun fileFor(userId: String): File {
        val safe = userId.replace(Regex("[^A-Za-z0-9]"), "_")
        return File(context.filesDir, "image_pack_cache_$safe.json")
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
}
