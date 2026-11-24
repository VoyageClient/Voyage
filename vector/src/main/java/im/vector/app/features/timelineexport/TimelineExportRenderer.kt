/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.R
import im.vector.app.core.epoxy.DividerItem_
import im.vector.app.core.glide.SvgPictureCache
import im.vector.app.core.ui.list.GenericHeaderItem_
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.edithistory.EditDiffMarkup
import im.vector.app.features.home.room.detail.timeline.factory.PollOptionViewStateFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemPresentation
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageLocationItem
import im.vector.app.features.home.room.detail.timeline.item.BasedMergedItem
import im.vector.app.features.home.room.detail.timeline.item.BindingOptions
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem_
import im.vector.app.features.home.room.detail.timeline.item.E2EDecoration
import im.vector.app.features.home.room.detail.timeline.item.ForwardedInfoData
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageAudioItem
import im.vector.app.features.home.room.detail.timeline.item.MessageFileItem
import im.vector.app.features.home.room.detail.timeline.item.MessageGalleryItem
import im.vector.app.features.home.room.detail.timeline.item.MessageImageVideoItem
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem
import im.vector.app.features.home.room.detail.timeline.item.MessageVoiceItem
import im.vector.app.features.home.room.detail.timeline.item.PollItem
import im.vector.app.features.home.room.detail.timeline.item.PollResponseData
import im.vector.app.features.home.room.detail.timeline.item.PollVoteSummaryData
import im.vector.app.features.home.room.detail.timeline.item.ReactionInfoData
import im.vector.app.features.home.room.detail.timeline.item.ReactionsSummaryData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem_
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContent
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewContentBuilder
import im.vector.app.features.home.room.detail.timeline.preview.MessagePreviewView
import im.vector.app.features.home.room.detail.timeline.readreceipts.DisplayReadReceiptItem_
import im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope
import im.vector.app.features.home.room.detail.timeline.reply.InReplyToView
import im.vector.app.features.home.room.detail.timeline.style.TimelineLayoutSettings
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayoutFactory
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlUiState
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlView
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.MediaContentRevealManager
import im.vector.app.features.themes.BubbleThemeUtils
import im.vector.lib.animatedimage.AnimatedImageReader
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.threads.ThreadDetails
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class TimelineExportRenderer @Inject constructor(
        private val components: TimelineExportRenderComponent.Factory,
) {
    class Document(
            var snapshot: TimelineExportSnapshot,
            val directory: File,
            val logicalWidth: Int,
            val density: Float,
    ) {
        lateinit var context: TimelineExportContext
        val rows = mutableListOf<Row>()
        var height: Int = 0
        var models: List<EpoxyModel<*>> = emptyList()
        val images = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(24L * 1024 * 1024).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.width * value.height * 4
        }
        val animationTimes = HashMap<String, Int>()
        val failedImages = Collections.synchronizedSet(mutableSetOf<String>())
        val assetsByPath = snapshot.assets.associateBy { it.path }
        val eventsById = snapshot.events.associateBy { it.id }
        val peopleById = (listOfNotNull(snapshot.roomPerson) + snapshot.mentionedPeople + snapshot.events.flatMap { event -> listOf(event.person) + event.receipts.map { it.person } + event.reactions.flatMap { it.people } }).associateBy { it.id }
        val inlineByEvent = snapshot.events.filter { it.html.contains("<img", ignoreCase = true) }.associate { event -> event.id to Jsoup.parseBodyFragment(event.html).select("img[src]").toList() }
        val mentionIdsByEvent = snapshot.events.associate { it.id to exportMentionIds(it.html) }
        val topicMentionIds = exportMentionIds(snapshot.topicFormatted.orEmpty())
        val topicImages = Jsoup.parseBodyFragment(snapshot.topicFormatted.orEmpty()).select("img[src]").toList()
        val inlineImages = (inlineByEvent.values.flatten() + topicImages).associateBy { it.attr("src") }
        val avatarPaths = peopleById.values.mapNotNull { it.avatar }.toSet()
        val fullMediaPaths = snapshot.events.flatMap { event -> listOfNotNull(event.attachment, event.mapImage, event.audioBackdrop) + event.extraAttachments.mapNotNull { it.path } + event.linkPreviews.mapNotNull { it.image } }.toSet()
        val inlineSizes = HashMap<String, Pair<Int, Int>>()
        val blocks = ExportBlocks.build(snapshot.events, snapshot.options.timeZoneId)
        private val recycled = mutableListOf<Pair<Pair<Class<*>, Int>, EpoxyViewHolder>>()
        private var released = false
        val revealedMedia = MediaContentRevealManager()
        var sheet: Sheet? = null
        var createdViews = 0
            private set
        var reusedViews = 0
            private set
        private val bound = object : LruCache<Int, EpoxyViewHolder>((Runtime.getRuntime().maxMemory() / (4 * 1024 * 1024)).toInt().coerceIn(16, 32)) {
            override fun sizeOf(key: Int, value: EpoxyViewHolder): Int = when (models.getOrNull(key)) {
                is MessageGalleryItem -> 8
                is MessageImageVideoItem -> 4
                else -> 1
            }
            override fun entryRemoved(evicted: Boolean, key: Int, old: EpoxyViewHolder, new: EpoxyViewHolder?) {
                val kind = old.model.let { it.javaClass to it.layout }
                old.unbind()
                context.forgetViews(old.itemView)
                if (evicted && !released && recycled.size < 4) recycled.add(kind to old)
            }
        }
        fun px(value: Int) = (value * density).roundToInt()
        fun bitmap(path: String?): Bitmap? = path?.let { images.get(it) }
        fun boundaries(outputWidth: Int): List<Int> {
            val scale = outputWidth.toDouble() / logicalWidth
            return rows.indices.filter { index ->
                val row = rows[index]
                row.events.isNotEmpty() && rows.getOrNull(index + 1)?.events?.firstOrNull()?.id != row.events.first().id
            }.map { ((rows[it].top + rows[it].height) * scale).roundToInt() }
        }
        fun clearBoundRows() { bound.evictAll(); recycled.clear() }
        fun cachedRowCount(): Int = bound.snapshot().size
        fun release() {
            context.onResourceChanged = null
            released = true
            Timber.d("ExportTrace document release cachedRows=%d cachedImageBytes=%d createdViews=%d reusedViews=%d", bound.size(), images.size(), createdViews, reusedViews)
            bound.evictAll(); recycled.clear(); images.evictAll()
        }
        fun invalidateImages() { context.refreshImages() }
        lateinit var configureView: (Int, View) -> Unit
        fun boundView(index: Int): View = TimelineRenderingScope.render(context) {
            bound.get(index)?.let { return@render it.itemView }
            val model = models[index]
            val kind = model.javaClass to model.layout
            val recycledIndex = recycled.indexOfFirst { it.first == kind }
            val holder = if (recycledIndex >= 0) {
                reusedViews++
                recycled.removeAt(recycledIndex).second
            } else {
                createdViews++
                val parent = FrameLayout(context)
                EpoxyViewHolder(parent, model.buildView(parent), false)
            }
            holder.bind(model, null, emptyList(), index)
            configureView(index, holder.itemView)
            bound.put(index, holder)
            holder.itemView
        }
        fun remeasureDirtyRows(): Boolean {
            var changed = false
            bound.snapshot().forEach { (index, holder) ->
                if (index < rows.size && holder.itemView.isLayoutRequested) {
                    configureView(index, holder.itemView)
                    val height = holder.itemView.measuredHeight
                    if (height != rows[index].height) { rows[index] = rows[index].copy(height = height); changed = true }
                }
            }
            if (changed) {
                updateRowPositions()
                Timber.d("ExportTrace native row geometry updated height=%d", height)
            }
            return changed
        }
        fun updateRowPositions() {
            var top = 0
            rows.indices.forEach { index -> rows[index] = rows[index].copy(top = top); top += rows[index].height }
            height = top
        }
        fun visibleRows(top: Int, bottom: Int): List<Pair<Int, Row>> {
            var low = 0
            var high = rows.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (rows[middle].top + rows[middle].height <= top) low = middle + 1 else high = middle
            }
            val result = mutableListOf<Pair<Int, Row>>()
            while (low < rows.size && rows[low].top < bottom) { result.add(low to rows[low]); low++ }
            return result
        }
    }

    data class Row(val top: Int, val height: Int, val events: List<ExportEvent> = emptyList(), val groupId: String? = null, val label: String? = null, val implicit: Boolean = false)

    /** The contents of one of the timeline's bottom sheets, laid out with the sheet's own items. */
    sealed interface Sheet {
        /** [snapshot]'s events are one message's revisions, newest first, as the edit history sheet lists them. */
        object Revisions : Sheet

        /** Who has read up to [snapshot]'s events, as the read receipts sheet lists them. */
        object Receipts : Sheet
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun layout(context: Context, snapshot: TimelineExportSnapshot, directory: File, previousDocument: Document? = null, reuseMeasurements: Boolean = false,
                       sheet: Sheet? = null, progress: (Int, Int) -> Unit = { _, _ -> }): Document = withContext(Dispatchers.Default) {
        val revisions = sheet == Sheet.Revisions
        val startedAt = SystemClock.elapsedRealtime()
        val phase = AtomicReference("models")
        val currentRow = AtomicInteger(-1)
        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                delay(5_000)
                Timber.w("ExportTrace layout waiting phase=%s row=%d events=%d peopleAvatar=%s roomAvatar=%s heapUsed=%d elapsedMs=%d main=%s",
                        phase.get(), currentRow.get(), snapshot.events.size, snapshot.options.peopleAvatarStyle, snapshot.options.roomAvatarStyle,
                        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), SystemClock.elapsedRealtime() - startedAt,
                        Looper.getMainLooper().thread.stackTrace.take(14).joinToString(" <- "))
            }
        }
        try {
            val component = components.create()
            val factory = component.items()
            val layouts = component.layouts()
            val avatars = component.avatars()
            val imageRenderer = component.images()
            val density = context.resources.displayMetrics.density
            val document = Document(snapshot, directory, (snapshot.options.layoutWidth * density).roundToInt(), density).also { it.sheet = sheet }
            val people = document.peopleById
            document.context = TimelineExportContext(context, snapshot.options, people, document.eventsById, document::bitmap, avatars, directory, document.assetsByPath.keys)
            withContext(TimelineRenderingScope.element(document.context)) {
                previousDocument?.images?.snapshot()?.forEach { (path, bitmap) -> document.images.put(path, bitmap) }
                val o = snapshot.options
                val timeZone = java.util.TimeZone.getTimeZone(o.timeZoneId)
                val shortSide = if (o.format == "HTML") document.logicalWidth
                        else (context.resources.configuration.smallestScreenWidthDp * density).roundToInt().takeIf { it > 0 } ?: document.logicalWidth
                component.mediaSizes().setPresentation(document.logicalWidth, shortSide,
                        maxOf(document.logicalWidth, context.resources.displayMetrics.heightPixels, context.resources.displayMetrics.widthPixels), o.bubbleStyle() != "none")
                val models = mutableListOf<EpoxyModel<*>>()
                progress(0, 100)
                val descriptors = mutableListOf<Row>()
                fun add(model: EpoxyModel<*>, events: List<ExportEvent> = emptyList(), groupId: String? = null, label: String? = null, implicit: Boolean = false) {
                    model.id("export-${models.size}")
                    models.add(model)
                    descriptors.add(Row(0, 0, events, groupId, label, implicit))
                }
                fun addReceipts(events: List<ExportEvent>) {
                    val receipts = receiptsOf(events)
                    if (receipts.isEmpty()) return
                    add(ReadReceiptsItem_().eventId(events.last().id).readReceipts(receipts.map {
                        ReadReceiptData(it.person.id, it.person.avatar, it.person.name, it.timestamp)
                    }).avatarRenderer(avatars).clickListener {}, events, implicit = true)
                }
                if (sheet == Sheet.Receipts) receiptsOf(snapshot.events).forEach { receipt ->
                    add(DisplayReadReceiptItem_().matrixItem(MatrixItem.UserItem(receipt.person.id, receipt.person.name, receipt.person.avatar))
                            .avatarRenderer(avatars).timestamp(component.dates().formatFullDate(receipt.timestamp, timeZone, o.twelveHourTime)))
                }
                if (o.roomHeader) {
                    val topic = SpannableStringBuilder(component.topics().render(snapshot.topic, snapshot.topicFormatted, null, null)).apply { offlineSpans(document, this) }
                    add(ExportRoomHeaderItem_().snapshot(snapshot).topic(topic.toEpoxyCharSequence()).avatarRenderer(avatars), label = snapshot.roomName)
                }
                var lastDate: String? = null
                val timelineEvents = snapshot.events.mapIndexed { index, event -> event.timelineEvent(index.toLong()) }
                val indices = snapshot.events.withIndex().associate { it.value.id to it.index }
                var processedEvents = 0
                var revisionDay: String? = null
                for (block in if (sheet == Sheet.Receipts) emptyList() else document.blocks) {
                    val date = ExportBlocks.day(block.events.first().timestamp, o.timeZoneId)
                    if (o.dates && date != lastDate) add(DaySeparatorItem_().formattedDay(component.dates().formatDayDivider(block.events.first().timestamp, timeZone)), label = date)
                    lastDate = date
                    val expanded = o.expandAll || block.groupId in o.expandedGroups
                    if (block.groupId != null) {
                        val title = when (block.events.first().group) {
                            EventType.STATE_ROOM_MEMBER -> CommonPlurals.membership_changes
                            EventType.STATE_ROOM_SERVER_ACL -> CommonPlurals.notice_room_server_acl_changes
                            "redacted" -> CommonPlurals.room_redacted_messages
                            else -> CommonPlurals.merged_hidden_events
                        }
                        val data = block.events.mapIndexed { index, event -> BasedMergedItem.Data(null, index.toLong(), event.id, event.person.id, event.person.name, event.person.avatar, snapshot.isDirect) }
                        add(MergedSimilarEventsItem_().leftGuideline(document.px(38)).attributes(MergedSimilarEventsItem.Attributes(title, !expanded, data, avatars, {})),
                                events = if (expanded) emptyList() else block.events, groupId = block.groupId)
                        if (!expanded) addReceipts(block.events)
                    }
                    if (block.groupId == null || expanded) block.events.forEach { event ->
                        coroutineContext.ensureActive()
                        val eventIndex = indices.getValue(event.id)
                        val older = snapshot.events.getOrNull(eventIndex - 1)
                        currentRow.set(eventIndex)
                        val timelineEvent = timelineEvents[eventIndex]
                        val content = timelineEvent.root.content.orEmpty()
                        if (revisions) {
                            if (revisionDay != null) add(DividerItem_())
                            if (date != revisionDay) add(GenericHeaderItem_().text(component.dates().formatDayDivider(event.timestamp, timeZone)))
                            revisionDay = date
                            add(GenericHeaderItem_().text(component.dates().formatMessageTime(event.timestamp, timeZone, o.twelveHourTime)))
                        }
                        val layoutParams = if (revisions) TimelineItemFactoryParams(timelineEvent) else TimelineItemFactoryParams(timelineEvent,
                                nextDisplayableEvent = timelineEvents.getOrNull(eventIndex - 1), prevDisplayableEvent = timelineEvents.getOrNull(eventIndex + 1))
                        val layout = layouts.create(layoutParams, TimelineMessageLayoutFactory.Presentation(
                                layout = when (o.bubbleStyle()) {
                                    "both", "start" -> TimelineLayoutSettings.SC_BUBBLE
                                    else -> TimelineLayoutSettings.MODERN
                                },
                                timestamps = o.timestamps, bubbles = BubbleThemeUtils(document.context),
                                sentByMe = event.sentByMe,
                                avatars = o.avatars != "HIDE", timeZone = timeZone, forceRealBubble = event.replyTo != null || event.replyMissing || event.forwardedSenderName != null))
                        val information = MessageInformationData(
                                eventId = event.id, senderId = event.person.id, sendState = SendState.SYNCED, ageLocalTS = null,
                                originServerTs = event.timestamp, avatarUrl = event.person.avatar, memberName = event.person.name,
                                time = if (o.timestamps) component.dates().formatMessageTime(event.timestamp, timeZone, o.twelveHourTime) else "", messageLayout = layout,
                                reactionsSummary = ReactionsSummaryData(event.reactions.map { ReactionInfoData(it.key, it.count, it.addedByMe) }, true, false),
                                pollResponseAggregatedSummary = event.poll?.let {
                                    PollResponseData(event.pollMyVote, event.pollVotes.mapValues { (_, count) -> PollVoteSummaryData(count, if (event.pollTotalVotes == 0) 0.0 else count * 100.0 / event.pollTotalVotes) },
                                            event.pollTotalVotes, event.pollVotes.values.maxOrNull() ?: 0, event.pollClosed)
                                },
                                hasBeenEdited = (event.edited || event.history.isNotEmpty()) && o.visible("MESSAGE_MARKERS"), sentByMe = event.sentByMe,
                                isFirstFromThisSender = revisions || older?.person?.id != event.person.id || ExportBlocks.day(older.timestamp, o.timeZoneId) != date, isDirect = snapshot.isDirect,
                                isMediaSpoiler = event.mediaHidden, hideMediaReactions = event.hideMediaReactions, hideAvatars = o.avatars == "HIDE", messageType = content["msgtype"] as? String,
                                isStatic = true, isPgp = event.isPgp, isRevealedRedaction = event.isRevealedRedaction,
                                e2eDecoration = E2EDecoration.values().firstOrNull { it.name == event.e2eDecoration } ?: E2EDecoration.NONE,
                                forwardedInfo = if (event.forwardedSenderName != null || event.forwardedRoom != null || event.forwardedDate != null) {
                                    ForwardedInfoData(event.forwardedSenderId, event.forwardedSenderName, event.forwardedRoom, event.forwardedDate, null)
                                } else {
                                    null
                                },
                        )
                        val model = factory.create(TimelineItemFactoryParams(timelineEvent, preserveSenderInfo = true,
                                partialState = TimelineEventController.PartialState(rootThreadEventId = if (snapshot.isThread) "export-thread" else null),
                                presentation = TimelineItemPresentation(information, if (event.notice) event.noticeText(people, !document.context.uncoloredNames, document::bitmap) else null)))
                        prepareModel(document, event, model)
                        add(model, listOf(event))
                        addReceipts(listOf(event))
                    }
                    processedEvents += block.events.size
                    progress(processedEvents * 40 / snapshot.events.size.coerceAtLeast(1), 100)
                }
                if (revisions) markUpRevisions(document, models, descriptors)
                val replyTargets = snapshot.events.mapNotNull { it.replyTo }.toSet()
                val previews = if (replyTargets.isEmpty()) emptyMap() else {
                    val builder = component.previews()
                    val res = document.context.resources
                    val width = res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.reply_thumbnail_max_width)
                    val height = res.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.reply_thumbnail_height)
                    replyTargets.mapNotNull { id -> document.eventsById[id]?.let { event ->
                        val nativeEvent = timelineEvents[indices.getValue(id)]
                        val preview = if (event.notice) builder.notice(nativeEvent, event.noticeText(people, !document.context.uncoloredNames, document::bitmap))
                        else builder.build(nativeEvent, MessagePreviewContentBuilder.Options(
                                width, height, width, height, isThreadTimeline = snapshot.isThread,
                                senderItem = MatrixItem.UserItem(event.person.id, event.person.name, event.person.avatar)))
                        fun offline(value: EpoxyCharSequence?) = value?.charSequence?.let {
                            SpannableStringBuilder(it).apply { offlineSpans(document, this) }.toEpoxyCharSequence()
                        }
                        id to preview.copy(text = offline(preview.text), captionlessText = offline(preview.captionlessText))
                    } }.toMap()
                }
                val previewRenderers = if (replyTargets.isEmpty()) null else MessagePreviewView.Renderers(
                        imageRenderer, component.richBodies(), arrayOf(component.pills().create(null), object : EventHtmlRenderer.PostProcessor {
                            override fun afterRender(renderedText: Spannable) = offlineSpans(document, renderedText)
                        }), component.pins())
                document.configureView = { index, view ->
                    TimelineRenderingScope.render(document.context) {
                        val event = descriptors[index].events.firstOrNull()
                        if (event != null && event.attachment == null && (event.attachmentMime?.startsWith("image/") == true || event.attachmentMime?.startsWith("video/") == true)) {
                            view.findViewById<TextView>(R.id.messageMediaShowButton)?.apply { setText(CommonStrings.export_media_omitted); isVisible = true }
                            view.findViewById<View>(R.id.messageMediaHiddenScrim)?.isVisible = true
                        }
                        if (event != null && descriptors[index].groupId == null) {
                            event.linkPreviews.firstOrNull()?.let { preview ->
                                view.findViewById<PreviewUrlView>(R.id.messageUrlPreview)?.let { card ->
                                    card.render(PreviewUrlUiState.Data(event.id, preview.url,
                                            PreviewUrlData(preview.url, null, preview.title, preview.description,
                                                    preview.image, preview.imageWidth, preview.imageHeight)), imageRenderer)
                                    card.findViewById<View>(R.id.url_preview_close)?.isVisible = false
                                }
                            }
                            val reply = view.findViewById<InReplyToView>(R.id.inReplyToContainer)
                            if (event.replyMissing) reply?.renderStaticStatus(context.getString(CommonStrings.export_message_not_exported))
                            else event.replyTo?.let { id -> document.eventsById[id]?.let { target ->
                                previews[id]?.let { preview ->
                                    val layout = (models[index] as? AbsMessageItem<*>)?.attributes?.informationData?.messageLayout
                                            ?: TimelineMessageLayout.Default(false, false, false)
                                    reply?.renderStatic(target.person.name, preview, target.person.color, !document.context.uncoloredNames, layout,
                                            previewRenderers!!, target.mediaHidden, false, repliedToEventId = id)
                                }
                            } }
                        }
                        measure(view, document.logicalWidth)
                        dispatchPreDraw(view)
                        if (view.isLayoutRequested) measure(view, document.logicalWidth)
                    }
                }
                document.models = models
                document.context.onResourceChanged = { document.remeasureDirtyRows() }
                var top = 0
                phase.set("measure")
                Timber.d("ExportTrace measure start rows=%d reuse=%s", descriptors.size, reuseMeasurements)
                progress(40, 100)
                try {
                    withContext(Dispatchers.Main) {
                        descriptors.forEachIndexed { index, descriptor ->
                            coroutineContext.ensureActive()
                            currentRow.set(index)
                            val cached = previousDocument?.rows?.getOrNull(index)?.takeIf { reuseMeasurements && previousDocument.rows.size == descriptors.size }
                            val rowHeight = cached?.height ?: run {
                                val view = document.boundView(index)
                                val media = models[index] as? MessageImageVideoItem
                                if (media != null && ((media.mediaData.width ?: 0) <= 0 || (media.mediaData.height ?: 0) <= 0)) {
                                    phase.set("media")
                                    document.context.awaitMedia(listOf(view))
                                    phase.set("measure")
                                    document.configureView(index, view)
                                }
                                view.measuredHeight
                            }
                            document.rows.add(descriptor.copy(top = top, height = rowHeight))
                            val next = top.toLong() + rowHeight
                            require(next < Int.MAX_VALUE)
                            top = next.toInt()
                            if (index % 8 == 7 || index == descriptors.lastIndex) progress(40 + (index + 1) * 60 / descriptors.size.coerceAtLeast(1), 100)
                            if (index % 4 == 3) kotlinx.coroutines.yield()
                        }
                    }
                } catch (failure: Throwable) {
                    withContext(NonCancellable + Dispatchers.Main) { document.release() }
                    throw failure
                }
                withContext(Dispatchers.Main) { document.updateRowPositions() }
                Timber.d("ExportTrace layout rows=%d reused=%s createdViews=%d reusedViews=%d heapUsed=%d elapsedMs=%d height=%d", document.rows.size, reuseMeasurements,
                        document.createdViews, document.reusedViews, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), SystemClock.elapsedRealtime() - startedAt, document.height)
                document
            }
        } finally { watchdog.cancel() }
    }

    /** The latest receipt of each reader of [events], newest first, as their receipts row shows them. */
    fun receiptsOf(events: List<ExportEvent>): List<ExportReceipt> = events.flatMap { it.receipts }.groupBy { it.person.id }
            .mapNotNull { (_, values) -> values.maxByOrNull { it.timestamp } }.sortedByDescending { it.timestamp }

    // Each revision diffed against the older one listed after it, over what both rows render.
    private fun markUpRevisions(document: Document, models: List<EpoxyModel<*>>, descriptors: List<Row>) {
        val byEvent = descriptors.indices.filter { descriptors[it].events.size == 1 }.associateBy { descriptors[it].events.single().id }
        fun text(model: EpoxyModel<*>?): CharSequence? = when (model) {
            is MessageTextItem -> model.message
            is MessageImageVideoItem -> model.caption
            is MessageGalleryItem -> model.caption
            is MessageFileItem -> model.caption
            is MessageAudioItem -> model.caption
            is MessageVoiceItem -> model.caption
            else -> null
        }?.charSequence
        val rendered = document.snapshot.events.map { event -> byEvent[event.id]?.let { text(models[it]) } }
        val removed = document.context.resolveColor(com.google.android.material.R.attr.colorError)
        val added = androidx.core.content.ContextCompat.getColor(document.context, im.vector.lib.ui.styles.R.color.palette_element_green)
        document.snapshot.events.forEachIndexed { index, event ->
            val model = byEvent[event.id]?.let(models::get) ?: return@forEachIndexed
            val current = rendered[index] ?: return@forEachIndexed
            val previous = rendered.getOrNull(index + 1) ?: return@forEachIndexed
            val marked = SpannableStringBuilder(EditDiffMarkup.markUpChanges(previous, current, removed, added)).toEpoxyCharSequence()
            when (model) {
                is MessageTextItem -> model.message = marked
                is MessageImageVideoItem -> model.caption = marked
                is MessageGalleryItem -> model.caption = marked
                is MessageFileItem -> model.caption = marked
                is MessageAudioItem -> model.caption = marked
                is MessageVoiceItem -> model.caption = marked
            }
        }
    }

    private fun prepareModel(document: Document, event: ExportEvent, model: EpoxyModel<*>) {
        val processor = object : EventHtmlRenderer.PostProcessor {
            override fun afterRender(renderedText: Spannable) { offlineSpans(document, renderedText) }
        }
        if (model is MessageTextItem) {
            model.message = model.message?.charSequence?.let { SpannableStringBuilder(it).apply { offlineSpans(document, this) }.toEpoxyCharSequence() }
            model.bindingOptions = BindingOptions(canUseTextFuture = false)
            model.htmlPostProcessors = (model.htmlPostProcessors ?: emptyArray()) + processor
            model.searchForPills = false
        }
        fun caption(value: EpoxyCharSequence?) = value?.charSequence?.let {
            SpannableStringBuilder(it).apply { offlineSpans(document, this) }.toEpoxyCharSequence()
        }
        when (model) {
            is MessageImageVideoItem -> model.caption = caption(model.caption)
            is MessageGalleryItem -> model.caption = caption(model.caption)
            is MessageAudioItem -> model.caption = caption(model.caption)
            is MessageVoiceItem -> model.caption = caption(model.caption)
            is MessageFileItem -> model.caption = caption(model.caption)
        }
        if (model is MessageImageVideoItem) {
            model.mediaRevealManager = document.revealedMedia
            model.mediaData = model.mediaData.copy(url = model.mediaData.url?.takeIf { path -> document.snapshot.assets.any { it.path == path } } ?: event.attachment, allowNonMxcUrls = true)
            model.hideMedia = event.mediaHidden
            model.handsOverToViewer = false
        }
        if (model is AbsMessageItem<*>) {
            model.attributes = model.attributes.copy(autoplayAnimatedImages = true,
                    areThreadMessagesEnabled = event.threadCount > 0,
                    threadDetails = if (event.threadCount > 0) ThreadDetails(isRootThread = true, numberOfThreads = event.threadCount) else null,
                    threadSummaryFormatted = "", decryptionErrorMessage = "")
        }
        if (model is AbsMessageLocationItem<*>) model.locationUrl = event.mapImage.orEmpty()
        if (model is PollItem) {
            val poll = (event.raw["content"] as? Map<*, *>)?.filterKeys { it is String }?.mapKeys { it.key as String }?.filterValues { it != null }?.mapValues { it.value!! }?.toModel<MessagePollContent>()
            val summary = model.attributes.informationData.pollResponseAggregatedSummary
            val factory = PollOptionViewStateFactory()
            model.optionViewStates = when {
                event.pollClosed -> factory.createPollEndedOptions(poll?.getBestPollCreationInfo(), summary)
                event.pollResultsVisible -> factory.createPollVotedOptions(poll?.getBestPollCreationInfo(), summary)
                else -> factory.createPollUndisclosedOptions(poll?.getBestPollCreationInfo(), summary)
            }
            model.canVote = false
            model.votesStatus = when {
                !event.pollResultsVisible -> document.context.getString(CommonStrings.poll_undisclosed_not_ended)
                event.pollClosed -> document.context.resources.getQuantityString(CommonPlurals.poll_total_vote_count_after_ended, event.pollTotalVotes, event.pollTotalVotes)
                event.pollTotalVotes == 0 -> document.context.getString(CommonStrings.poll_no_votes_cast)
                else -> document.context.resources.getQuantityString(CommonPlurals.poll_total_vote_count_before_ended_and_voted, event.pollTotalVotes, event.pollTotalVotes)
            }
        }
        if (model is MessageFileItem) model.izDownloaded = event.attachment != null
        if (model is MessageAudioItem) {
            model.localSource = null
            model.localSourceProvider = null
            model.coverArtHashLoader = null
            model.staticDetails = {
                val metadata = model.audioMetadata
                val backdrop = metadata?.coverArtBlurhash?.let(AudioDetails::coverArtBackdrop)
                        ?: document.bitmap(event.audioBackdrop)
                AudioDetails.Details(event.audioTitle ?: metadata?.title,
                        event.audioArtist ?: metadata?.artist, event.audioAlbum ?: metadata?.album, null, backdrop)
            }
        }
    }

    private fun offlineSpans(document: Document, text: Spannable) {
        fun replace(span: Any, path: String, emote: Boolean) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) return
            val replacement = if (emote) {
                val source = span as? MatrixEmoteSpan
                val tag = document.inlineImages[path]
                EmoteImageSpan(source?.shortcode.orEmpty(), path, source?.body ?: tag?.attr("alt"), null) { document.bitmap(path) }
            } else {
                val size = document.inlineSizes.getOrPut(path) {
                    val tag = document.inlineImages[path]
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(File(document.directory, path).absolutePath, bounds)
                    val width = tag?.attr("width")?.toIntOrNull()?.takeIf { it > 0 } ?: bounds.outWidth.takeIf { it > 0 } ?: 24
                    val height = tag?.attr("height")?.toIntOrNull()?.takeIf { it > 0 } ?: bounds.outHeight.takeIf { it > 0 } ?: 24
                    val scale = minOf(1f, (document.snapshot.options.layoutWidth - 80).coerceAtLeast(24).toFloat() / width)
                    document.px((width * scale).roundToInt().coerceAtLeast(1)) to document.px((height * scale).roundToInt().coerceAtLeast(1))
                }
                ExportImageSpan(size.first, size.second) { document.bitmap(path) }
            }
            text.removeSpan(span)
            text.setSpan(replacement, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.getSpans(0, text.length, AsyncDrawableSpan::class.java).forEach { span ->
            val path = span.drawable.destination
            val tag = document.inlineImages[path]
            val emote = tag?.let { it.hasAttr("data-mx-emoticon") || it.attr("width").toIntOrNull() == 32 || it.attr("height").toIntOrNull() == 32 } == true
            replace(span, path, emote)
        }
        text.getSpans(0, text.length, EmoteImageSpan::class.java).forEach { replace(it, it.mxcUrl, true) }
    }
    suspend fun messagePreview(context: Context, snapshot: TimelineExportSnapshot, directory: File, event: ExportEvent, galleryIndex: Int? = null): Pair<Document, MessagePreviewContent> {
        val events = snapshot.events.filter { it.id == event.id || it.id == event.replyTo }
        val document = layout(context, snapshot.copy(events = events, options = snapshot.options.copy(roomHeader = false, dates = false, expandAll = true)), directory)
        try {
            prepareAssets(document)
            val preview = withContext(Dispatchers.Main + TimelineRenderingScope.element(document.context)) {
                val builder = components.create().previews()
                val native = event.timelineEvent()
                val value = if (event.notice) builder.notice(native, event.noticeText(document.peopleById, !document.context.uncoloredNames, document::bitmap))
                else builder.build(native, MessagePreviewContentBuilder.Options(
                        document.px(132), document.px(66), document.px(220), document.px(220),
                        isThreadTimeline = snapshot.isThread, galleryItemIndex = galleryIndex, linkify = false,
                        senderItem = MatrixItem.UserItem(event.person.id, event.person.name, event.person.avatar)))
                fun offline(text: EpoxyCharSequence?) = text?.charSequence?.let {
                    SpannableStringBuilder(it).apply { offlineSpans(document, this) }.toEpoxyCharSequence()
                }
                value.copy(text = offline(value.text), captionlessText = offline(value.captionlessText))
            }
            return document to preview
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.Main) { document.release() }
            throw failure
        }
    }

    suspend fun prepareAssets(document: Document, timeMs: Int = 0, top: Int = 0, bottom: Int = document.height, waitForMedia: Boolean = true, bindRows: Boolean = true) = withContext(Dispatchers.IO) {
        val boundViews = withContext(Dispatchers.Main) {
            if (bindRows) document.visibleRows(top, bottom).map { document.boundView(it.first) } else emptyList()
        }
        if (waitForMedia) withContext(Dispatchers.Main) { document.context.awaitMedia(boundViews); document.remeasureDirtyRows() }
        val paths = linkedSetOf<String>()
        if (document.snapshot.options.roomHeader && document.visibleRows(top, bottom).any { it.first == 0 }) {
            document.topicImages.mapTo(paths) { it.attr("src") }
            document.topicMentionIds.mapNotNullTo(paths) { document.peopleById[it]?.avatar }
        }
        if (document.sheet == Sheet.Receipts) receiptsOf(document.snapshot.events).mapNotNullTo(paths) { it.person.avatar }
        val shown = document.visibleRows(top, bottom).flatMap { row -> row.second.events.flatMap { event ->
            listOfNotNull(event, event.replyTo?.let(document.eventsById::get))
        } }.distinctBy { it.id }
        shown.forEach { event ->
            event.person.avatar?.let(paths::add)
            event.receipts.mapNotNullTo(paths) { it.person.avatar }
            (document.mentionIdsByEvent[event.id].orEmpty() + document.mentionIdsByEvent[event.replyTo].orEmpty()).mapNotNullTo(paths) { document.peopleById[it]?.avatar }
            event.mapImage?.let(paths::add)
            event.audioBackdrop?.let(paths::add)
            event.reactions.forEach { paths.addAll(it.images.values) }
            event.noticeSpans.mapNotNullTo(paths) { it.image }
            event.linkPreviews.mapNotNullTo(paths) { preview -> preview.image?.takeIf { path -> document.assetsByPath[path]?.animated == true } }
            if (!event.mediaHidden) event.attachment?.takeIf { path -> document.assetsByPath[path]?.animated == true }?.let(paths::add)
            event.extraAttachments.filterNot { it.hidden }.mapNotNullTo(paths) { it.path?.takeIf { path -> document.assetsByPath[path]?.animated == true } }
            document.inlineByEvent[event.id].orEmpty().forEach { paths.add(it.attr("src")) }
        }
        document.snapshot.roomPerson?.avatar?.let(paths::add)
        var changed = false
        val maxDimension = kotlin.math.sqrt(document.images.maxSize() / 8.0).toInt().coerceIn(64, 1024)
        for (path in paths) {
            coroutineContext.ensureActive()
            if (path in document.failedImages) continue
            val file = File(document.directory, path)
            if (!file.isFile) continue
            val asset = document.assetsByPath[path]
            val imageLimit = if (path in document.avatarPaths && path !in document.fullMediaPaths) document.px(64).coerceIn(64, 256) else maxDimension
            if (asset?.animated == true) {
                if (document.images.get(path) != null && document.animationTimes[path] == timeMs) continue
                var targetIndex = 0
                var selected: Bitmap? = null
                val jobContext = coroutineContext
                try { AnimatedImageReader.visitFrames(file, onHeader = { width, height, times ->
                    val total = times.sumOf { it.toLong() }.coerceAtLeast(1)
                    var remaining = timeMs.toLong() % total
                    targetIndex = 0
                    while (targetIndex < times.lastIndex && remaining >= times[targetIndex]) {
                        remaining -= times[targetIndex].coerceAtLeast(1)
                        targetIndex++
                    }
                    minOf(1f, minOf(640, imageLimit).toFloat() / maxOf(width, height))
                }) { frame, index, _ ->
                    jobContext.ensureActive()
                    if (index == targetIndex) { selected = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false); throw FrameCaptured }
                } } catch (_: FrameCaptured) {
                    // Reader finally blocks release the decoder without visiting the remaining frames.
                } catch (cancelled: kotlinx.coroutines.CancellationException) { selected?.recycle(); throw cancelled } catch (failure: Exception) {
                    selected?.recycle(); selected = null
                    document.failedImages.add(path)
                    Timber.w(failure, "ExportTrace animated image decode failed")
                }
                coroutineContext.ensureActive()
                selected?.let { document.images.put(path, it); document.animationTimes[path] = timeMs; changed = true }
            } else if (document.images.get(path) == null) {
                val image = runCatching { ImageContentRenderer.decodeExportBitmap(document.context, file, imageLimit) }
                        .onFailure { Timber.w(it, "ExportTrace media decode failed mime=%s bytes=%d", asset?.mime, file.length()) }.getOrNull()
                val fallback = if (image == null && file.length() <= 2L * 1024 * 1024) runCatching {
                    val prefix = file.inputStream().use { input -> ByteArray(1024).let { bytes -> String(bytes, 0, input.read(bytes).coerceAtLeast(0), Charsets.UTF_8) } }
                    if (!prefix.contains("<svg", ignoreCase = true)) null else {
                        SvgPictureCache.decode(ByteBuffer.wrap(file.readBytes()))?.let { picture ->
                            val scale = minOf(1f, minOf(640, imageLimit).toFloat() / maxOf(picture.width, picture.height).coerceAtLeast(1))
                            Bitmap.createBitmap((picture.width * scale).toInt().coerceAtLeast(1), (picture.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
                                Canvas(bitmap).apply { scale(scale, scale); drawPicture(picture) }
                            }
                        }
                    }
                }.getOrNull() else null
                (image ?: fallback)?.let { decoded ->
                    val imageScale = minOf(1f, imageLimit.toFloat() / maxOf(decoded.width, decoded.height))
                    val bounded = if (imageScale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * imageScale).toInt().coerceAtLeast(1),
                            (decoded.height * imageScale).toInt().coerceAtLeast(1), true).also { decoded.recycle() } else decoded
                    document.images.put(path, bounded)
                    if (asset?.animated == true) document.animationTimes[path] = 0
                    changed = true
                }
                if (image == null && fallback == null) document.failedImages.add(path)
            }
        }
        // A cancelled pass may fill the cache before it can refresh the views.
        withContext(Dispatchers.Main) {
            document.context.animationTimeMs = timeMs
            document.invalidateImages()
            if (changed) Timber.d("ExportTrace asset views refreshed cachedBytes=%d", document.images.size())
        }
    }

    private object FrameCaptured : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun readyView(document: Document, index: Int): View = TimelineRenderingScope.render(document.context) {
        document.boundView(index).also { if (it.isLayoutRequested) measure(it, document.logicalWidth) }
    }

    fun draw(document: Document, canvas: Canvas, top: Int, outputWidth: Int, outputHeight: Int) = TimelineRenderingScope.draw(document.context) {
        val o = document.snapshot.options
        val scale = outputWidth.toFloat() / document.logicalWidth
        val logicalTop = top / scale
        val logicalBottom = (top + outputHeight) / scale
        val background = Color.parseColor(TimelineExportHtml.color(o.background, "#17191c"))
        canvas.drawColor(background)
        val saved = canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(0f, -logicalTop)
        document.visibleRows(logicalTop.toInt(), logicalBottom.toInt() + 1).forEach { (index, row) ->
            val view = readyView(document, index)
            val count = canvas.save()
            canvas.translate(0f, row.top.toFloat())
            view.draw(canvas)
            canvas.restoreToCount(count)
        }
        canvas.restoreToCount(saved)
    }

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun dispatchPreDraw(view: View) {
        // Unattached children have separate observers, unlike rows attached to a RecyclerView.
        if (view is ViewGroup) for (index in 0 until view.childCount) dispatchPreDraw(view.getChildAt(index))
        view.viewTreeObserver.dispatchOnPreDraw()
    }
}
