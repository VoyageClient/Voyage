/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.EventType

class SearchMsgTypesTest {

    @Test
    fun `a message the timeline shows as a file matches has file`() {
        val custom = mapOf("msgtype" to "org.example.custom", "body" to "sample.bin", "url" to "mxc://example.org/sample")
        searchMsgTypes(EventType.MESSAGE, custom) shouldBeEqualTo listOf("org.example.custom", "m.file")
        searchMsgTypes(EventType.MESSAGE, custom - "msgtype") shouldBeEqualTo listOf("m.file")
    }

    @Test
    fun `known and media-less messages keep their own msgtype`() {
        val image = mapOf("msgtype" to "m.image", "body" to "sample.png", "url" to "mxc://example.org/sample")
        searchMsgTypes(EventType.MESSAGE, image) shouldBeEqualTo listOf("m.image")
        searchMsgTypes(EventType.MESSAGE, mapOf("msgtype" to "org.example.custom", "body" to "A sample message")) shouldBeEqualTo
                listOf("org.example.custom")
        searchMsgTypes(EventType.MESSAGE, mapOf("body" to "A sample message")) shouldBeEqualTo emptyList()
    }
}
