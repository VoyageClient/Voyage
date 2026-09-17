/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.troubleshoot

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.getApplicationLabel
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 * The distributor holds the connection every push travels down, so a system that is allowed to doze
 * it makes notifications late or lost no matter how healthy our own registration is.
 */
class TestDistributorBatteryOptimization @Inject constructor(
        private val context: FragmentActivity,
        private val stringProvider: StringProvider,
        private val unifiedPushHelper: UnifiedPushHelper,
) : TroubleshootTest(CommonStrings.settings_troubleshoot_test_distributor_battery_title) {

    override fun perform(testParameters: TestParameters) {
        val distributor = unifiedPushHelper.getCurrentDistributor()
        if (distributor.isEmpty() || distributor == context.packageName) {
            status = TestStatus.SUCCESS
            return
        }
        val distributorName = context.getApplicationLabel(distributor)
        if (context.isIgnoringBatteryOptimizations(distributor)) {
            description = stringProvider.getString(CommonStrings.settings_troubleshoot_test_distributor_battery_success, distributorName)
            status = TestStatus.SUCCESS
        } else {
            description = stringProvider.getString(CommonStrings.settings_troubleshoot_test_distributor_battery_failed, distributorName)
            // Only the distributor itself can ask for its own exemption, so there is no quick fix here.
            status = TestStatus.FAILED
        }
    }

    private fun Context.isIgnoringBatteryOptimizations(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) == true
    }
}
