/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.model.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isGalleryMessage
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageDefaultContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.galleryFallbackBody
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.toGalleryItem
import org.matrix.android.sdk.internal.di.MoshiProvider

class MessageGalleryContentTest {

    private val adapter = MoshiProvider.providesMoshi().adapter(MessageContent::class.java)

    private fun galleryJson(msgtype: String) = """
        {
          "msgtype": "$msgtype",
          "body": "Holiday pictures",
          "itemtypes": [
            {
              "itemtype": "m.image",
              "body": "beach.jpg",
              "info": { "h": 398, "w": 394, "mimetype": "image/jpeg", "size": 31037 },
              "url": "mxc://example.org/abc"
            },
            {
              "itemtype": "m.video",
              "body": "waves.mp4",
              "info": { "h": 720, "w": 1280, "mimetype": "video/mp4", "size": 12345, "duration": 5000 },
              "url": "mxc://example.org/def"
            },
            {
              "itemtype": "m.file",
              "body": "notes.pdf",
              "info": { "mimetype": "application/pdf", "size": 500 },
              "url": "mxc://example.org/ghi"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses unstable msgtype`() {
        val content = adapter.fromJson(galleryJson("dm.filament.gallery"))
        assertTrue(content is MessageGalleryContent)
        val items = (content as MessageGalleryContent).galleryItems()
        assertEquals(3, items.size)
        val image = items[0]
        assertTrue(image is MessageImageContent)
        assertEquals("beach.jpg", image.body)
        assertEquals("mxc://example.org/abc", image.url)
        assertEquals(394, (image as MessageImageContent).info?.width)
        val video = items[1]
        assertTrue(video is MessageVideoContent)
        assertEquals(5000, (video as MessageVideoContent).videoInfo?.duration)
    }

    @Test
    fun `parses stable msgtype`() {
        val content = adapter.fromJson(galleryJson("m.gallery"))
        assertTrue(content is MessageGalleryContent)
        assertEquals("Holiday pictures", (content as MessageGalleryContent).body)
    }

    @Test
    fun `serializes with the unstable msgtype`() {
        val content = adapter.fromJson(galleryJson("dm.filament.gallery")) as MessageGalleryContent
        val json = adapter.toJson(content)
        assertTrue(json.contains("\"dm.filament.gallery\""))
        assertTrue(json.contains("\"itemtype\":\"m.image\""))
    }

    @Test
    fun `empty or missing itemtypes yield no items`() {
        val empty = adapter.fromJson("""{"msgtype":"dm.filament.gallery","body":"x","itemtypes":[]}""")
        assertEquals(0, (empty as MessageGalleryContent).galleryItems().size)
        val missing = adapter.fromJson("""{"msgtype":"dm.filament.gallery","body":"x"}""")
        assertEquals(0, (missing as MessageGalleryContent).galleryItems().size)
    }

    @Test
    fun `malformed and non-attachment items are dropped`() {
        val json = """
            {
              "msgtype": "dm.filament.gallery",
              "body": "x",
              "itemtypes": [
                { "body": "no-itemtype.jpg", "url": "mxc://example.org/a" },
                { "itemtype": "m.location", "body": "somewhere", "geo_uri": "geo:1,2" },
                { "itemtype": "m.image", "body": "ok.jpg", "url": "mxc://example.org/b" }
              ]
            }
        """.trimIndent()
        val content = adapter.fromJson(json) as MessageGalleryContent
        val items = content.galleryItems()
        assertEquals(1, items.size)
        assertEquals("ok.jpg", items[0].body)
    }

    @Test
    fun `encrypted item resolves its file url`() {
        val json = """
            {
              "msgtype": "dm.filament.gallery",
              "body": "x",
              "itemtypes": [
                {
                  "itemtype": "m.image",
                  "body": "secret.jpg",
                  "info": { "h": 10, "w": 10, "mimetype": "image/jpeg", "size": 1 },
                  "file": {
                    "url": "mxc://example.org/enc",
                    "key": { "alg": "A256CTR", "ext": true, "k": "aWv4", "key_ops": ["encrypt", "decrypt"], "kty": "oct" },
                    "iv": "aaaabbbbcccc",
                    "hashes": { "sha256": "abcd" },
                    "v": "v2"
                  }
                }
              ]
            }
        """.trimIndent()
        val content = adapter.fromJson(json) as MessageGalleryContent
        val item = content.galleryItems().single()
        assertEquals(null, item.url)
        assertEquals("mxc://example.org/enc", item.getFileUrl())
    }

    @Test
    fun `fallback body lists filenames and urls`() {
        val content = adapter.fromJson(galleryJson("dm.filament.gallery")) as MessageGalleryContent
        val body = galleryFallbackBody(content.galleryItems())
        assertEquals(
                "[beach.jpg: mxc://example.org/abc]\n[waves.mp4: mxc://example.org/def]\n[notes.pdf: mxc://example.org/ghi]",
                body
        )
    }

    @Test
    fun `fallback body shows file for encrypted items`() {
        val json = """
            {
              "msgtype": "dm.filament.gallery",
              "body": "x",
              "itemtypes": [
                {
                  "itemtype": "m.file",
                  "body": "doc.pdf",
                  "file": {
                    "url": "mxc://example.org/enc",
                    "key": { "alg": "A256CTR", "ext": true, "k": "aWv4", "key_ops": ["decrypt"], "kty": "oct" },
                    "iv": "iv", "hashes": { "sha256": "h" }, "v": "v2"
                  }
                }
              ]
            }
        """.trimIndent()
        val content = adapter.fromJson(json) as MessageGalleryContent
        assertEquals("[doc.pdf: file]", galleryFallbackBody(content.galleryItems()))
    }

    @Test
    fun `existing message types still parse after the dual-label registration`() {
        val image = adapter.fromJson("""{"msgtype":"m.image","body":"a.jpg","url":"mxc://x/y"}""")
        assertTrue(image is MessageImageContent)
        val text = adapter.fromJson("""{"msgtype":"m.text","body":"hi"}""")
        assertEquals("m.text", text?.msgType)
        val unknown = adapter.fromJson("""{"msgtype":"com.example.custom","body":"hi"}""")
        assertTrue(unknown is MessageDefaultContent)
    }

    @Test
    fun `event helper recognises both gallery msgtypes`() {
        fun eventWith(msgtype: String) = Event(
                type = EventType.MESSAGE,
                eventId = "\$e",
                content = mapOf("msgtype" to msgtype, "body" to "x"),
        )
        assertTrue(eventWith("dm.filament.gallery").isGalleryMessage())
        assertTrue(eventWith("m.gallery").isGalleryMessage())
        assertTrue(!eventWith("m.image").isGalleryMessage())
    }

    @Test
    fun `round-tripped items serialize whole numbers as integers`() {
        // Parsing pushes itemtypes through Moshi's Any adapter (numbers become Doubles); a patched
        // item must not re-serialize as "size":31037.0 — Synapse rejects floats (M_BAD_JSON).
        val content = adapter.fromJson(galleryJson("dm.filament.gallery")) as MessageGalleryContent
        val patched = content.copy(itemtypes = content.galleryItems().mapNotNull { it.toGalleryItem() })
        val json = adapter.toJson(patched)
        assertTrue("floats leaked into $json", !json.contains(".0"))
        assertTrue(json.contains("\"size\":31037"))
        val info = patched.itemtypes[0]["info"] as Map<*, *>
        assertTrue(info["size"] is Long)
    }

    @Test
    fun `item remap round trips`() {
        val content = adapter.fromJson(galleryJson("dm.filament.gallery")) as MessageGalleryContent
        val items = content.galleryItems()
        val roundTripped = items.mapNotNull { it.toGalleryItem() }
        assertEquals(content.itemtypes.size, roundTripped.size)
        roundTripped.forEach { dict ->
            assertTrue(dict.containsKey("itemtype"))
            assertTrue(!dict.containsKey("msgtype"))
        }
    }
}
