/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import timber.log.Timber

class MentionsViewModel @AssistedInject constructor(
        @Assisted initialState: MentionsViewState,
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val getMentionsUseCase: GetMentionsUseCase,
) : VectorViewModel<MentionsViewState, MentionsAction, EmptyViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<MentionsViewModel, MentionsViewState> {
        override fun create(initialState: MentionsViewState): MentionsViewModel
    }

    companion object : MavericksViewModelFactory<MentionsViewModel, MentionsViewState> by hiltMavericksViewModelFactory()

    private var loadJob: Job? = null

    /** Every page loaded so far; searching narrows this in memory rather than querying again. */
    private var loaded: List<MentionListItem> = emptyList()

    private var nextToken: String? = null
    private var profileJob: Job? = null

    /** Until the first page lands there is nothing to narrow, and an empty result is not an answer. */
    private var loadedOnce = false
    private var loadingMore = false

    init {
        setState { copy(filter = storedFilter()) }
        refresh()
    }

    private fun storedFilter() = MentionsFilter(
            includeRoomMentions = vectorPreferences.mentionsShowRoomMentions(),
            includeKeywords = vectorPreferences.mentionsShowKeywords(),
            excludeDms = vectorPreferences.mentionsExcludeDms(),
    )

    private fun refresh() = withState { state ->
        loadJob?.cancel()
        profileJob?.cancel()
        loadedOnce = false
        loadJob = viewModelScope.launch {
            if (state.mentions !is Success) setState { copy(mentions = Loading()) }
            try {
                val page = getMentionsUseCase.execute(state.filter)
                loaded = page.items
                loadedOnce = true
                nextToken = page.nextToken
                setState { copy(hasMore = page.nextToken != null) }
                applySearch()
                fillSenderProfiles()
            } catch (failure: Throwable) {
                setState { copy(mentions = Fail(failure)) }
            }
        }
    }

    /**
     * Naming a sender nothing local knows costs a request each, so the list is shown first and the
     * names fill in behind it. Restarted per page, since it also re-checks the rows already on screen.
     */
    private fun fillSenderProfiles() {
        profileJob?.cancel()
        val snapshot = loaded
        profileJob = viewModelScope.launch {
            tryOrNull {
                getMentionsUseCase.fillSenderProfiles(snapshot) { updated ->
                    // A page may have been appended while this ran; only replace what it covered.
                    val byId = updated.associateBy { it.event.eventId }
                    loaded = loaded.map { byId[it.event.eventId] ?: it }
                    applySearch()
                }
            }
        }
    }

    private fun applySearch() = withState { state ->
        if (!loadedOnce) return@withState
        val query = session.searchService().parseQuery(state.searchQuery)
        val matching = loaded.filter { query.matches(it.event.root) }
        setState { copy(mentions = Success(matching), senders = loaded.senderSummaries(), mentionsTick = mentionsTick + 1) }
    }

    private fun List<MentionListItem>.senderSummaries(): List<RoomMemberSummary> = map { it.event.senderInfo }
            .distinctBy { it.userId }
            .sortedBy { (it.displayName ?: it.userId).lowercase() }
            .map { RoomMemberSummary(userId = it.userId, displayName = it.displayName, avatarUrl = it.avatarUrl, membership = Membership.JOIN) }

    /** Reads further back through the server's notification history as the list is scrolled. */
    fun loadMore() = withState { state ->
        val from = nextToken
        if (from == null || loadingMore || loadJob?.isActive == true) return@withState
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = getMentionsUseCase.execute(state.filter, from)
                // Ids already held win: the first page resolved them against local sources too.
                val known = loaded.mapTo(HashSet()) { it.event.eventId }
                loaded = loaded + page.items.filterNot { it.event.eventId in known }
                nextToken = page.nextToken
                setState { copy(hasMore = page.nextToken != null) }
                applySearch()
                fillSenderProfiles()
            } catch (failure: Throwable) {
                Timber.w(failure, "Could not load more mentions")
            } finally {
                loadingMore = false
            }
        }
    }

    override fun handle(action: MentionsAction) {
        when (action) {
            is MentionsAction.SetIncludeRoomMentions -> {
                vectorPreferences.setMentionsShowRoomMentions(action.include)
                setState { copy(filter = filter.copy(includeRoomMentions = action.include)) }
            }
            is MentionsAction.SetIncludeKeywords -> {
                vectorPreferences.setMentionsShowKeywords(action.include)
                setState { copy(filter = filter.copy(includeKeywords = action.include)) }
            }
            is MentionsAction.SetExcludeDms -> {
                vectorPreferences.setMentionsExcludeDms(action.exclude)
                setState { copy(filter = filter.copy(excludeDms = action.exclude)) }
            }
            is MentionsAction.UpdateSearchQuery -> {
                setState { copy(searchQuery = action.query) }
                applySearch()
                return
            }
        }
        refresh()
    }
}
