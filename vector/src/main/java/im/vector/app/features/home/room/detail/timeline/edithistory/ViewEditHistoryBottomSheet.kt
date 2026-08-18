/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.edithistory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.databinding.BottomSheetGenericListWithTitleBinding
import im.vector.app.features.home.room.detail.timeline.action.EventSharedAction
import im.vector.app.features.home.room.detail.timeline.action.MessageSharedActionViewModel
import im.vector.app.features.home.room.detail.timeline.action.TimelineEventFragmentArgs
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.media.AttachmentData
import im.vector.app.features.media.AttachmentProviderFactory
import im.vector.app.features.navigation.Navigator
import im.vector.lib.attachmentviewer.AttachmentInfo
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import javax.inject.Inject

/**
 * Bottom sheet displaying list of edits for a given event ordered by timestamp.
 */
@AndroidEntryPoint
class ViewEditHistoryBottomSheet :
        VectorBaseBottomSheetDialogFragment<BottomSheetGenericListWithTitleBinding>(),
        ViewEditHistoryEpoxyController.Listener {

    private val viewModel: ViewEditHistoryViewModel by fragmentViewModel(ViewEditHistoryViewModel::class)
    private val fragmentArgs: TimelineEventFragmentArgs by args()

    @Inject lateinit var epoxyController: ViewEditHistoryEpoxyController
    @Inject lateinit var attachmentProviderFactory: AttachmentProviderFactory
    @Inject lateinit var navigator: Navigator

    private lateinit var sharedActionViewModel: MessageSharedActionViewModel

    private var renderedState: ViewEditHistoryViewState? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetGenericListWithTitleBinding {
        return BottomSheetGenericListWithTitleBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedActionViewModel = activityViewModelProvider.get(MessageSharedActionViewModel::class.java)
        epoxyController.start(fragmentArgs.roomId, lifecycleScope)
        epoxyController.listener = this
        // A revision's media and its caption belong together, so the list draws its own separators
        // between revisions instead of one under every row.
        views.bottomSheetRecyclerView.configureWith(epoxyController, hasFixedSize = false)
        views.bottomSheetTitle.text = context?.getString(CommonStrings.message_edits)
    }

    override fun onDestroyView() {
        epoxyController.listener = null
        views.bottomSheetRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun onRevisionMediaClicked(mediaData: AttachmentData, view: View) {
        // The provider maps the revisions the same way the timeline maps a room's media, so the viewer
        // pages over the versions of this one message — "x of y" counting only the ones that had media.
        val provider = attachmentProviderFactory.createProvider(epoxyController.mediaRevisions(), lifecycleScope)
        val revisions = (0 until provider.getItemCount()).mapNotNull {
            when (val info = provider.getAttachmentInfoAt(it)) {
                is AttachmentInfo.Image -> info.data
                is AttachmentInfo.AnimatedImage -> info.data
                is AttachmentInfo.Video -> info.data
                else -> null
            } as? AttachmentData
        }
        navigator.openMediaViewer(
                activity = requireActivity(),
                roomId = fragmentArgs.roomId,
                mediaData = mediaData,
                view = view,
                inMemory = revisions,
                pageOverRoomMedia = false,
                morphFromView = false,
                options = null,
        )
    }

    override fun onRevisionFileClicked(eventId: String, content: MessageWithAttachmentContent) {
        sharedActionViewModel.post(EventSharedAction.Save(eventId, content))
        dismiss()
    }

    override fun invalidate() = withState(viewModel) { state ->
        // Mavericks also invalidates on resume — coming back from the viewer — and rebuilding the rows
        // there makes every thumbnail load again.
        if (state != renderedState) {
            renderedState = state
            epoxyController.setData(state)
        }
        super.invalidate()
    }

    companion object {
        fun newInstance(roomId: String, informationData: MessageInformationData): ViewEditHistoryBottomSheet {
            return ViewEditHistoryBottomSheet().apply {
                setArguments(
                        TimelineEventFragmentArgs(
                                eventId = informationData.eventId,
                                roomId = roomId,
                                informationData = informationData
                        )
                )
            }
        }
    }
}
