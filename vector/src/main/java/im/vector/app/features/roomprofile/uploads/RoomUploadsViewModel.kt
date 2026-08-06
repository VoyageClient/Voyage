/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads

import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.redaction.preservation.PreservedAttachmentResolver
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap

class RoomUploadsViewModel @AssistedInject constructor(
        @Assisted initialState: RoomUploadsViewState,
        private val session: Session,
        private val preservedAttachmentResolver: PreservedAttachmentResolver,
) : VectorViewModel<RoomUploadsViewState, RoomUploadsAction, RoomUploadsViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomUploadsViewModel, RoomUploadsViewState> {
        override fun create(initialState: RoomUploadsViewState): RoomUploadsViewModel
    }

    companion object : MavericksViewModelFactory<RoomUploadsViewModel, RoomUploadsViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)!!

    init {
        observeRoomSummary()
        // Send a first request
        handleLoadMore()
    }

    private fun observeRoomSummary() {
        room.flow().liveRoomSummary()
                .unwrap()
                .execute { async ->
                    copy(roomSummary = async)
                }
    }

    private fun handleLoadMore() = withState { state ->
        if (state.asyncEventsRequest is Loading) return@withState
        if (!state.hasMore) return@withState

        setState {
            copy(
                    asyncEventsRequest = Loading()
            )
        }

        viewModelScope.launch {
            try {
                val result = room.uploadsService().getUploads(20, token)
                if (pendingPreserved == null) {
                    // Redacted uploads are absent from the server's pagination, so they are read once
                    // and then released page by page (see releasePreserved).
                    pendingPreserved = preservedAttachmentResolver.uploads(room.roomId)
                }

                token = result.nextToken

                val newEvents = result.uploadEvents + releasePreserved(result.uploadEvents, result.hasMore)
                val groupedUploadEvents = newEvents
                        .groupBy {
                            it.contentWithAttachmentContent.msgType == MessageType.MSGTYPE_IMAGE ||
                                    it.contentWithAttachmentContent.msgType == MessageType.MSGTYPE_VIDEO ||
                                    it.contentWithAttachmentContent.msgType == MessageType.MSGTYPE_STICKER_LOCAL
                        }

                setState {
                    copy(
                            asyncEventsRequest = Success(Unit),
                            mediaEvents = (this.mediaEvents + groupedUploadEvents[true].orEmpty()).newestFirst(),
                            fileEvents = (this.fileEvents + groupedUploadEvents[false].orEmpty()).newestFirst(),
                            hasMore = result.hasMore
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(RoomUploadsViewEvents.Failure(failure))
                setState {
                    copy(
                            asyncEventsRequest = Fail(failure)
                    )
                }
            }
        }
    }

    private var token: String? = null

    // Read on the first page, then drained as pagination reaches each item's point in time.
    private var pendingPreserved: List<UploadEvent>? = null

    /**
     * Redacted uploads all exist locally from the start, so releasing them at once would pin every one
     * of them to the top of a list the server hands back newest-first. Release only those at least as
     * new as the oldest event just loaded — they belong inside the window now on screen — and the rest
     * when there is no more history to wait for.
     */
    private fun releasePreserved(page: List<UploadEvent>, hasMore: Boolean): List<UploadEvent> {
        val pending = pendingPreserved.orEmpty()
        if (pending.isEmpty()) return emptyList()
        if (!hasMore) {
            pendingPreserved = emptyList()
            return pending
        }
        // An empty page pins nothing, so holding the rest back would strand them behind a paginator
        // that may never return anything again.
        val oldestLoadedTs = page.minOfOrNull { it.root.originServerTs ?: Long.MAX_VALUE }
        if (oldestLoadedTs == null) {
            pendingPreserved = emptyList()
            return pending
        }
        val (release, keep) = pending.partition { (it.root.originServerTs ?: 0L) >= oldestLoadedTs }
        pendingPreserved = keep
        return release
    }

    private fun List<UploadEvent>.newestFirst() = sortedByDescending { it.root.originServerTs ?: 0L }

    override fun handle(action: RoomUploadsAction) {
        when (action) {
            is RoomUploadsAction.Download -> handleDownload(action)
            is RoomUploadsAction.Share -> handleShare(action)
            RoomUploadsAction.Retry -> handleLoadMore()
            RoomUploadsAction.LoadMore -> handleLoadMore()
        }
    }

    private fun handleShare(action: RoomUploadsAction.Share) {
        viewModelScope.launch {
            val event = try {
                val file = session.fileService().downloadFile(
                        messageContent = action.uploadEvent.contentWithAttachmentContent
                )
                RoomUploadsViewEvents.FileReadyForSharing(file)
            } catch (failure: Throwable) {
                RoomUploadsViewEvents.Failure(failure)
            }
            _viewEvents.post(event)
        }
    }

    private fun handleDownload(action: RoomUploadsAction.Download) {
        viewModelScope.launch {
            val event = try {
                val file = session.fileService().downloadFile(
                        messageContent = action.uploadEvent.contentWithAttachmentContent
                )
                RoomUploadsViewEvents.FileReadyForSaving(file, action.uploadEvent.contentWithAttachmentContent.body)
            } catch (failure: Throwable) {
                RoomUploadsViewEvents.Failure(failure)
            }
            _viewEvents.post(event)
        }
    }
}
