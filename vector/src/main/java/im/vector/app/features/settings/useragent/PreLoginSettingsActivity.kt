/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.SimpleFragmentActivity
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 * Standalone host for the per-account settings that can be set before signing in (stealth mode, the
 * User-Agent spoof). Unlike VectorSettingsActivity it injects no Session, so it works before
 * authentication. UA edits target the PENDING scope (never the current account, if any);
 * [UserAgentSettings.migratePendingInto] moves the choice into the account on sign-in.
 */
@AndroidEntryPoint
class PreLoginSettingsActivity : SimpleFragmentActivity() {

    @Inject lateinit var userAgentSettings: UserAgentSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After super: Hilt injects fields in super.onCreate, and this must be set before the fragment
        // (added below / re-attached) reads it.
        userAgentSettings.editScopeOverride = UserAgentSettings.PENDING
        supportActionBar?.setTitle(CommonStrings.settings_prelogin_title)
        if (isFirstCreation()) {
            addFragment(views.container, PreLoginSettingsFragment::class.java)
        }
    }

    override fun onDestroy() {
        if (isFinishing) userAgentSettings.editScopeOverride = null
        super.onDestroy()
    }

    companion object {
        fun newIntent(context: Context) = Intent(context, PreLoginSettingsActivity::class.java)
    }
}
