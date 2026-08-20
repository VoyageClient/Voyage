/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategoryWithAction
import im.vector.app.features.settings.VectorSettingsBaseFragment
import im.vector.app.features.settings.useragent.data.UaDataRepository
import im.vector.app.features.settings.useragent.data.UaOption
import im.vector.app.features.settings.useragent.data.UaProviderIds
import im.vector.app.features.settings.useragent.data.mostPopularValue
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsUserAgentFragment : VectorSettingsBaseFragment(), UaVersionSortHost {

    @Inject lateinit var userAgentSettings: UserAgentSettings
    @Inject lateinit var spoofBuilder: UserAgentSpoofBuilder
    @Inject lateinit var dataRepository: UaDataRepository

    override var titleRes = CommonStrings.settings_user_agent_title
    override val preferenceXmlRes = R.xml.vector_settings_user_agent

    override var sortVersionsByLatest: Boolean
        get() = userAgentSettings.sortVersionsByLatest
        set(value) { userAgentSettings.sortVersionsByLatest = value }

    private val clientPref get() = findPreference<ListPreference>("SETTINGS_UA_CLIENT")
    private val autoUpgradePref get() = findPreference<androidx.preference.SwitchPreference>("SETTINGS_UA_AUTO_UPGRADE")
    private val previewPref get() = findPreference<VectorPreference>("SETTINGS_UA_PREVIEW")
    private val appliesToPref get() = findPreference<VectorPreference>("SETTINGS_UA_APPLIES_TO")
    private val categoryPref get() = findPreference<VectorPreferenceCategoryWithAction>("SETTINGS_UA_CATEGORY")

    override fun bindPref() {
        userAgentSettings.migrateClearLegacyValues()
        bindClientSelector()
        bindFieldListeners()
        bindUpdateAction()
        bindAutoUpgrade()
        bindAppliesTo()
        // Set field visibility from cache first (so None hides everything), then download what's missing.
        applyClient(userAgentSettings.selectedClient)
        val missing = allUnits().filterNot { it.cached() }
        if (missing.isNotEmpty()) runDownload(missing, isUpdate = false)
    }

    private fun bindAppliesTo() {
        appliesToPref?.setOnPreferenceClickListener {
            showAppliesToDialog(userAgentSettings.selectedClient)
            true
        }
    }

    private fun showAppliesToDialog(client: UaSpoofClient) {
        val surfaces = UaSurface.entries
        val active = userAgentSettings.surfaces(client)
        val checked = BooleanArray(surfaces.size) { surfaces[it] in active }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_ua_applies_to_title)
                .setMultiChoiceItems(surfaces.map { getString(it.labelRes) }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val selected = surfaces.filterIndexed { i, _ -> checked[i] }.toSet()
                            // API+media is the baseline; never let the user end up spoofing nothing.
                            .ifEmpty { setOf(UaSurface.API_MEDIA) }
                    userAgentSettings.setSurfaces(client, selected)
                    updateAppliesToSummary(client)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun updateAppliesToSummary(client: UaSpoofClient) {
        appliesToPref?.summary = userAgentSettings.surfaces(client).joinToString { getString(it.labelRes) }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is UaVersionListPreference) {
            if (parentFragmentManager.findFragmentByTag(VERSION_DIALOG_TAG) != null) return
            UaVersionListPreferenceDialogFragment.newInstance(preference.key).apply {
                @Suppress("DEPRECATION")
                setTargetFragment(this@VectorSettingsUserAgentFragment, 0)
            }.show(parentFragmentManager, VERSION_DIALOG_TAG)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    private fun bindClientSelector() {
        clientPref?.apply {
            val clients = UaSpoofClient.entries
            entries = clients.map { getString(it.labelRes) }.toTypedArray()
            entryValues = clients.map { it.id }.toTypedArray()
            value = userAgentSettings.selectedClient.id
            summary = getString(userAgentSettings.selectedClient.labelRes)
            setOnPreferenceChangeListener { _, newValue ->
                val client = UaSpoofClient.fromId(newValue as String)
                userAgentSettings.selectedClient = client
                summary = getString(client.labelRes)
                applyClient(client)
                true
            }
        }
    }

    private fun bindFieldListeners() {
        UaField.entries.forEach { field ->
            val pref = findPreference<Preference>(field.prefKey) ?: return@forEach
            pref.setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).let { if (pref is EditTextPreference) it.trim() else it }
                userAgentSettings.setValue(userAgentSettings.selectedClient, field, value)
                pref.summary = if (pref is ListPreference) labelFor(pref, value) else value
                val current = userAgentSettings.selectedClient
                when (field) {
                    // The browser OS drives whether the macOS version picker applies.
                    UaField.OS -> applyClient(current)
                    // A new manufacturer invalidates the chosen model; reset it, then re-list model/build.
                    UaField.DEVICE_MANUFACTURER -> {
                        userAgentSettings.setValue(current, UaField.DEVICE_MODEL, null)
                        populateModelField(current)
                        populateBuildIdField(current)
                        onFieldEdited(current)
                    }
                    // Device drives which build ids are possible.
                    UaField.DEVICE_MODEL -> {
                        populateBuildIdField(current)
                        onFieldEdited(current)
                    }
                    // Android version drives the build id.
                    UaField.ANDROID_VERSION -> {
                        populateBuildIdField(current)
                        onFieldEdited(current)
                    }
                    // iOS device restricts which iOS versions are possible.
                    UaField.IOS_DEVICE -> {
                        findPreference<UaVersionListPreference>(UaField.IOS_VERSION.prefKey)
                                ?.let { applyFetchedOptions(it, current, UaField.IOS_VERSION, spoofBuilder.iosVersionOptions(current)) }
                        onFieldEdited(current)
                    }
                    // The rust-sdk sha follows the app version.
                    UaField.APP_VERSION -> {
                        resolveSdkShaFor(current)
                        onFieldEdited(current)
                    }
                    else -> onFieldEdited(current)
                }
                true
            }
        }
    }

    private fun bindUpdateAction() {
        categoryPref?.actionClickListener = { updateToLatest() }
        categoryPref?.resetClickListener = { resetCurrentClient() }
        categoryPref?.upgradeClickListener = { upgradeSoftware() }
    }

    private fun bindAutoUpgrade() {
        // Per-client: the switch reflects/sets only the selected client's auto-upgrade.
        autoUpgradePref?.setOnPreferenceChangeListener { _, newValue ->
            userAgentSettings.setAutoUpgradeFor(userAgentSettings.selectedClient, newValue as Boolean)
            applyClient(userAgentSettings.selectedClient)
            true
        }
    }

    /** True when some software field has a newer cached value than what's resolved — enables the upgrade icon. */
    private fun hasUpgradeAvailable(client: UaSpoofClient): Boolean =
            client.fields.any { field ->
                field in SOFTWARE_FIELDS &&
                        spoofBuilder.newestValueFor(client, field)
                                .let { it.isNotEmpty() && it != spoofBuilder.resolvedValue(client, field) }
            }

    /** The "upgrade" icon: bump every software version field (not hardware) to its newest value. */
    private fun upgradeSoftware() {
        val client = userAgentSettings.selectedClient
        client.fields.filter { it in SOFTWARE_FIELDS }.forEach { field ->
            val newest = spoofBuilder.newestValueFor(client, field).takeIf { it.isNotEmpty() } ?: return@forEach
            // Only store a real change; storing a value equal to the current one would falsely mark the
            // client as modified (lighting up reset) even though the upgrade found nothing newer.
            if (newest != spoofBuilder.resolvedValue(client, field)) {
                userAgentSettings.setValue(client, field, newest)
            }
        }
        resolveSdkShaFor(client)
        applyClient(client)
    }

    /** Rebuild the visible fields, their entries, and their values for [client]. */
    private fun applyClient(client: UaSpoofClient) {
        val osValue = spoofBuilder.resolvedValue(client, UaField.OS)
        val autoUpgrade = userAgentSettings.autoUpgradeFor(client)
        UaField.entries.forEach { field ->
            val pref = findPreference<Preference>(field.prefKey) ?: return@forEach
            val providerId = client.providerIdFor(field, osValue)
            // OS_VERSION only means anything for a source-backed OS (macOS); the Windows token is fixed.
            val visible = field in client.fields && (field != UaField.OS_VERSION || providerId != null)
            pref.isVisible = visible
            // Software fields are driven by auto-upgrade when it's on, so lock them then.
            pref.isEnabled = !(autoUpgrade && field in SOFTWARE_FIELDS)
            if (!visible) return@forEach
            when (pref) {
                is UaVersionListPreference -> when (field) {
                    UaField.BUILD_ID -> populateBuildIdField(client)
                    UaField.ELECTRON_VERSION -> populateElectronField(client)
                    UaField.DEVICE_MANUFACTURER -> populateManufacturerField(client)
                    UaField.DEVICE_MODEL -> populateModelField(client)
                    UaField.IOS_VERSION -> applyFetchedOptions(pref, client, field, spoofBuilder.iosVersionOptions(client))
                    else -> populateVersionField(client, field, providerId)
                }
                is ListPreference -> {
                    populateStaticListEntries(field, client, pref)
                    val value = spoofBuilder.resolvedValue(client, field)
                    pref.value = value
                    pref.summary = pref.entry ?: value
                }
                is EditTextPreference -> {
                    val value = spoofBuilder.resolvedValue(client, field)
                    pref.text = value
                    pref.summary = value
                }
            }
        }
        previewPref?.isVisible = client != UaSpoofClient.NONE
        appliesToPref?.isVisible = client != UaSpoofClient.NONE
        val hasSoftware = client.fields.any { it in SOFTWARE_FIELDS }
        autoUpgradePref?.isVisible = client != UaSpoofClient.NONE && hasSoftware
        autoUpgradePref?.isChecked = autoUpgrade
        updateAppliesToSummary(client)
        categoryPref?.isActionVisible = hasFetchableFields(client, osValue)
        categoryPref?.isResetVisible = client != UaSpoofClient.NONE
        categoryPref?.isUpgradeVisible = client != UaSpoofClient.NONE && hasSoftware && !autoUpgrade
        categoryPref?.isUpgradeEnabled = hasUpgradeAvailable(client)
        refreshResetState(client)
        resolveSdkShaFor(client)
        refreshPreview()
    }

    // The populate methods only read cache; the download gate is the single fetch path.
    private fun populateVersionField(client: UaSpoofClient, field: UaField, providerId: String?) {
        val pref = findPreference<UaVersionListPreference>(field.prefKey) ?: return
        applyFetchedOptions(pref, client, field, providerId?.let { dataRepository.cached(it) }.orEmpty())
    }

    private fun populateBuildIdField(client: UaSpoofClient) {
        if (UaField.BUILD_ID !in client.fields) return
        val pref = findPreference<UaVersionListPreference>(UaField.BUILD_ID.prefKey) ?: return
        val device = spoofBuilder.resolvedValue(client, UaField.DEVICE_MODEL)
        applyFetchedOptions(pref, client, UaField.BUILD_ID, dataRepository.cachedBuildIds(device, androidMajorOf(client)))
    }

    private fun androidMajorOf(client: UaSpoofClient): Int =
            spoofBuilder.resolvedValue(client, UaField.ANDROID_VERSION).substringBefore(".").toIntOrNull() ?: -1

    /** Resolve and cache the rust-sdk sha for the current app version (derived, not user-editable). */
    private fun resolveSdkShaFor(client: UaSpoofClient) {
        val appVersion = spoofBuilder.resolvedValue(client, UaField.APP_VERSION)
        if (appVersion.isEmpty() || userAgentSettings.sdkShaFor(appVersion) != null) return
        val chain = sdkShaChainFor(client, appVersion) ?: return
        lifecycleScope.launch {
            val sha = dataRepository.resolveSdkSha(chain.appRepo, chain.tag, chain.componentsRepo, chain.releasePrefix) ?: return@launch
            if (!isAdded || userAgentSettings.selectedClient != client) return@launch
            userAgentSettings.setSdkShaFor(appVersion, sha)
            refreshPreview()
        }
    }

    private fun deviceCache() = dataRepository.cached(UaProviderIds.DEVICE_MODEL)

    private fun populateManufacturerField(client: UaSpoofClient) {
        val pref = findPreference<UaVersionListPreference>(UaField.DEVICE_MANUFACTURER.prefKey) ?: return
        val manufacturers = deviceCache().map { it.value.substringBefore(" ") }.distinct()
        applyFetchedOptions(pref, client, UaField.DEVICE_MANUFACTURER, manufacturers.map { UaOption(it, it, null) })
    }

    private fun populateModelField(client: UaSpoofClient) {
        val pref = findPreference<UaVersionListPreference>(UaField.DEVICE_MODEL.prefKey) ?: return
        val manufacturer = spoofBuilder.resolvedValue(client, UaField.DEVICE_MANUFACTURER)
        // Value is the bare Build.MODEL; label keeps the marketing name so search works ("Galaxy S24").
        val models = deviceCache()
                .filter { it.value.substringBefore(" ") == manufacturer }
                .map { UaOption(it.value.substringAfter(" "), it.label, null) }
        applyFetchedOptions(pref, client, UaField.DEVICE_MODEL, models)
    }

    private fun populateElectronField(client: UaSpoofClient) {
        val pref = findPreference<UaVersionListPreference>(UaField.ELECTRON_VERSION.prefKey) ?: return
        applyFetchedOptions(pref, client, UaField.ELECTRON_VERSION,
                dataRepository.cachedElectron().map { UaOption(it.version, it.version, null) })
    }

    /** Show [options], selecting the resolved value (the user's pick, else the most popular from cache). */
    private fun applyFetchedOptions(pref: UaVersionListPreference, client: UaSpoofClient, field: UaField, options: List<UaOption>) {
        val value = spoofBuilder.resolvedValue(client, field)
        pref.setOptions(withSelected(options, value))
        pref.value = value
        pref.summary = labelFor(pref, value)
    }

    /** Make sure the currently-selected value is always shown, even if it isn't in the fetched list. */
    private fun withSelected(options: List<UaOption>, value: String): List<UaOption> =
            if (value.isEmpty() || options.any { it.value == value }) options
            else listOf(UaOption(value, value, null)) + options

    private fun labelFor(pref: ListPreference, value: String): String {
        val index = pref.entryValues?.indexOfFirst { it == value } ?: -1
        return pref.entries?.getOrNull(index)?.toString() ?: value
    }

    private fun populateStaticListEntries(field: UaField, client: UaSpoofClient, pref: ListPreference) {
        when (field) {
            UaField.OS -> {
                pref.entries = UaOs.entries.map { getString(it.labelRes) }.toTypedArray()
                pref.entryValues = UaOs.entries.map { it.value }.toTypedArray()
            }
            UaField.FLAVOUR -> {
                val flavours = arrayOf("FDroid", "GooglePlay")
                pref.entries = flavours
                pref.entryValues = flavours
            }
            UaField.SUFFIX -> {
                pref.entries = client.suffixOptions.map { getString(it.labelRes) }.toTypedArray()
                pref.entryValues = client.suffixOptions.map { it.value }.toTypedArray()
            }
            UaField.SCALE -> {
                val scales = arrayOf<CharSequence>("2.00", "3.00")
                pref.entries = scales
                pref.entryValues = scales
            }
            else -> Unit
        }
    }

    private fun hasFetchableFields(client: UaSpoofClient, osValue: String): Boolean =
            client.fields.any { dataRepository.hasProvider(client.providerIdFor(it, osValue)) } ||
                    UaField.ELECTRON_VERSION in client.fields ||
                    UaField.BUILD_ID in client.fields

    /** A field was edited by the user: refresh the preview and the reset/upgrade buttons' enabled state. */
    private fun onFieldEdited(client: UaSpoofClient) {
        refreshPreview()
        refreshResetState(client)
        // Picking an out-of-date value must light up the upgrade button without leaving and re-entering.
        categoryPref?.isUpgradeEnabled = hasUpgradeAvailable(client)
    }

    // -- Download gate (fetch everything so any client works offline afterwards) ----------------

    private inner class DlUnit(
            val label: String,
            val fetch: suspend () -> Boolean,
            val top: () -> String?,
            val cached: () -> Boolean,
    )

    private data class DlResult(val label: String, val ok: Boolean, val changed: Boolean, val top: String?)

    private fun allUnits(): List<DlUnit> {
        val units = dataRepository.allProviderIds().map { id ->
            DlUnit(sourceLabel(id), { dataRepository.refresh(id).isNotEmpty() },
                    { mostPopularValue(dataRepository.cached(id)) }, { dataRepository.hasCache(id) })
        }.toMutableList()
        units += DlUnit(getString(CommonStrings.settings_ua_field_build_id_title),
                { dataRepository.refreshBuildIds("", -1); dataRepository.hasBuildIdsCache() }, { null }, { dataRepository.hasBuildIdsCache() })
        units += DlUnit(getString(CommonStrings.settings_ua_field_electron_version_title),
                { dataRepository.refreshElectron().isNotEmpty() }, { dataRepository.cachedElectron().firstOrNull()?.version }, { dataRepository.hasElectronCache() })
        return units
    }

    /** The download icon: re-fetch everything, then report what changed. */
    private fun updateToLatest() = runDownload(allUnits(), isUpdate = true)

    private fun runDownload(units: List<DlUnit>, isUpdate: Boolean) {
        val context = context ?: return
        val text = TextView(context)
        val bar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = units.size
        }
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(text)
            addView(bar)
        }
        var job: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(CommonStrings.settings_ua_downloading_title)
                .setView(content)
                .setCancelable(false)
                .setNegativeButton(CommonStrings.action_cancel) { d, _ ->
                    job?.cancel()
                    d.dismiss()
                    // The screen can't be shown without data, so leave it when the first download is cancelled.
                    if (!isUpdate) parentFragmentManager.popBackStack()
                }
                .show()

        job = lifecycleScope.launch {
            val total = units.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            // The download bodies all resume on the main thread, so this ordered set is only ever
            // touched there; it holds exactly the units in flight right now (not the last one started).
            val inFlight = LinkedHashSet<String>()
            val gate = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_DOWNLOADS)
            fun render() {
                text.text = buildString {
                    append(getString(CommonStrings.settings_ua_downloading, done.get(), total))
                    inFlight.forEach { append('\n').append(it) }
                }
            }
            render()
            val results = units.map { unit ->
                async {
                    gate.acquire()
                    inFlight.add(unit.label)
                    render()
                    try {
                        val before = if (isUpdate) unit.top() else null
                        val started = android.os.SystemClock.elapsedRealtime()
                        val ok = runCatching { unit.fetch() }.getOrDefault(false)
                        Timber.i("UA download %s took %d ms (ok=%b)", unit.label, android.os.SystemClock.elapsedRealtime() - started, ok)
                        val after = unit.top()
                        DlResult(unit.label, ok, isUpdate && ok && before != after, after)
                    } finally {
                        inFlight.remove(unit.label)
                        bar.progress = done.incrementAndGet()
                        render()
                        gate.release()
                    }
                }
            }.awaitAll()
            dialog.dismiss()
            if (!isAdded) return@launch
            applyClient(userAgentSettings.selectedClient)
            showResultDialog(results, isUpdate)
        }
    }

    private fun showResultDialog(results: List<DlResult>, isUpdate: Boolean) {
        val context = context ?: return
        val failed = results.filter { !it.ok }
        val changed = results.filter { it.changed }
        val message = buildString {
            if (isUpdate) {
                when {
                    changed.isEmpty() && failed.isEmpty() -> append(getString(CommonStrings.settings_ua_up_to_date))
                    changed.isNotEmpty() -> {
                        append(getString(CommonStrings.settings_ua_updated_header)).append('\n')
                        changed.forEach { append("• ${it.label}: ${it.top}\n") }
                    }
                }
            } else {
                append(getString(CommonStrings.settings_ua_downloaded, results.count { it.ok }, results.size))
            }
            if (failed.isNotEmpty()) {
                append('\n').append(getString(CommonStrings.settings_ua_download_failed_header)).append('\n')
                failed.forEach { append("• ${it.label}\n") }
            }
        }.trim()
        MaterialAlertDialogBuilder(context)
                .setTitle(CommonStrings.settings_ua_downloading_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
    }

    private fun sourceLabel(id: String): String = when (id) {
        UaProviderIds.ANDROID_VERSION -> "Android versions"
        UaProviderIds.CHROME_VERSION -> "Chrome versions"
        UaProviderIds.FIREFOX_VERSION -> "Firefox versions"
        UaProviderIds.MACOS_VERSION -> "macOS versions"
        UaProviderIds.IOS_VERSION -> "iOS versions"
        UaProviderIds.IOS_DEVICE -> "iOS devices"
        UaProviderIds.DEVICE_MODEL -> "Android devices"
        UaProviderIds.CURL_VERSION -> "curl versions"
        UaProviderIds.EXA_APP_VERSION -> "Element X Android versions"
        UaProviderIds.SCN_APP_VERSION -> "SchildiChat Next versions"
        UaProviderIds.LEGACY_APP_VERSION -> "Element Android versions"
        UaProviderIds.DESKTOP_APP_VERSION -> "Element Desktop versions"
        UaProviderIds.EXA_IOS_APP_VERSION -> "Element X iOS versions"
        UaProviderIds.IOS_CLASSIC_APP_VERSION -> "Element iOS versions"
        UaProviderIds.MTXCLIENT_VERSION -> "mtxclient versions"
        UaProviderIds.GOMUKS_VERSION -> "gomuks versions"
        UaProviderIds.MAUTRIX_VERSION -> "mautrix-go versions"
        UaProviderIds.GO_VERSION -> "Go versions"
        UaProviderIds.DART_VERSION -> "Dart versions"
        else -> id
    }

    private fun refreshResetState(client: UaSpoofClient) {
        categoryPref?.isResetEnabled = client != UaSpoofClient.NONE && spoofBuilder.isModified(client)
    }

    private fun resetCurrentClient() {
        val client = userAgentSettings.selectedClient
        userAgentSettings.clearClientValues(client)
        applyClient(client)
    }

    private fun refreshPreview() {
        previewPref?.summary = spoofBuilder.build(userAgentSettings.selectedClient).orEmpty()
    }

    companion object {
        private const val VERSION_DIALOG_TAG = "UaVersionListPreferenceDialogFragment"
        private const val MAX_PARALLEL_DOWNLOADS = 8
    }
}
