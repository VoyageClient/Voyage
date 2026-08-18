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
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
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
import im.vector.app.core.platform.showOptimizedSnackbar
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.UserBannerPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.core.profile.PronounHelper
import im.vector.app.core.profile.TimezoneFormatter
import im.vector.app.core.utils.TextUtils
import im.vector.app.core.utils.leadingEmojiRunLength
import im.vector.app.core.utils.openUrlInChromeCustomTab
import im.vector.app.core.utils.toast
import im.vector.app.databinding.DialogChangePasswordBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.discovery.DiscoverySettingsFragment
import im.vector.app.features.home.room.detail.timeline.tools.formatProfileBio
import im.vector.app.features.home.room.detail.timeline.tools.messageEmojiSpanify
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import im.vector.app.features.navigation.SettingsActivityPayload
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import im.vector.app.features.redaction.preservation.PreservedMediaStore
import im.vector.app.features.redaction.preservation.RedactionCacheCleaner
import im.vector.app.features.redaction.preservation.RedactionPreservationSettings
import im.vector.app.features.settings.admin.ServerAdminStatusDataSource
import im.vector.app.features.workers.signout.SignOutUiWorker
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.isInvalidPassword
import org.matrix.android.sdk.api.session.admin.ServerAdminStatus
import org.matrix.android.sdk.api.session.getUser
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerConfig
import org.matrix.android.sdk.api.session.integrationmanager.IntegrationManagerService
import org.matrix.android.sdk.api.session.profile.Pronoun
import org.matrix.android.sdk.api.session.profile.UserBio
import org.matrix.android.sdk.api.session.profile.UserStatus
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
    @Inject lateinit var preservedMediaStore: PreservedMediaStore
    @Inject lateinit var redactionCacheCleaner: RedactionCacheCleaner
    @Inject lateinit var recentEmojiDataSource: RecentEmojiDataSource
    @Inject lateinit var mediaCache: MediaCache
    @Inject lateinit var timezoneFormatter: TimezoneFormatter
    @Inject lateinit var serverAdminStatusDataSource: ServerAdminStatusDataSource
    @Inject lateinit var emoteShortcodeProcessor: EmoteShortcodeProcessor

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
    private val mStatusPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_STATUS_PREFERENCE_KEY")!!
    }
    private val mBiographyPreference by lazy {
        findPreference<VectorPreference>("SETTINGS_BIOGRAPHY_PREFERENCE_KEY")!!
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

    // Custom profile fields (pronouns/tz/status/bio) have no live store; seed from cache then fetch fresh.
    private fun refreshProfileFields() {
        updateProfileFieldSummaries()
        lifecycleScope.launch {
            tryOrNull { session.profileService().getProfile(session.myUserId) }
            if (isAdded) {
                updateProfileFieldSummaries()
            }
        }
    }

    private fun updateProfileFieldSummaries() {
        updatePronounsSummary(session.profileService().getCachedPronouns(session.myUserId))
        updateTimezoneSummary(session.profileService().getCachedTimezone(session.myUserId))
        updateStatusSummary(session.profileService().getCachedStatus(session.myUserId))
        updateBiographySummary(session.profileService().getCachedBio(session.myUserId))
    }

    private fun updateStatusSummary(status: UserStatus?) {
        mStatusPreference.summary = status?.display()?.takeIf { it.isNotBlank() }?.prepareForDisplay()
                ?: getString(CommonStrings.settings_status_not_set)
    }

    private fun updateBiographySummary(bio: UserBio?) {
        mBiographyPreference.summary = bio?.body?.takeIf { it.isNotBlank() }
                ?.formatProfileBio(bio.formattedBody)
                ?.toSingleLine()
                ?: getString(CommonStrings.settings_biography_not_set)
    }

    // The summary is one line, where a bio's block spans (quote stripes, list indents) and line breaks
    // would render as gaps or be cut off. Edited in place so the emoji/pill spans survive.
    private fun CharSequence.toSingleLine(): CharSequence {
        val builder = SpannableStringBuilder(this)
        builder.getSpans(0, builder.length, Any::class.java)
                .filter { it is LeadingMarginSpan || it is LineHeightSpan || it is AlignmentSpan }
                .forEach { builder.removeSpan(it) }
        var i = 0
        while (i < builder.length) {
            if (builder[i] == '\n') {
                var end = i + 1
                while (end < builder.length && builder[end].isWhitespace()) end++
                var start = i
                while (start > 0 && builder[start - 1].isWhitespace()) start--
                builder.replace(start, end, " ")
                i = start + 1
            } else {
                i++
            }
        }
        return builder.trim()
    }

    private fun updatePronounsSummary(pronouns: List<Pronoun>?) {
        val text = pronouns?.mapNotNull { it.summary.takeIf { s -> s.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
        mPronounsPreference.summary = text?.prepareForDisplay() ?: getString(CommonStrings.settings_pronouns_not_set)
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

        // Status
        mStatusPreference.singleLineSummary = true
        mStatusPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showStatusDialog()
            true
        }

        // Biography
        mBiographyPreference.singleLineSummary = true
        mBiographyPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showBiographyDialog()
            true
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
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_LOGGED_IN_PREFERENCE_KEY)!!.let {
            it.summary = session.myUserId
            // Capabilities are cached and only refetched periodically, so a server-side change (an
            // experimental feature being switched on, say) would otherwise not be picked up until
            // the cache expired or the user signed out.
            it.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                lifecycleScope.launch {
                    runCatching { session.homeServerCapabilitiesService().refreshHomeServerCapabilities() }
                            .onSuccess { view?.showOptimizedSnackbar(getString(CommonStrings.settings_homeserver_info_reloaded)) }
                            .onFailure { failure -> view?.showOptimizedSnackbar(errorFormatter.toHumanReadable(failure)) }
                }
                false
            }
        }

        // homeserver
        findPreference<VectorPreference>(VectorPreferences.SETTINGS_HOME_SERVER_PREFERENCE_KEY)!!
                .summary = session.sessionParams.homeServerUrl

        // Contacts
        setContactsPreferences()

        setupServerAdminPreference()

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
        // Sits with the other cache actions rather than under Redactions: the preserved event data is
        // only ever dropped along with the app cache, so this file cache is the one thing to clear here.
        findPreference<VectorPreference>(RedactionPreservationSettings.SETTINGS_REDACTION_CLEAR_MEDIA_CACHE_KEY)?.let { pref ->
            val canPreserveRedactions = session.homeServerCapabilitiesService().getHomeServerCapabilities().canViewUnredactedContent
            pref.isVisible = canPreserveRedactions
            // Listener first: sizing walks the whole preserved-media tree, and a tap before that
            // finishes would otherwise be silently swallowed.
            pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                lifecycleScope.launch(Dispatchers.Main) {
                    displayLoadingView()
                    redactionCacheCleaner.clearMediaCache()
                    if (!isAdded) return@launch
                    pref.summary = TextUtils.formatFileSize(requireContext(), preservedMediaStore.size())
                    hideLoadingView()
                }
                false
            }
            lifecycleScope.launch(Dispatchers.Main) {
                val size = preservedMediaStore.size()
                if (!isAdded) return@launch
                // Files an earlier server left behind would otherwise have no way to be cleared.
                pref.isVisible = canPreserveRedactions || size > 0
                pref.summary = TextUtils.formatFileSize(requireContext(), size)
            }
        }
        // Sign out
        findPreference<VectorPreference>("SETTINGS_SIGN_OUT_KEY")!!.let { signOutPref ->
            signOutPref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                activity?.let {
                    SignOutUiWorker(requireActivity()).perform()
                }

                false
            }
            signOutPref.onPreferenceLongClickListener = object : VectorPreference.OnPreferenceLongClickListener {
                override fun onPreferenceLongClick(preference: Preference): Boolean {
                    activity?.let {
                        SignOutUiWorker(requireActivity()).perform(localOnly = true)
                    }

                    return true
                }
            }
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

    private fun setupServerAdminPreference() {
        val preference = findPreference<VectorPreference>(VectorPreferences.SETTINGS_SERVER_ADMIN_PREFERENCE_KEY) ?: return
        preference.summary = summaryFor(serverAdminStatusDataSource.cachedStatus())
        preference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                preference.summary = getString(CommonStrings.loading)
                preference.summary = summaryFor(serverAdminStatusDataSource.refresh())
            }
            false
        }
        // Existing installs have no cached answer yet, so probe once rather than reporting "Unknown".
        lifecycleScope.launch(Dispatchers.Main) {
            preference.summary = summaryFor(serverAdminStatusDataSource.refreshIfUnknown())
        }
    }

    private fun summaryFor(status: ServerAdminStatus) = getString(
            when (status) {
                ServerAdminStatus.YES -> CommonStrings.settings_server_admin_yes
                ServerAdminStatus.NO -> CommonStrings.settings_server_admin_no
                ServerAdminStatus.UNKNOWN -> CommonStrings.settings_server_admin_unknown
            }
    )

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
            setupLiveEmojiInput()
            messageEmojiSpanify?.applyLive(text)
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

    private fun showStatusDialog() {
        val existing = session.profileService().getCachedStatus(session.myUserId)
        val editText = EditText(requireContext()).apply {
            hint = getString(CommonStrings.settings_status_text_hint)
            setText(existing?.display())
            setSingleLine()
            setupLiveEmojiInput()
            messageEmojiSpanify?.applyLive(text)
        }
        val inset = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(inset, 0, inset, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_status_title)
                .setView(container)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    val status = editText.text?.toString().orEmpty().toUserStatus()
                    if (status.text.utf8Size() > UserStatus.MAX_TEXT_BYTES || status.emoji.utf8Size() > UserStatus.MAX_EMOJI_BYTES) {
                        Toast.makeText(requireContext(), CommonStrings.settings_status_too_long, Toast.LENGTH_SHORT).show()
                    } else {
                        saveStatus(status.takeIf { !it.isEmpty() })
                    }
                }
                .setNeutralButton(CommonStrings.settings_status_clear) { _, _ -> saveStatus(null) }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    /** A status is typed as one line; emoji leading it become the MSC4426 emoji field, e.g. "🤫😂 hey". */
    private fun String.toUserStatus(): UserStatus {
        val typed = trim()
        val emojiEnd = typed.leadingEmojiRunLength()
        return UserStatus(text = typed.substring(emojiEnd).trimStart(), emoji = typed.substring(0, emojiEnd))
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    // Commonmark folds any run of blank lines into a single paragraph break, which would silently
    // flatten the spacing someone laid their bio out with. Each blank line past the first becomes a
    // raw <br /> block, which the parser passes through untouched. Code fences are left alone: their
    // blank lines are content.
    private fun String.withBlankLinesKept(): String {
        if (contains("```")) return this
        return replace(Regex("\n{3,}")) { match -> "\n\n" + "<br />\n\n".repeat(match.value.length - 2) }
    }

    private fun saveStatus(status: UserStatus?) {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { session.profileService().setStatus(session.myUserId, status) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.fold(
                    onSuccess = { updateStatusSummary(status) },
                    onFailure = { displayErrorDialog(it) }
            )
        }
    }

    private fun showBiographyDialog() {
        val existing = session.profileService().getCachedBio(session.myUserId)
        val editText = EditText(requireContext()).apply {
            hint = getString(CommonStrings.settings_biography_hint)
            setText(existing?.body)
            setupLiveEmojiInput()
            messageEmojiSpanify?.applyLive(text)
            // A bio is free-form prose, so let it wrap and grow like a message rather than a single line.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 8
        }
        val inset = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            setPadding(inset, 0, inset, 0)
            addView(editText)
        }
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.settings_biography_title)
                .setView(container)
                .setPositiveButton(CommonStrings.ok) { _, _ ->
                    saveTypedBiography(editText.text?.toString()?.trim().orEmpty())
                }
                .setNeutralButton(CommonStrings.settings_biography_clear) { _, _ -> saveBiography(null) }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    /**
     * Markdown and `:shortcode:` emotes are resolved once, on save, exactly as a message's are: the HTML
     * is only stored when the text actually carries formatting, so plain prose keeps its layout. The body
     * keeps the shortcodes it was typed with, which is what a client that shows no HTML falls back to.
     */
    private fun saveTypedBiography(body: String) {
        lifecycleScope.launch {
            val formatted = body.takeIf { it.isNotEmpty() }?.let { text ->
                // Resolving the emotes reads the image packs from the database.
                val withEmotes = withContext(Dispatchers.IO) {
                    emoteShortcodeProcessor.process(roomId = null, text = text.withBlankLinesKept())
                }
                tryOrNull { session.roomService().computeFormattedHtml(withEmotes, autoMarkdown = true) }
            }
            if (!isAdded) return@launch
            saveBiography(UserBio(body, formatted).takeIf { !it.isEmpty() })
        }
    }

    private fun saveBiography(bio: UserBio?) {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching { session.profileService().setBio(session.myUserId, bio) }
            if (!isAdded) return@launch
            hideLoadingView()
            result.fold(
                    onSuccess = { updateBiographySummary(bio) },
                    onFailure = { displayErrorDialog(it) }
            )
        }
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
