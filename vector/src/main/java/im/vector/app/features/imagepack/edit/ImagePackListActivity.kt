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
data class ImagePackListArgs(
        // null = account-only context (opened from settings); non-null = room context.
        val roomId: String?,
) : Parcelable

/**
 * Lists all image packs available from a room (or just the personal pack, from settings), with controls
 * to enable room packs globally, edit packs you own, and create new ones.
 */
@AndroidEntryPoint
class ImagePackListActivity : SimpleFragmentActivity() {

    override fun initUiAndData() {
        super.initUiAndData()
        supportActionBar?.title = getString(CommonStrings.image_pack_settings_title)
        if (isFirstCreation()) {
            addFragment(
                    views.container,
                    ImagePackListFragment::class.java,
                    intent.getParcelableExtraCompat<ImagePackListArgs>(EXTRA_ARGS),
            )
        }
    }

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"

        fun newIntent(context: Context, roomId: String?): Intent {
            return Intent(context, ImagePackListActivity::class.java).apply {
                putExtra(EXTRA_ARGS, ImagePackListArgs(roomId))
            }
        }
    }
}
