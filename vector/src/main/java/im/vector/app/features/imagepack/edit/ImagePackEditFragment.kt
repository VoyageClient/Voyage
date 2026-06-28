/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import im.vector.lib.core.utils.compat.use
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import im.vector.app.core.glide.RoundedCornersPercent
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.FragmentImagePackEditBinding
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackImage
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackMeta
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.imagepack.effectiveImages
import org.matrix.android.sdk.api.session.room.model.imagepack.resolveUsages
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import javax.inject.Inject
import im.vector.app.core.extensions.backgroundCompat

// Same rounded-square ratio as space avatars (RoundedCornersPercent), so rounding stays proportional to size.
private const val ROUNDED_CORNER_PERCENT = 0.20f

@AndroidEntryPoint
class ImagePackEditFragment :
        VectorBaseFragment<FragmentImagePackEditBinding>(),
        ImagePackEditController.Listener,
        GalleryOrCameraDialogHelper.Listener,
        VectorMenuProvider,
        OnBackPressed {

    @Inject lateinit var repository: ImagePackRepository
    @Inject lateinit var controller: ImagePackEditController
    @Inject lateinit var activeSessionHolder: im.vector.app.core.di.ActiveSessionHolder
    @Inject lateinit var galleryOrCameraDialogHelperFactory: GalleryOrCameraDialogHelperFactory

    private lateinit var galleryOrCameraDialogHelper: GalleryOrCameraDialogHelper

    private val pageArgs: ImagePackEditArgs by args()

    // Edit state lives in a ViewModel so it survives configuration changes (rotation) instead of reverting.
    private val editViewModel: ImagePackEditViewModel by viewModels()

    private val images get() = editViewModel.images
    private var packName: String?
        get() = editViewModel.packName
        set(value) { editViewModel.packName = value }
    private var packAvatarUrl: String?
        get() = editViewModel.packAvatarUrl
        set(value) { editViewModel.packAvatarUrl = value }
    // False for the create flow (no state event yet) — nothing to delete, so hide the trashcan.
    private var packExists: Boolean
        get() = editViewModel.packExists
        set(value) { editViewModel.packExists = value }
    // Pack-level usage (MSC2545). When it declares a single usage, every image inherits it and the per-image
    // emoticon/sticker toggles are hidden — the usage is decided by the pack, not per image.
    private var packUsage: List<String>?
        get() = editViewModel.packUsage
        set(value) { editViewModel.packUsage = value }

    // Snapshot of the pack as loaded, to detect unsaved changes when leaving.
    private var initialContent: ImagePackContent?
        get() = editViewModel.initialContent
        set(value) { editViewModel.initialContent = value }


    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImagePicked(it) }
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentImagePackEditBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
        views.imagePackImagesRecycler.configureWith(controller, hasFixedSize = true)
        views.imagePackImagesRecycler.addItemDecoration(im.vector.app.core.epoxy.ListDividerDecoration(requireContext()))
        if (pageArgs.canEdit) enableDragReorder()

        val firstLoad = !editViewModel.loaded
        if (firstLoad) {
            loadExisting()
            initialContent = buildContent()
            editViewModel.loaded = true
        }
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(
                if (packExists) CommonStrings.image_pack_edit_title else CommonStrings.image_pack_create_title
        )

        val isAccountPack = pageArgs.roomId == null
        views.imagePackNameTil.isVisible = !isAccountPack
        if (!isAccountPack) {
            if (firstLoad && packName == null) packName = pageArgs.displayName
            views.imagePackNameInput.setText(packName)
            views.imagePackNameInput.doAfterTextChanged {
                packName = it?.toString()?.takeIf { s -> s.isNotBlank() }
                requireActivity().invalidateOptionsMenu()
            }
        }

        // Rounded-square avatar (same 20%-of-side ratio as space avatars), so the corner rounding stays
        // proportional whatever size it's shown at. Transparent areas of (often-transparent) sticker images
        // read as the page background through the matching rounded container.
        val bgColor = ThemeUtils.getColor(requireContext(), android.R.attr.colorBackground)
        views.imagePackAvatarContainer.doOnLayout { container ->
            val radius = container.width * ROUNDED_CORNER_PERCENT
            views.imagePackAvatarImage.setCornerRadii(radius, radius, radius, radius)
            views.imagePackAvatarContainer.backgroundCompat = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius
                setColor(bgColor)
            }
        }
        if (pageArgs.canEdit) {
            // No delete option in the picker dialog — the X button next to the avatar handles removal.
            views.imagePackAvatarContainer.setOnClickListener { galleryOrCameraDialogHelper.show() }
        }
        views.imagePackAvatarDelete.setOnClickListener {
            packAvatarUrl = null
            renderAvatar()
            requireActivity().invalidateOptionsMenu()
        }
        renderAvatar()

        applyEditable()
        refresh()
    }

    private fun renderAvatar() {
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        // When no avatar is set, fall back to the pack's first image — pickers auto-use it as the avatar.
        val explicit = packAvatarUrl != null
        val mxc = packAvatarUrl ?: images.firstOrNull()?.mxcUrl
        val resolved = mxc?.let {
            contentUrlResolver?.resolveThumbnail(it, 160, 160, org.matrix.android.sdk.api.session.content.ContentUrlResolver.ThumbnailMethod.SCALE)
        }
        if (resolved != null) {
            androidx.core.widget.ImageViewCompat.setImageTintList(views.imagePackAvatarImage, null)
            // RoundedCornerImageView only clips pre-Lollipop, so round the bitmap itself (works on all APIs).
            im.vector.app.core.glide.GlideApp.with(views.imagePackAvatarImage)
                    .load(resolved)
                    .transform(MultiTransformation(CenterCrop(), RoundedCornersPercent(ROUNDED_CORNER_PERCENT)))
                    .into(views.imagePackAvatarImage)
        } else {
            im.vector.app.core.glide.GlideApp.with(views.imagePackAvatarImage.context.applicationContext).clear(views.imagePackAvatarImage)
            // The sticker glyph hard-codes Element green; tint it to follow the (SC) accent instead.
            androidx.core.widget.ImageViewCompat.setImageTintList(
                    views.imagePackAvatarImage,
                    android.content.res.ColorStateList.valueOf(ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorAccent))
            )
            views.imagePackAvatarImage.setImageResource(R.drawable.ic_attachment_sticker)
        }
        // Delete only makes sense for an explicitly-set avatar (clearing reverts to the first-image default).
        views.imagePackAvatarDelete.isVisible = pageArgs.canEdit && explicit
    }

    override fun onImageReady(uri: Uri?) {
        uri ?: return
        // contentResolver.getType() is null for uCrop's file:// output; without a mime the server stores it
        // as octet-stream and can't thumbnail it (blank avatar). Derive one from the extension instead.
        val mimeType = requireContext().contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
                ?: "image/png"
        lifecycleScope.launch {
            val mxcUrl = try {
                repository.uploadImage(uri, null, mimeType)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
                return@launch
            }
            packAvatarUrl = mxcUrl
            renderAvatar()
            activity?.invalidateOptionsMenu()
        }
    }

    override fun getMenuRes() = R.menu.menu_image_pack_edit

    override fun handlePrepareMenu(menu: Menu) {
        val canEdit = pageArgs.canEdit
        // Match the toolbar back arrow's grey; the Apply checkmark goes a dimmer grey when disabled.
        val enabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        val disabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        menu.findItem(R.id.imagePackMenuDelete)?.apply {
            // The personal account pack always exists; only room packs can be deleted, and only once they've
            // actually been created (no trashcan in the create flow — there's nothing to delete yet).
            isVisible = canEdit && pageArgs.roomId != null && packExists
            icon?.mutate()?.let { DrawableCompat.setTint(it, enabledTint) }
        }
        menu.findItem(R.id.imagePackMenuApply)?.apply {
            isVisible = canEdit
            // Disabled (and dimmer) when there's nothing to apply.
            val dirty = isDirty()
            isEnabled = dirty
            icon?.mutate()?.let { DrawableCompat.setTint(it, if (dirty) enabledTint else disabledTint) }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.imagePackMenuApply -> { save(); true }
            R.id.imagePackMenuDelete -> { confirmDeletePack(); true }
            else -> false
        }
    }

    private fun enableDragReorder() {
        com.airbnb.epoxy.EpoxyTouchHelper.initDragging(controller)
                .withRecyclerView(views.imagePackImagesRecycler)
                .forVerticalList()
                // Only image rows are draggable; the "Add to pack" row is a different model type and stays put.
                .withTarget(ImagePackEditItem_::class.java)
                .andCallbacks(object : com.airbnb.epoxy.EpoxyTouchHelper.DragCallbacks<ImagePackEditItem_>() {
                    override fun onDragStarted(model: ImagePackEditItem_?, itemView: View?, adapterPosition: Int) {
                        itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        itemView?.let { androidx.core.view.ViewCompat.setElevation(it, 6f) }
                    }

                    override fun clearView(model: ImagePackEditItem_?, itemView: View?) {
                        itemView?.let { androidx.core.view.ViewCompat.setElevation(it, 0f) }
                    }

                    override fun onModelMoved(fromPosition: Int, toPosition: Int, modelBeingMoved: ImagePackEditItem_?, itemView: View?) {
                        itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        // moveModel() schedules a delayed buildModels(); keep our backing list in step so that
                        // rebuild (which can fire mid-drag) reproduces the on-screen order instead of fighting it.
                        images.clear()
                        images.addAll(controller.currentOrderedImages())
                    }

                    override fun onDragReleased(model: ImagePackEditItem_?, itemView: View?) {
                        refresh()
                        requireActivity().invalidateOptionsMenu()
                    }
                })
    }

    private fun applyEditable() {
        val canEdit = pageArgs.canEdit
        controller.editable = canEdit
        controller.showUsageToggles = fixedUsage() == null
        views.imagePackReadOnlyNotice.isVisible = !canEdit
        views.imagePackNameInput.isEnabled = canEdit
        requireActivity().invalidateOptionsMenu()
    }

    override fun onDestroyView() {
        views.imagePackImagesRecycler.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    private fun loadExisting() {
        val content = if (pageArgs.roomId == null) {
            repository.getAccountPack()
        } else {
            repository.getRoomPack(pageArgs.roomId!!, pageArgs.stateKey)
        }
        packExists = content != null
        // The account pack has no name; never read or persist one for it.
        packName = if (pageArgs.roomId == null) null else content?.pack?.displayName
        packAvatarUrl = content?.pack?.avatarUrl
        packUsage = content?.pack?.usage?.takeIf { it.isNotEmpty() }
        images.clear()
        content?.effectiveImages()?.forEach { (shortcode, image) ->
            val usages = image.resolveUsages(content.pack)
            images += EditableImage(
                    shortcode = shortcode,
                    mxcUrl = image.url,
                    body = image.body,
                    info = image.info,
                    emoticon = ImagePackUsage.EMOTICON in usages,
                    sticker = ImagePackUsage.STICKER in usages,
            )
        }
    }

    private var uploadJob: Job? = null

    private fun onImagePicked(uri: Uri) {
        val mimeType = requireContext().contentResolver.getType(uri)
        val shortcode = shortcodeFromFileName(uri)
        uploadJob = lifecycleScope.launch {
            controller.uploading = true
            refresh()
            var compressedTemp: File? = null
            try {
                val (uploadUri, uploadMime) = withContext(Dispatchers.IO) { compressForUpload(uri, mimeType) }
                // compressForUpload returns a file:// temp distinct from the content:// source; clean it up after.
                compressedTemp = uploadUri.takeIf { it != uri && it.scheme == "file" }?.path?.let { File(it) }
                val info = withContext(Dispatchers.IO) { computeImageInfo(uploadUri, uploadMime) }
                val mxcUrl = uploadWithRetry(uploadUri, uploadMime)
                images += EditableImage(
                        shortcode = shortcode,
                        mxcUrl = mxcUrl,
                        body = shortcode,
                        info = info,
                        emoticon = true,
                        sticker = true,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Don't touch the UI if the user backed out mid-upload (fragment detached).
                if (isAdded) showFailure(failure)
            } finally {
                compressedTemp?.let { runCatching { it.delete() } }
            }
            controller.uploading = false
            refresh()
            activity?.invalidateOptionsMenu()
        }
    }

    // Foreground uploads have no retry (unlike the media-send worker), and a flaky TLS connection can stall
    // until the 60s read timeout. Use a shorter per-attempt timeout and retry on a fresh connection.
    private suspend fun uploadWithRetry(uri: Uri, mimeType: String?): String {
        var lastError: Throwable? = null
        repeat(UPLOAD_MAX_ATTEMPTS) {
            coroutineContext.ensureActive()
            try {
                return withTimeout(UPLOAD_ATTEMPT_TIMEOUT_MS) { repository.uploadImage(uri, null, mimeType) }
            } catch (timeout: TimeoutCancellationException) {
                lastError = timeout
            } catch (io: IOException) {
                coroutineContext.ensureActive()
                lastError = io
            }
        }
        // Not a CancellationException, so the caller surfaces it as a failure rather than silent cancellation.
        throw (lastError as? IOException) ?: IOException("Upload failed", lastError)
    }

    // Derive a valid shortcode from the picked file's name (strip extension, keep [A-Za-z0-9_-]).
    private fun shortcodeFromFileName(uri: Uri): String {
        val displayName = runCatching {
            requireContext().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val base = displayName?.substringBeforeLast('.')?.trim().orEmpty()
        // MSC2545 shortcodes are ASCII [a-zA-Z0-9-_] only; map anything else (incl. Unicode letters) to '_'.
        return base.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .take(100)
                .ifEmpty { "emote" }
    }

    // Decode the image's real dimensions and size; omit (0) any value we can't determine.
    // Always compress before upload via the SDK compressor (handles animated GIF/APNG/WebP as well as static
    // formats, downscaling within COMPRESS_MAX_DIMENSION and keeping the smaller of source/re-encode).
    private suspend fun compressForUpload(uri: Uri, mimeType: String?): Pair<Uri, String?> {
        return try {
            repository.compressImage(uri, mimeType, COMPRESS_MAX_DIMENSION)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Fall back to the original on compression failure rather than blocking the upload.
            uri to mimeType
        }
    }

    private fun computeImageInfo(uri: Uri, mimeType: String?): ImageInfo {
        var width = 0
        var height = 0
        runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                width = opts.outWidth.coerceAtLeast(0)
                height = opts.outHeight.coerceAtLeast(0)
            }
        }
        val size = runCatching {
            requireContext().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } ?: 0L } ?: 0L
        }.getOrDefault(0L)
        return ImageInfo(mimeType = mimeType, width = width, height = height, size = size)
    }

    private fun confirmDeletePack() {
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_delete)
                .setMessage(CommonStrings.image_pack_delete_confirm)
                .setPositiveButton(CommonStrings.action_delete) { _, _ -> deletePack() }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun deletePack() {
        lifecycleScope.launch {
            try {
                val roomId = pageArgs.roomId
                if (roomId == null) {
                    repository.deleteAccountPack()
                } else {
                    repository.clearRoomPack(roomId, pageArgs.stateKey)
                }
                activity?.finish()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
            }
        }
    }

    private fun save() {
        val roomId = pageArgs.roomId
        if (roomId != null && !repository.canEditRoomPacks(roomId)) {
            showErrorInSnackbar(IllegalStateException(getString(CommonStrings.image_pack_no_permission_room)))
            return
        }
        val duplicate = images.filter { it.shortcode.isNotBlank() }
                .groupBy { it.shortcode }
                .entries.firstOrNull { it.value.size > 1 }
                ?.key
        if (duplicate != null) {
            showErrorInSnackbar(IllegalStateException(getString(CommonStrings.image_pack_duplicate_shortcode, duplicate)))
            return
        }
        val content = buildContent()
        lifecycleScope.launch {
            try {
                if (roomId == null) {
                    repository.saveAccountPack(content)
                } else {
                    repository.saveRoomPack(roomId, pageArgs.stateKey, content)
                }
                activity?.finish()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
            }
        }
    }

    private fun buildContent(): ImagePackContent {
        val imageMap = images.filter { it.shortcode.isNotBlank() }.associate { editable ->
            editable.shortcode to ImagePackImage(
                    url = editable.mxcUrl,
                    body = editable.body,
                    // Don't persist fabricated zero dimensions.
                    info = editable.info?.takeIf { it.width > 0 && it.height > 0 },
                    usage = usageList(editable),
            )
        }
        return ImagePackContent(
                images = imageMap,
                pack = ImagePackMeta(displayName = packName, avatarUrl = packAvatarUrl),
        )
    }

    // A single pack-level usage decides every image; don't write a (possibly conflicting) per-image usage.
    private fun fixedUsage(): String? = packUsage?.singleOrNull()

    // null = usable everywhere (both / neither selected) or inherited from the pack; otherwise the explicit
    // single usage.
    private fun usageList(editable: EditableImage): List<String>? = when {
        fixedUsage() != null -> null
        editable.emoticon && editable.sticker -> null
        editable.emoticon -> listOf(ImagePackUsage.EMOTICON)
        editable.sticker -> listOf(ImagePackUsage.STICKER)
        else -> null
    }

    private fun refresh() {
        controller.setData(images)
        // The unset-avatar placeholder follows the pack's first image.
        if (packAvatarUrl == null) renderAvatar()
    }

    override fun onDeleteImage(image: EditableImage) {
        images.remove(image)
        refresh()
        requireActivity().invalidateOptionsMenu()
    }

    override fun onAddImage() {
        pickImageLauncher.launch("image/*")
    }

    override fun onImageEdited() {
        requireActivity().invalidateOptionsMenu()
    }

    private fun isDirty(): Boolean {
        if (!pageArgs.canEdit) return false
        if (buildContent() != initialContent) return true
        // Map equality ignores order, so detect a pure reorder separately.
        val currentOrder = images.filter { it.shortcode.isNotBlank() }.map { it.shortcode }
        return currentOrder != initialContent?.images?.keys?.toList().orEmpty()
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        val uploading = uploadJob?.isActive == true
        if (!isDirty() && !uploading) return false
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_unsaved_title)
                .setMessage(CommonStrings.image_pack_unsaved_message)
                .apply {
                    // "Apply" only when there are committed changes to save (not while an upload is still in flight).
                    if (isDirty() && !uploading) {
                        setPositiveButton(CommonStrings.image_pack_apply) { _, _ -> save() }
                    }
                }
                .setNegativeButton(CommonStrings.image_pack_unsaved_discard) { _, _ ->
                    uploadJob?.cancel()
                    activity?.finish()
                }
                .setNeutralButton(CommonStrings.action_cancel, null)
                .show()
        return true
    }

    companion object {
        private const val UPLOAD_MAX_ATTEMPTS = 3
        private const val UPLOAD_ATTEMPT_TIMEOUT_MS = 30_000L
        private const val COMPRESS_MAX_DIMENSION = 1024
    }
}
