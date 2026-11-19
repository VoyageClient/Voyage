/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

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
import im.vector.app.features.home.room.detail.poll.VoteToPollUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.isEdition
import org.matrix.android.sdk.api.session.events.model.isPollStart
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.search.EventAndSender
import org.matrix.android.sdk.api.session.search.SearchResult

class SearchViewModel @AssistedInject constructor(
        @Assisted private val initialState: SearchViewState,
        private val session: Session,
        private val voteToPollUseCase: VoteToPollUseCase,
) : VectorViewModel<SearchViewState, SearchAction, SearchViewEvents>(initialState) {

    private val room = session.getRoom(initialState.roomId)

    private var currentTask: Job? = null

    private val observedResults = HashSet<String>()

    private var nextBatch: String? = null

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<SearchViewModel, SearchViewState> {
        override fun create(initialState: SearchViewState): SearchViewModel
    }

    companion object : MavericksViewModelFactory<SearchViewModel, SearchViewState> by hiltMavericksViewModelFactory()

    override fun handle(action: SearchAction) {
        when (action) {
            is SearchAction.SearchWith -> handleSearchWith(action)
            is SearchAction.LoadMore -> handleLoadMore()
            is SearchAction.Retry -> handleRetry()
            is SearchAction.VoteToPoll -> voteToPollUseCase.execute(initialState.roomId, action.pollEventId, action.optionId)
        }
    }

    private fun observeResults(results: List<EventAndSender>) {
        val room = room ?: return
        results.filter { result -> result.event.eventId?.let { observedResults.add(it) } == true }
                .forEach { result ->
                    val hit = result.event
                    val eventId = hit.eventId ?: return@forEach
                    // Only a poll's aggregation changes while it's on screen, as votes come in.
                    if (hit.isPollStart()) {
                        room.relationService().getEventAnnotationsSummaryFlow(eventId)
                                .onEach { summary -> summary.getOrNull()?.let { onAnnotations(eventId, it) } }
                                .launchIn(viewModelScope)
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        val stored = room.relationService().getEventAnnotationsSummary(eventId)
                        if (hit.isPollStart()) {
                            if (stored?.pollResponseSummary == null) tryOrNull { room.relationService().fetchPollResponses(eventId) }
                        } else {
                            stored?.let { onAnnotations(eventId, it) }
                        }
                        resolveEdit(room, hit, editIsStored = stored?.editSummary != null)
                    }
                }
    }

    private fun onAnnotations(eventId: String, summary: EventAnnotationsSummary) {
        setState { copy(annotations = annotations + (eventId to summary)) }
    }

    /**
     * Search hits carry no edit aggregation, so an edit the room doesn't hold is fetched: the one the server
     * marks as the latest, else the hit itself when what matched was an edit, whose original is then shown.
     */
    private suspend fun resolveEdit(room: Room, hit: Event, editIsStored: Boolean) {
        val eventId = hit.eventId ?: return
        val hitIsEdit = hit.isEdition()
        val original = when {
            !hitIsEdit -> hit
            else -> room.getTimelineEvent(eventId)?.root ?: tryOrNull { session.eventService().getEvent(room.roomId, eventId) } ?: return
        }
        if (hitIsEdit) setState { copy(originals = originals + (eventId to original)) }
        if (editIsStored) return
        val edit = original.unsignedData?.relations?.replaces?.eventId
                ?.let { tryOrNull { session.eventService().getEvent(room.roomId, it) } }
                ?: hit.takeIf { hitIsEdit }
        edit?.takeIf { it.senderId == original.senderId }?.let { setState { copy(fetchedEdits = fetchedEdits + (eventId to it)) } }
    }

    private fun handleSearchWith(action: SearchAction.SearchWith) {
        if (action.searchTerm.isNotEmpty()) {
            setState {
                copy(
                        searchResult = emptyList(),
                        hasMoreResult = false,
                        lastBatchSize = 0,
                        searchTerm = action.searchTerm
                )
            }
            startSearching(false)
        }
    }

    private fun handleLoadMore() {
        startSearching(true)
    }

    private fun handleRetry() {
        startSearching(false)
    }

    private fun startSearching(isNextBatch: Boolean) = withState { state ->
        if (room == null) return@withState
        if (state.searchTerm == null) return@withState

        // There is no batch to retrieve
        if (isNextBatch && nextBatch == null) return@withState

        // Show full screen loading just for the clean search
        if (!isNextBatch) {
            // A clean search starts from the most recent messages; without this it would resume
            // from the previous search's pagination point and only return older results.
            nextBatch = null
            setState {
                copy(
                        asyncSearchRequest = Loading()
                )
            }
        }

        currentTask?.cancel()

        currentTask = viewModelScope.launch {
            try {
                val result = session.searchService().search(
                        searchTerm = state.searchTerm,
                        roomId = initialState.roomId,
                        nextBatch = nextBatch,
                        orderByRecent = true,
                        beforeLimit = 0,
                        afterLimit = 0,
                        includeProfile = true,
                        limit = 20
                )
                onSearchResultSuccess(result)
            } catch (failure: Throwable) {
                if (failure is CancellationException) return@launch

                _viewEvents.post(SearchViewEvents.Failure(failure))
                setState {
                    copy(
                            asyncSearchRequest = Fail(failure)
                    )
                }
            }
        }
    }

    private fun onSearchResultSuccess(searchResult: SearchResult) = withState { state ->
        // An edited message can match as both its original and its (remapped) edit event; keep one.
        val accumulatedResult = searchResult.results.orEmpty().plus(state.searchResult)
                .distinctBy { it.event.eventId }

        // Note: We do not care about the highlights for the moment, but it will be the same algorithm

        nextBatch = searchResult.nextBatch
        observeResults(searchResult.results.orEmpty())

        setState {
            copy(
                    searchResult = accumulatedResult,
                    highlights = searchResult.highlights.orEmpty(),
                    hasMoreResult = !nextBatch.isNullOrEmpty(),
                    lastBatchSize = searchResult.results.orEmpty().size,
                    asyncSearchRequest = Success(Unit)
            )
        }
    }
}
