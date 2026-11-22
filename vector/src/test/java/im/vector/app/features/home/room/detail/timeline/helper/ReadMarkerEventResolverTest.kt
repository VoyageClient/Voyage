/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class ReadMarkerEventResolverTest {

    private val local = event("\$local.pending")
    private val remote = event("\$remote", transactionId = local.eventId)
    private val preceding = event("\$preceding")
    private val unseen = event("\$unseen")

    @Test
    fun `resolve a retained local echo to its synced replacement`() {
        local.resolveReadMarkerEvent(listOf(unseen, remote, preceding)) shouldBe remote
    }

    @Test
    fun `prefer the remote replacement when both copies are present`() {
        local.resolveReadMarkerEvent(listOf(local, remote, preceding)) shouldBe remote
    }

    @Test
    fun `use the preceding synced event while the echo is waiting for sync`() {
        local.resolveReadMarkerEvent(listOf(local, preceding)) shouldBe preceding
    }

    @Test
    fun `skip other pending echoes when finding the preceding synced event`() {
        val anotherLocal = event("\$local.other")

        local.resolveReadMarkerEvent(listOf(local, anotherLocal, preceding)) shouldBe preceding
    }

    @Test
    fun `do not mark a newer unseen event read while waiting for sync`() {
        local.resolveReadMarkerEvent(listOf(unseen, local, preceding)) shouldBe preceding
    }

    @Test
    fun `do not guess a read position when the echo and its replacement are absent`() {
        local.resolveReadMarkerEvent(listOf(unseen, preceding)) shouldBe null
    }

    @Test
    fun `return no marker when only local echoes are available`() {
        local.resolveReadMarkerEvent(listOf(local)) shouldBe null
    }

    @Test
    fun `retain a seen synced event after it leaves the loaded window`() {
        preceding.resolveReadMarkerEvent(listOf(unseen)) shouldBe preceding
    }

    private fun event(eventId: String, transactionId: String? = null): TimelineEvent {
        return TimelineEvent(
                root = Event(
                        eventId = eventId,
                        roomId = "!room:example.org",
                        senderId = "@alice:example.org",
                        unsignedData = transactionId?.let { UnsignedData(age = null, transactionId = it) },
                ),
                eventId = eventId,
                localId = 1L,
                senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
        )
    }
}
