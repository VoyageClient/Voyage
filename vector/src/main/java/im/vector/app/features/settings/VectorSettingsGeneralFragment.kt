/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UNUSED_VARIABLE", "UNUSED_ANONYMOUS_PARAMETER", "UNUSED_PARAMETER")

package im.vector.app.features.settings

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelperFactory
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.hidePassword
import im.vector.app.core.extensions.toMvRxBundle
import im.vector.app.core.glide.MediaCache
import im.vector.app.core.intent.getFilenameFromUri
import im.vector.app.core.platform.SimpleTextWatcher
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.UserBannerPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.profile.PronounHelper
import im.vector.app.core.profile.TimezoneFormatter
import im.vector.app.core.utils.TextUtils
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogChangePasswordBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.discovery.DiscoverySettingsFragment
import im.vector.app.features.home.room.detail.timeline.tools.messageEmojiSpanify
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.features.navigation.SettingsActivityPayload
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import im.vector.app.features.workers.signout.SignOutUiWorker
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.isInvalidPassword
import org.matrix.android.sdk.api.session.getUser
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerConfig
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.profile.Pronoun
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap
import timber.log.Timber
import java.net.URL
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsGeneralFragment :
        VectorSettingsBaseFragment(),
        GalleryOrCameraDialogHelper.Listener {

    @Inject lateinit var galleryOrCameraDialogHelperFactory: GalleryOrCameraDialogHelperFactory
    @Inject lateinit var recentEmojiDataSource: RecentEmojiDataSource
    @Inject lateinit var mediaCache: MediaCache
    @Inject lateinit var timezoneFormatter: TimezoneFormatter

    override var titleRes = CommonStrings.settings_general_title
    override val preferenceXmlRes = R.xml.vector_settings_general

    private lateinit var galleryOrCameraDialogHelper: GalleryOrCameraDialogHelper
    private lateinit var bannerGalleryOrCameraDialogHelper: GalleryOrCameraDialogHelper

    private var currentAvatarUrl: String? = null
    private var currentBannerUrl: String? = null

    private val bannerListener = object : GalleryOrCameraDialogHelper.Listener {
        override fun onImageReady(uri: Uri?) {
            if (uri != null) {
                uploadBanner(uri)
            } else {
                Toast.makeText(requireContext(), "Cannot retrieve cropped value", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onImageDeleted() {
            deleteBanner()
        }
    }

    private val mUserSettingsCategory by lazy {
        findPreference<PreferenceCategory>(VectorPreferences.SETTINGS_USER_SETTINGS_PREFERENCE_KEY)!!
    }
    private val mUserAvatarPreference by lazy {
        findPreference<UserAvatarPreference>(VectorPreferences.SETTINGS_PROFILE_PICTURE_PREFERENCE_KEY)!!
    }
    private val mUserBannerPreference by lazy {
        findPreference<UserBannerPreference>(VectorPreferences.SETTINGS_PROFILE_BANNER_PREFERENCE_KEY)!!
    }
    private val mDisplayNamePreference by lazy {
        findPreference<EditTextPreference>("SETTINGS_DISPLAY_NAME_PREFERENCE_KEY")!!
    }
    private val mPronounsPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_PRONOUNS_PREFERENCE_KEY")!!
    }
    private val mTimezonePreference by lazy {
        findPreference<VectorPreference>("SETTINGS_TIMEZONE_PREFERENCE_KEY")!!
    }
    private val mPasswordPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CHANGE_PASSWORD_PREFERENCE_KEY)!!
    }
    private val mManage3pidsPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_EMAILS_AND_PHONE_NUMBERS_PREFERENCE_KEY)!!
    }
    private val mIdentityServerPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_IDENTITY_SERVER_PREFERENCE_KEY)!!
    }
    private val mExternalAccountManagementPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_EXTERNAL_ACCOUNT_MANAGEMENT_KEY)!!
    }
    private val mDeactivateAccountCategory by lazy {
        findPreference<VectorPreferenceCategory>("SETTINGS_DEACTIVATE_ACCOUNT_CATEGORY_KEY")!!
    }

    // Local contacts
    private val mContactSettingsCategory by lazy {
        findPreference<PreferenceCategory>(VectorPreferences.SETTINGS_CONTACT_PREFERENCE_KEYS)!!
    }

    private val mContactPhonebookCountryPreference by lazy {
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CONTACTS_PHONEBOOK_COUNTRY_PREFERENCE_KEY)!!
    }

    private val integrationServiceListener = object : IntegrationManagerService.Listener {
        override fun onConfigurationChanged(configs: List<IntegrationManagerConfig>) {
            refreshIntegrationManagerSettings()
        }

        override fun onIsEnabledChanged(enabled: Boolean) {
            refreshIntegrationManagerSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fixed construction order: launcher registration must be deterministic across process death.
        galleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(this)
        bannerGalleryOrCameraDialogHelper = galleryOrCameraDialogHelperFactory.create(
                this,
                GalleryOrCameraDialogHelper.Aspect.BANNER,
                bannerListener
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeUserAvatar()
        observeUserDisplayName()
        refreshBanner()
        refreshProfileFields()
    }

    // Custom profile fields (pronouns/tz) have no live store; seed from cache then fetch fresh.
    private fun refreshProfileFields() {
        updatePronounsSummary(session.profileService().getCachedPronouns(session.myUserId))
        updateTimezoneSummary(session.profileService().getCachedTimezone(session.myUserId))
        lifecycleScope.launch {
            tryOrNull { session.profileService().getProfile(session.myUserId) }
            if (isAdded) {
                updatePronounsSummary(session.profileService().getCachedPronouns(session.myUserId))
                updateTimezoneSummary(session.profileService().getCachedTimezone(session.myUserId))
            }
        }
    }

    private fun updatePronounsSummary(pronouns: List<Pronoun>?) {
        val text = pronouns?.mapNotNull { it.summary.takeIf { s -> s.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
        mPronounsPreference.summary = text ?: getString(CommonStrings.settings_pronouns_not_set)
    }

    private fun updateTimezoneSummary(timezoneId: String?) {
        mTimezonePreference.summary = when {
            timezoneId.isNullOrBlank() -> getString(CommonStrings.settings_timezone_not_set)
            else -> timezoneFormatter.formatToShort(timezoneId)?.let { "$timezoneId ($it)" } ?: timezoneId
        }
    }

    // There is no live store for custom profile fields, so seed from the session cache
    // (avoids a pop-in) and then fetch fresh on show and after each change.
    private fun refreshBanner() {
        currentBannerUrl = session.profileService().getCachedBannerUrl(session.myUserId)
        mUserBannerPreference.refreshBanner(currentBannerUrl)
        lifecycleScope.launch {
            currentBannerUrl = tryOrNull { session.profileService().getBannerUrl(session.myUserId).getOrNull() }
            if (isAdded) {
                mUserBannerPreference.refreshBanner(currentBannerUrl)
            }
        }
    }

    private fun observeUserAvatar() {
        session.flow()
                .liveUser(session.myUserId)
                .unwrap()
                .distinctUntilChangedBy { user -> user.avatarUrl }
                .onEach {
                    currentAvatarUrl = it.avatarUrl
                    mUserAvatarPreference.refreshAvatar(it)
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun observeUserDisplayName() {
        session.flow()
                .liveUser(session.myUserId)
                .unwrap()
                .map { it.displayName ?: "" }
                .distinctUntilChanged()
                .onEach { displayName ->
                    mDisplayNamePreference.let {
                        it.summary = displayName.prepareForDisplay()
                        it.text = displayName
                    }
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun bindPref() {
        // Avatar
        mUserAvatarPreference.let {
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                galleryOrCameraDialogHelper.show(withDeleteOption = !currentAvatarUrl.isNullOrBlank())
                false
            }
        }

        // Banner
        mUserBannerPreference.let {
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                bannerGalleryOrCameraDialogHelper.show(withDeleteOption = !currentBannerUrl.isNullOrBlank())
                false
            }
        }

        // Display name
        mDisplayNamePreference.setOnBindEditTextListener { editText ->
            editText.hint = session.myUserId
            editText.setupLiveEmojiInput()
            messageEmojiSpanify?.applyLive(editText.text)
        }
        mDisplayNamePreference.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                newValue
                        ?.let { value -> (value as? String)?.trim() }
                        ?.let { value -> onDisplayNameChanged(value) }
                false
            }
        }

        // Pronouns
        mPronounsPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showPronounsDialog()
            true
        }

        val homeServerCapabilities = session.homeServerCapabilitiesService().getHomeServerCapabilities()
        // Password
        // Hide the preference if password can not be updated
        if (homeServerCapabilities.canChangePassword) {
            mPasswordPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                onPasswordUpdateClick()
                false
            }
        } else {
            mPasswordPreference.isVisible = false
        }

        // Manage 3Pid
        // Hide the preference if 3pids can not be updated
        mManage3pidsPreference.isVisible = homeServerCapabilities.canChange3pid

        val openDiscoveryScreenPreferenceClickListener = Preference.OnPreferenceClickListener {
            (requireActivity() as VectorSettingsActivity).navigateTo(
                    DiscoverySettingsFragment::class.java,
                    SettingsActivityPayload.DiscoverySettings().toMvRxBundle()
            )
            true
        }

        val discoveryPreference = findPreference<VectorPreference>(VectorPreferences.SETTINGS_DISCOVERY_PREFERENCE_KEY)!!
        discoveryPreference.onPreferenceClickListener = openDiscoveryScreenPreferenceClickListener

        mIdentityServerPreference.onPreferenceClickListener = openDiscoveryScreenPreferenceClickListener

        // External account management URL for delegated OIDC auth
        // Hide the preference if no URL is given by server
        if (homeServerCapabilities.externalAccountManagementUrl != null) {
            mExternalAccountManagementPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                openUrlInChromeCustomTab(it.context, null, homeServerCapabilities.externalAccountManagementUrl!!)
                true
            }

            val hostname = URL(homeServerCapabilities.externalAccountManagementUrl).host

            mExternalAccountManagementPreference.summary = requireContext().getString(
                    CommonStrings.settings_external_account_management,
                    hostname
            )
        } else {
            mExternalAccountManagementPreference.isVisible = false
        }

        // Advanced settings

        // user account
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_LOGGED_IN_PREFERENCE_KEY)!!
                .summary = session.myUserId

        // homeserver
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_HOME_SERVER_PREFERENCE_KEY)!!
                .summary = session.sessionParams.homeServerUrl

        // Contacts
        setContactsPreferences()

        // clear cache
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CLEAR_CACHE_PREFERENCE_KEY)!!.let {
            /*
            TODO
            MXSession.getApplicationSizeCaches(activity, object : SimpleApiCallback<Long>() {
                override fun onSuccess(size: Long) {
                    if (null != activity) {
                        it.summary = TextUtils.formatFileSize(activity, size)
                    }
                }
            })
             */

            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                displayLoadingView()
                MainActivity.restartApp(requireActivity(), MainActivityArgs(clearCache = true))
                false
            }
        }

        (findPreference(VectorPreferences.SETTINGS_ALLOW_INTEGRATIONS_KEY) as? VectorSwitchPreference)?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                // Disable it while updating the state, will be re-enabled by the account data listener.
                it.isEnabled = false
                lifecycleScope.launch {
                    try {
                        session.integrationManagerService().setIntegrationEnabled(newValue as Boolean)
                    } catch (failure: Throwable) {
                        Timber.e(failure, "Failed to update integration manager state")
                        activity?.let { activity ->
                            Toast.makeText(activity, errorFormatter.toHumanReadable(failure), Toast.LENGTH_SHORT).show()
                        }
                        // Restore the previous state
                        it.isChecked = !it.isChecked
                        it.isEnabled = true
                    }
                }
                true
            }
        }

        // clear medias cache
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CLEAR_MEDIA_CACHE_PREFERENCE_KEY)!!.let {
            lifecycleScope.launch(Dispatchers.Main) {
                it.summary = getString(CommonStrings.loading)
                val size = getCacheSize()
                it.summary = TextUtils.formatFileSize(requireContext(), size)
                it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    lifecycleScope.launch(Dispatchers.Main) {
                        displayLoadingView()
                        mediaCache.clear(session)
                        it.summary = TextUtils.formatFileSize(requireContext(), getCacheSize())
                        hideLoadingView()
                    }
                    false
                }
            }
        }
        // clear recent emoji
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_CLEAR_EMOJI_CACHE_PREFERENCE_KEY)!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            recentEmojiDataSource.clear()
            false
        }
        // Sign out
        findPreference<VectorPreference>("SETTINGS_SIGN_OUT_KEY")!!
                .onPreferenceClickListener = Preference.OnPreferenceClickListener {
            activity?.let {
                SignOutUiWorker(requireActivity()).perform()
            }

            false
        }
        // Account deactivation is visible only if account is not managed by an external URL.
        mDeactivateAccountCategory.isVisible = homeServerCapabilities.delegatedOidcAuthEnabled.not()
    }

    private suspend fun getCacheSize(): Long = mediaCache.size(session)

    override fun onResume() {
        super.onResume()
        // Refresh identity server summary
        mIdentityServerPreference.summary = session.identityService().getCurrentIdentityServerUrl() ?: getString(CommonStrings.identity_server_not_defined)
        refreshIntegrationManagerSettings()
        session.integrationManagerService().addListener(integrationServiceListener)
        // Time zone is edited on a separate picker screen; refresh its summary on return.
        refreshProfileFields()
    }

    override fun onPause() {
        super.onPause()
        session.integrationManagerService().removeListener(integrationServiceListener)
    }

    private fun refreshIntegrationManagerSettings() {
        val integrationAllowed = session.integrationManagerService().isIntegrationEnabled()
        (findPreference<SwitchPreference>(VectorPreferences.SETTINGS_ALLOW_INTEGRATIONS_KEY))!!.let {
            val savedListener = it.onPreferenceChangeListener
            it.onPreferenceChangeListener = null
            it.isChecked = integrationAllowed
            it.isEnabled = true
            it.onPreferenceChangeListener = savedListener
        }
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_INTEGRATION_MANAGER_UI_URL_KEY)!!.let {
            if (integrationAllowed) {
                it.summary = session.integrationManagerService().getPreferredConfig().uiUrl
                it.isVisible = true
            } else {
                it.isVisible = false
            }
        }
    }

    override fun onImageReady(uri: Uri?) {
        if (uri != null) {
            uploadAvatar(uri)
        } else {
            Toast.makeText(requireContext(), "Cannot retrieve cropped value", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onImageDeleted() {
        deleteAvatar()
    }

    private fun uploadAvatar(uri: Uri) {
        displayLoadingView()

        lifecycleScope.launch {
            val result = runCatching {
                session.profileService().updateAvatar(session.myUserId, uri.toString(), getFilenameFromUri(context, uri) ?: UUID.randomUUID().toString())
            }
            if (!isAdded) return@launch

            result.fold(
                    onSuccess = { hideLoadingView() },
                    onFailure = {
                        hideLoadingView()
                        displayErrorDialog(it)
                    }
            )
        }
    }

    private fun deleteAvatar() {
        displayLoadingView()

        lifecycleScope.launch {
            val result = runCatching {
                session.profileService().deleteAvatar(session.myUserId)
            }
            if (!isAdded) return@launch

            result.fold(
                    onSuccess = { hideLoadingView() },
                    onFailure = {
                        hideLoadingView()
                        displayErrorDialog(it)
                    }
            )
        }
    }

    private fun uploadBanner(uri: Uri) {
        displayLoadingView()

        lifecycleScope.launch {
            val result = runCatching {
                session.profileService().updateBanner(session.myUserId, uri.toString(), getFilenameFromUri(context, uri) ?: UUID.randomUUID().toString())
            }
            if (!isAdded) return@launch

            result.fold(
                    onSuccess = {
                        hideLoadingView()
                        refreshBanner()
                    },
                    onFailure = {
                        hideLoadingView()
                        displayErrorDialog(it)
                    }
            )
        }
    }

    private fun deleteBanner() {
        displayLoadingView()

        lifecycleScope.launch {
            val result = runCatching {
                session.profileService().deleteBanner(session.myUserId)
            }
            if (!isAdded) return@launch

            result.fold(
                    onSuccess = {
                        hideLoadingView()
                        refreshBanner()
                    },
                    onFailure = {
                        hideLoadingView()
                        displayErrorDialog(it)
                    }
            )
        }
    }

    // ==============================================================================================================
    // contacts management
    // ==============================================================================================================

    private fun setContactsPreferences() {
        /* TODO
        // Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // on Android >= 23, use the system one
            mContactSettingsCategory.removePreference(findPreference(ContactsManager.CONTACTS_BOOK_ACCESS_KEY))
        }
        // Phonebook country
        mContactPhonebookCountryPreference.summary = PhoneNumberUtils.getHumanCountryCode(PhoneNumberUtils.getCountryCode(activity))

        mContactPhonebookCountryPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val intent = CountryPickerActivity.getIntent(activity, true)
            startActivityForResult(intent, REQUEST_PHONEBOOK_COUNTRY)
            true
        }
         */
    }

    // ==============================================================================================================
    // Phone number management
    // ==============================================================================================================

    /**
     * Update the password.
     */
    private fun onPasswordUpdateClick() {
        activity?.let { activity ->
            val view: ViewGroup = activity.layoutInflater.inflate(R.layout.dialog_change_password, null) as ViewGroup
            val views = DialogChangePasswordBinding.bind(view)

            val dialog = MaterialAlertDialogBuilder(activity)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(CommonStrings.settings_change_password, null)
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .setOnDismissListener {
                        view.hideKeyboard()
                    }
                    .create()

            dialog.setOnShowListener {
                val updateButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                updateButton.isEnabled = false

                fun updateUi() {
                    val oldPwd = views.changePasswordOldPwdText.text.toString()
                    val newPwd = views.changePasswordNewPwdText.text.toString()

                    updateButton.isEnabled = oldPwd.isNotEmpty() && newPwd.isNotEmpty()
                }

                views.changePasswordOldPwdText.addTextChangedListener(object : SimpleTextWatcher() {
                    override fun afterTextChanged(s: Editable) {
                        views.changePasswordOldPwdTil.error = null
                        updateUi()
                    }
                })

                views.changePasswordNewPwdText.addTextChangedListener(object : SimpleTextWatcher() {
                    override fun afterTextChanged(s: Editable) {
                        updateUi()
                    }
                })

                fun showPasswordLoadingView(toShow: Boolean) {
                    if (toShow) {
                        views.changePasswordOldPwdText.isEnabled = false
                        views.changePasswordNewPwdText.isEnabled = false
                        views.changePasswordLoader.isVisible = true
                        updateButton.isEnabled = false
                        cancelButton.isEnabled = false
                    } else {
                        views.changePasswordOldPwdText.isEnabled = true
                        views.changePasswordNewPwdText.isEnabled = true
                        views.changePasswordLoader.isVisible = false
                        updateButton.isEnabled = true
                        cancelButton.isEnabled = true
                    }
                }

                updateButton.debouncedClicks {
                    // Hide passwords during processing
                    views.changePasswordOldPwdText.hidePassword()
                    views.changePasswordNewPwdText.hidePassword()

                    view.hideKeyboard()

                    val oldPwd = views.changePasswordOldPwdText.text.toString()
                    val newPwd = views.changePasswordNewPwdText.text.toString()

                    showPasswordLoadingView(true)
                    lifecycleScope.launch {
                        val result = runCatching {
                            session.accountService().changePassword(oldPwd, newPwd)
                        }
                        if (!isAdded) {
                            return@launch
                        }
                        showPasswordLoadingView(false)
                        result.fold({
                            dialog.dismiss()
                            activity.toast(CommonStrings.settings_password_updated)
                        }, { failure ->
                            if (failure.isInvalidPassword()) {
                                views.changePasswordOldPwdTil.error = getString(CommonStrings.settings_fail_to_update_password_invalid_current_password)
                            } else {
                                views.changePasswordOldPwdTil.error = getString(CommonStrings.settings_fail_to_update_password)
                            }
                        })
                    }
                }
            }
            dialog.show()
        }
    }

    /**
     * Update the displayname.
     */
    private fun onDisplayNameChanged(value: String) {
        val currentDisplayName = session.getUser(session.myUserId)?.displayName ?: ""
        if (currentDisplayName != value) {
            displayLoadingView()

            lifecycleScope.launch {
                val result = runCatching { session.profileService().setDisplayName(session.myUserId, value) }
                if (!isAdded) return@launch
                result.fold(
                        onSuccess = {
                            // refresh the settings value
                            mDisplayNamePreference.summary = value
                            mDisplayNamePreference.text = value
                            hideLoadingView()
                        },
                        onFailure = {
                            hideLoadingView()
                            displayErrorDialog(it)
                        }
                )
            }
        }
    }

    private fun showPronounsDialog() {
        val presets = listOf(
                getString(CommonStrings.settings_pronouns_she_her),
                getString(CommonStrings.settings_pronouns_he_him),
                getString(CommonStrings.settings_pronouns_they_them),
                getString(CommonStrings.settings_pronouns_it_its),
        )
        // Multiple pronoun sets are allowed (preference-ordered); pre-check what's already set.
        val current = session.profileService().getCachedPronouns(session.myUserId).orEmpty()
        val checked = BooleanArray(presets.size) { i -> current.any { it.summary.equals(presets[i], ignoreCase = true) } }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_pronouns)
                .setMultiChoiceItems(presets.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(CommonStrings.action_save) { _, _ ->
                    val selected = presets.filterIndexed { i, _ -> checked[i] }.map { PronounHelper.build(it) }
                    savePronouns(selected)
                }
                .setNeutralButton(CommonStrings.settings_pronouns_custom) { _, _ -> showCustomPronounsDialog() }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun showCustomPronounsDialog() {
        val existing = session.profileService().getCachedPronouns(session.myUserId).orEmpty()
                .joinToString(", ") { it.summary }
        val editText = EditText(requireContext()).apply {
            hint = getString(CommonStrings.settings_pronouns_custom_hint)
            setText(existing)
            setSingleLine()
        }
        val inset = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(inset, 0, inset, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_pronouns_custom_title)
                .setView(container)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    // Comma-separated, preference-ordered — supports multiple sets.
                    val summaries = editText.text?.toString().orEmpty()
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    savePronouns(summaries.map { PronounHelper.build(it) })
                }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun savePronouns(pronouns: List<Pronoun>) {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { session.profileService().setPronouns(session.myUserId, pronouns) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.fold(
                    onSuccess = { updatePronounsSummary(pronouns) },
                    onFailure = { displayErrorDialog(it) }
            )
        }
    }
}
