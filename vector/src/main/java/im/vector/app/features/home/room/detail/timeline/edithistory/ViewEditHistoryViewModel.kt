/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.edithistory

import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.EmptyAction
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.redaction.preservation.relationType
import im.vector.app.features.redaction.preservation.toEvent
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.events.model.isReply
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber
import java.util.UUID

class ViewEditHistoryViewModel @AssistedInject constructor(
        @Assisted initialState: ViewEditHistoryViewState,
        private val session: Session
) : VectorViewModel<ViewEditHistoryViewState, EmptyAction, EmptyViewEvents>(initialState) {

    private val roomId = initialState.roomId
    private val eventId = initialState.eventId
    private val room = session.getRoom(roomId)
            ?: throw IllegalStateException("Shouldn't use this ViewModel without a room")

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<ViewEditHistoryViewModel, ViewEditHistoryViewState> {
        override fun create(initialState: ViewEditHistoryViewState): ViewEditHistoryViewModel
    }

    companion object : MavericksViewModelFactory<ViewEditHistoryViewModel, ViewEditHistoryViewState> by hiltMavericksViewModelFactory()

    init {
        loadHistory()
    }

    private suspend fun restorePreservedContent(event: Event): Event {
        if (!event.isRedacted()) return event
        val preserved = session.redactedContentService().getPreservedContent(event.eventId ?: return event) ?: return event
        return event.copyAll(
                type = preserved.clearType?.takeIf { it.isNotEmpty() } ?: event.type,
                content = preserved.content,
                unsignedData = event.unsignedData?.copy(redactedEvent = null, redactedBy = null),
                mxDecryptionResult = null,
        )
    }

    private fun loadHistory() {
        setState { copy(editList = Loading()) }

        viewModelScope.launch {
            val data = try {
                // A redacted message's edits are dropped from the server's relations (their relation
                // is pruned with them), so the fetched history is typically just the original. Rebuild
                // the rest from the message logger's preserved copies: restore pruned entries, add the
                // preserved edits the server no longer returns, and keep newest-first with the
                // original last (the order the fetch produces).
                val fetched = room.relationService().fetchEditHistory(eventId).map { restorePreservedContent(it) }
                val fetchedIds = fetched.mapNotNull { it.eventId }.toHashSet()
                val preservedEdits = session.redactedContentService().getPreservedRelationsOf(room.roomId, eventId)
                        .filter { it.relationType() == RelationType.REPLACE && it.eventId !in fetchedIds }
                        .map { it.toEvent() }
                val (edits, original) = (fetched + preservedEdits).partition { it.eventId != eventId }
                edits.sortedByDescending { it.originServerTs ?: 0 } + original
            } catch (failure: Throwable) {
                setState {
                    copy(editList = Fail(failure))
                }
                return@launch
            }

            var originalIsReply = false

            data.forEach { event ->
                val timelineID = event.roomId + UUID.randomUUID().toString()
                // We need to check encryption
                if (event.isEncrypted() && event.mxDecryptionResult == null) {
                    // for now decrypt sync
                    try {
                        val result = session.cryptoService().decryptEvent(event, timelineID)
                        event.mxDecryptionResult = OlmDecryptionResult(
                                payload = result.clearEvent,
                                senderKey = result.senderCurve25519Key,
                                keysClaimed = result.claimedEd25519Key?.let { k -> mapOf("ed25519" to k) },
                                forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                                verificationState = result.messageVerificationState
                        )
                    } catch (e: MXCryptoError) {
                        Timber.w("Failed to decrypt event in history")
                    }
                }

                if (event.eventId == eventId) {
                    originalIsReply = event.isReply()
                }
            }
            setState {
                copy(
                        editList = Success(data),
                        isOriginalAReply = originalIsReply
                )
            }
        }
    }

    override fun handle(action: EmptyAction) {
        // No op
    }
}
