/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dialogs

import android.app.Activity
import android.net.Uri
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper.Listener
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.PERMISSIONS_FOR_TAKING_PHOTO
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.onPermissionDeniedDialog
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.features.attachments.editor.image.ImageEditorActivity
import im.vector.app.features.attachments.editor.isRestoreOriginal
import im.vector.app.features.attachments.editor.restoreOriginalUri
import im.vector.lib.multipicker.MultiPicker
import im.vector.lib.multipicker.entity.MultiPickerImageType
import im.vector.lib.strings.CommonStrings

/**
 * Use to let the user choose between Camera (with permission handling) and Gallery (with single image selection),
 * then edit the image
 * [Listener.onImageReady] will be called with an uri of the edited image stored in the cache of the application,
 * or of the picked image itself when nothing was cropped out of it.
 */
class GalleryOrCameraDialogHelper(
        // must implement GalleryOrCameraDialogHelper.Listener, unless an explicit listener is given
        private val fragment: Fragment,
        private val colorProvider: ColorProvider,
        private val aspect: Aspect = Aspect.SQUARE,
        overrideListener: Listener? = null,
) {
    enum class Aspect {
        SQUARE,
        BANNER,
    }

    interface Listener {
        fun onImageReady(uri: Uri?)
        fun onImageDeleted() = Unit
        fun onImageReset() = Unit
    }

    private val activity
        get() = fragment.requireActivity()

    private val listener = overrideListener
            ?: fragment as? Listener
            ?: error("Fragment must implement GalleryOrCameraDialogHelper.Listener")

    private val takePhotoPermissionActivityResultLauncher = fragment.registerForPermissionsResult { allGranted, deniedPermanently ->
        if (allGranted) {
            doOpenCamera()
        } else if (deniedPermanently) {
            activity.onPermissionDeniedDialog(CommonStrings.denied_permission_camera)
        }
    }

    private val takePhotoActivityResultLauncher = fragment.registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            avatarCameraUri?.let { uri ->
                MultiPicker.get(MultiPicker.CAMERA)
                        .getTakenPhoto(activity, uri)
                        ?.let { startImageEditor(it) }
            }
        }
    }

    private val pickImageActivityResultLauncher = fragment.registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            MultiPicker
                    .get(MultiPicker.IMAGE)
                    .getSelectedFiles(activity, activityResult.data)
                    .firstOrNull()
                    ?.let { startImageEditor(it) }
        }
    }

    private val imageEditorActivityResultLauncher = fragment.registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode != Activity.RESULT_OK) return@registerStartForActivityResult
        // Nothing to crop out: the picked image already has the right shape, so it is used as it is.
        if (activityResult.data?.isRestoreOriginal() == true) {
            listener.onImageReady(activityResult.data?.restoreOriginalUri())
        } else {
            listener.onImageReady(activityResult.data?.let { ImageEditorActivity.getOutput(it)?.uri })
        }
    }

    private fun startImageEditor(image: MultiPickerImageType) {
        imageEditorActivityResultLauncher.launch(
                ImageEditorActivity.newIntent(
                        activity,
                        image.contentUri,
                        image.displayName,
                        image.mimeType,
                        edits = null,
                        aspectRatio = if (aspect == Aspect.BANNER) BANNER_ASPECT_RATIO else 1f
                )
        )
    }

    private enum class Type {
        Camera,
        Gallery
    }

    fun show(
            withDeleteOption: Boolean = false,
            @StringRes deleteActionTitle: Int = CommonStrings.action_delete,
            withResetOption: Boolean = false,
            @StringRes resetActionTitle: Int = CommonStrings.action_reset,
    ) {
        val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(CommonStrings.attachment_type_dialog_title)
                .setItems(
                        arrayOf(
                                fragment.getString(CommonStrings.attachment_type_camera),
                                fragment.getString(CommonStrings.attachment_type_gallery)
                        )
                ) { _, which ->
                    onAvatarTypeSelected(if (which == 0) Type.Camera else Type.Gallery)
                }
                .setPositiveButton(CommonStrings.action_cancel, null)
                .apply {
                    // Neutral is the leftmost button, so delete lands left of reset when both show.
                    when {
                        withDeleteOption -> {
                            setNeutralButton(deleteActionTitle) { _, _ -> listener.onImageDeleted() }
                            if (withResetOption) {
                                setNegativeButton(resetActionTitle) { _, _ -> listener.onImageReset() }
                            }
                        }
                        withResetOption -> setNeutralButton(resetActionTitle) { _, _ -> listener.onImageReset() }
                    }
                }
                .show()
        if (withDeleteOption) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setTextColor(colorProvider.getColorFromAttribute(com.google.android.material.R.attr.colorError))
        }
    }

    private fun onAvatarTypeSelected(type: Type) {
        when (type) {
            Type.Camera ->
                if (checkPermissions(PERMISSIONS_FOR_TAKING_PHOTO, activity, takePhotoPermissionActivityResultLauncher)) {
                    doOpenCamera()
                }
            Type.Gallery ->
                MultiPicker.get(MultiPicker.IMAGE).single().startWith(pickImageActivityResultLauncher)
        }
    }

    private var avatarCameraUri: Uri? = null
    private fun doOpenCamera() {
        avatarCameraUri = MultiPicker.get(MultiPicker.CAMERA).startWithExpectingFile(activity, takePhotoActivityResultLauncher)
    }

    companion object {
        private const val BANNER_ASPECT_RATIO = 2.8f
    }
}
