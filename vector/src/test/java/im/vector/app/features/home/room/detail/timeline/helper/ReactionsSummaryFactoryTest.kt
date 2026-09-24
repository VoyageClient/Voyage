/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.features.home.room.detail.timeline.item.ReactionInfoData
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.ReactionAggregatedSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

private const val AN_EVENT_ID = "\$event"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReactionsSummaryFactoryTest {

    private val factory = ReactionsSummaryFactory()

    @Test
    fun `given an unreact tap, when the summary still has my reaction, then the pill stays off`() {
        factory.onReactionToggled(AN_EVENT_ID, "👍", addedByMe = false)

        factory.create(anEvent(reaction("👍", count = 1, addedByMe = true))).reactions shouldBeEqualTo emptyList()
        factory.create(anEvent(reaction("👍", count = 3, addedByMe = true))).reactions shouldBeEqualTo
                listOf(ReactionInfoData("👍", 2, false))
    }

    @Test
    fun `given a react tap, when the summary does not have it yet, then the pill shows on`() {
        factory.onReactionToggled(AN_EVENT_ID, "👍", addedByMe = true)

        factory.create(anEvent()).reactions shouldBeEqualTo listOf(ReactionInfoData("👍", 1, true))
        factory.create(anEvent(reaction("👍", count = 2, addedByMe = false))).reactions shouldBeEqualTo
                listOf(ReactionInfoData("👍", 3, true))
    }

    @Test
    fun `given a pending tap, once the summary agrees, then later summaries are shown as they are`() {
        factory.onReactionToggled(AN_EVENT_ID, "👍", addedByMe = false)
        factory.create(anEvent())

        factory.create(anEvent(reaction("👍", count = 1, addedByMe = true))).reactions shouldBeEqualTo
                listOf(ReactionInfoData("👍", 1, true))
    }

    @Test
    fun `given a pending tap that never lands, when it expires, then the summary wins`() {
        factory.onReactionToggled(AN_EVENT_ID, "👍", addedByMe = false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))

        factory.create(anEvent(reaction("👍", count = 1, addedByMe = true))).reactions shouldBeEqualTo
                listOf(ReactionInfoData("👍", 1, true))
    }

    private fun reaction(key: String, count: Int, addedByMe: Boolean) =
            ReactionAggregatedSummary(key, count, addedByMe, firstTimestamp = 0L, sourceEvents = emptyList(), localEchoEvents = emptyList())

    private fun anEvent(vararg reactions: ReactionAggregatedSummary) = TimelineEvent(
            root = Event(eventId = AN_EVENT_ID),
            localId = 0L,
            eventId = AN_EVENT_ID,
            senderInfo = SenderInfo(userId = "@me:hs", displayName = null, isUniqueDisplayName = true, avatarUrl = null),
            annotations = EventAnnotationsSummary(reactionsSummary = reactions.toList()),
    )
}
