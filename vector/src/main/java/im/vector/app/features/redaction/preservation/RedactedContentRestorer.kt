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
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.events.model.isRedacted
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
        return event.copy(root = restoredRoot)
    }
}
