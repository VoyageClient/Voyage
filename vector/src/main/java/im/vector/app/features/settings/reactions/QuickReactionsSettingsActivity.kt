/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.reactions

import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.SimpleFragmentActivity
import im.vector.lib.strings.CommonStrings

/** Hosts the reorderable quick-reactions editor (replaces the old space-separated text preference). */
@AndroidEntryPoint
class QuickReactionsSettingsActivity : SimpleFragmentActivity() {

    override fun initUiAndData() {
        super.initUiAndData()
        supportActionBar?.setTitle(CommonStrings.settings_quick_reactions)
        if (isFirstCreation()) {
            addFragment(views.container, QuickReactionsSettingsFragment::class.java)
        }
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, QuickReactionsSettingsActivity::class.java)
    }
}
