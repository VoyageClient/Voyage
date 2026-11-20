/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.SimpleFragmentActivity
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class GradientListActivity : SimpleFragmentActivity() {

    override fun initUiAndData() {
        super.initUiAndData()
        supportActionBar?.title = getString(CommonStrings.gradient_settings_title)
        if (isFirstCreation()) {
            addFragment(views.container, GradientListFragment::class.java)
        }
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, GradientListActivity::class.java)
    }
}
