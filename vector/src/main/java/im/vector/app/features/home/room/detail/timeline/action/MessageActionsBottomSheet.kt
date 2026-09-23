/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.action

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isEmpty
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.core.utils.PerfTrace
import im.vector.app.databinding.BottomSheetGenericListBinding
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import javax.inject.Inject

/**
 * Bottom sheet fragment that shows a message preview with list of contextual actions.
 */
@AndroidEntryPoint
class MessageActionsBottomSheet :
        VectorBaseBottomSheetDialogFragment<BottomSheetGenericListBinding>(),
        MessageActionsEpoxyController.MessageActionsEpoxyControllerListener {

    @Inject lateinit var messageActionsEpoxyController: MessageActionsEpoxyController

    private val viewModel: MessageActionsViewModel by fragmentViewModel(MessageActionsViewModel::class)

    private lateinit var sharedActionViewModel: MessageSharedActionViewModel

    private var enterDelay: PerfTrace.Marker? = null

    private var actionsDelivered = false

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetGenericListBinding {
        return BottomSheetGenericListBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterDelay = PerfTrace.mark("longpress.enterDelay")
        // The state is computed within ~30ms of the long press, but reaches the view well after the first
        // frame — so the sheet waits for its content rather than being drawn without it.
        postponeEnter()
        // Higher than the 400dp the base class peeks at, which leaves the list resting near the bottom
        // of a tall screen.
        setPeekHeightAsScreenPercentage(PEEK_HEIGHT_SCREEN_FRACTION, animate = false)
        sharedActionViewModel = activityViewModelProvider.get(MessageSharedActionViewModel::class.java)
        views.bottomSheetRecyclerView.configureWith(messageActionsEpoxyController, hasFixedSize = false, disableItemAnimation = true)
        views.bottomSheetRecyclerView.isVerticalScrollBarEnabled = false
        messageActionsEpoxyController.listener = this
        views.bottomSheetRecyclerView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if ((v as ViewGroup).isEmpty()) return
                v.removeOnLayoutChangeListener(this)
                maybeStartEnter()
            }
        })
    }

    // Not merely when the actions reach the state: the rows are inflated and laid out ~100ms after that,
    // and a sheet that starts rising before then arrives with its list still filling in.
    private fun maybeStartEnter() {
        if (!actionsDelivered || views.bottomSheetRecyclerView.isEmpty()) return
        startPostponedEnter()
        enterDelay?.end()
        enterDelay = null
    }

    override fun onDestroyView() {
        views.bottomSheetRecyclerView.cleanup()
        super.onDestroyView()
    }

    override fun onUrlClicked(url: String, title: String): Boolean {
        sharedActionViewModel.post(EventSharedAction.OnUrlClicked(url, title))
        // Always consume
        return true
    }

    override fun onUrlLongClicked(url: String): Boolean {
        sharedActionViewModel.post(EventSharedAction.OnUrlLongClicked(url))
        // Always consume
        return true
    }

    override fun didSelectMenuAction(eventAction: EventSharedAction) {
        sharedActionViewModel.post(eventAction)
        dismiss()
    }

    override fun invalidate() = withState(viewModel) {
        actionsDelivered = actionsDelivered || it.actions.isNotEmpty()
        messageActionsEpoxyController.setData(it)
        maybeStartEnter()
        super.invalidate()
    }

    companion object {
        private const val PEEK_HEIGHT_SCREEN_FRACTION = 0.6f

        fun newInstance(
                roomId: String,
                informationData: MessageInformationData,
                isFromThreadTimeline: Boolean,
                galleryItemIndex: Int? = null,
        ): MessageActionsBottomSheet {
            return MessageActionsBottomSheet().apply {
                setArguments(
                        TimelineEventFragmentArgs(
                                informationData.eventId,
                                roomId,
                                informationData,
                                isFromThreadTimeline,
                                galleryItemIndex,
                        )
                )
            }
        }
    }
}
