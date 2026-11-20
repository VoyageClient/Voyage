/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

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
data class GradientEditArgs(
        // null when creating a new gradient.
        val label: String?,
) : Parcelable

@AndroidEntryPoint
class GradientEditActivity : SimpleFragmentActivity() {

    override fun initUiAndData() {
        super.initUiAndData()
        val args = intent.getParcelableExtraCompat<GradientEditArgs>(EXTRA_ARGS)
        supportActionBar?.title = getString(if (args?.label == null) CommonStrings.gradient_new else CommonStrings.gradient_edit)
        if (isFirstCreation()) {
            addFragment(views.container, GradientEditFragment::class.java, args)
        }
    }

    companion object {
        private const val EXTRA_ARGS = "EXTRA_ARGS"

        fun newIntent(context: Context, label: String?): Intent {
            return Intent(context, GradientEditActivity::class.java).apply {
                putExtra(EXTRA_ARGS, GradientEditArgs(label))
            }
        }
    }
}
