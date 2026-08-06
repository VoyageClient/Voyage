/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.localDateTime
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.AvatarSizeProvider
import im.vector.app.features.home.room.detail.timeline.helper.MergedTimelineEventVisibilityStateChangedListener
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventVisibilityHelper
import im.vector.app.features.home.room.detail.timeline.helper.isRoomConfiguration
import im.vector.app.features.home.room.detail.timeline.helper.timelineMergeGroupType
import im.vector.app.features.home.room.detail.timeline.item.BasedMergedItem
import im.vector.app.features.home.room.detail.timeline.item.MergedRoomCreationItem
import im.vector.app.features.home.room.detail.timeline.item.MergedRoomCreationItem_
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem
import im.vector.app.features.home.room.detail.timeline.item.MergedSimilarEventsItem_
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.redaction.preservation.RedactedContentRestorer
import im.vector.lib.strings.CommonPlurals
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getRoomPowerLevels
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.threeten.bp.LocalDate
import java.util.Objects
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Groups consecutive timeline events into collapsible "merged" summaries (membership/ACL/image-pack changes,
 * redacted messages, hidden debug events, and the room-creation block).
 *
 * The set of runs is derived from scratch on every snapshot by [computeRuns] — a single forward walk that
 * classifies each event into exactly one merge kind — so a structural change (e.g. a redaction splitting a
 * run) can never leave events stuck in a stale hidden-set. Collapse state is stored per run identity and
 * reconciled against the freshly computed runs each pass; the derived collapsed-id set is what the controller
 * hides. Toggling collapse only records intent + bumps [collapseGeneration]; the actual set is recomputed on
 * the next build pass, so the main-thread click and the background build never race on shared mutable sets.
 */
