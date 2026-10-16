/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.billcarsonfr.jsonviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JSonViewerValueDetectorTest {

    private fun string(value: String) = JSonViewerValueDetector.detect(JSONType.STRING, value)
    private fun number(value: String) = JSonViewerValueDetector.detect(JSONType.NUMBER, value)

    private val hash = "acR1l0raoZnm60CBwAVgqbZqoO_mYU81xysh1u7XcJk"

    @Test
    fun `detects user IDs, including historical localparts and ports`() {
        assertEquals(JSonViewerValueKind.USER_ID, string("@alice:example.org"))
        assertEquals(JSonViewerValueKind.USER_ID, string("@Alice!x:example.org:8448"))
        assertEquals(JSonViewerValueKind.USER_ID, string("@bob:[1234:5678::abcd]"))
        assertEquals(JSonViewerValueKind.USER_ID, string("@bob:127.0.0.1"))
        assertNull(string("@alice"))
        assertNull(string("@alice:"))
        assertNull(string("@everyone: hello"))
        assertNull(string("@alice:example.org:port"))
    }

    @Test
    fun `detects room IDs with a server name and domainless v12 room IDs`() {
        assertEquals(JSonViewerValueKind.ROOM_ID, string("!OGEhHVWSdvArJzumhm:matrix.org"))
        assertEquals(JSonViewerValueKind.ROOM_ID, string("!$hash"))
        assertNull(string("!short"))
        assertNull(string("!${hash}x"))
        assertNull(string("!"))
    }

    @Test
    fun `detects event IDs of every room version`() {
        assertEquals(JSonViewerValueKind.EVENT_ID, string("\$1435641916114394fHBLK:matrix.org"))
        assertEquals(JSonViewerValueKind.EVENT_ID, string("\$acR1l0raoZnm60CBwAVgqbZqoO/mYU81xysh1u7XcJk"))
        assertEquals(JSonViewerValueKind.EVENT_ID, string("\$$hash"))
        assertNull(string("\$5.00"))
        assertNull(string("\$HOME"))
    }

    @Test
    fun `rejects IDs over 255 bytes`() {
        assertNull(string("@" + "a".repeat(250) + ":example.org"))
    }

    @Test
    fun `detects millisecond timestamps only among numbers`() {
        assertEquals(JSonViewerValueKind.TIMESTAMP, number("1760611128712"))
        assertEquals(JSonViewerValueKind.TIMESTAMP, number("1.760611128712E12"))
        assertNull(number("1760611128"))
        assertNull(number("1760611128712.5"))
        assertNull(number("42"))
        assertNull(string("1760611128712"))
    }
}
