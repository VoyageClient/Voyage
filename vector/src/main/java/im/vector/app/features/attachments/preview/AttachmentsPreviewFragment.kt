/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.CompoundButtonCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.OnSnapPositionChangeListener
import im.vector.app.core.utils.SnapOnScrollListener
import im.vector.app.core.utils.attachSnapHelperWithListener
import im.vector.app.databinding.FragmentAttachmentsPreviewBinding
import im.vector.app.features.attachments.editor.image.ImageEditorActivity
import im.vector.app.features.attachments.editor.image.ImageEditorEdits
import im.vector.app.features.attachments.editor.video.VideoEditorActivity
import im.vector.app.features.attachments.editor.video.VideoEditorEdits
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class AttachmentsPreviewArgs(
        val attachments: List<ContentAttachmentData>
) : Parcelable

@AndroidEntryPoint
class AttachmentsPreviewFragment :
        VectorBaseFragment<FragmentAttachmentsPreviewBinding>(),
        AttachmentMiniaturePreviewController.Callback,
        VectorMenuProvider {

    @Inject lateinit var attachmentMiniaturePreviewController: AttachmentMiniaturePreviewController
    @Inject lateinit var attachmentBigPreviewController: AttachmentBigPreviewController

    private val fragmentArgs: AttachmentsPreviewArgs by args()
    private val viewModel: AttachmentsPreviewViewModel by fragmentViewModel()

    private var lastScrolledIndex = -1

    /** Source the editor was opened against, needed to record the edit when it returns. */
    private var pendingEditOriginalUri: String? = null

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentAttachmentsPreviewBinding {
        return FragmentAttachmentsPreviewBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyInsets()
        setupRecyclerViews()
        setupToolbar(views.attachmentPreviewerToolbar)
        views.attachmentPreviewerSendButton.debouncedClicks {
            setResultAndFinish()
        }
        // This activity's theme has no accent variant, so ?colorAccent here is the default (green) — for
        // both the layout and any ColorStateList. Resolve the configured accent from the application theme
        // so the send button, the "original size" checkbox and the selected-media highlight all match.
        val accent = ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorAccent)
        views.attachmentPreviewerSendButton.backgroundTintList = ColorStateList.valueOf(accent)
        CompoundButtonCompat.setButtonTintList(
                views.attachmentPreviewerSendImageOriginalSize,
                ColorStateList.valueOf(accent)
        )
    }

    private val videoEditorActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == RESULT_OK) {
            val output = activityResult.data?.let { VideoEditorActivity.getOutput(it) }
            if (output != null) {
                discardSupersededExport()
                viewModel.handle(
                        AttachmentsPreviewAction.UpdateCurrentAttachment(
                                newUri = output.uri,
                                width = output.width.toLong(),
                                height = output.height.toLong(),
                                size = output.size,
                                mimeType = output.mimeType,
                                duration = output.durationMs,
                                editRecord = pendingEditOriginalUri?.let { EditRecord(it, output.edits) }
                        )
                )
            } else {
                Toast.makeText(requireContext(), getString(CommonStrings.video_editor_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val imageEditorActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == RESULT_OK) {
            val output = activityResult.data?.let { ImageEditorActivity.getOutput(it) }
            if (output != null) {
                discardSupersededExport()
                viewModel.handle(
                        AttachmentsPreviewAction.UpdateCurrentAttachment(
                                newUri = output.uri,
                                width = output.width.toLong(),
                                height = output.height.toLong(),
                                size = output.size,
                                mimeType = output.mimeType,
                                editRecord = pendingEditOriginalUri?.let { EditRecord(it, output.edits) }
                        )
                )
            } else {
                Toast.makeText(requireContext(), getString(CommonStrings.image_editor_save_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.attachmentsPreviewRemoveAction -> {
                handleRemoveAction()
                true
            }
            R.id.attachmentsPreviewEditAction -> {
                handleEditAction()
                true
            }
            else -> false
        }
    }

    override fun handlePrepareMenu(menu: Menu) {
        withState(viewModel) { state ->
            val editMenuItem = menu.findItem(R.id.attachmentsPreviewEditAction)
            val showEditMenuItem = state.attachments.getOrNull(state.currentAttachmentIndex)?.isEditable().orFalse()
            editMenuItem.setVisible(showEditMenuItem)
        }
    }

    override fun getMenuRes() = R.menu.vector_attachments_preview

    override fun onResume() {
        super.onResume()
        attachmentBigPreviewController.playbackAllowed = true
    }

    override fun onPause() {
        super.onPause()
        attachmentBigPreviewController.playbackAllowed = false
    }

    override fun onDestroyView() {
        views.attachmentPreviewerMiniatureList.cleanup()
        views.attachmentPreviewerBigList.cleanup()
        attachmentMiniaturePreviewController.callback = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        invalidateOptionsMenu()
        if (state.attachments.isEmpty()) {
            requireActivity().setResult(RESULT_CANCELED)
            requireActivity().finish()
        } else {
            attachmentMiniaturePreviewController.setData(state)
            attachmentBigPreviewController.setData(state)
            // Scrolling on every state emission would yank the pager out from under an in-progress
            // swipe, now that playback changes also re-emit state.
            if (state.currentAttachmentIndex != lastScrolledIndex) {
                lastScrolledIndex = state.currentAttachmentIndex
                views.attachmentPreviewerBigList.scrollToPosition(state.currentAttachmentIndex)
                views.attachmentPreviewerMiniatureList.scrollToPosition(state.currentAttachmentIndex)
            }
            views.attachmentPreviewerSendImageOriginalSize.text = getCheckboxText(state)
        }
    }

    private fun getCheckboxText(state: AttachmentsPreviewViewState): CharSequence {
        val nbImages = state.attachments.count { it.type == ContentAttachmentData.Type.IMAGE }
        val nbVideos = state.attachments.count { it.type == ContentAttachmentData.Type.VIDEO }
        return when {
            nbVideos == 0 -> resources.getQuantityString(CommonPlurals.send_images_with_original_size, nbImages)
            nbImages == 0 -> resources.getQuantityString(CommonPlurals.send_videos_with_original_size, nbVideos)
            else -> getString(CommonStrings.send_images_and_video_with_original_size)
        }
    }

    override fun onAttachmentClicked(position: Int, contentAttachmentData: ContentAttachmentData) {
        viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(position))
    }

    private fun setResultAndFinish() = withState(viewModel) {
        (requireActivity() as? AttachmentsPreviewActivity)?.setResultAndFinish(
                it.attachments,
                views.attachmentPreviewerSendImageOriginalSize.isChecked
        )
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            activity?.window?.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            view?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ViewCompat.setOnApplyWindowInsetsListener(views.attachmentPreviewerBottomContainer) { v, insets ->
                val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(bottom = systemBarsInsets.bottom)
                insets
            }
            ViewCompat.setOnApplyWindowInsetsListener(views.attachmentPreviewerToolbar) { v, insets ->
                val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = systemBarsInsets.top
                }
                insets
            }
        } else {
            // Pre-21 has no window-insets dispatch, so derive the status bar height from platform
            // resources; otherwise the toolbar renders under the status bar. The nav bar isn't
            // overlapped pre-21 (and many KitKat devices have hardware keys), so no bottom padding.
            views.attachmentPreviewerToolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = getSystemBarHeightPx("status_bar_height")
            }
        }
    }

    private fun getSystemBarHeightPx(resName: String): Int {
        val resId = resources.getIdentifier(resName, "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun handleRemoveAction() {
        viewModel.handle(AttachmentsPreviewAction.RemoveCurrentAttachment)
    }

    /**
     * Exports live in filesDir, which nothing reclaims, so editing the same attachment repeatedly
     * would leave a full-size file behind each time. The one being replaced is ours to delete.
     */
    private fun discardSupersededExport() = withState(viewModel) { state ->
        val current = state.attachments.getOrNull(state.currentAttachmentIndex) ?: return@withState
        if (!state.editRecords.containsKey(current.queryUri)) return@withState
        runCatching { requireContext().contentResolver.delete(current.queryUriAndroid, null, null) }
                .onFailure { Timber.w(it, "Could not delete superseded export") }
    }

    private fun handleEditAction() = withState(viewModel) { state ->
        val currentAttachment = state.attachments.getOrNull(state.currentAttachmentIndex) ?: return@withState
        // Always edit the untouched original, replaying the previous edits, so repeated trips
        // through the editor don't compound cropping and JPEG loss.
        val record = state.editRecords[currentAttachment.queryUri]
        pendingEditOriginalUri = record?.originalUri ?: currentAttachment.queryUri
        val source = record?.originalUri?.toUri() ?: currentAttachment.queryUriAndroid
        if (currentAttachment.isVideoEditable()) {
            videoEditorActivityResultLauncher.launch(
                    VideoEditorActivity.newIntent(
                            requireContext(),
                            source,
                            currentAttachment.name,
                            record?.edits as? VideoEditorEdits
                    )
            )
        } else {
            imageEditorActivityResultLauncher.launch(
                    ImageEditorActivity.newIntent(
                            requireContext(),
                            source,
                            currentAttachment.name,
                            currentAttachment.getSafeMimeType(),
                            record?.edits as? ImageEditorEdits
                    )
            )
        }
    }

    private fun setupRecyclerViews() {
        attachmentMiniaturePreviewController.callback = this

        views.attachmentPreviewerMiniatureList.let {
            it.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            it.setHasFixedSize(true)
            it.adapter = attachmentMiniaturePreviewController.adapter
        }

        views.attachmentPreviewerBigList.let {
            it.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            it.attachSnapHelperWithListener(
                    PagerSnapHelper(),
                    SnapOnScrollListener.Behavior.NOTIFY_ON_SCROLL_STATE_IDLE,
                    object : OnSnapPositionChangeListener {
                        override fun onSnapPositionChange(position: Int) {
                            viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(position))
                        }
                    })
            it.setHasFixedSize(true)
            it.adapter = attachmentBigPreviewController.adapter
        }
    }
}
