/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.JSON_DICT_PARAMETERIZED_TYPE
import org.matrix.android.sdk.api.util.MatrixJsonParser

class RoomTopicContentTest {

    // Goes through the same Map -> model hop the room summary updater uses, not a direct string parse,
    // so the shape tolerance is exercised on Moshi's JsonValueReader.
    private fun parse(json: String): RoomTopicContent? = MatrixJsonParser.getMoshi()
            .adapter<Content>(JSON_DICT_PARAMETERIZED_TYPE)
            .fromJson(json)
            .toModel<RoomTopicContent>()

    @Test
    fun `spec shape - m dot topic wrapping an m dot text array`() {
        val content = parse(
                """
                {"topic":"All about pizza",
                 "m.topic":{"m.text":[{"body":"All about <b>pizza</b>","mimetype":"text/html"},
                                      {"body":"All about pizza","mimetype":"text/plain"}]}}
                """.trimIndent()
        )

        assertEquals("All about pizza", content?.getBestTopic())
        assertEquals("All about <b>pizza</b>", content?.getBestFormattedTopic())
    }

    @Test
    fun `legacy js-sdk shape - m dot topic as a bare array`() {
        val content = parse(
                """
                {"topic":"All about pizza",
                 "m.topic":[{"body":"All about <b>pizza</b>","mimetype":"text/html"},
                            {"body":"All about pizza","mimetype":"text/plain"}]}
                """.trimIndent()
        )

        assertEquals("All about pizza", content?.getBestTopic())
        assertEquals("All about <b>pizza</b>", content?.getBestFormattedTopic())
    }

    @Test
    fun `unstable prefix as a bare array`() {
        val content = parse("""{"topic":"Kernel talk","org.matrix.msc3765.topic":[{"body":"Kernel talk","mimetype":"text/plain"}]}""")

        assertEquals("Kernel talk", content?.getBestTopic())
        assertNull(content?.getBestFormattedTopic())
    }

    @Test
    fun `a representation without a mimetype is plain text`() {
        val content = parse("""{"m.topic":{"m.text":[{"body":"No mimetype here"}]}}""")

        assertEquals("No mimetype here", content?.getBestTopic())
    }

    @Test
    fun `an unparseable topic block still yields the legacy topic`() {
        val content = parse("""{"topic":"Kernel talk","m.topic":"not a topic block"}""")

        assertEquals("Kernel talk", content?.getBestTopic())
        assertNull(content?.getBestFormattedTopic())
    }

    @Test
    fun `legacy topic only`() {
        val content = parse("""{"topic":"Kernel talk"}""")

        assertEquals("Kernel talk", content?.getBestTopic())
        assertNull(content?.getBestFormattedTopic())
    }
}
