/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData

/**
 * "Redacted by room admin" may only be claimed when the redacter is known and is someone else;
 * search hits carry no redacting event when the index couldn't reach it.
 */
class RedactedEventFormattingTest {

    private val sp = mockk<StringProvider>().also {
        every { it.getString(any()) } answers { "string:${firstArg<Int>()}" }
        every { it.getString(any(), *anyVararg()) } answers { "string:${firstArg<Int>()}:${arg<Array<Any>>(1).joinToString()}" }
    }

    private val formatter = NoticeEventFormatter(
            activeSessionDataSource = mockk(relaxed = true),
            roomHistoryVisibilityFormatter = mockk(relaxed = true),
            roleFormatter = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            colorProvider = mockk(relaxed = true),
            reactionFormatter = mockk(relaxed = true),
            sp = sp,
    )

    private fun redactedEvent(redaction: Event?) = Event(
            type = EventType.MESSAGE,
            eventId = "\$target",
            senderId = "@alice:example.org",
            unsignedData = UnsignedData(age = null, redactedEvent = redaction, redactedBy = redaction?.eventId ?: "\$target"),
    )

    private fun redaction(sender: String, reason: String? = null) = Event(
            type = EventType.REDACTION,
            eventId = "\$redaction",
            senderId = sender,
            content = reason?.let { mapOf("reason" to it) },
    )

    @Test
    fun `unknown redacter does not blame an admin`() {
        assertEquals(
                "string:${CommonStrings.event_redacted}",
                formatter.formatRedactedEvent(redactedEvent(redaction = null))
        )
    }

    @Test
    fun `self redaction is not an admin redaction`() {
        assertEquals(
                "string:${CommonStrings.event_redacted}",
                formatter.formatRedactedEvent(redactedEvent(redaction("@alice:example.org")))
        )
    }

    @Test
    fun `redaction by someone else is an admin redaction`() {
        assertEquals(
                "string:${CommonStrings.event_redacted_by_admin}",
                formatter.formatRedactedEvent(redactedEvent(redaction("@bob:example.org")))
        )
    }

    @Test
    fun `admin redaction keeps the reason`() {
        assertEquals(
                "string:${CommonStrings.event_redacted_by_admin_with_reason}:spam",
                formatter.formatRedactedEvent(redactedEvent(redaction("@bob:example.org", reason = "spam")))
        )
    }

    @Test
    fun `self redaction keeps the reason`() {
        assertEquals(
                "string:${CommonStrings.event_redacted_with_reason}:oops",
                formatter.formatRedactedEvent(redactedEvent(redaction("@alice:example.org", reason = "oops")))
        )
    }
}
