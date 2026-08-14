/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.home.room.detail.timeline.factory.TimelineItemFactoryParams
import im.vector.app.features.home.room.detail.timeline.helper.TimelineDisplayableEvents
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.ReactionAggregatedSummary
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts preserved content back onto a redacted event so the ordinary per-type factories render it —
 * images stay images, files stay files — instead of everything collapsing to the redacted tile.
 *
 * The substituted event drops `unsigned.redacted_because`, because every downstream check keys off
 * [isRedacted]; the fact that it *was* redacted travels separately via
 * [TimelineItemFactoryParams.isRevealedRedaction] so the item can still be marked as such.
 */
@Singleton
class RedactedContentRestorer @Inject constructor(
        private val revealManager: RedactedContentRevealManager,
        private val repository: RedactedContentRepository,
        // Lazy: ActiveSessionHolder reaches this class through ConfigureAndStartSessionUseCase.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
) {

    fun restore(params: TimelineItemFactoryParams): TimelineItemFactoryParams {
        val restored = restoreEvent(params.event) ?: return params
        return params.copy(event = restored, isRevealedRedaction = true)
    }

    /**
     * Whether [event] is currently rendering restored content, so callers that branch on
     * `isRedacted()` don't treat it as deleted. Free of the side effects of [restoreEvent] — no
     * fetch is started — so it is safe from grouping and visibility passes.
     */
    fun isShowingRestoredContent(event: TimelineEvent): Boolean {
        val root = event.root
        if (!root.isRedacted() || root.isStateEvent()) return false
        if (repository.cachedContent(event.eventId) == null) return false
        val roomId = root.roomId ?: return false
        val isOwnMessage = root.senderId != null && root.senderId == activeSessionHolder.get().getSafeActiveSession()?.myUserId
        return revealManager.isRevealed(roomId, event.eventId, isOwnMessage)
    }

    /**
     * True when what [event] reveals is something the timeline hides by default — an m.replace edit
     * (its content shows applied on the original) or a non-displayable type like a reaction (its
     * pill shows on the target). The raw event then hides like its unredacted self would, visible
     * only through show-hidden-events.
     */
    fun isRevealedHiddenEvent(event: TimelineEvent): Boolean {
        if (!isShowingRestoredContent(event)) return false
        val body = repository.cachedContent(event.eventId) ?: return false
        val relates = body.content["m.relates_to"] as? Map<*, *>
        if (relates?.get("rel_type") == RelationType.REPLACE) return true
        val clearType = body.clearType ?: return false
        return clearType !in TimelineDisplayableEvents.DISPLAYABLE_TYPES
    }

    /** @return the event with its pre-redaction content put back, or null if it should stay redacted. */
    fun restoreEvent(event: TimelineEvent): TimelineEvent? {
        val root = event.root
        if (!root.isRedacted() || root.isStateEvent()) return null
        val roomId = root.roomId ?: return null
        val isOwnMessage = root.senderId != null && root.senderId == activeSessionHolder.get().getSafeActiveSession()?.myUserId
        if (!revealManager.isRevealed(roomId, event.eventId, isOwnMessage)) return null

        val preserved = repository.cachedContent(event.eventId)
        if (preserved == null) {
            // Not resolved yet: keep rendering the redacted tile and let the fetch call us back.
            repository.requestContent(roomId, event.eventId)
            return null
        }

        // copyAll (not copy): Event's decryption result, crypto error and thread details are transient
        // vars outside the constructor, so a data-class copy would silently drop them.
        //
        // The type has to be restored too. A redacted encrypted event stays m.room.encrypted with its
        // decryption result nulled, so getClearType()/getClearContent() would keep reporting it as
        // undecryptable and route it to the encrypted renderer whatever content we put back.
        val restoredRoot = root.copyAll(
                type = preserved.clearType?.takeIf { it.isNotEmpty() } ?: root.type,
                content = preserved.content,
                // Both fields, not just redacted_because: isRedacted() is an OR over the two, and
                // Synapse sets redacted_by alone whenever it can't inline the redaction event.
                unsignedData = root.unsignedData?.copy(redactedEvent = null, redactedBy = null)
                        ?: UnsignedData(null, null),
                mxDecryptionResult = null,
        )
        return event.copy(root = restoredRoot, annotations = restoredAnnotations(event, preserved))
    }

    /**
     * A message's relations (edits, reactions, …) are redacted — and preserved — along with it, so
     * their aggregations are rebuilt from the preserved copies. The ordinary machinery then does the
     * rest — latest-edit rendering, the "(edited)" marker, the edit-history action, reaction pills —
     * while the root keeps the ORIGINAL content (view source must show the event as sent, not its
     * latest edit).
     */
    private fun restoredAnnotations(event: TimelineEvent, preserved: RedactedContentRepository.PreservedBody): EventAnnotationsSummary? {
        if (preserved.relations.isEmpty()) return event.annotations
        val edits = preserved.relations.filter { it.relationType() == RelationType.REPLACE }
        val editSummary = edits.lastOrNull()?.let { latest ->
            EditAggregatedSummary(
                    latestEdit = latest.toEvent(),
                    sourceEvents = edits.map { it.eventId },
                    localEchos = emptyList(),
                    lastEditTs = latest.originServerTs ?: 0,
            )
        }
        val reactionsSummary = restoredReactions(event, preserved)
        if (editSummary == null && reactionsSummary == null) return event.annotations
        return (event.annotations ?: EventAnnotationsSummary()).copy(
                editSummary = editSummary ?: event.annotations?.editSummary,
                reactionsSummary = reactionsSummary ?: event.annotations?.reactionsSummary.orEmpty(),
        )
    }

    private fun restoredReactions(event: TimelineEvent, preserved: RedactedContentRepository.PreservedBody): List<ReactionAggregatedSummary>? {
        val live = event.annotations?.reactionsSummary.orEmpty()
        // Anything the live aggregation still knows about was never redacted, so it must not be
        // counted a second time from the preserved copies.
        val liveIds = live.flatMapTo(HashSet()) { it.sourceEvents }
        val myUserId = activeSessionHolder.get().getSafeActiveSession()?.myUserId
        val restored = preserved.relations
                .filter { it.relationType() == RelationType.ANNOTATION && it.eventId !in liveIds }
                .groupBy { it.relationKey() ?: return@groupBy "" }
                .filterKeys { it.isNotEmpty() }
        if (restored.isEmpty()) return null
        val merged = live.toMutableList()
        restored.forEach { (key, reactions) ->
            val existing = merged.indexOfFirst { it.key == key }
            val addedByMe = reactions.any { it.senderId != null && it.senderId == myUserId }
            if (existing >= 0) {
                val summary = merged[existing]
                merged[existing] = summary.copy(
                        count = summary.count + reactions.size,
                        addedByMe = summary.addedByMe || addedByMe,
                        sourceEvents = summary.sourceEvents + reactions.map { it.eventId },
                )
            } else {
                merged.add(
                        ReactionAggregatedSummary(
                                key = key,
                                count = reactions.size,
                                addedByMe = addedByMe,
                                firstTimestamp = reactions.minOf { it.originServerTs ?: 0 },
                                sourceEvents = reactions.map { it.eventId },
                                localEchoEvents = emptyList(),
                        )
                )
            }
        }
        return merged
    }
}
