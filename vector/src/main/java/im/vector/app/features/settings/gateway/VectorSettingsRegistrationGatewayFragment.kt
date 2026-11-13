/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gateway

import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorPreference
import im.vector.app.features.onboarding.gateway.GatewayService
import im.vector.app.features.onboarding.gateway.RegistrationGatewayClient
import im.vector.app.features.onboarding.gateway.RegistrationGatewayErrorFormatter
import im.vector.app.features.onboarding.gateway.RegistrationGatewaySettings
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shown both before sign-in and from Advanced settings; it holds no session state. */
@AndroidEntryPoint
class VectorSettingsRegistrationGatewayFragment : VectorSettingsBaseFragment() {

    @Inject lateinit var gatewayClient: RegistrationGatewayClient
    @Inject lateinit var gatewayErrorFormatter: RegistrationGatewayErrorFormatter

    override var titleRes = CommonStrings.settings_registration_gateway_title
    override val preferenceXmlRes = R.xml.vector_settings_registration_gateway

    override fun bindPref() {
        findPreference<EditTextPreference>(RegistrationGatewaySettings.KEY_URL)?.let { pref ->
            pref.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.hint = getString(CommonStrings.settings_registration_gateway_url_hint)
            }
            bindSummary(pref) { it?.trim().orEmpty().ifEmpty { getString(CommonStrings.settings_registration_gateway_not_set) } }
        }
        findPreference<EditTextPreference>(RegistrationGatewaySettings.KEY_TOKEN)?.let { pref ->
            pref.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            bindSummary(pref) { if (it.isNullOrBlank()) getString(CommonStrings.settings_registration_gateway_not_set) else "••••••••" }
        }
        findPreference<VectorPreference>(KEY_CHECK)?.setOnPreferenceClickListener {
            checkGateway()
            true
        }
    }

    private fun bindSummary(pref: EditTextPreference, render: (String?) -> String) {
        pref.summary = render(pref.text)
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            pref.summary = render(newValue as? String)
            true
        }
    }

    private fun checkGateway() {
        val pref = findPreference<VectorPreference>(KEY_CHECK) ?: return
        if (!pref.isEnabled) return
        pref.isEnabled = false
        pref.summary = getString(CommonStrings.settings_registration_gateway_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val message = try {
                val services = gatewayClient.services()
                if (services.isEmpty()) getString(CommonStrings.settings_registration_gateway_no_services) else services.joinToString("\n\n") { describe(it) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                gatewayErrorFormatter.format(failure)
            }
            pref.isEnabled = true
            pref.summary = getString(CommonStrings.settings_registration_gateway_check_summary)
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CommonStrings.settings_registration_gateway_title)
                    .setMessage(message)
                    .setPositiveButton(CommonStrings.ok, null)
                    .show()
        }
    }

    private fun describe(service: GatewayService): String {
        val readiness = when {
            service.requiresEmail -> getString(CommonStrings.settings_registration_gateway_service_needs_email)
            else -> describeReadiness(service)
        }
        return buildString {
            append(service.name).append(": ").append(readiness)
            if (!service.requiresEmail && !service.isReady && !service.readinessReason.isNullOrEmpty()) append('\n').append(service.readinessReason)
            service.homeservers.forEach { append('\n').append("• ").append(it) }
        }
    }

    private fun describeReadiness(service: GatewayService): String {
        return when (service.readiness) {
            GatewayService.READY -> getString(CommonStrings.settings_registration_gateway_service_ready)
            "degraded" -> getString(CommonStrings.settings_registration_gateway_service_degraded)
            "preparing" -> getString(CommonStrings.settings_registration_gateway_service_preparing)
            else -> getString(CommonStrings.settings_registration_gateway_service_unavailable)
        }
    }

    companion object {
        private const val KEY_CHECK = "SETTINGS_REGISTRATION_GATEWAY_CHECK"
    }
}
