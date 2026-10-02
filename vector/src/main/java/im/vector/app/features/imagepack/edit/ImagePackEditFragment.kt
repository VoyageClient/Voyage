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
import im.vector.app.features.imagepack.telegram.TelegramMarker
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
import org.matrix.android.sdk.api.session.room.model.imagepack.withSequentialOrder
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
    private var packAvatarDraft: DraftImage?
        get() = editViewModel.packAvatarDraft
        set(value) { editViewModel.packAvatarDraft = value }

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
            // After the snapshot, so the draft counts as unsaved changes.
            pageArgs.draft?.let { applyDraft(it) }
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
            packAvatarDraft = null
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
        val explicit = packAvatarDraft != null || packAvatarUrl != null
        val firstImage = images.firstOrNull { !it.pendingRemoval }
        val resolved: Any? = packAvatarDraft?.file
                ?: packAvatarUrl?.let { contentUrlResolver?.resolveFullSize(it) }
                ?: firstImage?.local?.file
                ?: firstImage?.mxcUrl?.let { contentUrlResolver?.resolveFullSize(it) }
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
        lifecycleScope.launch {
            val avatar = try {
                archiver.copyPickedImage(uri, localDir())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
                return@launch
            }
            packAvatarDraft = avatar
            if (view == null) return@launch
            renderAvatar()
            requireActivity().invalidateOptionsMenu()
        }
    }

    private fun localDir(): File =
            editViewModel.localDir ?: newDraftDir(requireContext()).also {
                editViewModel.localDir = it
                editViewModel.draftDirs += it
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
            isVisible = !exporting && packExists && images.any { !it.pendingRemoval }
            icon?.mutate()?.let { DrawableCompat.setTint(it, enabledTint) }
        }
        menu.findItem(R.id.imagePackMenuType)?.apply {
            // The personal pack is always usable for both emoticons and stickers.
            isVisible = !exporting && canEdit && pageArgs.roomId != null
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
        val exportImages = images.filter { !it.pendingRemoval }
        showExportScreen(exportImages.size)
        exportJob = lifecycleScope.launch {
            try {
                val result = archiver.exportPack(exportName(), exportImages, packUsage, packAvatarUrl, packAvatarDraft?.file) { done, total ->
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
        // A drafted pack whose images differ in usage will be written with the legacy id.
        pageArgs.draft?.perImageUsage == true || repository.isRoomPackLegacy(roomId, pageArgs.stateKey)
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
        packUsage = if (pageArgs.roomId == null) null else content?.pack?.usage?.takeIf { it.isNotEmpty() }
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

    private fun applyDraft(draft: PackDraft) {
        editViewModel.draftDirs += File(draft.dir)
        if (!packExists) {
            draft.packName?.let { packName = it }
            draft.avatar?.let { packAvatarDraft = it }
            draft.usage?.let { packUsage = it }
            editViewModel.forceLegacy = draft.perImageUsage
        }
        draft.telegramSet?.let {
            editViewModel.telegramSet = it
            editViewModel.telegramStickers = draft.telegramStickers
        }
        appendDrafts(draft.images, draft.telegramOrder)
    }

    // With [telegramOrder], re-added stickers go back to their place in the set; anything else is appended.
    private fun appendDrafts(drafts: List<DraftImage>, telegramOrder: List<String> = emptyList()) {
        val used = images.map { it.shortcode }.toMutableSet()
        val telegramIndexByImage = HashMap<EditableImage, Int>()
        if (telegramOrder.isNotEmpty()) {
            val indexByUrl = telegramOrder.withIndex()
                    .mapNotNull { (index, uniqueId) -> editViewModel.telegramStickers[uniqueId]?.let { it to index } }
                    .toMap()
            images.forEach { image -> image.mxcUrl?.let { indexByUrl[it] }?.let { telegramIndexByImage[image] = it } }
        }
        val ordered = if (telegramOrder.isNotEmpty()) drafts.sortedBy { it.telegramIndex } else drafts
        ordered.forEach { draft ->
            var shortcode = draft.shortcode
            var suffix = 2
            while (!used.add(shortcode)) shortcode = "${draft.shortcode}_${suffix++}"
            val image = EditableImage(
                    shortcode = shortcode,
                    mxcUrl = null,
                    body = shortcode,
                    info = ImageInfo(mimeType = draft.mimeType, width = draft.width, height = draft.height, size = draft.file.length()),
                    emoticon = draft.emoticon,
                    sticker = draft.sticker,
                    local = draft,
                    added = true,
            )
            if (telegramOrder.isNotEmpty() && draft.telegramIndex >= 0) {
                images.add(TelegramMarker.insertionIndex(images, telegramIndexByImage, draft.telegramIndex), image)
                telegramIndexByImage[image] = draft.telegramIndex
            } else {
                images += image
            }
        }
    }

    private var pickJob: Job? = null
    private val pendingPicks = ArrayDeque<Uri>()

    // Copied in on the device, in selection order; a zip contributes all of its images.
    private fun onImagesPicked(uris: List<Uri>) {
        pendingPicks.addAll(uris)
        if (pickJob?.isActive == true) return
        pickJob = lifecycleScope.launch {
            controller.loading = true
            refresh()
            var firstFailure: Throwable? = null
            while (pendingPicks.isNotEmpty()) {
                val uri = pendingPicks.removeFirst()
                try {
                    if (isZip(uri)) {
                        val draft = archiver.extractDraft(uri)
                        editViewModel.draftDirs += File(draft.dir)
                        appendDrafts(draft.images)
                    } else {
                        appendDrafts(listOf(archiver.copyPickedImage(uri, localDir())))
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure
                }
                if (view != null) {
                    refresh()
                    requireActivity().invalidateOptionsMenu()
                }
            }
            controller.loading = false
            if (view == null) return@launch
            refresh()
            requireActivity().invalidateOptionsMenu()
            firstFailure?.let { showFailure(it) }
        }
    }

    private fun isZip(uri: Uri): Boolean {
        val mimeType = requireContext().contentResolver.getType(uri)
        return mimeType in ZIP_MIME_TYPES || requireContext().queryDisplayName(uri)?.endsWith(".zip", ignoreCase = true) == true
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
        if (saveJob?.isActive == true || pickJob?.isActive == true || !isDirty()) return false
        if (pageArgs.roomId == null) return true
        // New packs must be named. A pack another client created unnamed stays editable and saveable
        // unnamed (its display falls back to the room name per MSC2545) — but clearing the name of a
        // pack that HAS one is still blocked.
        val existedUnnamed = packExists && initialContent?.pack?.displayName.isNullOrBlank()
        return existedUnnamed || !packName.isNullOrBlank()
    }

    private var saveJob: Job? = null

    // Apply keeps the editor open; only the leave-with-unsaved-changes prompt closes it after saving.
    private fun save(finishAfter: Boolean = false) {
        if (saveJob?.isActive == true) return
        val roomId = pageArgs.roomId
        if (roomId != null && !repository.canEditRoomPacks(roomId)) {
            showErrorInSnackbar(IllegalStateException(getString(CommonStrings.image_pack_no_permission_room)))
            return
        }
        val duplicate = images.filter { !it.pendingRemoval && it.shortcode.isNotBlank() }
                .groupBy { it.shortcode }
                .entries.firstOrNull { it.value.size > 1 }
                ?.key
        if (duplicate != null) {
            showErrorInSnackbar(IllegalStateException(getString(CommonStrings.image_pack_duplicate_shortcode, duplicate)))
            return
        }
        saveJob = lifecycleScope.launch {
            try {
                uploadLocalImages()
                val content = buildContent()
                val telegramStickers = telegramStickersToSave()
                if (roomId == null) {
                    repository.saveAccountPack(content, includeUsage = true)
                } else {
                    repository.saveRoomPack(
                            roomId,
                            pageArgs.stateKey,
                            content,
                            includeUsage = true,
                            forceLegacy = editViewModel.forceLegacy,
                            extraTopLevel = editViewModel.telegramSet?.let { TelegramMarker.toTopLevel(it, telegramStickers) },
                    )
                }
                editViewModel.telegramStickers = telegramStickers
                if (finishAfter) {
                    activity?.finish()
                } else if (view != null) {
                    onSaved(content)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
            } finally {
                if (view != null) requireActivity().invalidateOptionsMenu()
            }
        }
        requireActivity().invalidateOptionsMenu()
    }

    // Uploads whatever is still only on the device. Images that made it keep their url, so a retry after a
    // partial failure only re-sends the rest.
    private suspend fun uploadLocalImages() {
        val pending = images.filter { !it.pendingRemoval && it.mxcUrl == null && it.local != null }
        val avatar = packAvatarDraft
        val total = pending.size + if (avatar != null) 1 else 0
        if (total == 0) return
        val dialog = ImagePackProgressDialog(requireContext(), CommonStrings.image_pack_uploading_title) { saveJob?.cancel() }
        try {
            var done = 0
            dialog.update(getString(CommonStrings.image_pack_uploading, done, total), done, total)
            val onDone = {
                done++
                dialog.update(getString(CommonStrings.image_pack_uploading, done, total), done, total)
            }
            var firstFailure: Throwable? = null
            val semaphore = Semaphore(UPLOAD_PARALLELISM)
            coroutineScope {
                pending.forEach { image ->
                    launch {
                        semaphore.withPermit {
                            val local = image.local ?: return@withPermit
                            try {
                                val uploaded = archiver.uploadImageFile(
                                        local.file,
                                        local.mimeType,
                                        image.shortcode,
                                        body = image.body,
                                        compress = local.compress,
                                        knownSize = (local.width to local.height).takeIf { local.width > 0 && local.height > 0 },
                                )
                                image.mxcUrl = uploaded.url
                                uploaded.info?.let { image.info = it }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                if (firstFailure == null) firstFailure = failure
                            }
                            onDone()
                        }
                    }
                }
            }
            firstFailure?.let { throw it }
            if (avatar != null) {
                // A zip's icon can be one of its images, which then needs no second upload.
                packAvatarUrl = images.firstOrNull { it.local?.path == avatar.path }?.mxcUrl
                        ?: archiver.uploadImageFile(avatar.file, avatar.mimeType, "pack_icon", body = null, compress = avatar.compress).url
                packAvatarDraft = null
                onDone()
            }
        } finally {
            dialog.dismiss()
        }
    }

    private fun telegramStickersToSave(): Map<String, String> {
        val stickers = HashMap(editViewModel.telegramStickers)
        images.forEach { image ->
            val uniqueId = image.local?.telegramUniqueId ?: return@forEach
            val url = image.mxcUrl ?: return@forEach
            if (!image.pendingRemoval) stickers[uniqueId] = url
        }
        return stickers
    }

    private fun onSaved(saved: ImagePackContent) {
        packExists = true
        initialContent = saved
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.image_pack_edit_title)
        images.removeAll { it.pendingRemoval }
        images.forEach { it.added = false }
        renderAvatar()
        refresh()
    }

    private fun buildContent(): ImagePackContent {
        val imageMap = images.filter { !it.pendingRemoval && it.shortcode.isNotBlank() }.mapNotNull { editable ->
            val url = editable.mxcUrl ?: return@mapNotNull null
            editable.shortcode to ImagePackImage(
                    url = url,
                    body = editable.body,
                    // Don't persist fabricated zero dimensions.
                    info = editable.info?.takeIf { it.width > 0 && it.height > 0 },
                    usage = usageList(editable),
            )
        }.toMap()
        return ImagePackContent(
                // The server hands the map back with its keys sorted, so the on-screen order must be explicit.
                images = imageMap.withSequentialOrder(),
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
        if (packAvatarUrl == null && packAvatarDraft == null) renderAvatar()
    }

    // Something added in this session just goes; anything already in the pack is marked and only goes on Apply.
    override fun onDeleteImage(image: EditableImage) {
        if (image.added) {
            images.remove(image)
        } else {
            image.pendingRemoval = !image.pendingRemoval
        }
        refresh()
        requireActivity().invalidateOptionsMenu()
    }

    override fun onAddImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*") + ZIP_MIME_TYPES)
        } else {
            // No multi-type filter before KitKat; anything that isn't an image or zip fails to load and is reported.
            intent.setType("*/*")
        }
        pickImagesLauncher.launch(intent)
    }

    override fun onImageEdited() {
        requireActivity().invalidateOptionsMenu()
    }

    private fun isDirty(): Boolean {
        if (!pageArgs.canEdit) return false
        // A pure reorder changes the images' MSC4389 order values, so content equality covers it.
        return packAvatarDraft != null ||
                images.any { it.pendingRemoval || it.mxcUrl == null } ||
                buildContent() != initialContent
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
        val picking = pickJob?.isActive == true
        if (!isDirty() && !picking) return false
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_unsaved_title)
                .setMessage(CommonStrings.image_pack_unsaved_message)
                .apply {
                    // "Apply" only when there are saveable changes (named pack, nothing still being read in).
                    if (canApply()) {
                        setPositiveButton(CommonStrings.image_pack_apply) { _, _ -> save(finishAfter = true) }
                    }
                }
                .setNegativeButton(CommonStrings.image_pack_unsaved_discard) { _, _ ->
                    pickJob?.cancel()
                    activity?.finish()
                }
                .setNeutralButton(CommonStrings.action_cancel, null)
                .show()
        return true
    }

    companion object {
        private const val UPLOAD_PARALLELISM = 10
        private val ZIP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed")
    }
}
