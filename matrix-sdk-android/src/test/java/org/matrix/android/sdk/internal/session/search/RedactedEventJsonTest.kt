/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.internal.di.MoshiProvider

/** The search index stores events as JSON; a redacted one carries its redaction inline. */
class RedactedEventJsonTest {

    private val adapter = MoshiProvider.providesMoshi().adapter(Event::class.java)

    @Test
    fun `an event carrying its redaction round-trips through the index json`() {
        val redaction = Event(
                type = EventType.REDACTION,
                eventId = "\$redaction",
                senderId = "@bob:example.org",
                roomId = "!room:example.org",
                content = mapOf("reason" to "spam"),
        )
        val event = Event(
                type = EventType.MESSAGE,
                eventId = "\$target",
                senderId = "@alice:example.org",
                roomId = "!room:example.org",
                content = mapOf("msgtype" to "m.text", "body" to "hello"),
                unsignedData = UnsignedData(age = null, redactedEvent = redaction, redactedBy = redaction.eventId),
        )

        val parsed = adapter.fromJson(adapter.toJson(event))!!

        parsed.eventId shouldBeEqualTo "\$target"
        parsed.unsignedData?.redactedEvent?.senderId shouldBeEqualTo "@bob:example.org"
        parsed.unsignedData?.redactedEvent?.content?.get("reason") shouldBeEqualTo "spam"
    }

    /** A stripped row carries no text, so only a query that doesn't match on text can surface it. */
    @Test
    fun `a stripped redaction matches filter-only queries and no text query`() {
        val stub = { query: String ->
            SearchQueryParser.parse(query).matches(
                    text = "",
                    sender = "@alice:example.org",
                    originServerTs = 1_000L,
                    msgtypes = emptyList(),
                    eventMentions = emptyList(),
            )
        }

        stub("from:@alice:example.org") shouldBeEqualTo true
        stub("from:@bob:example.org") shouldBeEqualTo false
        stub("hello") shouldBeEqualTo false
        stub("has:image") shouldBeEqualTo false
    }
}
