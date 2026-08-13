/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.vpn

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.extensions.startSyncing
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.vpn.VpnGate
import im.vector.app.core.vpn.VpnGateExemptActivity
import im.vector.app.databinding.ActivityVpnWarningBinding
import javax.inject.Inject
import kotlin.system.exitProcess

/**
 * Full-screen blocking warning shown while the VPN gate is closed. No network happens until the
 * user taps Proceed (which acknowledges the VPN-off state and reopens the gate).
 */
@AndroidEntryPoint
class VpnWarningActivity : VectorBaseActivity<ActivityVpnWarningBinding>(), VpnGateExemptActivity {

    @Inject lateinit var vpnGate: VpnGate
    @Inject lateinit var activeSessionDataSource: ActiveSessionDataSource

    override fun getBinding() = ActivityVpnWarningBinding.inflate(layoutInflater)

    override val rootView: View
        get() = views.vpnWarningRoot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        views.vpnWarningProceed.setOnClickListener { proceed() }
        views.vpnWarningExit.setOnClickListener { exit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exit()
            }
        })

        // The gate can reopen without user action (VPN back on): dismiss the warning then
        vpnGateState.liveClosed.observe(this) { closed ->
            if (!closed) finish()
        }
    }

    private fun proceed() {
        vpnGate.acknowledge()
        activeSessionDataSource.currentValue?.orNull()?.startSyncing(applicationContext)
        finish()
    }

    private fun exit() {
        ActivityCompat.finishAffinity(this)
        exitProcess(0)
    }

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, VpnWarningActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        }
    }
}
