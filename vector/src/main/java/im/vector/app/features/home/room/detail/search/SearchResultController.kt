/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.epoxy.VisibilityState
import im.vector.app.R
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.LoadingItem_
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.noResultItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.helper.StubTimelineEventCallback
import im.vector.app.features.home.room.detail.timeline.helper.TimelineRetrieversFactory
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem_
import im.vector.app.features.media.AttachmentData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import java.util.Calendar
import javax.inject.Inject

/**
 * Renders search results with the actual timeline item factories so messages look and behave
 * exactly like in the room timeline (media viewer, inline audio playback, HTML, bubbles...).
 * Interactions that only make sense inside a live timeline are no-ops (via
 * [StubTimelineEventCallback]); tapping anywhere outside an interactive part of a result
 * navigates to the event in the timeline.
 */
class SearchResultController @Inject constructor(
        private val session: Session,
        private val stringProvider: StringProvider,
        private val dateFormatter: VectorDateFormatter,
        private val clock: Clock,
        private val timelineItemFactory: TimelineItemFactory,
        private val timelineRetrieversFactory: TimelineRetrieversFactory,
        private val stubCallback: StubTimelineEventCallback,
) : TypedEpoxyController<SearchViewState>(), TimelineEventController.Callback by stubCallback {

    var listener: Listener? = null

    private var idx = 0

    // Some cells hand the long press to a child view and let the press-release reach the item's own
    // click listener, which here would navigate away from the actions sheet just opened. The release
    // can land well after the long press fired, so swallow the next click outright rather than
    // guessing at a time window.
    private var swallowNextClick = false
    private var roomSummary: RoomSummary? = null
    private val eventsById = HashMap<String, Event>()

    interface Listener {
        fun onItemClicked(event: Event)
        fun onThreadSummaryClicked(event: Event)
        fun loadMore()
        fun onImageMessageClicked(messageImageContent: MessageImageInfoContent, mediaData: ImageContentRenderer.Data, view: View, inMemory: List<AttachmentData>)
        fun onVideoMessageClicked(
                messageVideoContent: MessageVideoContent,
                mediaData: VideoContentRenderer.Data,
                view: View,
                inMemory: List<AttachmentData>
        )
        fun onVoiceControlButtonClicked(eventId: String, messageAudioContent: MessageAudioContent)
        fun onAudioSeekBarMovedTo(eventId: String, duration: Int, percentage: Float)
        fun onAvatarClicked(userId: String)
        fun onEventLongClicked(informationData: MessageInformationData): Boolean
        fun onUrlLongClicked(url: String): Boolean
    }

    /** Must be called once before the first [setData]. */
    fun start(roomId: String, coroutineScope: CoroutineScope) {
        roomSummary = session.roomService().getRoomSummary(roomId)
        stubCallback.retrievers = timelineRetrieversFactory.create(roomId, coroutineScope)
    }

    override fun buildModels(data: SearchViewState?) {
        data ?: return

        val host = this

        if (data.hasMoreResult) {
            LoadingItem_()
                    // Always use a different id, because we can be notified several times of visibility state changed
                    .id("loadMore${host.idx++}")
                    // The stock loading layout is a ~130dp mostly-empty block; sitting at the top
                    // of this bottom-anchored list for the whole crawl it reads as a blank band.
                    .layout(R.layout.item_loading_compact)
                    .onVisibilityStateChanged { _, _, visibilityState ->
                        if (visibilityState == VisibilityState.VISIBLE) {
                            host.listener?.loadMore()
                        }
                    }
                    .addTo(this)
        } else {
            if (data.searchResult.isEmpty()) {
                noResultItem {
                    id("noResult")
                    text(host.stringProvider.getString(CommonStrings.no_result_placeholder))
                }
            } else {
                noResultItem {
                    id("noMoreResult")
                    text(host.stringProvider.getString(CommonStrings.no_more_results))
                }
            }
        }

        buildSearchResultItems(data)
    }

    private fun buildSearchResultItems(data: SearchViewState) {
        var lastDate: Calendar? = null
        eventsById.clear()

        data.searchResult.forEach { eventAndSender ->
            val event = eventAndSender.event
            val eventId = event.eventId ?: return@forEach
            eventsById[eventId] = event

            val eventDate = Calendar.getInstance().apply {
                timeInMillis = event.originServerTs ?: clock.epochMillis()
            }
            if (lastDate?.isSameDayAs(eventDate) != true) {
                DaySeparatorItem_()
                        .id("day-${eventDate.get(Calendar.YEAR)}-${eventDate.get(Calendar.DAY_OF_YEAR)}")
                        .formattedDay(dateFormatter.format(eventDate.timeInMillis, DateFormatKind.TIMELINE_DAY_DIVIDER))
                        .addTo(this)
            }
            lastDate = eventDate

            val timelineEvent = event.toTimelineEvent(eventAndSender.sender?.displayName, eventAndSender.sender?.avatarUrl)
            val params = TimelineItemFactoryParams(
                    event = timelineEvent,
                    partialState = TimelineEventController.PartialState(roomSummary = roomSummary),
                    callback = this,
            )
            val model = timelineItemFactory.create(params)
            model.boldSearchMatches(data.highlights)
            model.id("search-$eventId")
            model.addTo(this)
        }
    }

    private fun Calendar.isSameDayAs(other: Calendar) =
            get(Calendar.YEAR) == other.get(Calendar.YEAR) && get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

    private fun Event.toTimelineEvent(senderDisplayName: String?, senderAvatarUrl: String?): TimelineEvent {
        return TimelineEvent(
                root = this,
                localId = eventId.hashCode().toLong(),
                eventId = eventId.orEmpty(),
                displayIndex = 0,
                senderInfo = SenderInfo(
                        userId = senderId.orEmpty(),
                        displayName = senderDisplayName,
                        isUniqueDisplayName = true,
                        avatarUrl = senderAvatarUrl,
                ),
        )
    }

    /** Bold the query matches inside plain/HTML text items, like the old search list did. */
    private fun VectorEpoxyModel<*>.boldSearchMatches(highlights: List<String>) {
        if (highlights.isEmpty()) return
        val textItem = this as? MessageTextItem_ ?: return
        val message = textItem.message()?.charSequence ?: return
        val plain = message.toString()
        val spannable: Spannable = SpannableString(message)
        var found = false
        highlights.forEach { highlight ->
            var from = 0
            while (from < plain.length) {
                val index = plain.indexOf(highlight, from, ignoreCase = true)
                if (index == -1) break
                spannable.setSpan(StyleSpan(Typeface.BOLD), index, index + highlight.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                found = true
                from = index + 1
            }
        }
        if (found) textItem.message(spannable.toEpoxyCharSequence())
    }

    // Interactions supported by the search screen; everything else no-ops in StubTimelineEventCallback.

    override fun onEventCellClicked(informationData: MessageInformationData, messageContent: Any?, view: View, isRootThreadEvent: Boolean) {
        navigateTo(informationData)
    }

    override fun onEventLongClicked(informationData: MessageInformationData, messageContent: Any?, view: View): Boolean {
        val handled = listener?.onEventLongClicked(informationData) == true
        swallowNextClick = handled
        return handled
    }

    private fun navigateTo(informationData: MessageInformationData) {
        if (swallowNextClick) {
            swallowNextClick = false
            return
        }
        eventsById[informationData.eventId]?.let { listener?.onItemClicked(it) }
    }

    override fun onImageMessageClicked(
            messageImageContent: MessageImageInfoContent,
            mediaData: ImageContentRenderer.Data,
            view: View,
            inMemory: List<AttachmentData>
    ) {
        listener?.onImageMessageClicked(messageImageContent, mediaData, view, inMemory)
    }

    override fun onVideoMessageClicked(messageVideoContent: MessageVideoContent, mediaData: VideoContentRenderer.Data, view: View, inMemory: List<AttachmentData>) {
        listener?.onVideoMessageClicked(messageVideoContent, mediaData, view, inMemory)
    }

    override fun onVoiceControlButtonClicked(eventId: String, messageAudioContent: MessageAudioContent) {
        listener?.onVoiceControlButtonClicked(eventId, messageAudioContent)
    }

    override fun onVoiceWaveformTouchedUp(eventId: String, duration: Int, percentage: Float) {
        listener?.onAudioSeekBarMovedTo(eventId, duration, percentage)
    }

    override fun onVoiceWaveformMovedTo(eventId: String, duration: Int, percentage: Float) {
        listener?.onAudioSeekBarMovedTo(eventId, duration, percentage)
    }

    override fun onAudioSeekBarMovedTo(eventId: String, duration: Int, percentage: Float) {
        listener?.onAudioSeekBarMovedTo(eventId, duration, percentage)
    }

    override fun onUrlLongClicked(url: String): Boolean {
        return listener?.onUrlLongClicked(url) == true
    }

    override fun onAvatarClicked(informationData: MessageInformationData) {
        listener?.onAvatarClicked(informationData.senderId)
    }

    override fun onMemberNameClicked(informationData: MessageInformationData) {
        // The sender header isn't part of the message cell; treat taps there as navigation too.
        navigateTo(informationData)
    }

    override fun onThreadSummaryClicked(eventId: String, isRootThreadEvent: Boolean): Boolean {
        val event = eventsById[eventId] ?: return false
        listener?.onThreadSummaryClicked(event)
        return true
    }

    override fun onRepliedToEventClicked(sourceEventId: String?, targetEventId: String) {
        // Navigate to the replied-to message in the timeline.
        eventsById[sourceEventId]?.roomId?.let {
            listener?.onItemClicked(Event(eventId = targetEventId, roomId = it))
        }
    }
}
