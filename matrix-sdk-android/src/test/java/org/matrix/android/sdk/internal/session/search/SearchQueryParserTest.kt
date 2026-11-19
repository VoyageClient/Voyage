/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.util.DateArgumentParser

class SearchQueryParserTest {

    @Test
    fun `a fully quoted filter stays literal text`() {
        val query = SearchQueryParser.parse("\"before:2026-01-02\"")

        query.tokens shouldBeEqualTo listOf("before:2026-01-02")
        query.beforeTs shouldBeEqualTo null
    }

    @Test
    fun `search covers messages and timeline notices but not verification or hidden events`() {
        isSearchable(EventType.MESSAGE, listOf("m.text")) shouldBeEqualTo true
        isSearchable(EventType.MESSAGE, listOf("m.key.verification.request")) shouldBeEqualTo false
        isSearchable(EventType.MESSAGE, emptyList()) shouldBeEqualTo false
        isSearchable(EventType.STATE_ROOM_MEMBER, emptyList()) shouldBeEqualTo true
        isSearchable(EventType.POLL_END.unstable, emptyList()) shouldBeEqualTo true
        isSearchable(EventType.REACTION, emptyList()) shouldBeEqualTo false

        searchableText(EventType.STATE_ROOM_TOPIC, mapOf("topic" to "Weekly sync")) shouldBeEqualTo "Weekly sync"
        searchableText(EventType.STATE_ROOM_MEMBER, mapOf("membership" to "join")) shouldBeEqualTo null
    }

    @Test
    fun `extended date shapes work as filters`() {
        val query = SearchQueryParser.parse("hello before:2026-11-16-19-00 world")
        query.tokens shouldBeEqualTo listOf("hello", "world")
        query.beforeTs.shouldNotBeNull() shouldBeEqualTo DateArgumentParser.parse("2026-11-16T19:00")
        SearchQueryParser.parse("after:2026-11-16-19-00-00").afterTs shouldBeEqualTo DateArgumentParser.parse("2026-11-16T19:00:00")
        SearchQueryParser.parse("after:2026-11-16T19:00Z").afterTs shouldBeEqualTo DateArgumentParser.parse("2026-11-16T19:00z")
    }
}
