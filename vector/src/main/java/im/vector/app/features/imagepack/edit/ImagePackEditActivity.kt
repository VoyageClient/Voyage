/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.SimpleFragmentActivity
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.parcelize.Parcelize

@Parcelize
data class ImagePackEditArgs(
        // null roomId = the user's personal account pack.
        val roomId: String?,
        val stateKey: String,
        val canEdit: Boolean,
        // Known from the list, so the name field can populate instantly while the images load.
        val displayName: String? = null,
        // Already-uploaded images from a re-import, appended (highlighted, unsaved) on first load.
        val importedAdditions: ImportedAdditions? = null,
) : Parcelable

@Parcelize
data class ImportedImage(
        val shortcode: String,
        val mxcUrl: String,
        val body: String?,
        val mimeType: String?,
        val width: Int,
        val height: Int,
        val size: Long,
        val telegramIndex: Int,
) : Parcelable

@Parcelize
data class ImportedAdditions(
        val images: List<ImportedImage>,
        val telegramSet: String,
        // file_unique_id → mxc url, for every sticker of the set now in the pack.
        val telegramStickers: Map<String, String>,
        // The set's file_unique_ids in Telegram order.
        val telegramOrder: List<String>,
) : Parcelable

/**
 * Container for [ImagePackEditFragment]: edits (or views) the personal pack or a room's image pack.
 */
@AndroidEntryPoint
class ImagePackEditActivity : SimpleFragmentActivity() {

    override fun initUiAndData() {
        super.initUiAndData()
        supportActionBar?.title = getString(CommonStrings.image_pack_edit_title)
        if (isFirstCreation()) {
            addFragment(
                    views.container,
                    ImagePackEditFragment::class.java,
                    intent.getParcelableExtraCompat<ImagePackEditArgs>(EXTRA_ARGS),
            )
        }
    }

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"

        fun newIntent(
                context: Context,
                roomId: String?,
                stateKey: String = "",
                canEdit: Boolean = true,
                displayName: String? = null,
                importedAdditions: ImportedAdditions? = null,
        ): Intent {
            return Intent(context, ImagePackEditActivity::class.java).apply {
                putExtra(EXTRA_ARGS, ImagePackEditArgs(roomId, stateKey, canEdit, displayName, importedAdditions))
            }
        }
    }
}
