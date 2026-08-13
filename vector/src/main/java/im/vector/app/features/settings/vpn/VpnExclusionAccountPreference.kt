/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.vpn

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import im.vector.app.R
import im.vector.app.core.preference.VectorSwitchPreference

/** Switch preference rendered like an account-switcher row (avatar + name + user id). */
class VpnExclusionAccountPreference(context: Context) : VectorSwitchPreference(context) {

    var bindAvatar: ((ImageView) -> Unit)? = null

    init {
        layoutResource = R.layout.item_vpn_exclusion_account
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Super forces the title multiline; the switcher row keeps it to one line
        (holder.findViewById(android.R.id.title) as? TextView)?.isSingleLine = true
        (holder.findViewById(R.id.vpnExclusionItemAvatar) as? ImageView)?.let { bindAvatar?.invoke(it) }
    }
}
