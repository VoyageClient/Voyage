/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.imagepack

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.JSON_DICT_PARAMETERIZED_TYPE
import org.matrix.android.sdk.api.util.MatrixJsonParser

class ImagePackOrderTest {

    private fun parse(json: String): ImagePackContent? = MatrixJsonParser.getMoshi()
            .adapter<Content>(JSON_DICT_PARAMETERIZED_TYPE)
            .fromJson(json)
            .toModel<ImagePackContent>()

    @Test
    fun `images sort by order with the stable key read first`() {
        // Keys arrive sorted, as the server returns them.
        val content = parse(
                """
                {"images":{
                  "a":{"url":"mxc://s/a","order":3,"fi.mau.msc4389.order":1},
                  "b":{"url":"mxc://s/b","fi.mau.msc4389.order":2},
                  "c":{"url":"mxc://s/c","order":1}
                }}
                """.trimIndent()
        )
        assertEquals(listOf("c", "b", "a"), content?.effectiveImages()?.keys?.toList())
    }

    @Test
    fun `a missing order sorts as zero and ties keep their order`() {
        val content = parse("""{"images":{"a":{"url":"mxc://s/a","order":1},"b":{"url":"mxc://s/b"},"c":{"url":"mxc://s/c"}}}""")
        assertEquals(listOf("b", "c", "a"), content?.effectiveImages()?.keys?.toList())
    }

    @Test
    fun `sequential order is written under both keys as integers`() {
        val images = linkedMapOf("z" to ImagePackImage(url = "mxc://s/z"), "a" to ImagePackImage(url = "mxc://s/a")).withSequentialOrder()
        val written = ImagePackContent(images = images).toContent()

        @Suppress("UNCHECKED_CAST")
        val z = (written["images"] as Map<String, Map<String, Any>>)["z"]!!
        assertEquals(1L, z["order"])
        assertEquals(1L, z["fi.mau.msc4389.order"])
        assertEquals(listOf("z", "a"), written.toModel<ImagePackContent>()?.effectiveImages()?.keys?.toList())
    }

    @Test
    fun `compact pack keeps image order and distinct body`() {
        val images = linkedMapOf(
                "z" to ImagePackImage(url = "mxc://s/z", body = "z"),
                "a" to ImagePackImage(url = "mxc://s/a", body = "description"),
        ).withSequentialOrder()
        val compact = ImagePackContent(images = images).compactForSizeLimit()
        val written = compact.toContent()

        @Suppress("UNCHECKED_CAST")
        val entries = written["images"] as Map<String, Map<String, Any>>
        assertEquals(null, entries["z"]?.get("body"))
        assertEquals("description", entries["a"]?.get("body"))
        assertEquals(null, entries["z"]?.get("fi.mau.msc4389.order"))
        assertEquals(listOf("z", "a"), written.toModel<ImagePackContent>()?.effectiveImages()?.keys?.toList())

        val legacy = ImagePackContent(images = images).compactForSizeLimit(preferUnstableOrder = true).toContent()
        @Suppress("UNCHECKED_CAST")
        val legacyEntries = legacy["images"] as Map<String, Map<String, Any>>
        assertEquals(null, legacyEntries["z"]?.get("order"))
        assertEquals(1L, legacyEntries["z"]?.get("fi.mau.msc4389.order"))
        assertEquals(listOf("z", "a"), legacy.toModel<ImagePackContent>()?.effectiveImages()?.keys?.toList())
    }
}