class MergedHeaderItemFactory @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val avatarRenderer: AvatarRenderer,
        private val avatarSizeProvider: AvatarSizeProvider,
        private val timelineEventVisibilityHelper: TimelineEventVisibilityHelper,
        private val redactedContentRestorer: RedactedContentRestorer,
) {

    private val mergeableEventTypes = listOf(
            EventType.STATE_ROOM_MEMBER,
            EventType.STATE_ROOM_SERVER_ACL,
            EventType.STATE_ROOM_IMAGE_PACK,
            EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE,
    )

    private enum class RunKind { SAME_TYPE, REDACTED, HIDDEN, ROOM_CREATION }

    /**
     * A maximal run of consecutive events that collapse into one summary.
     * @param identity stable-ish key for collapse persistence (newest member's localId, or the create event's).
     * @param anchorLocalId the oldest member; the header model is attached to this position.
     * @param members all member events, ordered oldest→newest (index-descending); includes the anchor.
     */
    private data class MergedRun(
            val kind: RunKind,
            val identity: Long,
            val anchorLocalId: Long,
            val members: List<TimelineEvent>,
            val epoxyId: String,
            val summaryTitleResId: Int?,
            val encryptionAlgorithm: String? = null,
            val hasEncryption: Boolean = false,
    )

    // Guards collapseStates. The derived sets/maps below are swapped wholesale (immutable snapshots) so
    // readers on the build thread never need the lock.
    private val collapseLock = Any()

    // Desired collapse per run identity; true = collapsed. Migrated across snapshots by member localId.
    private val collapseStates = HashMap<Long, Boolean>()

    @Volatile private var collapsedLocalIds: Set<Long> = emptySet()
    @Volatile private var anchorLocalIds: Set<Long> = emptySet()
    @Volatile private var runsByAnchor: Map<Long, MergedRun> = emptyMap()

    /** Bumped on every collapse toggle so the controller invalidates its per-snapshot processing cache. */
    private val collapseGenerationCounter = AtomicInteger(0)
    val collapseGeneration: Int get() = collapseGenerationCounter.get()

    /**
     * Recompute all runs for [snapshot] and reconcile collapse state. Must run in the controller's
     * per-snapshot preprocess, before the displayable-neighbour computation (which excludes collapsed
     * members) and the per-position build loop (which reads [isCollapsed] / [isMergedAnchor]).
     *
     * @return per-position "shown in timeline" flags (ignoring collapse) computed as a side effect, so the
     *   controller can reuse them for neighbour computation instead of running [shouldShowEvent] again.
     */
    fun updateRuns(
            snapshot: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            forcedVisibleEventIds: Set<String>,
    ): BooleanArray {
        val shown = BooleanArray(snapshot.size)
        val runs = computeRuns(snapshot, partialState, forcedVisibleEventIds, shown)
        synchronized(collapseLock) {
            val newStates = HashMap<Long, Boolean>(runs.size)
            val collapsed = HashSet<Long>()
            val anchors = HashSet<Long>(runs.size)
            val byAnchor = HashMap<Long, MergedRun>(runs.size)
            val highlightId = partialState.highlightedEventId
            runs.forEach { run ->
                val prior = collapseStates[run.identity]
                        ?: run.members.firstNotNullOfOrNull { collapseStates[it.localId] }
                        ?: true
                // Jumping to an event inside a collapsed run has to reveal it, and the run stays open
                // afterwards: re-collapsing the moment the highlight clears would hide it again.
                val holdsTarget = highlightId != null && run.members.any { it.eventId == highlightId }
                val nowCollapsed = prior && !holdsTarget
                newStates[run.identity] = nowCollapsed
                anchors.add(run.anchorLocalId)
                byAnchor[run.anchorLocalId] = run
                if (nowCollapsed) run.members.forEach { collapsed.add(it.localId) }
            }
            collapseStates.clear()
            collapseStates.putAll(newStates)
            collapsedLocalIds = collapsed
            anchorLocalIds = anchors
            runsByAnchor = byAnchor
        }
        return shown
    }

    private fun computeRuns(
            snapshot: List<TimelineEvent>,
            partialState: TimelineEventController.PartialState,
            forcedVisibleEventIds: Set<String>,
            shown: BooleanArray,
    ): List<MergedRun> {
        val runs = ArrayList<MergedRun>()
        // Room creation is a special, contiguous block anchored on the (immutable) create event; compute it
        // first and exclude its members from the generic walk so no event lands in two runs.
        val creationRun = computeRoomCreationRun(snapshot)
        creationRun?.let { runs.add(it) }
        val creationMemberIds = creationRun?.members?.mapTo(HashSet()) { it.localId } ?: emptySet<Long>()

        val highlightId = partialState.highlightedEventId
        val isThread = partialState.isFromThreadTimeline()
        val rootThreadId = partialState.rootThreadEventId

        var members: ArrayList<TimelineEvent>? = null
        var kind: RunKind? = null
        var groupType: String? = null
        var date: LocalDate? = null

        fun flush() {
            val m = members
            val k = kind
            if (m != null && k != null) addSimilarRunIfBigEnough(runs, k, m)
            members = null; kind = null; groupType = null; date = null
        }

        // Snapshot is newest→oldest (index 0 = newest). Walking index-ascending visits newest first, so a
        // collected list is [newest … oldest]; runs are stored oldest→newest to match the legacy avatar order.
        for (i in snapshot.indices) {
            val event = snapshot[i]
            val isShown = timelineEventVisibilityHelper.shouldShowEvent(event, highlightId, isThread, rootThreadId, forcedVisibleEventIds)
            shown[i] = isShown
            if (event.localId in creationMemberIds) { flush(); continue }
            when (val cls = classify(event, isShown, isThread, rootThreadId)) {
                Classification.Skip -> Unit // transparent: neither joins nor breaks a run
                Classification.Wall -> flush()
                is Classification.Run -> {
                    val eventDate = event.root.localDateTime().toLocalDate()
                    val continues = members != null && kind == cls.kind &&
                            (cls.kind != RunKind.SAME_TYPE || groupType == cls.groupType) &&
                            date == eventDate
                    if (!continues) {
                        flush()
                        members = ArrayList()
                        kind = cls.kind
                        groupType = cls.groupType
                        date = eventDate
                    }
                    members!!.add(event)
                }
            }
        }
        flush()
        return runs
    }

    private sealed interface Classification {
        object Skip : Classification
        object Wall : Classification
        data class Run(val kind: RunKind, val groupType: String?) : Classification
    }

    private fun classify(
            event: TimelineEvent,
            shown: Boolean,
            isThread: Boolean,
            rootThreadId: String?,
    ): Classification {
        // A revealed message is showing real content, so it must not be folded into the deleted summary.
        val redacted = event.root.isRedacted() && !redactedContentRestorer.isShowingRestoredContent(event)
        if (redacted) {
            // A shown redaction groups into the redacted summary; a hidden one (e.g. a redacted reaction) is
            // transparent so it neither fragments a surrounding run nor renders on its own.
            return if (shown) Classification.Run(RunKind.REDACTED, null) else Classification.Skip
        }
        if (timelineEventVisibilityHelper.isHiddenEvent(event, rootThreadId, isThread)) {
            return Classification.Run(RunKind.HIDDEN, null)
        }
        if (!shown) return Classification.Skip
        if (event.root.getClearType() in mergeableEventTypes) {
            return Classification.Run(RunKind.SAME_TYPE, event.root.timelineMergeGroupType())
        }
        return Classification.Wall
    }

    private fun addSimilarRunIfBigEnough(runs: MutableList<MergedRun>, kind: RunKind, collected: List<TimelineEvent>) {
        if (collected.size < MIN_NUMBER_OF_MERGED_EVENTS) return
        val members = collected.asReversed() // oldest→newest
        val newest = collected.first()
        val oldest = members.first()
        val title = when (kind) {
            RunKind.HIDDEN -> CommonPlurals.merged_hidden_events
            RunKind.REDACTED -> CommonPlurals.room_redacted_messages
            RunKind.SAME_TYPE -> sameTypeTitle(oldest.root) ?: return
            RunKind.ROOM_CREATION -> return
        }
        runs.add(
                MergedRun(
                        kind = kind,
                        identity = newest.localId,
                        anchorLocalId = oldest.localId,
                        members = members.toList(),
                        epoxyId = "merged_${newest.localId}",
                        summaryTitleResId = title,
                )
        )
    }

    private fun sameTypeTitle(event: Event): Int? = when (event.getClearType()) {
        EventType.STATE_ROOM_MEMBER -> CommonPlurals.membership_changes
        EventType.STATE_ROOM_SERVER_ACL -> CommonPlurals.notice_room_server_acl_changes
        EventType.STATE_ROOM_IMAGE_PACK,
        EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE -> CommonPlurals.image_pack_changes
        else -> null
    }

    private fun computeRoomCreationRun(snapshot: List<TimelineEvent>): MergedRun? {
        val createIndex = snapshot.indexOfFirst { it.root.getClearType() == EventType.STATE_ROOM_CREATE }
        if (createIndex <= 0) return null
        val createEvent = snapshot[createIndex]
        val creator = createEvent.root.getClearContent().toModel<RoomCreateContent>()?.creator
        // The anchor is the config event directly newer than create; it may be the creator's own join, so it
        // is matched against the creator. The rest of the block is pure (non-member) configuration.
        val anchor = snapshot[createIndex - 1]
        if (!anchor.isRoomConfiguration(creator)) return null
        val members = ArrayList<TimelineEvent>()
        members.add(anchor)
        var pos = createIndex - 2
        while (pos >= 0) {
            val candidate = snapshot[pos]
            if (!candidate.isRoomConfiguration(null)) break
            members.add(candidate)
            pos--
        }
        if (members.size <= MIN_NUMBER_OF_MERGED_EVENTS) return null
        var hasEncryption = false
        var encryptionAlgorithm: String? = null
        members.forEach { member ->
            if (member.root.isStateEvent() && member.root.getClearType() == EventType.STATE_ROOM_ENCRYPTION) {
                hasEncryption = true
                encryptionAlgorithm = member.root.getClearContent().toModel<EncryptionEventContent>()?.algorithm
            }
        }
        return MergedRun(
                kind = RunKind.ROOM_CREATION,
                identity = createEvent.localId,
                anchorLocalId = anchor.localId,
                members = members, // oldest→newest
                epoxyId = "creation_${createEvent.localId}",
                summaryTitleResId = null,
                encryptionAlgorithm = encryptionAlgorithm,
                hasEncryption = hasEncryption,
        )
    }

    /** True when [localId] is the anchor (oldest member) of a run — i.e. the position carrying its header. */
    fun isMergedAnchor(localId: Long): Boolean = localId in anchorLocalIds

    fun isCollapsed(localId: Long): Boolean = localId in collapsedLocalIds

    /**
     * A cheap signature over the run anchored at [localId] (its identity, member set, and collapsed flag), so
     * the controller's enrichment can detect when a header needs rebuilding even though the event's own
     * neighbours are unchanged (e.g. a nearby redaction grew or split the run).
     */
    fun mergeSignatureAt(localId: Long): Int {
        val run = runsByAnchor[localId] ?: return 0
        return Objects.hash(run.identity, run.members.map { it.localId }, localId in collapsedLocalIds)
    }

    private fun setCollapsed(identity: Long, collapsed: Boolean, requestModelBuild: () -> Unit) {
        synchronized(collapseLock) { collapseStates[identity] = collapsed }
        collapseGenerationCounter.incrementAndGet()
        requestModelBuild()
    }

    /**
     * Build the merged-header model for the run anchored at [event], or null if [event] is not an anchor.
     */
    fun create(
            event: TimelineEvent,
            partialState: TimelineEventController.PartialState,
            eventIdToHighlight: String?,
            callback: TimelineEventController.Callback?,
            requestModelBuild: () -> Unit,
    ): BasedMergedItem<*>? {
        val run = runsByAnchor[event.localId] ?: return null
        return when (run.kind) {
            RunKind.ROOM_CREATION -> buildRoomCreationHeader(run, partialState, eventIdToHighlight, callback, requestModelBuild)
            else -> buildSimilarEventsHeader(run, partialState, eventIdToHighlight, callback, requestModelBuild)
        }
    }

    private fun buildSimilarEventsHeader(
            run: MergedRun,
            partialState: TimelineEventController.PartialState,
            eventIdToHighlight: String?,
            callback: TimelineEventController.Callback?,
            requestModelBuild: () -> Unit,
    ): MergedSimilarEventsItem_? {
        val summaryTitle = run.summaryTitleResId ?: return null
        var highlighted = false
        val mergedData = ArrayList<BasedMergedItem.Data>(run.members.size)
        run.members.forEach { mergedEvent ->
            if (!highlighted && mergedEvent.root.eventId == eventIdToHighlight) {
                highlighted = true
            }
            mergedData.add(mergedEvent.toMergedData(partialState))
        }
        val isCollapsed = isCollapsed(run.anchorLocalId)
        val attributes = MergedSimilarEventsItem.Attributes(
                summaryTitleResId = summaryTitle,
                isCollapsed = isCollapsed,
                mergeData = mergedData,
                avatarRenderer = avatarRenderer,
                onCollapsedStateChanged = { setCollapsed(run.identity, it, requestModelBuild) }
        )
        return MergedSimilarEventsItem_()
                .id(run.epoxyId)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .highlighted(isCollapsed && highlighted)
                .attributes(attributes)
                .also {
                    it.setOnVisibilityStateChanged(MergedTimelineEventVisibilityStateChangedListener(callback, run.members))
                }
    }

    private fun buildRoomCreationHeader(
            run: MergedRun,
            partialState: TimelineEventController.PartialState,
            eventIdToHighlight: String?,
            callback: TimelineEventController.Callback?,
            requestModelBuild: () -> Unit,
    ): MergedRoomCreationItem_? {
        var highlighted = false
        val mergedData = ArrayList<BasedMergedItem.Data>(run.members.size)
        run.members.asReversed().forEach { mergedEvent ->
            if (!highlighted && mergedEvent.root.eventId == eventIdToHighlight) {
                highlighted = true
            }
            mergedData.add(mergedEvent.toMergedData(partialState))
        }
        val isCollapsed = isCollapsed(run.anchorLocalId)
        val roomPowerLevels = activeSessionHolder.getSafeActiveSession()?.getRoom(run.members.first().roomId)?.getRoomPowerLevels()
        val currentUserId = activeSessionHolder.getSafeActiveSession()?.myUserId ?: ""
        val attributes = MergedRoomCreationItem.Attributes(
                isCollapsed = isCollapsed,
                mergeData = mergedData,
                avatarRenderer = avatarRenderer,
                onCollapsedStateChanged = { setCollapsed(run.identity, it, requestModelBuild) },
                hasEncryptionEvent = run.hasEncryption,
                isEncryptionAlgorithmSecure = run.encryptionAlgorithm == MXCRYPTO_ALGORITHM_MEGOLM,
                callback = callback,
                currentUserId = currentUserId,
                roomSummary = partialState.roomSummary,
                canInvite = roomPowerLevels?.isUserAbleToInvite(currentUserId) ?: false,
                canChangeAvatar = roomPowerLevels?.isUserAllowedToSend(currentUserId, true, EventType.STATE_ROOM_AVATAR) ?: false,
                canChangeTopic = roomPowerLevels?.isUserAllowedToSend(currentUserId, true, EventType.STATE_ROOM_TOPIC) ?: false,
                canChangeName = roomPowerLevels?.isUserAllowedToSend(currentUserId, true, EventType.STATE_ROOM_NAME) ?: false
        )
        return MergedRoomCreationItem_()
                .id(run.epoxyId)
                .leftGuideline(avatarSizeProvider.leftGuideline)
                .highlighted(isCollapsed && highlighted)
                .attributes(attributes)
                .movementMethod(createLinkMovementMethod(callback))
                .also {
                    it.setOnVisibilityStateChanged(MergedTimelineEventVisibilityStateChangedListener(callback, run.members))
                }
    }

    private fun TimelineEvent.toMergedData(partialState: TimelineEventController.PartialState) = BasedMergedItem.Data(
            roomId = root.roomId,
            userId = root.senderId ?: "",
            avatarUrl = senderInfo.avatarUrl,
            memberName = senderInfo.disambiguatedDisplayName,
            localId = localId,
            eventId = root.eventId ?: "",
            isDirectRoom = partialState.isDirectRoom()
    )

    private fun TimelineEventController.PartialState.isDirectRoom(): Boolean {
        return roomSummary?.isDirect.orFalse()
    }

    companion object {
        private const val MIN_NUMBER_OF_MERGED_EVENTS = 2
    }
}
