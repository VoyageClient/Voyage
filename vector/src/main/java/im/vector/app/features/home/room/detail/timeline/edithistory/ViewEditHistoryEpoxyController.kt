/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.edithistory

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.VectorEpoxyModel
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericHeaderItem
import im.vector.app.core.ui.list.genericItem
import im.vector.app.core.ui.list.genericLoaderItem
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.helper.StubTimelineEventCallback
import im.vector.app.features.home.room.detail.timeline.helper.TimelineRetrieversFactory
import im.vector.app.features.home.room.detail.timeline.item.MessageAudioItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageFileItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageGalleryItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageVoiceItem_
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.media.AttachmentData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import name.fraser.neil.plaintext.diff_match_patch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.ContentUtils.extractUsefulTextFromReply
import org.matrix.android.sdk.api.util.TextContent
import java.util.Calendar
import javax.inject.Inject

/**
 * Epoxy controller for edit history list. A revision which carries media is rendered by the timeline's
 * own item factory, so it looks and behaves exactly like the message it is a version of.
 */
class ViewEditHistoryEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val eventHtmlRenderer: EventHtmlRenderer,
        private val dateFormatter: VectorDateFormatter,
        private val clock: Clock,
        private val session: Session,
        private val timelineItemFactory: TimelineItemFactory,
        private val timelineRetrieversFactory: TimelineRetrieversFactory,
        private val stubCallback: StubTimelineEventCallback,
) : TypedEpoxyController<ViewEditHistoryViewState>(), TimelineEventController.Callback by stubCallback {

    interface Listener {
        fun onRevisionMediaClicked(mediaData: AttachmentData, view: View)
        fun onRevisionFileClicked(eventId: String, content: MessageWithAttachmentContent)
    }

    var listener: Listener? = null

    private var room: Room? = null

    /** The revisions which carry media, newest first — what the attachment viewer pages over. */
    private val mediaRevisions = mutableListOf<TimelineEvent>()

    /** Must be called once before the first [setData]. */
    fun start(roomId: String, coroutineScope: CoroutineScope) {
        room = session.getRoom(roomId)
        stubCallback.retrievers = timelineRetrieversFactory.create(roomId, coroutineScope)
    }

    fun mediaRevisions(): List<TimelineEvent> = mediaRevisions.toList()

    override fun buildModels(state: ViewEditHistoryViewState) {
        val host = this
        when (state.editList) {
            Uninitialized,
            is Loading -> {
                genericLoaderItem {
                    id("Spinner")
                }
            }
            is Fail -> {
                genericFooterItem {
                    id("failure")
                    text(host.stringProvider.getString(CommonStrings.unknown_error).toEpoxyCharSequence())
                }
            }
            is Success -> {
                state.editList()?.let { renderEvents(it, state.isOriginalAReply) }
            }
        }
    }

    private fun renderEvents(sourceEvents: List<Event>, isOriginalReply: Boolean) {
        val host = this
        val revisions = sourceEvents.map { it.toRevision() }
        mediaRevisions.clear()
        revisions.filterNotNull().filterTo(mediaRevisions) { it.carriesMedia() }
        if (sourceEvents.isEmpty()) {
            genericItem {
                id("footer")
                title(host.stringProvider.getString(CommonStrings.no_message_edits_found).toEpoxyCharSequence())
            }
        } else {
            var lastDate: Calendar? = null
            sourceEvents.forEachIndexed { index, timelineEvent ->

                val evDate = Calendar.getInstance().apply {
                    timeInMillis = timelineEvent.originServerTs
                            ?: clock.epochMillis()
                }
                if (index > 0) {
                    dividerItem { id("sep-${timelineEvent.eventId}") }
                }
                if (lastDate?.get(Calendar.DAY_OF_YEAR) != evDate.get(Calendar.DAY_OF_YEAR)) {
                    // need to display header with day
                    genericHeaderItem {
                        id(evDate.hashCode())
                        text(host.dateFormatter.format(evDate.timeInMillis, DateFormatKind.EDIT_HISTORY_HEADER))
                    }
                }
                lastDate = evDate
                val revision = revisions[index]
                if (revision == null) {
                    val plain = getCorrectContent(timelineEvent, isOriginalReply).let {
                        it.formattedText?.let { html -> eventHtmlRenderer.render(html) } ?: it.text
                    }
                    genericItem {
                        id(timelineEvent.eventId)
                        title(host.dateFormatter.format(timelineEvent.originServerTs, DateFormatKind.EDIT_HISTORY_ROW).toEpoxyCharSequence())
                        description(plain.prepareForDisplay().toEpoxyCharSequence())
                    }
                    return@forEachIndexed
                }
                // This version under the time it was sent. The row is one timeline item — media, caption,
                // sender, links and pills included — so it looks and reacts exactly as it does in the
                // timeline; what changed since the version before it is marked over the text it rendered,
                // and its own time is left to the header.
                genericHeaderItem {
                    id("time-${timelineEvent.eventId}")
                    text(host.dateFormatter.format(timelineEvent.originServerTs, DateFormatKind.EDIT_HISTORY_ROW))
                }
                val previousVersion = sourceEvents.getOrNull(index + 1)
                        ?.let { getCorrectContent(it, isOriginalReply) }
                        ?.let { it.formattedText?.let { html -> eventHtmlRenderer.render(html) } ?: it.text }
                val model = timelineItemFactory.create(TimelineItemFactoryParams(event = revision, callback = host, hideTimestamp = true))
                // The viewer cross-fades in from here rather than morphing the media out of the row, so
                // the row has nothing to stand in for.
                (model as? MessageImageVideoItem_)?.handsOverToViewer(false)
                previousVersion?.let { model.withText(model.markUpChangesSince(it)) }
                model.id("revision-${timelineEvent.eventId}").addTo(this)
            }
        }
    }

    private fun getCorrectContent(event: Event, isOriginalReply: Boolean): TextContent {
        val topContent = event.getClearContent()
        val clearContent = topContent.toModel<MessageTextContent>()
        val newContentMap = clearContent?.newContent
        val newContent = newContentMap?.toModel<MessageTextContent>()
        val effectiveMap = newContentMap ?: topContent
        val effectiveModel = newContent ?: clearContent

        // For media messages the body holds the filename when the MSC2530 `filename` field is
        // absent; that isn't user-facing, so show nothing to keep caption add/remove readable.
        if (effectiveModel?.msgType in MEDIA_MSG_TYPES && effectiveMap?.containsKey("filename") != true) {
            return TextContent("")
        }

        if (isOriginalReply) {
            return TextContent(extractUsefulTextFromReply(newContent?.body ?: clearContent?.body ?: ""))
        }
        return TextContent(newContent?.body ?: clearContent?.body ?: "", newContent?.formattedBody ?: clearContent?.formattedBody)
    }

    /**
     * The revision as the timeline would see it: an event holding what this version put in the message,
     * or null when there is no message content to render.
     */
    private fun Event.toRevision(): TimelineEvent? {
        val eventId = eventId ?: return null
        val type = getClearType()
        val content = (getClearContent()?.get("m.new_content") as? Map<*, *>)
                ?.let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any>) }
                ?: getClearContent()
                ?: return null
        val messageContent = if (type == EventType.STICKER) content.toModel<MessageStickerContent>() else content.toModel<MessageContent>()
        messageContent ?: return null
        val root = Event(
                type = type,
                eventId = eventId,
                roomId = roomId,
                senderId = senderId,
                originServerTs = originServerTs,
                content = content,
        ).also { it.sendState = SendState.SYNCED }
        val member = senderId?.let { room?.membershipService()?.getRoomMember(it) }
        return TimelineEvent(
                root = root,
                localId = eventId.hashCode().toLong(),
                eventId = eventId,
                displayIndex = 0,
                senderInfo = SenderInfo(
                        userId = senderId.orEmpty(),
                        displayName = member?.displayName,
                        isUniqueDisplayName = true,
                        avatarUrl = member?.avatarUrl,
                ),
        )
    }

    /** The text the timeline rendered for this row — links, pills, emotes and all. */
    private fun VectorEpoxyModel<*>.renderedText(): CharSequence? = when (this) {
        is MessageTextItem_ -> message()
        is MessageImageVideoItem_ -> caption()
        is MessageGalleryItem_ -> caption()
        is MessageFileItem_ -> caption()
        is MessageAudioItem_ -> caption()
        is MessageVoiceItem_ -> caption()
        else -> null
    }?.charSequence

    /**
     * The row's own text with the previous version's differences marked on it: what is gone struck out
     * in red, what arrived in green. Built over what the timeline rendered rather than over the raw body,
     * so everything it put there survives being diffed.
     */
    private fun VectorEpoxyModel<*>.markUpChangesSince(previous: CharSequence): CharSequence {
        val current = renderedText() ?: ""
        val dmp = diff_match_patch()
        val diff = dmp.diff_main(previous.toString(), current.toString())
        dmp.diff_cleanupSemantic(diff)
        val marked = SpannableStringBuilder()
        var at = 0
        diff.forEach { change ->
            when (change.operation) {
                diff_match_patch.Operation.DELETE -> {
                    // Gone from this version, so it can only be spelled out as plain text.
                    val from = marked.length
                    marked.append(change.text.replace("\n", " "))
                    marked.setSpan(ForegroundColorSpan(colorProvider.getColorFromAttribute(colorError)), from, marked.length, SPAN_FLAGS)
                    marked.setSpan(StrikethroughSpan(), from, marked.length, SPAN_FLAGS)
                }
                diff_match_patch.Operation.INSERT -> {
                    val from = marked.length
                    marked.append(current.subSequence(at, at + change.text.length))
                    marked.setSpan(ForegroundColorSpan(colorProvider.getColor(colorAdded)), from, marked.length, SPAN_FLAGS)
                    at += change.text.length
                }
                else -> {
                    marked.append(current.subSequence(at, at + change.text.length))
                    at += change.text.length
                }
            }
        }
        return marked
    }

    /** What the row shows: this version's text, marked up with what changed since the one before. */
    private fun VectorEpoxyModel<*>.withText(diff: CharSequence): VectorEpoxyModel<*> {
        // A text item hands its body to the markwon plugins as a Spanned, whatever it was made of.
        val text = diff.takeIf { it.isNotEmpty() }
                ?.let { if (it is Spanned) it else SpannableString(it) }
                ?.toEpoxyCharSequence()
        when (this) {
            is MessageTextItem_ -> message(text)
            is MessageImageVideoItem_ -> caption(text)
            is MessageGalleryItem_ -> caption(text)
            is MessageFileItem_ -> caption(text)
            is MessageAudioItem_ -> caption(text)
            is MessageVoiceItem_ -> caption(text)
        }
        return this
    }

    private fun TimelineEvent.carriesMedia(): Boolean {
        val content = if (root.getClearType() == EventType.STICKER) {
            root.getClearContent().toModel<MessageStickerContent>()
        } else {
            root.getClearContent().toModel<MessageContent>()
        }
        return content is MessageWithAttachmentContent || content is MessageGalleryContent
    }

    override fun onImageMessageClicked(
            messageImageContent: MessageImageInfoContent,
            mediaData: ImageContentRenderer.Data,
            view: View,
            inMemory: List<AttachmentData>
    ) {
        listener?.onRevisionMediaClicked(mediaData, view)
    }

    override fun onVideoMessageClicked(
            messageVideoContent: MessageVideoContent,
            mediaData: VideoContentRenderer.Data,
            view: View,
            inMemory: List<AttachmentData>
    ) {
        listener?.onRevisionMediaClicked(mediaData, view)
    }

    // What a file or audio revision offers instead of a viewer: a copy of that version of the file.
    override fun onEventCellClicked(informationData: MessageInformationData, messageContent: Any?, view: View, isRootThreadEvent: Boolean) {
        (messageContent as? MessageWithAttachmentContent)?.let { listener?.onRevisionFileClicked(informationData.eventId, it) }
    }

    companion object {
        private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        private val colorError = com.google.android.material.R.attr.colorError
        private val colorAdded = im.vector.lib.ui.styles.R.color.palette_element_green

        private val MEDIA_MSG_TYPES = setOf(
                MessageType.MSGTYPE_IMAGE,
                MessageType.MSGTYPE_VIDEO,
                MessageType.MSGTYPE_AUDIO,
                MessageType.MSGTYPE_FILE,
        )
    }
}
