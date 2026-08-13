/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.vpn

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.session.AccountInfoCache
import im.vector.app.core.vpn.VpnGate
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

/**
 * Per-account toggles for the VPN launch warning: an account switched OFF is excluded — no launch
 * warning while it is active, and no confirmation when switching to it.
 */
@AndroidEntryPoint
class VectorSettingsVpnExclusionsFragment : VectorSettingsBaseFragment() {

    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var accountInfoCache: AccountInfoCache
    @Inject lateinit var vpnGate: VpnGate
    @Inject lateinit var avatarRenderer: AvatarRenderer

    override var titleRes = CommonStrings.settings_vpn_exclusions_title
    override val preferenceXmlRes = R.xml.vector_settings_vpn_exclusions

    override fun bindPref() {
        // bindPref() runs in onCreatePreferences, before the view exists: use the fragment scope
        lifecycleScope.launch {
            val accounts = accountInfoCache.listAccounts()
            if (!isAdded) return@launch
            preferenceScreen.removeAll()
            val excluded = vectorPreferences.getVpnExcludedSessionIds()
            accounts.forEach { account ->
                val pref = VpnExclusionAccountPreference(requireContext())
                pref.isPersistent = false
                pref.title = (account.displayName?.takeIf { it.isNotBlank() } ?: account.userId).prepareForDisplay()
                pref.summary = account.homeServerHost?.let { "${account.userId} — $it" } ?: account.userId
                // Same source as the account switcher: the cached binary written while this
                // account was active, or a locally-generated placeholder — never the network
                val matrixItem = MatrixItem.UserItem(account.userId, account.displayName, null)
                val cached = accountInfoCache.avatarFileFor(account.sessionId).takeIf { it.exists() && it.length() > 0 }
                pref.bindAvatar = { imageView ->
                    avatarRenderer.render(matrixItem, cached?.let { Uri.fromFile(it) }, imageView)
                }
                pref.isChecked = account.sessionId !in excluded
                pref.setOnPreferenceChangeListener { _, newValue ->
                    vectorPreferences.setVpnSessionExcluded(account.sessionId, excluded = newValue == false)
                    vpnGate.recompute()
                    true
                }
                preferenceScreen.addPreference(pref)
            }
        }
    }
}
