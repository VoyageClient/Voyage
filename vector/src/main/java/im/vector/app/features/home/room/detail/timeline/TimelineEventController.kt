/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyController
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.VisibilityState
import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.core.epoxy.LoadingItem_
import im.vector.app.core.epoxy.TimelineEmptyItem_
import im.vector.app.core.extensions.localDateTime
import im.vector.app.core.extensions.nextOrNull
import im.vector.app.core.extensions.prevOrNull
import im.vector.app.core.ui.PerformanceMode
import im.vector.app.core.utils.PerfTrace
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.RoomDetailAction
import im.vector.app.features.home.room.detail.RoomDetailViewState
import im.vector.app.features.home.room.detail.UnreadState
import im.vector.app.features.home.room.detail.timeline.factory.MergedHeaderItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.ReadReceiptsItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactory
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.helper.ContentDownloadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.helper.ReactionsSummaryFactory
import im.vector.app.features.home.room.detail.timeline.helper.TimelineControllerInterceptorHelper
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventDiffUtilCallback
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityStateChangedListener
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventsGroups
import im.vector.app.features.home.room.detail.timeline.helper.TimelineMediaSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import im.vector.app.features.home.room.detail.timeline.item.BaseEventItem
import im.vector.app.features.home.room.detail.timeline.item.BasedMergedItem
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem
import im.vector.app.features.home.room.detail.timeline.item.DaySeparatorItem_
import im.vector.app.features.home.room.detail.timeline.item.ItemWithEvents
import im.vector.app.features.home.room.detail.timeline.item.MergedRoomCreationItem_
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.ReactionsSummaryEvents
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptsItem
import im.vector.app.features.home.room.detail.timeline.item.TypingItem_
import im.vector.app.features.home.room.detail.timeline.pgp.PgpDecryptionRetriever
import im.vector.app.features.home.room.detail.timeline.readreceipts.ReadReceiptsCache
import im.vector.app.features.home.room.detail.timeline.reply.ReplyPreviewRetriever
import im.vector.app.features.home.room.detail.timeline.url.PreviewUrlRetriever
import im.vector.app.features.media.AttachmentData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.timer.Clock
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isAttachmentMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.ReadReceipt
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import timber.log.Timber
import javax.inject.Inject

