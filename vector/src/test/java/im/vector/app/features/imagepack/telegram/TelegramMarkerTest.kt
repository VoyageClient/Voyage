/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class TelegramMarkerTest {

    private fun sticker(id: String) = TelegramSticker(fileId = "file_$id", fileUniqueId = id, emoji = null, format = TelegramStickerFormat.STATIC)

    private val set = listOf(sticker("a"), sticker("b"), sticker("c"))

    @Test
    fun `nothing is missing when every imported sticker is still in the pack`() {
        val imported = mapOf("a" to "mxc://s/a", "b" to "mxc://s/b", "c" to "mxc://s/c")
        TelegramMarker.missing(set, imported, setOf("mxc://s/a", "mxc://s/b", "mxc://s/c", "mxc://s/own")) shouldBeEqualTo emptyList()
    }

    @Test
    fun `stickers new to the set are missing`() {
        val imported = mapOf("a" to "mxc://s/a", "b" to "mxc://s/b")
        TelegramMarker.missing(set, imported, setOf("mxc://s/a", "mxc://s/b")).map { it.fileUniqueId } shouldBeEqualTo listOf("c")
    }

    @Test
    fun `stickers removed from the pack come back`() {
        val imported = mapOf("a" to "mxc://s/a", "b" to "mxc://s/b", "c" to "mxc://s/c")
        TelegramMarker.missing(set, imported, setOf("mxc://s/a", "mxc://s/c")).map { it.fileUniqueId } shouldBeEqualTo listOf("b")
    }

    @Test
    fun `a pack without a marker is missing everything`() {
        TelegramMarker.missing(set, emptyMap(), setOf("mxc://s/a")).size shouldBeEqualTo 3
    }

    @Test
    fun `re-added stickers go back to their telegram position`() {
        val indexByUrl = mapOf("u0" to 0, "u1" to 1, "u2" to 2, "u3" to 3, "u4" to 4)
        // The first three were deleted: each re-added one goes before u3, in order.
        val pack = mutableListOf("u3", "u4")
        listOf(0, 1, 2).forEach { index -> pack.add(TelegramMarker.insertionIndex(pack, indexByUrl, index), "u$index") }
        pack shouldBeEqualTo listOf("u0", "u1", "u2", "u3", "u4")
    }

    @Test
    fun `re-added stickers keep user images where they are`() {
        val indexByUrl = mapOf("u0" to 0, "u1" to 1, "u2" to 2)
        TelegramMarker.insertionIndex(listOf("u0", "own", "u2"), indexByUrl, 1) shouldBeEqualTo 2
        TelegramMarker.insertionIndex(listOf("u0", "own"), indexByUrl, 2) shouldBeEqualTo 1
        TelegramMarker.insertionIndex(listOf("own"), indexByUrl, 1) shouldBeEqualTo 1
    }

    @Test
    fun `the marker round-trips through the event content`() {
        val content = TelegramMarker.toTopLevel("AniColle", mapOf("a" to "mxc://s/a"))
        TelegramMarker.read(content) shouldBeEqualTo TelegramMarker.Marker("AniColle", mapOf("a" to "mxc://s/a"))
        TelegramMarker.read(mapOf("images" to emptyMap<String, Any>())) shouldBeEqualTo null
    }
}
