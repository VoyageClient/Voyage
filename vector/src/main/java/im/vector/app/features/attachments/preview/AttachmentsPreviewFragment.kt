/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.animation.ObjectAnimator
import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.CompoundButtonCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.OnSnapPositionChangeListener
import im.vector.app.core.utils.SnapOnScrollListener
import im.vector.app.core.utils.attachSnapHelperWithListener
import im.vector.app.databinding.BottomSheetAttachmentCompressionBinding
import im.vector.app.databinding.FragmentAttachmentsPreviewBinding
import im.vector.app.features.attachments.editor.image.ImageEditorActivity
import im.vector.app.features.attachments.editor.image.ImageEditorEdits
import im.vector.app.features.attachments.editor.isRestoreOriginal
import im.vector.app.features.attachments.editor.video.VideoEditorActivity
import im.vector.app.features.attachments.editor.video.VideoEditorEdits
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeVideo
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
        VideoPlaybackListener,
        VectorMenuProvider {

    @Inject lateinit var attachmentMiniaturePreviewController: AttachmentMiniaturePreviewController
    @Inject lateinit var attachmentBigPreviewController: AttachmentBigPreviewController
    @Inject lateinit var vectorPreferences: VectorPreferences

    private val fragmentArgs: AttachmentsPreviewArgs by args()
    private val viewModel: AttachmentsPreviewViewModel by fragmentViewModel()

    private var lastScrolledIndex = -1

    /** Attachment the editor was opened against, needed to record the edit when it returns. */
    private var pendingEditOriginal: ContentAttachmentData? = null
    private val animatedFormats = mutableMapOf<String, AnimatedImageFormat?>()

    private var videoControls: VideoPlaybackControls? = null
    private var scrubbingVideo = false
    private var seekBarAnimator: ObjectAnimator? = null
    private var bigListSnapListener: SnapOnScrollListener? = null

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
        val accent = ThemeUtils.getColorFromContextTheme(requireContext(), com.google.android.material.R.attr.colorAccent)
        val (fill, onFill) = ThemeUtils.accentFillOnDarkSurface(requireContext())
        views.attachmentPreviewerSendButton.backgroundTintList = ColorStateList.valueOf(fill)
        ImageViewCompat.setImageTintList(views.attachmentPreviewerSendButton, ColorStateList.valueOf(onFill))
        CompoundButtonCompat.setButtonTintList(
                views.attachmentPreviewerSendImageOriginalSize,
                ColorStateList.valueOf(accent)
        )
        setupVideoControls(accent)
    }

    /**
     * The bar belongs to the fragment rather than the attachment on show: it sits under the send
     * options, and whichever video is on screen hands it the player to drive.
     */
    private fun setupVideoControls(accent: Int) {
        // progressTintList is API 21+, and this fork runs from 14.
        @Suppress("DEPRECATION")
        views.attachmentPreviewerVideoSeekBar.apply {
            progressDrawable?.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            thumb?.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        views.attachmentPreviewerVideoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) views.attachmentPreviewerVideoTime.text = videoTimeLabel(progress, seekBar.max)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                scrubbingVideo = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                scrubbingVideo = false
                videoControls?.seekTo(seekBar.progress)
            }
        })
    }

    override fun onVideoControlsAvailable(controls: VideoPlaybackControls?) {
        videoControls = controls
        refreshVideoControlsVisibility()
    }

    override fun onVideoControlsReleased(controls: VideoPlaybackControls) {
        // Only the holder still driving the controls may take them away.
        if (videoControls !== controls) return
        videoControls = null
        refreshVideoControlsVisibility()
    }

    /**
     * Epoxy binds its models from inside the pager's layout pass, so flipping this bar's visibility
     * there has its requestLayout() swallowed and it is left measured 0x0 until something forces a
     * fresh pass. Applying it on the next frame gets a real measure.
     */
    private fun refreshVideoControlsVisibility() {
        val bar = views.attachmentPreviewerVideoControls
        bar.post {
            if (!isAdded) return@post
            bar.isVisible = videoControls != null
            // The controls row grows the bottom panel upwards and the send button, anchored to
            // the panel's top edge, would ride up with it; sit it back down by the row's height
            // so it keeps the same spot it has for image attachments.
            if (videoControls != null) {
                bar.doOnLayout { views.attachmentPreviewerSendButton.translationY = it.height.toFloat() }
            } else {
                views.attachmentPreviewerSendButton.translationY = 0f
            }
        }
    }

    override fun onVideoProgress(positionMs: Int, durationMs: Int, isPlaying: Boolean) {
        val bar = views.attachmentPreviewerVideoSeekBar
        views.attachmentPreviewerVideoTime.text = videoTimeLabel(positionMs, durationMs)
        if (scrubbingVideo) return
        seekBarAnimator?.cancel()
        if (bar.max != durationMs.coerceAtLeast(1)) {
            bar.max = durationMs.coerceAtLeast(1)
            bar.progress = positionMs.coerceIn(0, durationMs)
            return
        }
        val target = positionMs.coerceIn(0, durationMs)
        // Same glide between the 100ms reports as the media viewer's scrubber; jumps snap.
        val delta = target - bar.progress
        if (isPlaying && delta in 0..1200) {
            seekBarAnimator = ObjectAnimator.ofInt(bar, "progress", target).apply {
                duration = 120L
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            bar.progress = target
        }
    }

    private fun videoTimeLabel(positionMs: Int, durationMs: Int) = getString(
            CommonStrings.video_position_of_duration,
            DateUtils.formatElapsedTime((positionMs / 1000).toLong()),
            DateUtils.formatElapsedTime((durationMs / 1000).toLong())
    )

    private val videoEditorActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == RESULT_OK) {
            if (activityResult.data?.isRestoreOriginal() == true) {
                restoreOriginal()
                return@registerStartForActivityResult
            }
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
                                // An animated image comes back through the same editor, and a
                                // duration on an image attachment means nothing.
                                duration = output.durationMs.takeIf { output.mimeType.isMimeTypeVideo() },
                                editRecord = pendingEditOriginal?.let { EditRecord(it, output.edits) }
                        )
                )
            } else {
                Toast.makeText(requireContext(), getString(CommonStrings.video_editor_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val imageEditorActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == RESULT_OK) {
            if (activityResult.data?.isRestoreOriginal() == true) {
                restoreOriginal()
                return@registerStartForActivityResult
            }
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
                                editRecord = pendingEditOriginal?.let { EditRecord(it, output.edits) }
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
            R.id.attachmentsPreviewCompressionAction -> {
                showCompressionSheet()
                true
            }
            else -> false
        }
    }

    override fun handlePrepareMenu(menu: Menu) {
        withState(viewModel) { state ->
            val current = state.attachments.getOrNull(state.currentAttachmentIndex)
            val editable = current?.isEditable(animated = animatedFormatOf(current) != null)
            menu.findItem(R.id.attachmentsPreviewEditAction).setVisible(editable.orFalse())
            menu.findItem(R.id.attachmentsPreviewCompressionAction).setVisible(current?.isCompressible().orFalse())
        }
    }

    /**
     * Telling an animated image from a still one means reading its header, and the menu is prepared
     * far more often than the selection changes, so the answer is kept.
     */
    private fun animatedFormatOf(attachment: ContentAttachmentData): AnimatedImageFormat? {
        // Not getOrPut: "this one is a still image" is a null, and that is worth remembering too.
        val key = attachment.queryUri
        if (!animatedFormats.containsKey(key)) {
            animatedFormats[key] = attachment.animatedImageFormat(requireContext())
        }
        return animatedFormats[key]
    }

    /**
     * Compression belongs to the preview rather than an editor: it is about the upload, not the
     * picture, and the SDK's own compressors act on it at send time.
     */
    private fun showCompressionSheet() = withState(viewModel) { state ->
        val current = state.attachments.getOrNull(state.currentAttachmentIndex) ?: return@withState
        // This screen's own theme has no accent variant, so ?colorAccent inside it is always the
        // default green. Hosting the sheet in the configured application theme fixes every widget
        // at once — seek bar, buttons, the text cursor and its selection handles — rather than
        // tinting drawables one at a time and missing the ones with no drawable to tint.
        val themedContext = ContextThemeWrapper(requireContext(), ThemeUtils.getApplicationThemeRes(requireContext()))
        val binding = BottomSheetAttachmentCompressionBinding.inflate(LayoutInflater.from(themedContext))
        val dialog = BottomSheetDialog(themedContext).apply { setContentView(binding.root) }
        // As the picture appears, not as it is stored: a photo carrying an EXIF quarter-turn would
        // otherwise be offered its sides the wrong way round, and typing a size against those
        // numbers squashes the image — the compressor rotates before it scales.
        val sourceWidth = current.displayWidth?.toInt() ?: 0
        val sourceHeight = current.displayHeight?.toInt() ?: 0
        val aspect = if (sourceWidth > 0 && sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 1f
        // Seeded with the source size so the model always holds what the boxes show: tracking only
        // the box the user typed in left the other null, and a half-specified size does nothing.
        var settings = state.compressionSettings[state.stableIdOf(current)]
                ?: CompressionSettings(width = sourceWidth.takeIf { it > 0 }, height = sourceHeight.takeIf { it > 0 })
        // "Original size" is a quality decision of its own, so the slider has nothing left to say.
        val originalSize = views.attachmentPreviewerSendImageOriginalSize.isChecked

        // Writing the linked dimension back into its field would otherwise re-enter this watcher.
        var updating = false
        fun render(fields: Boolean) {
            updating = true
            binding.compressionQualityValue.text = getString(CommonStrings.attachment_compression_percent, settings.quality)
            binding.compressionQualitySeekBar.progress = settings.quality
            binding.compressionLinkToggle.setImageResource(
                    if (settings.linked) R.drawable.ic_aspect_linked else R.drawable.ic_aspect_unlinked
            )
            // Accent only while it is doing something; the broken chain reads as an inactive control.
            // Not colorControlNormal: AppCompat defines that as a ColorStateList, and resolving it
            // as a plain colour yields the resource id, which tints the icon to nothing.
            val toggleAttribute = if (settings.linked) {
                com.google.android.material.R.attr.colorAccent
            } else {
                im.vector.lib.ui.styles.R.attr.vctr_content_secondary
            }
            ImageViewCompat.setImageTintList(
                    binding.compressionLinkToggle,
                    ColorStateList.valueOf(ThemeUtils.getColorFromContextTheme(themedContext, toggleAttribute))
            )
            if (fields) {
                // Prefilled with the source size, so it is edited from rather than typed from scratch.
                binding.compressionWidth.setText((settings.width ?: sourceWidth).takeIf { it > 0 }?.toString().orEmpty())
                binding.compressionHeight.setText((settings.height ?: sourceHeight).takeIf { it > 0 }?.toString().orEmpty())
            }
            updating = false
        }

        binding.compressionSourceLabel.isVisible = sourceWidth > 0 && sourceHeight > 0
        binding.compressionSourceLabel.text = getString(CommonStrings.attachment_compression_source, sourceWidth, sourceHeight)
        binding.compressionQualitySeekBar.isEnabled = !originalSize
        if (originalSize) settings = settings.copy(quality = CompressionSettings.MAX_QUALITY)
        render(fields = true)

        binding.compressionQualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                settings = settings.copy(quality = progress)
                binding.compressionQualityValue.text = getString(CommonStrings.attachment_compression_percent, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.compressionWidth.doAfterTextChanged {
            if (updating) return@doAfterTextChanged
            val value = it?.toString()?.toIntOrNull()?.takeIf { typed -> typed > 0 } ?: return@doAfterTextChanged
            settings = settings.withWidth(value, aspect)
            if (settings.linked) {
                updating = true
                binding.compressionHeight.setText(settings.height?.toString().orEmpty())
                updating = false
            }
        }
        binding.compressionHeight.doAfterTextChanged {
            if (updating) return@doAfterTextChanged
            val value = it?.toString()?.toIntOrNull()?.takeIf { typed -> typed > 0 } ?: return@doAfterTextChanged
            settings = settings.withHeight(value, aspect)
            if (settings.linked) {
                updating = true
                binding.compressionWidth.setText(settings.width?.toString().orEmpty())
                updating = false
            }
        }

        binding.compressionLinkToggle.setOnClickListener {
            settings = settings.copy(linked = !settings.linked)
            // Re-linking pulls the height back onto the source's shape straight away.
            settings.width?.takeIf { settings.linked }?.let { settings = settings.withWidth(it, aspect) }
            render(fields = settings.linked)
        }
        binding.compressionReset.setOnClickListener {
            settings = CompressionSettings(
                    quality = if (originalSize) CompressionSettings.MAX_QUALITY else CompressionSettings.STANDARD_QUALITY,
                    width = sourceWidth.takeIf { it > 0 },
                    height = sourceHeight.takeIf { it > 0 }
            )
            render(fields = true)
        }
        binding.compressionDone.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            val chosen = settings.withoutRedundantSize(sourceWidth, sourceHeight)
            viewModel.handle(AttachmentsPreviewAction.SetCompression(chosen))
        }
        dialog.show()
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
        attachmentBigPreviewController.playbackListener = null
        videoControls = null
        bigListSnapListener = null
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
                bigListSnapListener?.onScrolledProgrammatically(state.currentAttachmentIndex)
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

    private fun setResultAndFinish() = withState(viewModel) { state ->
        val originalSize = views.attachmentPreviewerSendImageOriginalSize.isChecked
        val attachments = state.attachments.map { attachment ->
            // Boxes left at the source size are not a resize request, so they must not make the
            // attachment look custom-compressed.
            val sourceWidth = attachment.width?.toInt()
            val sourceHeight = attachment.height?.toInt()
            val settings = state.compressionSettings[state.stableIdOf(attachment)]
                    ?.let { if (sourceWidth != null && sourceHeight != null) it.withoutRedundantSize(sourceWidth, sourceHeight) else it }
            if (originalSize) {
                // Original size means the file is uploaded untouched: anything non-null here sets
                // hasCustomCompression, which is what re-encodes (and, for video, transcodes) it.
                // Only an explicitly typed size overrides that, and then at full quality.
                if (settings?.width == null) return@map attachment
                return@map attachment.copy(
                        compressionQuality = CompressionSettings.MAX_QUALITY,
                        compressionWidth = settings.width,
                        compressionHeight = settings.height,
                )
            }
            if (settings == null) return@map attachment
            attachment.copy(
                    compressionQuality = settings.quality.takeIf { it != CompressionSettings.STANDARD_QUALITY },
                    compressionWidth = settings.width,
                    compressionHeight = settings.height,
            )
        }
        (requireActivity() as? AttachmentsPreviewActivity)?.setResultAndFinish(attachments, originalSize)
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

    /** Everything was undone in the editor, so the untouched attachment takes its place again. */
    private fun restoreOriginal() {
        discardSupersededExport()
        viewModel.handle(AttachmentsPreviewAction.RestoreOriginalAttachment)
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
        val original = record?.original ?: currentAttachment
        pendingEditOriginal = original
        // Freed now, not on the async rebuild after onPause: the editor is about to open its own
        // decoder on this same clip, and one still held here can cost it its codec — an editor
        // that opens on a black frame.
        videoControls?.suspendPlayback()
        val source = original.queryUriAndroid
        val animatedFormat = animatedFormatOf(currentAttachment)
        if (currentAttachment.isVideoEditable() || animatedFormat != null) {
            // The editor shows the shape it will be sent at, not the source's.
            val compression = state.compressionSettings[state.stableIdOf(currentAttachment)]
            videoEditorActivityResultLauncher.launch(
                    VideoEditorActivity.newIntent(
                            requireContext(),
                            source,
                            currentAttachment.name,
                            record?.edits as? VideoEditorEdits,
                            compression?.width?.let { width -> compression.height?.let { width to it } },
                            animatedFormat
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
            bigListSnapListener = it.attachSnapHelperWithListener(
                    PagerSnapHelper(),
                    SnapOnScrollListener.Behavior.NOTIFY_ON_SCROLL_STATE_IDLE,
                    object : OnSnapPositionChangeListener {
                        override fun onSnapPositionChange(position: Int) {
                            viewModel.handle(AttachmentsPreviewAction.SetCurrentAttachment(position))
                        }
                    })
            it.setHasFixedSize(true)
            it.adapter = attachmentBigPreviewController.adapter
            attachmentBigPreviewController.playbackListener = this
            attachmentBigPreviewController.loopVideos = vectorPreferences.loopVideos()
        }
    }
}
