/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.os.Build
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.utils.toast
import im.vector.app.features.translation.TranslationEngine
import im.vector.app.features.translation.TranslationSettings
import im.vector.app.features.translation.ondevice.NllbModelStore
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsTranslationFragment : VectorSettingsBaseFragment() {

    @Inject lateinit var modelStore: NllbModelStore

    override var titleRes = CommonStrings.settings_translation_title
    override val preferenceXmlRes = R.xml.vector_settings_translation

    private val localSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun bindPref() {
        val primaryFallback = if (localSupported) TranslationEngine.LOCAL.id else TranslationSettings.NONE
        bindEngineList(TranslationSettings.KEY_ENGINE, withNone = true, fallback = primaryFallback)
        bindEngineList(TranslationSettings.KEY_BACKUP_ENGINE, withNone = true, fallback = TranslationSettings.NONE)
        bindLocalModels()
        TranslationEngine.entries.filter { it.needsKey }.forEach { engine ->
            findPreference<EditTextPreference>(TranslationSettings.apiKeyPrefKey(engine))?.let { pref ->
                val title = getString(CommonStrings.settings_translation_api_key_title, engine.displayName)
                pref.title = title
                // dialogTitle was captured from the raw XML title at inflation; set it too.
                pref.dialogTitle = title
                bindSecretSummary(pref)
            }
        }
        bindTextSummary(TranslationSettings.KEY_MICROSOFT_REGION, "global")
        bindTextSummary(TranslationSettings.KEY_OAI_ENDPOINT, TranslationEngine.OPENAI_ENDPOINT)
        bindTextSummary(TranslationSettings.KEY_OAI_MODEL, TranslationEngine.OPENAI_MODEL)
    }

    private fun bindLocalModels() {
        val download = findPreference<VectorPreference>("SETTINGS_TRANSLATION_LOCAL_DOWNLOAD")
        val delete = findPreference<VectorPreference>("SETTINGS_TRANSLATION_LOCAL_DELETE")
        if (!localSupported) {
            findPreference<im.vector.app.core.preference.VectorPreferenceCategory>("SETTINGS_TRANSLATION_LOCAL_CATEGORY")?.isVisible = false
            return
        }
        download?.setOnPreferenceClickListener {
            if (modelStore.state.value !is NllbModelStore.State.Ready) modelStore.startDownload()
            true
        }
        delete?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CommonStrings.settings_translation_local_delete_title)
                    .setMessage(CommonStrings.settings_translation_local_delete_message)
                    .setPositiveButton(CommonStrings.action_remove) { _, _ ->
                        modelStore.delete()
                        activity?.toast(getString(CommonStrings.settings_translation_local_deleted))
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
            true
        }
        stateBinder = { state ->
            when (state) {
                NllbModelStore.State.NotDownloaded -> {
                    download?.summary = getString(CommonStrings.settings_translation_local_status_missing)
                    download?.isEnabled = true
                    delete?.isVisible = false
                }
                is NllbModelStore.State.Downloading -> {
                    val percent = (state.downloadedBytes * 100 / state.totalBytes).toInt().coerceIn(0, 100)
                    download?.summary = getString(CommonStrings.settings_translation_local_status_downloading, percent)
                    download?.isEnabled = false
                    delete?.isVisible = false
                }
                NllbModelStore.State.Ready -> {
                    download?.summary = getString(CommonStrings.settings_translation_local_status_ready)
                    download?.isEnabled = false
                    delete?.isVisible = true
                }
                is NllbModelStore.State.Failed -> {
                    download?.summary = getString(CommonStrings.settings_translation_local_status_failed, state.message)
                    download?.isEnabled = true
                    delete?.isVisible = false
                }
            }
        }
    }

    // bindPref runs in onCreatePreferences, before the view exists; flows need the view lifecycle.
    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        stateBinder?.let { binder ->
            modelStore.state
                    .onEach { binder(it) }
                    .launchIn(viewLifecycleOwner.lifecycleScope)
        }
    }

    private var stateBinder: ((NllbModelStore.State) -> Unit)? = null

    private fun bindEngineList(key: String, withNone: Boolean, fallback: String) {
        val pref = findPreference<ListPreference>(key) ?: return
        val engines = TranslationEngine.entries.filter { it != TranslationEngine.LOCAL || localSupported }
        pref.entries = (if (withNone) listOf(getString(CommonStrings.settings_translation_backup_engine_none)) else emptyList())
                .plus(engines.map { it.displayName })
                .toTypedArray()
        pref.entryValues = (if (withNone) listOf(TranslationSettings.NONE) else emptyList())
                .plus(engines.map { it.id })
                .toTypedArray()
        if (pref.value == null || pref.entryValues.none { it == pref.value }) pref.value = fallback
        pref.summary = pref.entry
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            pref.summary = pref.entries[pref.entryValues.indexOf(newValue)]
            true
        }
    }

    private fun bindSecretSummary(pref: EditTextPreference) {
        fun render(value: String?) {
            pref.summary = if (value.isNullOrBlank()) getString(CommonStrings.settings_translation_not_set) else "••••••••"
        }
        render(pref.text)
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            render(newValue as? String)
            true
        }
    }

    private fun bindTextSummary(key: String, default: String) {
        val pref = findPreference<EditTextPreference>(key) ?: return
        fun render(value: String?) {
            pref.summary = value?.trim().orEmpty().ifEmpty { default }
        }
        render(pref.text)
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            render(newValue as? String)
            true
        }
    }
}
