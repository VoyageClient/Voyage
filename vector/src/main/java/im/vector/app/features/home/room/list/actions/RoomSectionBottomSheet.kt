/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.databinding.BottomSheetGenericListBinding
import im.vector.app.features.home.room.list.sections.RoomSectionDialogs
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@Parcelize
data class RoomSectionArgs(
        val roomId: String
) : Parcelable

/**
 * Bottom sheet to move a room into, out of, or into a newly created custom section.
 */
@AndroidEntryPoint
class RoomSectionBottomSheet :
        VectorBaseBottomSheetDialogFragment<BottomSheetGenericListBinding>(),
        RoomSectionController.Listener {

    @Inject lateinit var sharedViewPool: RecyclerView.RecycledViewPool
    @Inject lateinit var controller: RoomSectionController
    @Inject lateinit var errorFormatter: ErrorFormatter

    private val roomSectionArgs: RoomSectionArgs by args()
    private val viewModel: RoomSectionViewModel by fragmentViewModel()

    override val showExpanded = true

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetGenericListBinding {
        return BottomSheetGenericListBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        views.bottomSheetRecyclerView.configureWith(
                epoxyController = controller,
                viewPool = sharedViewPool,
                hasFixedSize = false,
                disableItemAnimation = true
        )
        controller.listener = this

        viewModel.observeViewEvents {
            when (it) {
                RoomSectionViewEvents.Dismiss -> dismiss()
                is RoomSectionViewEvents.PromptDeleteSection -> {
                    RoomSectionDialogs.showDeleteDialog(requireContext(), it.isEmpty) {
                        viewModel.handle(RoomSectionAction.DeleteSection(it.tag))
                    }
                }
                is RoomSectionViewEvents.Failure -> displayErrorDialog(it.throwable)
            }
        }
    }

    override fun onDestroyView() {
        views.bottomSheetRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun invalidate() {
        super.invalidate()
        withState(viewModel) { controller.setData(it) }
    }

    override fun onMoveToSection(tag: String) {
        viewModel.handle(RoomSectionAction.MoveToSection(tag))
    }

    override fun onRemoveFromSection() {
        viewModel.handle(RoomSectionAction.MoveToSection(null))
    }

    override fun onCreateNewSection() {
        RoomSectionDialogs.showNameDialog(requireContext(), CommonStrings.room_section_create_title, initialName = null) { name ->
            viewModel.handle(RoomSectionAction.CreateSectionAndMove(name))
        }
    }

    override fun onRenameSection(tag: String, currentName: String) {
        RoomSectionDialogs.showNameDialog(requireContext(), CommonStrings.room_section_rename, currentName) { name ->
            viewModel.handle(RoomSectionAction.RenameSection(tag, name))
        }
    }

    override fun onDeleteSection(tag: String) {
        viewModel.handle(RoomSectionAction.RequestDeleteSection(tag))
    }

    private fun displayErrorDialog(throwable: Throwable) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.dialog_title_error)
                .setMessage(errorFormatter.toHumanReadable(throwable))
                .setPositiveButton(CommonStrings.ok, null)
                .show()
    }

    companion object {
        fun newInstance(roomId: String): RoomSectionBottomSheet {
            return RoomSectionBottomSheet().apply {
                setArguments(RoomSectionArgs(roomId))
            }
        }
    }
}
