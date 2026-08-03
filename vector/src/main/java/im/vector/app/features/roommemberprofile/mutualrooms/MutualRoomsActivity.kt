/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile.mutualrooms

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
data class MutualRoomsArgs(
        val userId: String,
) : Parcelable

@AndroidEntryPoint
class MutualRoomsActivity : SimpleFragmentActivity() {

    override fun initUiAndData() {
        super.initUiAndData()
        supportActionBar?.title = getString(CommonStrings.room_member_profile_mutual_rooms)
        if (isFirstCreation()) {
            addFragment(
                    views.container,
                    MutualRoomsFragment::class.java,
                    intent.getParcelableExtraCompat<MutualRoomsArgs>(EXTRA_ARGS),
            )
        }
    }

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"

        fun newIntent(context: Context, userId: String): Intent {
            return Intent(context, MutualRoomsActivity::class.java).apply {
                putExtra(EXTRA_ARGS, MutualRoomsArgs(userId))
            }
        }
    }
}
