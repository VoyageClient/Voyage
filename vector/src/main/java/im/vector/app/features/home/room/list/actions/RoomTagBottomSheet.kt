/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.databinding.BottomSheetGenericListBinding
import im.vector.app.features.spaces.tags.normaliseUserTag
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

@Parcelize
data class RoomTagArgs(
        val roomId: String
) : Parcelable

/**
 * Bottom sheet to assign, create and remove tags on a room.
 */
@AndroidEntryPoint
class RoomTagBottomSheet :
        VectorBaseBottomSheetDialogFragment<BottomSheetGenericListBinding>(),
        RoomTagController.Listener {

    @Inject lateinit var sharedViewPool: RecyclerView.RecycledViewPool
    @Inject lateinit var controller: RoomTagController
    @Inject lateinit var errorFormatter: ErrorFormatter

    private val roomTagArgs: RoomTagArgs by args()
    private val viewModel: RoomTagViewModel by fragmentViewModel()

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
                is RoomTagViewEvents.Failure -> displayErrorDialog(it.throwable)
            }
        }
    }

    override fun onDestroyView() {
        views.bottomSheetRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) {
        controller.setData(it)
    }

    override fun onAddTag(tag: String) {
        viewModel.handle(RoomTagAction.AddTag(tag))
    }

    override fun onRemoveTag(tag: String) {
        viewModel.handle(RoomTagAction.RemoveTag(tag))
    }

    override fun onCreateNewTag() {
        val view = layoutInflater.inflate(R.layout.dialog_base_edit_text, null)
        val editText = view.findViewById<EditText>(R.id.editText)
        editText.setHint(CommonStrings.room_tag_new_hint)
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.room_tag_create_new)
                .setView(view)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    normaliseUserTag(editText.text.toString())?.let { viewModel.handle(RoomTagAction.AddTag(it)) }
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun displayErrorDialog(throwable: Throwable) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.dialog_title_error)
                .setMessage(errorFormatter.toHumanReadable(throwable))
                .setPositiveButton(CommonStrings.ok, null)
                .show()
    }

    companion object {
        fun newInstance(roomId: String): RoomTagBottomSheet {
            return RoomTagBottomSheet().apply {
                setArguments(RoomTagArgs(roomId))
            }
        }
    }
}
