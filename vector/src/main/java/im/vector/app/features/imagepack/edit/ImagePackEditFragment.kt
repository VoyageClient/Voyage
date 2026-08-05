/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.safeOpenOutputStream
import im.vector.app.core.glide.RoundedCornersPercent
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.saveMedia
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentImagePackEditBinding
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.compat.use
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackImage
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackMeta
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.imagepack.effectiveImages
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject

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
    @Inject lateinit var archiver: ImagePackArchiver
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var clock: Clock

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

    // Pack-level usage (MSC2545); null/empty = usable as both (spec default). Set from the Image Pack Type
    // menu; images without their own usage inherit it.
    private var packUsage: List<String>?
        get() = editViewModel.packUsage
        set(value) { editViewModel.packUsage = value }

    // Snapshot of the pack as loaded, to detect unsaved changes when leaving.
    private var initialContent: ImagePackContent?
        get() = editViewModel.initialContent
        set(value) { editViewModel.initialContent = value }

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = extractPickedUris(result.data)
            if (uris.isNotEmpty()) onImagesPicked(uris)
        }
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
            // No pageArgs.displayName prefill: for unnamed packs it carries the resolved room-name fallback,
            // which must not silently become the stored name on the next apply.
            // Animation off for the prefill, or the hint starts in-field drawn over the restored name.
            views.imagePackNameTil.isHintAnimationEnabled = false
            views.imagePackNameInput.setText(packName)
            views.imagePackNameTil.isHintAnimationEnabled = true
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
            // Rounds animated avatars (which skip the baked-bitmap rounding below) on L+; pre-L the
            // RoundedCornerImageView canvas clip covers them.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                views.imagePackAvatarContainer.clipToOutline = true
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
        val resolved = mxc?.let { contentUrlResolver?.resolveFullSize(it) }
        if (resolved != null) {
            androidx.core.widget.ImageViewCompat.setImageTintList(views.imagePackAvatarImage, null)
            // optionalTransform, NOT transform: Glide can't snapshot Animatable drawables (WebP/APNG), so a
            // required transform fails the whole load. Optional leaves animated content untransformed (and
            // animating); the view/container clip rounds it instead.
            im.vector.app.core.glide.GlideApp.with(views.imagePackAvatarImage)
                    .load(resolved)
                    .optionalTransform(MultiTransformation(CenterCrop(), RoundedCornersPercent(ROUNDED_CORNER_PERCENT)))
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
        val exporting = exportJob?.isActive == true
        // Match the toolbar back arrow's grey; the Apply checkmark goes a dimmer grey when disabled.
        val enabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        val disabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
        menu.findItem(R.id.imagePackMenuDelete)?.apply {
            // The personal account pack always exists; only room packs can be deleted, and only once they've
            // actually been created (no trashcan in the create flow — there's nothing to delete yet).
            isVisible = !exporting && canEdit && pageArgs.roomId != null && packExists
            icon?.mutate()?.let { DrawableCompat.setTint(it, enabledTint) }
        }
        menu.findItem(R.id.imagePackMenuApply)?.apply {
            isVisible = !exporting && canEdit
            // Disabled (and dimmer) when there's nothing to apply — or no pack name yet (room packs
            // must be named; unnamed ones would show as the room's name in MSC2545-following clients).
            val applicable = canApply()
            isEnabled = applicable
            icon?.mutate()?.let { DrawableCompat.setTint(it, if (applicable) enabledTint else disabledTint) }
        }
        menu.findItem(R.id.imagePackMenuExport)?.apply {
            // Read-only viewers can export too; hidden until the pack has actually been created (and has images).
            isVisible = !exporting && packExists && images.isNotEmpty()
            icon?.mutate()?.let { DrawableCompat.setTint(it, enabledTint) }
        }
        menu.findItem(R.id.imagePackMenuType)?.apply {
            isVisible = !exporting && canEdit
            icon?.mutate()?.let { DrawableCompat.setTint(it, enabledTint) }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.imagePackMenuApply -> { save(); true }
            R.id.imagePackMenuDelete -> { confirmDeletePack(); true }
            R.id.imagePackMenuExport -> { exportPack(); true }
            R.id.imagePackMenuType -> { showPackTypeDialog(); true }
            else -> false
        }
    }

    private var exportJob: Job? = null

    private val createExportDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runExport(
                    write = { zip ->
                        withContext(Dispatchers.IO) {
                            // "wt" (via safeOpenOutputStream) truncates an existing document instead of leaving a tail.
                            val out = requireContext().safeOpenOutputStream(uri) ?: throw FileNotFoundException(uri.toString())
                            out.use { zip.inputStream().use { input -> input.copyTo(it) } }
                        }
                    },
                    // The picker creates the document up front, so a cancelled/failed export must remove
                    // the empty zip it leaves behind.
                    onAbort = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            runCatching { android.provider.DocumentsContract.deleteDocument(requireContext().contentResolver, uri) }
                        }
                    },
            )
        }
    }

    // Blank is fine: the archiver falls back to "image_pack" for the file name and omits the category.
    private fun exportName(): String = if (pageArgs.roomId == null) {
        getString(CommonStrings.image_pack_personal_pack)
    } else {
        packName ?: pageArgs.displayName.orEmpty()
    }

    private fun exportPack() {
        if (exportJob?.isActive == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Same flow as key export: a create-document dialog with the pack name pre-filled as the file name.
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "${exportName()}.zip")
            try {
                createExportDocumentLauncher.launch(intent)
            } catch (activityNotFound: ActivityNotFoundException) {
                exportToDownloads()
            }
        } else {
            // No SAF before KitKat — save straight to Downloads (no runtime permissions pre-23 either).
            exportToDownloads()
        }
    }

    private fun exportToDownloads() {
        runExport(write = { zip ->
            saveMedia(
                    context = requireContext(),
                    file = zip,
                    title = zip.name,
                    mediaMimeType = "application/zip",
                    notificationUtils = notificationUtils,
                    currentTimeMillis = clock.epochMillis(),
            )
        })
    }

    // "Exporting" screen state: an opaque overlay with a centered spinner replaces the editor, the toolbar
    // title switches, and the menu hides. Back asks to cancel; the screen closes itself once saved.
    private fun showExportScreen(total: Int) {
        views.imagePackExportOverlay.isVisible = true
        views.imagePackExportProgress.text = getString(CommonStrings.image_pack_exporting, 0, total)
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.image_pack_exporting_title)
        requireActivity().invalidateOptionsMenu()
    }

    private fun hideExportScreen() {
        views.imagePackExportOverlay.isVisible = false
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(
                if (packExists) CommonStrings.image_pack_edit_title else CommonStrings.image_pack_create_title
        )
        requireActivity().invalidateOptionsMenu()
    }

    private fun runExport(write: suspend (File) -> Unit, onAbort: () -> Unit = {}) {
        if (exportJob?.isActive == true) return
        val exportImages = images.toList()
        showExportScreen(exportImages.size)
        exportJob = lifecycleScope.launch {
            try {
                val result = archiver.exportPack(exportName(), exportImages, packUsage) { done, total ->
                    // Progress arrives on IO; hop to main for the view.
                    lifecycleScope.launch {
                        if (view != null) views.imagePackExportProgress.text = getString(CommonStrings.image_pack_exporting, done, total)
                    }
                }
                try {
                    write(result.zipFile)
                } finally {
                    runCatching { result.zipFile.parentFile?.deleteRecursively() }
                }
                if (isAdded) {
                    if (result.skippedShortcodes.isNotEmpty()) {
                        MaterialAlertDialogBuilder(requireContext())
                                .setTitle(CommonStrings.image_pack_export)
                                .setMessage(getString(CommonStrings.image_pack_export_skipped, result.skippedShortcodes.joinToString(", ")))
                                .setPositiveButton(CommonStrings.ok, null)
                                .show()
                    } else {
                        requireContext().toast(getString(CommonStrings.image_pack_export_done))
                    }
                }
            } catch (cancellation: CancellationException) {
                runCatching { onAbort() }
                throw cancellation
            } catch (failure: Throwable) {
                runCatching { onAbort() }
                if (isAdded) showFailure(failure)
            } finally {
                if (isAdded && view != null) hideExportScreen()
            }
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

    // The account pack (im.ponies.user_emotes) and legacy im.ponies.room_emotes packs support per-image
    // usage; the current MSC2545 schema carries usage on the pack only, so stable packs get no toggles.
    // Cached: resolving it parses the full pack event, and hot paths hit this per image (loadExisting,
    // buildContent via isDirty on every menu invalidation).
    private val supportsPerImageUsage: Boolean by lazy {
        val roomId = pageArgs.roomId ?: return@lazy true
        repository.isRoomPackLegacy(roomId, pageArgs.stateKey)
    }

    private fun applyEditable() {
        val canEdit = pageArgs.canEdit
        controller.editable = canEdit
        // Per-image toggles only matter when the pack allows everything: a restricting pack usage wins
        // over per-image values, so the toggles would be inert.
        controller.showUsageToggles = supportsPerImageUsage && fixedUsage() == null
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
            // The toggles carry the PER-ENTRY layer only (not the pack-resolved usage): while a restricting
            // pack usage hides them, the layer is preserved through saves and restored when the pack goes
            // back to Both. Stable packs have no per-entry layer.
            val perEntry = if (supportsPerImageUsage) image.usage?.takeIf { it.isNotEmpty() }?.toSet() else null
            val usages = perEntry ?: setOf(ImagePackUsage.EMOTICON, ImagePackUsage.STICKER)
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
    private val pendingUploads = ArrayDeque<Uri>()

    // Uploads run in parallel (bounded), but a finished image is only appended once every
    // earlier-selected one has been (prefix flush) — so the list order is the selection order,
    // not upload-completion luck. Images picked mid-batch queue up for the next batch.
    private fun onImagesPicked(uris: List<Uri>) {
        pendingUploads.addAll(uris)
        if (uploadJob?.isActive == true) return
        uploadJob = lifecycleScope.launch {
            controller.uploading = true
            refresh()
            var firstFailure: Throwable? = null
            while (pendingUploads.isNotEmpty()) {
                val batch = pendingUploads.toList()
                pendingUploads.clear()
                val results = arrayOfNulls<EditableImage>(batch.size)
                val completed = BooleanArray(batch.size)
                var flushed = 0
                val semaphore = Semaphore(UPLOAD_PARALLELISM)
                coroutineScope {
                    batch.forEachIndexed { index, uri ->
                        launch {
                            semaphore.withPermit {
                                // Failures don't cancel the batch: record the first, skip the image.
                                results[index] = try {
                                    uploadOneImage(uri)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (failure: Throwable) {
                                    if (firstFailure == null) firstFailure = failure
                                    null
                                }
                                // All state mutation happens on the main dispatcher — no locking needed.
                                completed[index] = true
                                while (flushed < batch.size && completed[flushed]) {
                                    results[flushed]?.let { images += it }
                                    flushed++
                                }
                                refresh()
                                activity?.invalidateOptionsMenu()
                            }
                        }
                    }
                }
            }
            controller.uploading = false
            refresh()
            activity?.invalidateOptionsMenu()
            // Don't touch the UI if the user backed out mid-upload (fragment detached).
            firstFailure?.let { if (isAdded) showFailure(it) }
        }
    }

    private suspend fun uploadOneImage(uri: Uri): EditableImage {
        val mimeType = requireContext().contentResolver.getType(uri)
        val shortcode = shortcodeFromFileName(uri)
        var compressedTemp: File? = null
        try {
            val (uploadUri, uploadMime) = withContext(Dispatchers.IO) { compressForUpload(uri, mimeType) }
            // compressForUpload returns a file:// temp distinct from the content:// source; clean it up after.
            compressedTemp = uploadUri.takeIf { it != uri && it.scheme == "file" }?.path?.let { File(it) }
            val info = withContext(Dispatchers.IO) { computeImageInfo(uploadUri, uploadMime) }
            val mxcUrl = repository.uploadImageWithRetry(uploadUri, null, uploadMime)
            return EditableImage(
                    shortcode = shortcode,
                    mxcUrl = mxcUrl,
                    body = shortcode,
                    info = info,
                    emoticon = true,
                    sticker = true,
            )
        } finally {
            compressedTemp?.let { runCatching { it.delete() } }
        }
    }

    // Derive a valid shortcode from the picked file's name (strip extension, keep [A-Za-z0-9_-]).
    private fun shortcodeFromFileName(uri: Uri): String {
        val displayName = requireContext().queryDisplayName(uri)
        return sanitizeShortcode(displayName?.substringBeforeLast('.').orEmpty())
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

    private fun canApply(): Boolean {
        if (!isDirty()) return false
        if (pageArgs.roomId == null) return true
        // New packs must be named. A pack another client created unnamed stays editable and saveable
        // unnamed (its display falls back to the room name per MSC2545) — but clearing the name of a
        // pack that HAS one is still blocked.
        val existedUnnamed = packExists && initialContent?.pack?.displayName.isNullOrBlank()
        return existedUnnamed || !packName.isNullOrBlank()
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
                    repository.saveAccountPack(content, includeUsage = true)
                } else {
                    repository.saveRoomPack(roomId, pageArgs.stateKey, content, includeUsage = true)
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
                pack = ImagePackMeta(displayName = packName, avatarUrl = packAvatarUrl, usage = packUsage),
        )
    }

    private fun fixedUsage(): String? = packUsage?.singleOrNull()

    // Per-image usage is only written for legacy im.ponies packs; null = usable everywhere (both / neither
    // selected). Stable packs express usage at pack level only (spec).
    private fun usageList(editable: EditableImage): List<String>? = when {
        !supportsPerImageUsage -> null
        editable.emoticon && editable.sticker -> null
        editable.emoticon -> listOf(ImagePackUsage.EMOTICON)
        editable.sticker -> listOf(ImagePackUsage.STICKER)
        else -> null
    }

    private fun showPackTypeDialog() {
        val options = arrayOf(
                getString(CommonStrings.image_pack_usage_emoticons),
                getString(CommonStrings.image_pack_usage_stickers),
                getString(CommonStrings.image_pack_usage_both),
        )
        val checked = when (fixedUsage()) {
            ImagePackUsage.EMOTICON -> 0
            ImagePackUsage.STICKER -> 1
            else -> 2
        }
        // Plain AlertDialog.Builder, not Material: matches the ListPreference dialogs in Settings
        // (Theme, App logo) that this deliberately mimics.
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(CommonStrings.image_pack_type_title)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    packUsage = when (which) {
                        0 -> listOf(ImagePackUsage.EMOTICON)
                        1 -> listOf(ImagePackUsage.STICKER)
                        // Absent usage means all types (spec default).
                        else -> null
                    }
                    // Toggle visibility follows the pack type (per-entry only matters on Both).
                    applyEditable()
                    refresh()
                    dialog.dismiss()
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
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
        val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pickImagesLauncher.launch(intent)
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
        if (exportJob?.isActive == true) {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CommonStrings.image_pack_exporting_title)
                    .setMessage(CommonStrings.image_pack_export_cancel_prompt)
                    .setPositiveButton(CommonStrings.yes) { _, _ -> exportJob?.cancel() }
                    .setNegativeButton(CommonStrings.no, null)
                    .show()
            return true
        }
        val uploading = uploadJob?.isActive == true
        if (!isDirty() && !uploading) return false
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_unsaved_title)
                .setMessage(CommonStrings.image_pack_unsaved_message)
                .apply {
                    // "Apply" only when there are committed, saveable changes (named pack, no upload in flight).
                    if (canApply() && !uploading) {
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
        private const val COMPRESS_MAX_DIMENSION = 1024
        private const val UPLOAD_PARALLELISM = 10
    }
}
