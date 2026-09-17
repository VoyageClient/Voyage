/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import androidx.annotation.MainThread
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.getApplicationLabel
import im.vector.app.features.mdm.MdmData
import im.vector.app.features.mdm.MdmService
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import org.unifiedpush.android.connector.UnifiedPush
import timber.log.Timber
import java.net.URL
import javax.inject.Inject
import javax.inject.Provider

class UnifiedPushHelper @Inject constructor(
        private val context: Context,
        private val unifiedPushStore: UnifiedPushStore,
        private val stringProvider: StringProvider,
        private val gatewayResolver: UnifiedPushGatewayResolver,
        private val fcmHelper: FcmHelper,
        private val mdmService: MdmService,
        private val vectorPreferences: VectorPreferences,
        // ActiveSessionHolder reaches back here through UnregisterUnifiedPushUseCase.
        private val activeSessionHolder: Provider<ActiveSessionHolder>,
) {

    @MainThread
    fun showSelectDistributorDialog(
            context: Context,
            onDistributorSelected: (String) -> Unit,
    ) {
        val internalDistributorName = stringProvider.getString(
                if (fcmHelper.isFirebaseAvailable()) {
                    CommonStrings.unifiedpush_distributor_fcm_fallback
                } else {
                    CommonStrings.unifiedpush_distributor_background_sync
                }
        )

        val distributors = UnifiedPush.getDistributors(context)
        val distributorsName = distributors.map {
            if (it == context.packageName) {
                internalDistributorName
            } else {
                context.getApplicationLabel(it)
            }
        }

        MaterialAlertDialogBuilder(context)
                .setTitle(stringProvider.getString(CommonStrings.unifiedpush_getdistributors_dialog_title))
                .setItems(distributorsName.toTypedArray()) { _, which ->
                    val distributor = distributors[which]
                    onDistributorSelected(distributor)
                }
                .setOnCancelListener {
                    // we do not want to change the distributor on behalf of the user
                    if (getCurrentDistributor().isEmpty()) {
                        // By default, use internal solution (fcm/background sync)
                        onDistributorSelected(context.packageName)
                    }
                }
                .setCancelable(true)
                .show()
    }

    /**
     * Work out which gateway [endpoint] should be pushed through and store it for [instance].
     *
     * A probe that fails outright leaves the previous gateway in place: it tells us nothing about
     * the host, and replacing a working gateway on the strength of one lost request would silently
     * redirect every later push.
     */
    suspend fun storeGatewayForEndpoint(instance: String, endpoint: String): String {
        // The embedded distributor pushes through the app's own gateway, the pushkey being an FCM token.
        if (isInternalDistributor()) {
            val gateway = mdmService.getData(
                    mdmData = MdmData.DefaultPushGatewayUrl,
                    defaultValue = stringProvider.getString(im.vector.app.config.R.string.pusher_http_url),
            )
            unifiedPushStore.storePushGateway(instance, gateway)
            return gateway
        }

        vectorPreferences.customPushGateway()?.let {
            Timber.i("Using the gateway the user pinned")
            unifiedPushStore.storePushGateway(instance, it)
            return it
        }

        val gateway = when (val result = gatewayResolver.getGateway(endpoint)) {
            is UnifiedPushGatewayResolverResult.Success -> result.gateway
            is UnifiedPushGatewayResolverResult.Error -> unifiedPushStore.getPushGateway(instance) ?: getDefaultPushGateway()
            UnifiedPushGatewayResolverResult.NoMatrixGateway,
            UnifiedPushGatewayResolverResult.ErrorInvalidUrl -> getDefaultPushGateway()
        }
        unifiedPushStore.storePushGateway(instance, gateway)
        return gateway
    }

    fun getExternalDistributors(): List<String> {
        return UnifiedPush.getDistributors(context)
                .filterNot { it == context.packageName }
    }

    fun getCurrentDistributorName(): String {
        return when {
            isEmbeddedDistributor() -> stringProvider.getString(CommonStrings.unifiedpush_distributor_fcm_fallback)
            isBackgroundSync() -> stringProvider.getString(CommonStrings.unifiedpush_distributor_background_sync)
            else -> context.getApplicationLabel(getCurrentDistributor())
        }
    }

    fun getCurrentDistributor(): String = UnifiedPush.getSavedDistributor(context).orEmpty()

    fun isEmbeddedDistributor(): Boolean {
        return isInternalDistributor() && fcmHelper.isFirebaseAvailable()
    }

    fun isBackgroundSync(): Boolean {
        return isInternalDistributor() && !fcmHelper.isFirebaseAvailable()
    }

    private fun isInternalDistributor(): Boolean {
        val distributor = getCurrentDistributor()
        return distributor.isEmpty() || distributor == context.packageName
    }

    fun getPrivacyFriendlyUpEndpoint(): String? {
        val endpoint = getEndpointOrToken()
        if (endpoint.isNullOrEmpty()) return null
        if (isEmbeddedDistributor()) {
            return endpoint
        }
        return try {
            val parsed = URL(endpoint)
            "${parsed.protocol}://${parsed.host}/***"
        } catch (e: Exception) {
            Timber.e(e, "Error parsing unifiedpush endpoint")
            null
        }
    }

    /** The instance the current session registers with the distributor, or null without a session. */
    fun getCurrentInstance(): String? {
        val sessionId = activeSessionHolder.get().getSafeActiveSession()?.sessionId ?: return null
        return unifiedPushStore.getOrCreateInstance(sessionId)
    }

    fun getEndpointOrToken(): String? {
        if (isEmbeddedDistributor()) return fcmHelper.getFcmToken()
        return getCurrentInstance()?.let { unifiedPushStore.getEndpoint(it) }
    }

    fun getPushGateway(): String? {
        if (isEmbeddedDistributor()) {
            return mdmService.getData(
                    mdmData = MdmData.DefaultPushGatewayUrl,
                    defaultValue = stringProvider.getString(im.vector.app.config.R.string.pusher_http_url),
            )
        }
        vectorPreferences.customPushGateway()?.let { return it }
        return getCurrentInstance()?.let { unifiedPushStore.getPushGateway(it) }
    }

    /** The gateway used when the user pins none and the distributor advertises none. */
    fun getDefaultPushGateway(): String {
        return stringProvider.getString(im.vector.app.config.R.string.default_push_gateway_http_url)
    }
}
