/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.trackItemsVisibilityChange
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentSearchBinding
import im.vector.app.features.home.room.detail.composer.AudioMessageHelper
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.home.room.threads.arguments.ThreadTimelineArgs
import im.vector.app.features.media.AttachmentData
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VideoContentRenderer
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class SearchArgs(
        val roomId: String,
        val roomDisplayName: String?,
        val roomAvatarUrl: String?
) : Parcelable

@AndroidEntryPoint
class SearchFragment :
        VectorBaseFragment<FragmentSearchBinding>(),
        StateView.EventCallback,
        SearchResultController.Listener {

    @Inject lateinit var controller: SearchResultController
    @Inject lateinit var session: Session
    @Inject lateinit var audioMessageHelper: AudioMessageHelper
    @Inject lateinit var playbackTracker: AudioMessagePlaybackTracker
    private val fragmentArgs: SearchArgs by args()
    private val searchViewModel: SearchViewModel by fragmentViewModel()
    private val playedEventIds = mutableSetOf<String>()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentSearchBinding {
        return FragmentSearchBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.stateView.contentView = views.searchResultRecycler
        views.stateView.eventCallback = this

        configureRecyclerView()
    }

    private fun configureRecyclerView() {
        controller.start(fragmentArgs.roomId, viewLifecycleOwner.lifecycleScope)
        views.searchResultRecycler.trackItemsVisibilityChange()
        views.searchResultRecycler.configureWith(controller)
        (views.searchResultRecycler.layoutManager as? LinearLayoutManager)?.stackFromEnd = true
        controller.listener = this
        // When the keyboard closes the viewport grows, but the stacked-from-end layout keeps its
        // anchor and leaves a keyboard-sized blank band at the top; re-anchor to the bottom.
        views.searchResultRecycler.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldHeight = oldBottom - oldTop
            if (oldHeight in 1 until (bottom - top) && !v.canScrollVertically(1)) {
                v.post {
                    val lastIndex = (controller.adapter.itemCount) - 1
                    if (lastIndex >= 0 && view != null) views.searchResultRecycler.scrollToPosition(lastIndex)
                }
            }
        }
    }

    override fun onPause() {
        // Leaving the screen (e.g. jumping to a result) must not keep audio playing.
        stopAudioPlayback()
        super.onPause()
    }

    override fun onDestroyView() {
        stopAudioPlayback()
        views.searchResultRecycler.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    private fun stopAudioPlayback() {
        audioMessageHelper.stopPlayback()
        // stopPlayback() only halts the player; reset the played items' progress UI too.
        playedEventIds.forEach { playbackTracker.stopPlaybackOrRecorder(it) }
        playedEventIds.clear()
    }

    override fun invalidate() = withState(searchViewModel) { state ->
        if (state.searchResult.isNullOrEmpty()) {
            when (state.asyncSearchRequest) {
                is Loading -> {
                    views.stateView.state = StateView.State.Loading
                }
                is Fail -> {
                    views.stateView.state = StateView.State.Error(errorFormatter.toHumanReadable(state.asyncSearchRequest.error))
                }
                is Success -> {
                    views.stateView.state = StateView.State.Empty(
                            title = getString(CommonStrings.search_no_results),
                            image = ContextCompat.getDrawable(requireContext(), R.drawable.ic_search_no_results)
                    )
                }
                else -> Unit
            }
        } else {
            controller.setData(state)
            views.stateView.state = StateView.State.Content
        }
    }

    fun search(query: String) {
        view?.hideKeyboard()
        searchViewModel.handle(SearchAction.SearchWith(query))
    }

    override fun onRetryClicked() {
        searchViewModel.handle(SearchAction.Retry)
    }

    override fun onItemClicked(event: Event) =
            navigateToEvent(event)

    override fun onThreadSummaryClicked(event: Event) {
        navigateToEvent(event, true)
    }

    /**
     * Navigate and highlight the event. If this is a thread event,
     * user will be redirected to the appropriate thread room
     * @param event the event to navigate and highlight
     * @param forceNavigateToThread force navigate within the thread (ex. when user clicks on thread summary)
     */
    private fun navigateToEvent(event: Event, forceNavigateToThread: Boolean = false) {
        val roomId = event.roomId ?: return
        val rootThreadEventId = if (forceNavigateToThread) {
            event.eventId
        } else {
            event.getRootThreadEventId()
        }

        rootThreadEventId?.let {
            val threadTimelineArgs = ThreadTimelineArgs(
                    roomId = roomId,
                    displayName = fragmentArgs.roomDisplayName,
                    avatarUrl = fragmentArgs.roomAvatarUrl,
                    roomEncryptionTrustLevel = null,
                    rootThreadEventId = it
            )
            navigator.openThread(requireContext(), threadTimelineArgs, event.eventId)
        } ?: openRoom(roomId, event.eventId)
    }

    private fun openRoom(roomId: String, eventId: String?) {
        navigator.openRoom(
                context = requireContext(),
                roomId = roomId,
                eventId = eventId,
        )
    }

    override fun loadMore() {
        searchViewModel.handle(SearchAction.LoadMore)
    }

    override fun onImageMessageClicked(
            messageImageContent: MessageImageInfoContent,
            mediaData: ImageContentRenderer.Data,
            view: View,
            inMemory: List<AttachmentData>
    ) {
        navigator.openMediaViewer(
                activity = requireActivity(),
                roomId = fragmentArgs.roomId,
                mediaData = mediaData,
                view = view,
                // The room attachment provider only knows locally cached events, so a crawled
                // search hit isn't found and the viewer would open on the first room attachment.
                inMemory = inMemory.ifEmpty { listOf(mediaData) },
                // A search hit stands on its own rather than being one of the room's media.
                standalonePreview = true,
        ) { }
    }

    override fun onVideoMessageClicked(
            messageVideoContent: MessageVideoContent,
            mediaData: VideoContentRenderer.Data,
            view: View,
            inMemory: List<AttachmentData>
    ) {
        navigator.openMediaViewer(
                activity = requireActivity(),
                roomId = fragmentArgs.roomId,
                mediaData = mediaData,
                view = view,
                // A hit stands on its own; a gallery's tile pages over that gallery's items.
                inMemory = inMemory.ifEmpty { listOf(mediaData) },
                standalonePreview = inMemory.isEmpty(),
        ) { }
    }

    override fun onVoiceControlButtonClicked(eventId: String, messageAudioContent: MessageAudioContent) {
        playedEventIds.add(eventId)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val audioFile = withContext(Dispatchers.IO) {
                    audioMessageHelper.resolveLocalFile(messageAudioContent.url)
                            ?: session.fileService().downloadFile(messageAudioContent)
                }
                audioMessageHelper.startOrPausePlayback(eventId, audioFile)
            } catch (failure: Throwable) {
                Timber.w(failure, "Unable to play audio message from search")
            }
        }
    }

    override fun onAudioSeekBarMovedTo(eventId: String, duration: Int, percentage: Float) {
        audioMessageHelper.movePlaybackTo(eventId, percentage, duration)
    }

    override fun onAvatarClicked(userId: String) {
        navigator.openRoomMemberProfile(userId, fragmentArgs.roomId, requireContext())
    }
}