class TimelineEventController @Inject constructor(
        private val dateFormatter: VectorDateFormatter,
        private val vectorPreferences: VectorPreferences,
        private val contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder,
        private val contentDownloadStateTrackerBinder: ContentDownloadStateTrackerBinder,
        private val timelineItemFactory: TimelineItemFactory,
        private val timelineMediaSizeProvider: TimelineMediaSizeProvider,
        private val mergedHeaderItemFactory: MergedHeaderItemFactory,
        private val session: Session,
        @TimelineEventControllerHandler
        private val backgroundHandler: Handler,
        private val timelineEventVisibilityHelper: TimelineEventVisibilityHelper,
        private val readReceiptsItemFactory: ReadReceiptsItemFactory,
        private val reactionListFactory: ReactionsSummaryFactory,
        private val clock: Clock,
        private val avatarRenderer: AvatarRenderer,
) : EpoxyController(backgroundHandler, backgroundHandler), Timeline.Listener, EpoxyController.Interceptor {

    private companion object {
        // Keeps a pass under ~0.5s (~13ms/model build + ~150ms fixed epoxy overhead), so a message sent
        // while a bulk reveal is trickling in waits at most one short pass to render.
        private const val MAX_MODEL_BUILDS_PER_PASS = 20

        // The count budget assumes cheap models, but on slow hardware a formatted message costs
        // 40-80ms to build — 20 of those is well over a second of blank timeline on room open. Cap
        // measured build time per pass too, tighter in performance mode, so the first models
        // (newest-first = the visible screen) are delivered quickly and the rest trickle in.
        private val maxBuildNanosPerPass: Long
            get() = if (PerformanceMode.enabled) 250_000_000L else 500_000_000L

        // Budget for the pass that paints a room's first frame: about a screenful, not the whole window.
        private const val FIRST_PAINT_BUILD_NANOS = 150_000_000L

        // Builds faster than this are "cheap" (hidden/redacted/notice items) and don't count against the
        // per-pass count cap — the time budget bounds them instead.
        private const val EXPENSIVE_BUILD_NANOS = 4_000_000L

        // Fields a caption edit is allowed to touch; anything else differing means the media changed.
        private val CAPTION_MUTABLE_KEYS = setOf("body", "filename", "formatted_body", "format", "m.mentions", "m.relates_to", "m.new_content")
    }

    /**
     * This is a partial state of the RoomDetailViewState.
     */
    data class PartialState(
            val unreadState: UnreadState = UnreadState.Unknown,
            val highlightedEventId: String? = null,
            val highlightNonce: Long = 0,
            val roomSummary: RoomSummary? = null,
            val rootThreadEventId: String? = null,
    ) {

        constructor(state: RoomDetailViewState) : this(
                unreadState = state.unreadState,
                highlightedEventId = state.highlightedEventId,
                highlightNonce = state.highlightNonce,
                roomSummary = state.asyncRoomSummary(),
                rootThreadEventId = state.rootThreadEventId,
        )

        fun isFromThreadTimeline(): Boolean = rootThreadEventId != null
    }

    interface Callback :
            BaseCallback,
            ReactionPillCallback,
            AvatarCallback,
            ThreadCallback,
            UrlClickCallback,
            ReadReceiptsCallback,
            InReplyToClickCallback,
            PreviewUrlCallback {
        fun onLoadMore(direction: Timeline.Direction)
        fun onEventInvisible(event: TimelineEvent)
        fun onEventVisible(event: TimelineEvent)
        fun onRoomCreateLinkClicked(url: String)
        fun onEncryptedMessageClicked(informationData: MessageInformationData, view: View)
        fun onImageMessageClicked(
                messageImageContent: MessageImageInfoContent,
                mediaData: ImageContentRenderer.Data,
                view: View,
                inMemory: List<AttachmentData>
        )

        fun onVideoMessageClicked(messageVideoContent: MessageVideoContent, mediaData: VideoContentRenderer.Data, view: View)

        //        fun onFileMessageClicked(eventId: String, messageFileContent: MessageFileContent)
//        fun onAudioMessageClicked(messageAudioContent: MessageAudioContent)
        fun onEditedDecorationClicked(informationData: MessageInformationData)

        // TODO move all callbacks to this?
        fun onTimelineItemAction(itemAction: RoomDetailAction)

        // Introduce ViewModel scoped component (or Hilt?)
        fun getPreviewUrlRetriever(): PreviewUrlRetriever

        fun getPgpDecryptionRetriever(): PgpDecryptionRetriever

        fun getReplyPreviewRetriever(): ReplyPreviewRetriever

        fun onVoiceControlButtonClicked(eventId: String, messageAudioContent: MessageAudioContent)
        fun onVoiceWaveformTouchedUp(eventId: String, duration: Int, percentage: Float)
        fun onVoiceWaveformMovedTo(eventId: String, duration: Int, percentage: Float)

        fun onAudioSeekBarMovedTo(eventId: String, duration: Int, percentage: Float)

        fun onAddMoreReaction(event: TimelineEvent)
    }

    interface ReactionPillCallback {
        fun onClickOnReactionPill(informationData: MessageInformationData, reaction: String, on: Boolean)
        fun onLongClickOnReactionPill(informationData: MessageInformationData, reaction: String)
    }

    interface BaseCallback {
        fun onEventCellClicked(informationData: MessageInformationData, messageContent: Any?, view: View, isRootThreadEvent: Boolean)
        fun onEventLongClicked(informationData: MessageInformationData, messageContent: Any?, view: View): Boolean
    }

    interface AvatarCallback {
        fun onAvatarClicked(informationData: MessageInformationData)
        fun onMemberNameClicked(informationData: MessageInformationData)
    }

    interface ThreadCallback {
        fun onThreadSummaryClicked(eventId: String, isRootThreadEvent: Boolean): Boolean
    }

    interface ReadReceiptsCallback {
        fun onReadReceiptsClicked(readReceipts: List<ReadReceiptData>)
        fun onReadMarkerVisible()
    }

    interface UrlClickCallback {
        fun onUrlClicked(url: String, title: String): Boolean
        fun onUrlLongClicked(url: String): Boolean
    }

    interface InReplyToClickCallback {
        fun onRepliedToEventClicked(sourceEventId: String?, targetEventId: String)
    }

    interface PreviewUrlCallback {
        fun onPreviewUrlClicked(url: String)
        fun onPreviewUrlCloseClicked(eventId: String, url: String)
        fun onPreviewUrlImageClicked(sharedView: View?, mxcUrl: String?, title: String?)
    }

    // Map eventId to adapter position.
    // Guarded by positionsLock, NOT the modelCache lock: a build pass holds modelCache for up to
    // seconds on slow hardware, and these positions are read from the main thread while scrolling
    // (pinned banner, jump-to-reply) — blocking there froze the UI for the whole pass.
    private val adapterPositionMapping = HashMap<String, Int>()

    @Volatile
    private var buildFocusEventId: String? = null

    /** Hint from the UI: the event the viewport currently sits at (null when at the live edge). */
    fun setBuildFocusEventId(eventId: String?) {
        buildFocusEventId = eventId
    }

    fun findEventInSnapshot(eventId: String?): TimelineEvent? =
            eventId?.let { id -> currentSnapshot.firstOrNull { it.eventId == id } }
    private val positionsLock = Any()
    private val timelineEventsGroups = TimelineEventsGroups()
    private val readReceiptsCache = ReadReceiptsCache()
    private val modelCache = arrayListOf<CacheItemData?>()

    // A pass hit its budget with events left to build; see addWhenLoading.
    @Volatile private var hasUnbuiltEvents = false

    // Per-snapshot O(n) work (reverse preprocess, displayable-neighbour arrays, receipts) is identical across
    // the multiple budget-limited build passes over one snapshot; recomputing it each pass was the bulk of the
    // per-pass cost on a big (e.g. redaction-heavy) window. Cache it, keyed on the snapshot + partial-state
    // identity — any structural change replaces currentSnapshot wholesale (see submitSnapshot), so identity is
    // a sound key.
    private var processedSnapshot: List<TimelineEvent>? = null
    private var processedPartialState: PartialState? = null

    // A collapse toggle mutates run visibility without changing the snapshot; bumping the factory's
    // generation invalidates this cache so the runs/collapsed-set/neighbours recompute.
    private var processedCollapseGeneration: Int = -1
    private var cachedPrevDisplayable: Array<TimelineEvent?> = emptyArray()
    private var cachedNextDisplayable: Array<TimelineEvent?> = emptyArray()
    private var cachedReceiptsByEvent: Map<String, List<ReadReceipt>> = emptyMap()
    private var cachedLastSentWithoutRr: String? = null

    // Volatile: replaced wholesale on the background thread, read (never mutated) from main-thread
    // position lookups under positionsLock only.
    @Volatile private var currentSnapshot: List<TimelineEvent> = emptyList()
    private var inSubmitList: Boolean = false
    private var hasReachedInvite: Boolean = false
    private var hasUTD: Boolean = false
    @Volatile private var positionOfReadMarker: Int? = null
    private var partialState: PartialState = PartialState()
    private var forcedVisibleEditIds: Set<String> = emptySet()

    var callback: Callback? = null
    var timeline: Timeline? = null

    private val listUpdateCallback = object : ListUpdateCallback {

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                Timber.v(
                        "listUpdateCallback.onChanged(position: $position, count: $count). " +
                                "\ncurrentSnapshot has size of ${currentSnapshot.size} items"
                )
                (position until position + count).forEach {
                    // Invalidate cache
                    modelCache[it] = null
                }
                // Neighbours whose grouping depends on a changed event rebuild via the content-aware
                // neighbour check in buildCacheItemsIfNeeded. Positions here are intermediate diff-dispatch
                // coordinates, so any lookup into currentSnapshot from this callback would be off by the
                // batch's pending inserts/removes.
                requestModelBuild()
            }
        }

        override fun onMoved(fromPosition: Int, toPosition: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                Timber.v(
                        "listUpdateCallback.onMoved(fromPosition: $fromPosition, toPosition: $toPosition). " +
                                "\ncurrentSnapshot has size of ${currentSnapshot.size} items"
                )
                val model = modelCache.removeAt(fromPosition)
                modelCache.add(toPosition, model)
                requestModelBuild()
            }
        }

        override fun onInserted(position: Int, count: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                Timber.v(
                        "listUpdateCallback.onInserted(position: $position, count: $count). " +
                                "\ncurrentSnapshot has size of ${currentSnapshot.size} items"
                )
                repeat(count) {
                    modelCache.add(position, null)
                }
                requestModelBuild()
            }
        }

        override fun onRemoved(position: Int, count: Int) {
            synchronized(modelCache) {
                assertUpdateCallbacksAllowed()
                Timber.v(
                        "listUpdateCallback.onRemoved(position: $position, count: $count). " +
                                "\ncurrentSnapshot has size of ${currentSnapshot.size} items"
                )
                repeat(count) {
                    modelCache.removeAt(position)
                }
                requestModelBuild()
            }
        }
    }

    private val interceptorHelper = TimelineControllerInterceptorHelper(
            ::positionOfReadMarker,
            adapterPositionMapping,
    )

    init {
        addInterceptor(this)
        requestModelBuild()
    }

    override fun intercept(models: MutableList<EpoxyModel<*>>) = synchronized(modelCache) {
        // positionsLock only around the (fast) mapping rebuild, so main-thread position lookups
        // wait at most this long — never a whole model-build pass.
        synchronized(positionsLock) {
            interceptorHelper.intercept(models, partialState.unreadState, timeline, callback)
        }
    }

    /**
     * Drop any cached model that references [eventId], either as the event itself or as a
     * reply target. Used to force a fresh build (e.g. after on-demand fetching the target of
     * an unresolved reply). Cheap to call — touches at most a handful of cache slots and only
     * triggers a model rebuild when something actually changed.
     */
    fun invalidateEventCache(eventId: String) = backgroundHandler.post {
        // On the build thread: the modelCache lock can be held for a whole build pass, so callers
        // (often the main thread, e.g. decrypt listeners) must never take it directly.
        synchronized(modelCache) {
            // The invalidated event may have changed CATEGORY (revealed/hidden, decrypted), which can
            // grow/split/collapse a merged run — the per-snapshot run structure must recompute too.
            processedSnapshot = null
            currentSnapshot.forEachIndexed { index, event ->
                if (index >= modelCache.size) return@forEachIndexed
                if (modelCache[index] == null) return@forEachIndexed
                if (event.eventId == eventId || event.root.getRelationContent()?.inReplyTo?.eventId == eventId) {
                    modelCache[index] = null
                }
            }
        }
        requestModelBuild()
    }

    /**
     * Batched [invalidateEventCache]: drop every cached model referencing any of [eventIds] in a
     * single locked pass + a single rebuild. A burst of PGP decrypts would otherwise call
     * invalidateEventCache once per event — each grabbing the model-build lock (contended with the
     * background build thread) and each scheduling another full rebuild, which convoys the main
     * thread into an ANR.
     */
    /** Runs regrouped by a reveal/hide start expanded; see [MergedHeaderItemFactory.expandRunsContaining]. */
    fun keepRunsExpandedFor(eventIds: Collection<String>) {
        mergedHeaderItemFactory.expandRunsContaining(eventIds)
    }

    fun invalidateEventCaches(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        val ids = eventIds.toHashSet()
        backgroundHandler.post {
            synchronized(modelCache) {
                // See invalidateEventCache: category changes must also regroup merged runs.
                processedSnapshot = null
                currentSnapshot.forEachIndexed { index, event ->
                    if (index >= modelCache.size) return@forEachIndexed
                    if (modelCache[index] == null) return@forEachIndexed
                    if (event.eventId in ids || event.root.getRelationContent()?.inReplyTo?.eventId in ids) {
                        modelCache[index] = null
                    }
                }
            }
            requestModelBuild()
        }
    }

    /**
     * Drop cached models of events sent by any of [userIds], then rebuild. Used when a sender's
     * MSC4247 pronouns arrive after the fact, so their profile-change notices re-render gendered.
     */
    fun invalidateEventCachesForSenders(userIds: Collection<String>) {
        if (userIds.isEmpty()) return
        val ids = userIds.toHashSet()
        backgroundHandler.post {
            var dirty = false
            synchronized(modelCache) {
                currentSnapshot.forEachIndexed { index, event ->
                    if (index >= modelCache.size) return@forEachIndexed
                    if (modelCache[index] == null) return@forEachIndexed
                    if (event.senderInfo.userId in ids) {
                        modelCache[index] = null
                        dirty = true
                    }
                }
            }
            if (dirty) requestModelBuild()
        }
    }

    /**
     * Drop only the cached models of events that are replies, then rebuild. A reply's preview header
     * renders the replied-to message's plaintext via the shared PgpDecryptor (peeked at bind time),
     * so when an off-timeline decrypt finishes only reply items need re-binding — invalidating the
     * whole cache here would rebuild + rebind every visible item and jank the main thread.
     */
    fun invalidateReplyEventCaches() = backgroundHandler.post {
        var dirty = false
        synchronized(modelCache) {
            currentSnapshot.forEachIndexed { index, event ->
                if (index >= modelCache.size) return@forEachIndexed
                if (modelCache[index] == null) return@forEachIndexed
                if (event.root.getRelationContent()?.inReplyTo?.eventId != null) {
                    modelCache[index] = null
                    dirty = true
                }
            }
        }
        if (dirty) requestModelBuild()
    }

    /** Drop every cached model and rebuild. Used after a PGP OpenKeychain interaction so all
     * visible PGP items re-request decryption. Heavy, so reserved for rare one-shot events. */
    fun invalidateAllCache() = backgroundHandler.post {
        synchronized(modelCache) {
            processedSnapshot = null
            for (i in modelCache.indices) {
                modelCache[i] = null
            }
        }
        requestModelBuild()
    }

    fun update(viewState: RoomDetailViewState) = PerfTrace.time("timeline.controller.update") {
        val newPartialState = PartialState(viewState)
        reactionListFactory.canAddReaction = newPartialState.roomSummary?.membership == Membership.JOIN
        if (newPartialState != partialState) {
            partialState = newPartialState
            requestModelBuild()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        timeline?.addListener(this)
        timelineMediaSizeProvider.recyclerView = recyclerView
        reactionListFactory.onRequestBuild = { requestModelBuild() }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        timelineMediaSizeProvider.recyclerView = null
        contentUploadStateTrackerBinder.clear()
        contentDownloadStateTrackerBinder.clear()
        timeline?.removeListener(this)
        reactionListFactory.onRequestBuild = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun buildModels() {
        PerfTrace.time("timeline.buildModels") { buildModelsInner() }
    }

    private fun buildModelsInner() {
        // NONE is a world-readable preview (PeekedRoom); LEAVE/BAN is a kicked/banned room kept
        // browsable up to the removal; INVITE renders whatever preview history is available
        // (empty for a plain invite, whose full-screen invite view covers the timeline anyway).
        val membership = partialState.roomSummary?.membership
        if (membership != Membership.JOIN && membership != Membership.NONE &&
                membership != Membership.LEAVE && membership != Membership.BAN &&
                membership != Membership.INVITE) {
            return
        }
        val timestamp = clock.epochMillis()

        val showingForwardLoader = LoadingItem_()
                .id("forward_loading_item_$timestamp")
                .setVisibilityStateChangedListener(Timeline.Direction.FORWARDS)
                .addWhenLoading(Timeline.Direction.FORWARDS)

        if (!showingForwardLoader) {
            val typingUsers = partialState.roomSummary?.typingUsers.orEmpty()
            val typingItem = TypingItem_().id("typing_view").avatarRenderer(avatarRenderer).users(typingUsers)
            add(typingItem)
        }

        val timelineModels = getModels()
        add(timelineModels)
        if (hasReachedInvite && hasUTD) {
            return
        }
        // Avoid displaying two loaders if there is no elements between them
        val showBackwardsLoader = !showingForwardLoader || timelineModels.isNotEmpty()
        // With events still unbuilt the list is shorter than what is loaded, so this loader must not request
        // history the timeline hasn't rendered yet — but it still has to be ADDED, or the list just ends at
        // the oldest built message and a scroll to the top stops there with no spinner and nothing above it.
        LoadingItem_()
                .id("backward_loading_item_$timestamp")
                .setVisibilityStateChangedListener(Timeline.Direction.BACKWARDS, requestsMore = !hasUnbuiltEvents)
                .showLoader(showBackwardsLoader)
                .addWhenLoading(Timeline.Direction.BACKWARDS)
    }

// Timeline.LISTENER ***************************************************************************

    // A backstacked room keeps its RecyclerView attached, so its controller keeps receiving snapshots
    // and re-running full model passes for a screen nobody sees — doubling (or worse) the shared epoxy
    // thread's load. The fragment pauses us while stopped; the latest snapshot is replayed on restart.
    private val isPaused = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pendingPausedSnapshot: List<TimelineEvent>? = null

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        if (!paused) {
            pendingPausedSnapshot?.let {
                pendingPausedSnapshot = null
                submitSnapshot(it)
            }
        }
    }

    override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
        if (isPaused.get()) {
            pendingPausedSnapshot = snapshot
        } else {
            submitSnapshot(snapshot)
        }
    }

    private fun submitSnapshot(newSnapshot: List<TimelineEvent>) {
        // Update is triggered on any DB change
        backgroundHandler.post {
            inSubmitList = true
            val diffCallback = TimelineEventDiffUtilCallback(currentSnapshot, newSnapshot)
            currentSnapshot = newSnapshot
            Timber.v("Submit a new snapshot of ${currentSnapshot.size} items.")
            val diffResult = PerfTrace.time("timeline.snapshotDiff") { DiffUtil.calculateDiff(diffCallback) }
            diffResult.dispatchUpdatesTo(listUpdateCallback)
            requestDelayedModelBuild(0)
            inSubmitList = false
        }
    }

    private fun assertUpdateCallbacksAllowed() {
        require(inSubmitList || Looper.myLooper() == backgroundHandler.looper)
    }

    private fun getModels(): List<EpoxyModel<*>> {
        buildCacheItemsIfNeeded()
        val models = ArrayList<EpoxyModel<*>>(modelCache.size)
        // Newest first, so the first receipts of a collapsed run are the ones that belong at its
        // bottom edge. They move onto the run's header rather than vanishing with the events.
        var collapsedReceipts: ReadReceiptsItem? = null
        modelCache.forEach { cacheItemData ->
            val collapsed = cacheItemData != null && mergedHeaderItemFactory.isCollapsed(cacheItemData.localId)
            val eventModel = cacheItemData?.eventModel?.takeUnless { collapsed }
            if (collapsed) {
                collapsedReceipts = collapsedReceipts ?: cacheItemData?.readReceiptsItem
            } else {
                // Leaving a collapsed run: if it had no header to hoist onto, the receipts belong here
                // rather than being carried into the next run.
                collapsedReceipts?.let { models.add(it) }
                collapsedReceipts = null
                cacheItemData?.readReceiptsItem?.let { models.add(it) }
            }
            eventModel?.let { models.add(it) }
            cacheItemData?.mergedHeaderModel?.let { header ->
                collapsedReceipts?.let { models.add(it) }
                collapsedReceipts = null
                models.add(header)
            }
            cacheItemData?.formattedDayModel
                    ?.takeIf { eventModel != null || cacheItemData.mergedHeaderModel != null }
                    ?.let { models.add(it) }
        }
        return models
    }

    // True when [event] is the oldest event of ANY merged run (redacted, hidden, membership/ACL/image-pack,
    // room-creation). Its build is what collapses the run, so it must bypass the per-pass build budget —
    // otherwise the run shows expanded until a later pass catches up (the compact<->expand flicker).
    private fun isMergedRunStart(event: TimelineEvent): Boolean {
        return mergedHeaderItemFactory.isMergedAnchor(event.localId)
    }

    private fun buildCacheItemsIfNeeded() = synchronized(modelCache) {
        hasUTD = false
        hasReachedInvite = false
        if (modelCache.isEmpty()) {
            return
        }
        var numberOfEventsToBuild = 0
        val perfEnabled = PerfTrace.isEnabled
        // Reuse the per-snapshot O(n) work across budget passes over the same (unchanged) snapshot. A collapse
        // toggle bumps the factory's generation, invalidating this so runs + collapsed-set + neighbours redo.
        val processStable = processedSnapshot === currentSnapshot && processedPartialState === partialState &&
                processedCollapseGeneration == mergedHeaderItemFactory.collapseGeneration
        if (perfEnabled && !processStable) {
            PerfTrace.report(
                    "timeline.process.miss snapshot=${processedSnapshot !== currentSnapshot} " +
                            "partialState=${processedPartialState !== partialState} " +
                            "collapseGen=${processedCollapseGeneration != mergedHeaderItemFactory.collapseGeneration}",
                    0,
            )
        }
        val processStart = if (perfEnabled && !processStable) System.nanoTime() else 0L
        if (!processStable) {
            var p = if (perfEnabled) System.nanoTime() else 0L
            fun lap(): Long = if (!perfEnabled) 0L else (System.nanoTime() - p).also { p = System.nanoTime() } / 1_000_000
            preprocessReverseEvents()
            val reverseMs = lap()
            forcedVisibleEditIds = computeRejectedMediaEdits()
            val mediaEditMs = lap()
            // Derive all merged runs + the collapsed-id set from scratch before neighbours (which exclude
            // collapsed members) and the build loop (which reads isCollapsed / isMergedAnchor). Reuse the
            // per-position "shown" flags it computes so neighbours don't re-run shouldShowEvent.
            val shown = mergedHeaderItemFactory.updateRuns(currentSnapshot, partialState, forcedVisibleEditIds)
            val runsMs = lap()
            cachedReceiptsByEvent = readReceiptsCache.receiptsByEvent()
            val receiptsMs = lap()
            cachedLastSentWithoutRr = searchLastSentEventWithoutReadReceipts(cachedReceiptsByEvent)
            val lastSentMs = lap()
            // Nearest displayable neighbours, precomputed in O(n) — was a per-event subList scan (O(n²)).
            val (prev, next) = computeDisplayableNeighbours(shown)
            val neighboursMs = lap()
            cachedPrevDisplayable = prev
            cachedNextDisplayable = next
            processedSnapshot = currentSnapshot
            processedPartialState = partialState
            processedCollapseGeneration = mergedHeaderItemFactory.collapseGeneration
            if (perfEnabled) {
                PerfTrace.report(
                        "timeline.process.phases n=${currentSnapshot.size} reverse=${reverseMs}ms mediaEdit=${mediaEditMs}ms " +
                                "runs=${runsMs}ms receipts=${receiptsMs}ms lastSent=${lastSentMs}ms neighbours=${neighboursMs}ms",
                        0,
                )
            }
        }
        val processNanos = if (perfEnabled && !processStable) System.nanoTime() - processStart else 0L
        val receiptsByEvent = cachedReceiptsByEvent
        val lastSentEventWithoutReadReceipts = cachedLastSentWithoutRr
        val prevDisplayableEvents = cachedPrevDisplayable
        val nextDisplayableEvents = cachedNextDisplayable
        var checkNanos = 0L
        var buildNanos = 0L
        var enrichNanos = 0L
        var enrichSkips = 0
        var buildBudgetExhausted = false
        // Build order is normally newest-first (position 0 = live edge, where a just-sent message sits).
        // After a jump-to-event, though, the snapshot spans live edge → target and the viewport is at the
        // target — deep in the list — so newest-first leaves everything around the target unbuilt (invisible)
        // for dozens of budgeted passes. Build outward from the highlight target, or failing that from the
        // viewport (fed by the fragment's scroll listener), so the user's area materializes first.
        val focusIdx = (partialState.highlightedEventId ?: buildFocusEventId)
                ?.let { id -> currentSnapshot.indexOfFirst { it.eventId == id } }
                ?.takeIf { it > 0 }
        val buildOrder = if (focusIdx == null) (0 until modelCache.size).asSequence() else {
            (0 until modelCache.size).sortedBy { kotlin.math.abs(it - focusIdx) }.asSequence()
        }
        // The small per-pass budget exists so a just-sent message renders fast — a live-edge concern.
        // With a jump/scroll focus active the user is deep in history and each pass carries a large
        // fixed cost (interceptor + diff over the whole list), so bigger passes fill the huge
        // post-jump snapshot several times faster.
        // Nothing painted yet (room open): building the whole window first costs ~35ms a message of blank
        // screen, and everything past the first screenful is invisible anyway.
        val nothingOnScreenYet = modelCache.none { it?.eventModel != null }
        val maxBuildsThisPass = when {
            focusIdx != null -> MAX_MODEL_BUILDS_PER_PASS * 6
            nothingOnScreenYet -> MAX_MODEL_BUILDS_PER_PASS / 4
            else -> MAX_MODEL_BUILDS_PER_PASS
        }
        val maxNanosThisPass = when {
            focusIdx != null -> maxBuildNanosPerPass * 6
            nothingOnScreenYet -> FIRST_PAINT_BUILD_NANOS
            else -> maxBuildNanosPerPass
        }
        buildOrder.forEach { position ->
            val event = currentSnapshot[position]
            val nextEvent = currentSnapshot.nextOrNull(position)
            var t0 = if (perfEnabled) System.nanoTime() else 0L
            val cached = modelCache[position]
            // Known-collapsed (from a prior pass's merged-header build) — an O(1) lookup, not a rescan. Its
            // model is never displayed (the run's header replaces it), so skip the expensive build.
            val isCollapsed = mergedHeaderItemFactory.isCollapsed(event.localId)
            // Should build if not cached or if model should be refreshed. A placeholder that is no longer
            // collapsed (run expanded) needs a real build; a real model that is now collapsed can stay
            // (getModels hides it anyway) so we don't waste a rebuild switching it to a placeholder.
            val neighboursChanged = cached != null && !cached.isCollapsedPlaceholder &&
                    (cached.builtPrevDisplayable != prevDisplayableEvents[position] ||
                            cached.builtNextDisplayable != nextDisplayableEvents[position])
            val needsBuild = cached == null || cached.isCacheable(partialState) == false ||
                    reactionListFactory.needsRebuild(event) ||
                    neighboursChanged ||
                    (cached.isCollapsedPlaceholder && !isCollapsed)
            if (perfEnabled) checkNanos += System.nanoTime() - t0
            if (needsBuild && isCollapsed) {
                modelCache[position] = buildCollapsedPlaceholder(event, nextEvent)
                val itemCachedData = modelCache[position] ?: return@forEach
                modelCache[position] = itemCachedData.enrichWithModels(
                        event, nextEvent, currentSnapshot.prevOrNull(position), receiptsByEvent
                )
                return@forEach
            }
            // A model build is ~10-25ms (Markwon etc.), so a bulk reveal (room open, scroll-up) built for
            // seconds in one pass — and a message sent meanwhile couldn't render until the whole pass
            // finished. Build newest-first (position 0 = live edge, where a just-sent message sits) up to
            // a budget and finish the (older, off-screen) rest in follow-up passes.
            // Exception: an event that starts a merged run (oldest redacted/hidden event, whose older
            // neighbour is a normal event) must never be deferred — its build is what collapses the whole run
            // into one item. Deferring it leaves the run's events showing individually until a later pass,
            // which is the compacted<->expanded flicker when more redactions load in.
            if (needsBuild && !isMergedRunStart(event) &&
                    (numberOfEventsToBuild >= maxBuildsThisPass || buildNanos >= maxNanosThisPass)) {
                buildBudgetExhausted = true
                return@forEach
            }
            if (needsBuild) {
                val prevEvent = currentSnapshot.prevOrNull(position)
                val prevDisplayableEvent = prevDisplayableEvents[position]
                val nextDisplayableEvent = nextDisplayableEvents[position]
                val timelineEventsGroup = timelineEventsGroups.getOrNull(event)
                val params = TimelineItemFactoryParams(
                        event = event,
                        lastEdit = event.annotations?.editSummary?.latestEdit,
                        prevEvent = prevEvent,
                        prevDisplayableEvent = prevDisplayableEvent,
                        nextEvent = nextEvent,
                        nextDisplayableEvent = nextDisplayableEvent,
                        partialState = partialState,
                        lastSentEventIdWithoutReadReceipts = lastSentEventWithoutReadReceipts,
                        callback = callback,
                        eventsGroup = timelineEventsGroup,
                        reactionsSummaryEvents = ReactionsSummaryEvents(
                                onAddMoreClicked = { reactionListFactory.onAddMoreClicked(callback, event) },
                                onShowLessClicked = { reactionListFactory.onShowLessClicked(event.eventId) },
                                onShowMoreClicked = { reactionListFactory.onShowMoreClicked(event.eventId) }
                        ),
                        forcedVisibleEventIds = forcedVisibleEditIds
                )
                t0 = System.nanoTime()
                modelCache[position] = buildCacheItem(params)
                val buildDelta = System.nanoTime() - t0
                buildNanos += buildDelta
                // Only expensive (formatted-message) builds count toward the per-pass count cap; cheap ones
                // (hidden, redacted, notices) are bounded by the time budget alone. Otherwise a big run of
                // cheap events (e.g. thousands of redactions) forces hundreds of passes before the real
                // content above them ever builds.
                if (buildDelta >= EXPENSIVE_BUILD_NANOS) numberOfEventsToBuild++
            }
            val itemCachedData = modelCache[position] ?: return@forEach
            // Then update with additional models if needed
            t0 = if (perfEnabled) System.nanoTime() else 0L
            val enriched = itemCachedData.enrichWithModels(
                    event, nextEvent, currentSnapshot.prevOrNull(position), receiptsByEvent
            )
            if (perfEnabled) {
                enrichNanos += System.nanoTime() - t0
                if (enriched === itemCachedData) enrichSkips++
            }
            modelCache[position] = enriched
        }
        hasUnbuiltEvents = buildBudgetExhausted
        if (buildBudgetExhausted) {
            // Cannot request from inside buildModels; queue it behind this pass on the same handler.
            backgroundHandler.post { requestDelayedModelBuild(0) }
        }
        if (perfEnabled) {
            PerfTrace.report(
                    "timeline.buildCache rebuilt=$numberOfEventsToBuild/${modelCache.size} " +
                            "check=${checkNanos / 1_000_000}ms enrich=${enrichNanos / 1_000_000}ms(skips=$enrichSkips) " +
                            "process=${processNanos / 1_000_000}ms(stable=$processStable) more=$buildBudgetExhausted",
                    buildNanos / 1_000_000,
            )
        }
    }

    // A media "edit" that swaps the file/thumbnail/metadata (anything but the caption) is refused as an
    // edit by the SDK, so it must not be hidden like a real edit — that would let a sender smuggle or
    // hide media through the edit mechanism. Surface those as standalone messages. The check mirrors the
    // SDK's EventEditValidator and is done directly against the loaded original, so it is immune to the
    // async aggregation timing (a normal caption edit never flashes as a duplicate).
    private fun computeRejectedMediaEdits(): Set<String> {
        val byId = currentSnapshot.associateBy { it.eventId }
        val rejected = HashSet<String>()
        for (event in currentSnapshot) {
            if (!event.root.isAttachmentMessage()) continue
            val relation = event.root.getRelationContent()?.takeIf { it.type == RelationType.REPLACE } ?: continue
            val original = relation.eventId?.let { byId[it] }?.root?.getClearContent() ?: continue
            @Suppress("UNCHECKED_CAST")
            val newContent = event.root.getClearContent()?.get("m.new_content") as? Map<String, Any?> ?: continue
            if (original.withoutCaptionFields() != newContent.withoutCaptionFields()) {
                rejected.add(event.eventId)
            }
        }
        return rejected
    }

    private fun Map<String, Any?>.withoutCaptionFields(): Map<String, Any?> = this - CAPTION_MUTABLE_KEYS

    // Nearest displayable event before / after each position, via one forward + one backward pass.
    private fun computeDisplayableNeighbours(shown: BooleanArray): Pair<Array<TimelineEvent?>, Array<TimelineEvent?>> {
        val size = currentSnapshot.size
        // A member collapsed into a merged run (its anchor included — that slot shows the header, not a
        // bubble) is no one's grouping neighbour, but the run's header still sits between its neighbours
        // on screen, so it breaks the chain: messages on either side each start a fresh group.
        val collapsed = BooleanArray(size) { mergedHeaderItemFactory.isCollapsed(currentSnapshot[it].localId) }
        val displayable = BooleanArray(size) { shown[it] && !collapsed[it] }
        val prev = arrayOfNulls<TimelineEvent>(size)
        var lastDisplayable: TimelineEvent? = null
        for (position in 0 until size) {
            if (collapsed[position]) lastDisplayable = null
            prev[position] = lastDisplayable
            if (displayable[position]) lastDisplayable = currentSnapshot[position]
        }
        val next = arrayOfNulls<TimelineEvent>(size)
        var nextDisplayable: TimelineEvent? = null
        for (position in size - 1 downTo 0) {
            if (collapsed[position]) nextDisplayable = null
            next[position] = nextDisplayable
            if (displayable[position]) nextDisplayable = currentSnapshot[position]
        }
        return prev to next
    }

    private fun buildCollapsedPlaceholder(event: TimelineEvent, nextEvent: TimelineEvent?): CacheItemData {
        // Still track invite/UTD boundaries — a redacted membership in the run can flip hasReachedInvite.
        updateUTDStates(event, nextEvent)
        return CacheItemData(
                localId = event.localId,
                eventId = event.root.eventId,
                eventModel = TimelineEmptyItem_()
                        .id(event.localId)
                        .eventId(event.eventId)
                        .notBlank(false),
                isCollapsedPlaceholder = true,
        )
    }

    private fun buildCacheItem(params: TimelineItemFactoryParams): CacheItemData {
        val event = params.event
        if (hasReachedInvite && hasUTD) {
            return CacheItemData(event.localId, event.root.eventId)
        }
        updateUTDStates(event, params.nextEvent)
        val eventModel = PerfTrace.time("timeline.item.create.${event.root.getClearType()}") {
            timelineItemFactory.create(params)
        }.also {
            // Stable across the local-echo → synced-event swap so Epoxy rebinds in place (see
            // areItemsTheSame in TimelineEventDiffUtilCallback) instead of flashing the bubble out.
            it.id(event.timelineStableId())
            it.setOnVisibilityStateChanged(TimelineEventVisibilityStateChangedListener(callback, event))
            // Central spot every event model passes through — spares each factory from threading it.
            if (params.isHighlighted) (it as? BaseEventItem<*>)?.highlightNonce = params.highlightNonce
        }
        val isCacheable = (eventModel !is ItemWithEvents || eventModel.isCacheable()) && !params.isHighlighted
        return CacheItemData(
                localId = event.localId,
                eventId = event.root.eventId,
                eventModel = eventModel,
                isCacheable = isCacheable,
                builtPrevDisplayable = params.prevDisplayableEvent,
                builtNextDisplayable = params.nextDisplayableEvent
        )
    }

    private fun CacheItemData.enrichWithModels(
            event: TimelineEvent,
            nextEvent: TimelineEvent?,
            prevEvent: TimelineEvent?,
            receiptsByEvents: Map<String, List<ReadReceipt>>,
    ): CacheItemData {
        val readReceipts = receiptsByEvents[event.eventId].orEmpty()
        // Enrichment inputs unchanged → keep the cached models. Day-separator and receipt decisions read only
        // the event and its immediate neighbours; the merged-header depends on the whole run (membership +
        // collapse state), which the factory's per-anchor signature captures — so a nearby redaction that
        // grows/splits/collapses the run changes the signature even when this event's own neighbours don't.
        val mergeSignature = mergedHeaderItemFactory.mergeSignatureAt(event.localId)
        if (enrichedNextEventId == nextEvent?.eventId && enrichedPrevEventId == prevEvent?.eventId &&
                enrichedReceipts == readReceipts && enrichedMergeSignature == mergeSignature) {
            // The creation header renders the room summary (name/topic/avatar); without this a just-created
            // room keeps showing "Empty room" in the "beginning of" tile until the room is reopened.
            val summaryStable = mergedHeaderModel !is MergedRoomCreationItem_ ||
                    enrichedCreationSummaryStamp == partialState.roomSummary.creationTileRenderState()
            if (summaryStable) return this
        }
        val wantsDateSeparator = wantsDateSeparator(event, nextEvent)
        val mergedHeaderModel = mergedHeaderItemFactory.create(
                event,
                partialState = partialState,
                eventIdToHighlight = partialState.highlightedEventId,
                callback = callback
        ) {
            requestModelBuild()
        }
        val formattedDayModel = if (wantsDateSeparator) {
            buildDaySeparatorItem(event.root.originServerTs)
        } else {
            null
        }
        return copy(
                readReceiptsItem = readReceiptsItemFactory.create(
                        event.eventId,
                        event.roomId,
                        readReceipts,
                        callback,
                        partialState.isFromThreadTimeline(),
                ),
                formattedDayModel = formattedDayModel,
                mergedHeaderModel = mergedHeaderModel,
                enrichedNextEventId = nextEvent?.eventId,
                enrichedPrevEventId = prevEvent?.eventId,
                enrichedReceipts = readReceipts,
                enrichedMergeSignature = mergeSignature,
                enrichedCreationSummaryStamp = if (mergedHeaderModel is MergedRoomCreationItem_) {
                    partialState.roomSummary.creationTileRenderState()
                } else {
                    null
                },
        )
    }

    // Only the summary fields the creation tile actually renders, so unrelated summary churn
    // (typing, unread counts, last message) doesn't rebuild it.
    private fun RoomSummary?.creationTileRenderState(): List<Any?>? = this?.run {
        listOf(displayName, name, topic, avatarUrl, isDirect, directUserId, isEncrypted, joinedMembersCount, invitedMembersCount, otherMemberIds)
    }

    private fun searchLastSentEventWithoutReadReceipts(receiptsByEvent: Map<String, List<ReadReceipt>>): String? {
        if (timeline?.isLive == false) {
            // If timeline is not live we don't want to show SentStatus
            return null
        }
        for (event in currentSnapshot) {
            // If there is any RR on the event, we stop searching for Sent event
            if (receiptsByEvent[event.eventId]?.isNotEmpty() == true) {
                return null
            }
            // If the event is not shown, we go to the next one
            if (!timelineEventVisibilityHelper.shouldShowEvent(
                            timelineEvent = event,
                            highlightedEventId = partialState.highlightedEventId,
                            isFromThreadTimeline = partialState.isFromThreadTimeline(),
                            rootThreadEventId = partialState.rootThreadEventId
                    )) {
                continue
            }
            // If the event is sent by us, we update the holder with the eventId and stop the search
            if (event.root.senderId == session.myUserId && event.root.sendState.isSent()) {
                return event.eventId
            }
        }
        return null
    }

    private fun preprocessReverseEvents() {
        readReceiptsCache.clear()
        timelineEventsGroups.clear()
        val itr = currentSnapshot.listIterator(currentSnapshot.size)
        var lastShownEventId: String? = null
        while (itr.hasPrevious()) {
            val event = itr.previous()
            timelineEventsGroups.addOrIgnore(event)
            val currentReadReceipts = event.readReceipts.filter {
                it.roomMember.userId != session.myUserId
            }
            if (timelineEventVisibilityHelper.shouldShowEvent(
                            timelineEvent = event,
                            highlightedEventId = partialState.highlightedEventId,
                            isFromThreadTimeline = partialState.isFromThreadTimeline(),
                            rootThreadEventId = partialState.rootThreadEventId
                    )) {
                lastShownEventId = event.eventId
            }
            if (lastShownEventId == null) {
                continue
            }
            readReceiptsCache.addReceiptsOnEvent(currentReadReceipts, lastShownEventId)
        }
    }

    private fun buildDaySeparatorItem(originServerTs: Long?): DaySeparatorItem {
        val formattedDay = dateFormatter.format(originServerTs, DateFormatKind.TIMELINE_DAY_DIVIDER)
        return DaySeparatorItem_().formattedDay(formattedDay).id(formattedDay)
    }

    private fun LoadingItem_.setVisibilityStateChangedListener(direction: Timeline.Direction, requestsMore: Boolean = true): LoadingItem_ {
        val host = this@TimelineEventController
        return onVisibilityStateChanged { _, _, visibilityState ->
            if (requestsMore && visibilityState == VisibilityState.VISIBLE) {
                host.callback?.onLoadMore(direction)
            }
        }
    }

    private fun updateUTDStates(event: TimelineEvent, nextEvent: TimelineEvent?) {
        if (vectorPreferences.labShowCompleteHistoryInEncryptedRoom()) {
            return
        }
        if (event.root.type == EventType.STATE_ROOM_MEMBER &&
                event.root.stateKey == session.myUserId) {
            val content = event.root.content.toModel<RoomMemberContent>()
            if (content?.membership == Membership.INVITE) {
                hasReachedInvite = true
            } else if (content?.membership == Membership.JOIN) {
                val prevContent = event.root.resolvedPrevContent().toModel<RoomMemberContent>()
                if (prevContent?.membership?.isActive() == false) {
                    hasReachedInvite = true
                }
            }
        }
        if (nextEvent?.root?.getClearType() == EventType.ENCRYPTED) {
            hasUTD = true
        }
    }

    private fun wantsDateSeparator(event: TimelineEvent, nextEvent: TimelineEvent?): Boolean {
        return when {
            hasReachedInvite && hasUTD -> true
            else -> {
                val date = event.root.localDateTime()
                val nextDate = nextEvent?.root?.localDateTime()
                date.toLocalDate() != nextDate?.toLocalDate()
            }
        }
    }

    /**
     * Return true if added.
     */
    private fun LoadingItem_.addWhenLoading(direction: Timeline.Direction): Boolean {
        val host = this@TimelineEventController
        val shouldAdd = host.timeline?.hasMoreToLoad(direction) ?: false
        addIf(shouldAdd, host)
        return shouldAdd
    }

    fun searchPositionOfEvent(eventId: String?): Int? = synchronized(positionsLock) {
        return adapterPositionMapping[eventId]
    }

    /**
     * Whether [eventId] is present in the timeline snapshot fed to the controller, regardless of whether a
     * model has been built (and thus mapped to an adapter position) for it yet. Used by the highlight-scroll
     * to tell "loaded, model not built yet — wait" apart from "not loaded — approximate/paginate".
     */
    fun isEventInSnapshot(eventId: String?): Boolean {
        eventId ?: return false
        return currentSnapshot.any { it.eventId == eventId }
    }

    /**
     * Like [searchPositionOfEvent], but when the target event has no rendered item (e.g. a hidden state
     * event, or one merged/aggregated away), fall back to the nearest event that does — so jumping to it
     * still lands in the right place instead of leaving the timeline blank.
     */
    fun searchPositionOfEventOrNearest(eventId: String?): Int? = synchronized(positionsLock) {
        adapterPositionMapping[eventId]?.let { return it }
        val targetIndex = currentSnapshot.indexOfFirst { it.eventId == eventId }.takeIf { it >= 0 } ?: return null
        for (distance in 1 until currentSnapshot.size) {
            currentSnapshot.getOrNull(targetIndex - distance)?.eventId?.let { adapterPositionMapping[it] }?.let { return it }
            currentSnapshot.getOrNull(targetIndex + distance)?.eventId?.let { adapterPositionMapping[it] }?.let { return it }
        }
        return null
    }

    /**
     * Return the newest timeline event still visible at or below the given adapter position.
     * The timeline is reverse-laid-out, so the smallest adapter position is the newest event.
     */
    fun getNewestVisibleEvent(firstVisibleAdapterPosition: Int): TimelineEvent? = synchronized(positionsLock) {
        val eventId = adapterPositionMapping.entries
                .filter { it.value >= firstVisibleAdapterPosition }
                .minByOrNull { it.value }
                ?.key
                ?: return null
        return currentSnapshot.firstOrNull { it.eventId == eventId }
    }

    fun getPositionOfReadMarker(): Int? = synchronized(positionsLock) {
        return positionOfReadMarker
    }

    private data class CacheItemData(
            val localId: Long,
            val eventId: String?,
            val readReceiptsItem: ReadReceiptsItem? = null,
            val eventModel: EpoxyModel<*>? = null,
            val mergedHeaderModel: BasedMergedItem<*>? = null,
            val formattedDayModel: DaySeparatorItem? = null,
            // A cheap stand-in built for an event collapsed into a merged run: its real (expensive) model
            // would be discarded by the collapse, so it's deferred until the run is expanded.
            val isCollapsedPlaceholder: Boolean = false,
            private val isCacheable: Boolean = true,
            // Displayable neighbours the event model's grouping (isFirst/LastFromThisSender, avatar/name
            // header, bubble shape) was computed from. Full references, not just ids: a neighbour whose
            // CONTENT changed (e.g. a redaction turning the message above into a placeholder) must also
            // trigger a rebuild. The SDK memoizes mapped events, so the comparison is an identity check
            // for unchanged rows.
            val builtPrevDisplayable: TimelineEvent? = null,
            val builtNextDisplayable: TimelineEvent? = null,
            // Inputs the enrichment step (receipts/day-separator/merged-header models) was computed
            // from, so unchanged events can skip it on subsequent passes.
            val enrichedNextEventId: String? = null,
            val enrichedPrevEventId: String? = null,
            val enrichedReceipts: List<ReadReceipt>? = null,
            val enrichedMergeSignature: Int = 0,
            val enrichedCreationSummaryStamp: List<Any?>? = null,
    ) {
        fun isCacheable(partialState: PartialState): Boolean {
            return isCacheable && partialState.highlightedEventId != eventId
        }
    }
}
