/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.util.LruCache

/**
 * Keeps the projected faces of a solid, so avatars sharing a shape share the work of placing it.
 *
 * Where a room list shows one shape over many rooms, every one of those avatars is the same solid at
 * the same angle and the same size on any given frame; all that differs is the picture painted onto
 * it. Working the faces out once and filling them many times is the whole saving here.
 *
 * Frames are not cached, only geometry: a frame is per avatar and would be megabytes, while the
 * geometry is shared and measured in kilobytes.
 */
object SolidGeometryCache {

    private val entries = object : LruCache<Long, SolidGeometry>(budget()) {
        override fun sizeOf(key: Long, value: SolidGeometry) = value.sizeInBytes()
    }

    fun get(effect: AvatarEffect, sizePx: Int, frame: Int): SolidGeometry? = entries.get(key(effect, sizePx, frame))

    fun put(effect: AvatarEffect, sizePx: Int, frame: Int, geometry: SolidGeometry) {
        entries.put(key(effect, sizePx, frame), geometry)
    }

    fun clear() = entries.evictAll()

    private fun key(effect: AvatarEffect, sizePx: Int, frame: Int) =
            (effect.ordinal.toLong() shl 32) or (sizePx.toLong() shl 12) or frame.toLong()

    private fun budget() = (Runtime.getRuntime().maxMemory() / 128).toInt().coerceIn(512 * 1024, 4 * 1024 * 1024)
}
