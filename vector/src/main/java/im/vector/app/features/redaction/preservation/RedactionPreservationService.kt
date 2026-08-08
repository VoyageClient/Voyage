/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import org.matrix.android.sdk.api.session.LiveEventListener
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.util.JsonDict
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps content that a redaction would otherwise destroy: when a redaction turns up, the original
 * is fetched via MSC2815 before the server's retention window closes on it.
 *
 * That has to work with no room open, so this listens on the session's event stream rather than on
 * a timeline.
 */
@Singleton
class RedactionPreservationService @Inject constructor(
        private val settings: RedactionPreservationSettings,
        private val repository: RedactedContentRepository,
        private val mediaPreserver: PreservedMediaPreserver,
) : LiveEventListener {

    private var boundSession: Session? = null

    fun start(session: Session) {
        if (boundSession === session) return
        stop()
        boundSession = session
        session.eventStreamService().addEventStreamListener(this)
    }

    fun stop() {
        val previous = boundSession ?: return
        previous.eventStreamService().removeEventStreamListener(this)
        boundSession = null
    }

    /**
     * Sign-out only. The preservation DB lives in the session directory and goes with it, but the media
     * sits under filesDir, so it has to be removed explicitly — and only here: [stop] also runs when
     * merely switching accounts, where the other account's copies must survive.
     */
    suspend fun clearForSignedOutUser(userId: String) = mediaPreserver.clearForUser(userId)

    override fun onLiveEvent(roomId: String, event: Event) {
        if (event.type == EventType.REDACTION) {
            onRedactionReceived(roomId, event)
        } else {
            repository.carryOverSuppression(roomId, event)
        }
    }

    override fun onPaginatedEvent(roomId: String, event: Event) = Unit

    override fun onEventDecrypted(event: Event, clearEvent: JsonDict) = Unit

    override fun onEventDecryptionError(event: Event, cryptoError: MXCryptoError) = Unit

    override fun onLiveToDeviceEvent(event: Event) = Unit

    /**
     * The redaction's own content is already gone by the time it reaches us, so this fetches the
     * target rather than reading anything off the redaction event.
     */
    private fun onRedactionReceived(roomId: String, event: Event) {
        if (!settings.preserveRedactedFor(roomId)) return
        val targetId = event.redacts ?: return
        repository.requestContent(roomId, targetId)
    }
}
