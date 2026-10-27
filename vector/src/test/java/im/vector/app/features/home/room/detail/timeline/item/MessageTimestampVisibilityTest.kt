/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import im.vector.app.R
import im.vector.app.core.epoxy.VectorEpoxyHolder
import im.vector.app.core.ui.views.BubbleDependentView
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.view.ScMessageBubbleWrapView
import im.vector.app.features.themes.defaultScBubbleAppearance
import im.vector.app.test.fixtures.PollFixture
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageTimestampVisibilityTest {

    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    private val view = LayoutInflater.from(context).inflate(R.layout.item_timeline_event_sc_bubble_incoming_base, null) as ScMessageBubbleWrapView
    private val dependentView = mockk<BubbleDependentView<VectorEpoxyHolder>>(relaxed = true)
    private val holder = mockk<VectorEpoxyHolder>(relaxed = true)

    @Test
    fun `recycled bubbles hide every timestamp while keeping the sender visible`() {
        listOf(false, true).forEach { singleSided ->
            listOf(true, false, true).forEach { showTimestamp ->
                val information = PollFixture.A_MESSAGE_INFORMATION_DATA.copy(
                        memberName = "Alice",
                        sentByMe = false,
                        time = "12:34",
                        messageLayout = TimelineMessageLayout.ScBubble(
                                showAvatar = false,
                                showDisplayName = true,
                                showTimestamp = showTimestamp,
                                bubbleAppearance = defaultScBubbleAppearance,
                                isIncoming = true,
                                reverseBubble = false,
                                singleSidedLayout = singleSided,
                                isRealBubble = true,
                                isPseudoBubble = false,
                                isNotice = false,
                                timestampAsOverlay = false,
                        ),
                )
                view.customBind(
                        dependentView,
                        holder,
                        AbsMessageItem.Attributes(
                                avatarSize = 40,
                                informationData = information,
                                avatarRenderer = mockk(relaxed = true),
                                messageColorProvider = mockk(relaxed = true),
                        ),
                        mockk(relaxed = true),
                )

                val visibleTimes = listOf(R.id.messageTimeView, R.id.bubbleMessageTimeView, R.id.bubbleFooterMessageTimeView)
                        .map { view.findViewById<TextView>(it) }
                        .filter { it.visibility == View.VISIBLE }
                visibleTimes.size shouldBeEqualTo if (showTimestamp) 1 else 0
                val nameId = if (singleSided) R.id.messageMemberNameView else R.id.bubbleMessageMemberNameView
                view.findViewById<TextView>(nameId).visibility shouldBeEqualTo View.VISIBLE
                val anchor = view.findViewById<View>(R.id.messageForwardedNoticeView).layoutParams as RelativeLayout.LayoutParams
                anchor.rules[RelativeLayout.BELOW] shouldBeEqualTo if (showTimestamp) R.id.bubbleMessageTimeView else R.id.bubbleMessageMemberNameView
                if (!singleSided && !showTimestamp) {
                    view.getFooterMeasures(information).toList() shouldBeEqualTo listOf(0, 0)
                }
            }
        }
    }
}
