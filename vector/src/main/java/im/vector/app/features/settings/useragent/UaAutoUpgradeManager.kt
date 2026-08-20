/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import im.vector.app.features.settings.useragent.data.UaDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps auto-upgrade clients tracking the latest software versions without the user opening settings.
 * When the app comes to the foreground, this refreshes the option data for the software fields of any
 * client that has auto-upgrade on (the interceptor then resolves those fields to the newest value). The
 * refresh is conditional (ETag/Last-Modified), so unchanged sources cost a single 304, and it's
 * throttled so it runs at most a few times a day. Hardware fields (device) are never touched.
 */
@Singleton
class UaAutoUpgradeManager @Inject constructor(
        private val settings: UserAgentSettings,
        private val repository: UaDataRepository,
        private val appScope: CoroutineScope,
) {

    @Volatile private var lastRunElapsed = 0L

    fun onAppForegrounded() {
        // Read the active account's flags, matching the interceptor (never the pre-login edit override).
        val scope = settings.sessionScope()
        val clients = UaSpoofClient.entries.filter { it != UaSpoofClient.NONE && settings.autoUpgradeFor(it, scope) }
        if (clients.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRunElapsed != 0L && now - lastRunElapsed < THROTTLE_MS) return
        lastRunElapsed = now
        appScope.launch(Dispatchers.IO) {
            runCatching { refreshFor(clients) }.onFailure { Timber.w(it, "UA auto-upgrade refresh failed") }
        }
    }

    private suspend fun refreshFor(clients: List<UaSpoofClient>) {
        val scope = settings.sessionScope()
        val providerIds = LinkedHashSet<String>()
        var electron = false
        var buildIds = false
        clients.forEach { client ->
            val os = settings.storedValue(client, UaField.OS, scope) ?: UaOs.WINDOWS.value
            client.fields.filter { it in SOFTWARE_FIELDS }.forEach { field ->
                when (field) {
                    UaField.ELECTRON_VERSION -> electron = true
                    UaField.BUILD_ID -> buildIds = true
                    else -> client.providerIdFor(field, os)?.let { providerIds.add(it) }
                }
            }
        }
        Timber.i("UA auto-upgrade refreshing %d sources for %d clients", providerIds.size, clients.size)
        providerIds.forEach { repository.refresh(it) }
        if (electron) repository.refreshElectron()
        if (buildIds) repository.refreshBuildIds("", -1)
        clients.forEach { resolveSdkShaIfNeeded(it) }
    }

    /** After the app-version list refreshes, make sure the newest version's rust-sdk sha is cached too. */
    private suspend fun resolveSdkShaIfNeeded(client: UaSpoofClient) {
        val providerId = client.providerIdFor(UaField.APP_VERSION, "") ?: return
        val newest = repository.cached(providerId).firstOrNull()?.value ?: return
        if (settings.sdkShaFor(newest) != null) return
        val chain = sdkShaChainFor(client, newest) ?: return
        repository.resolveSdkSha(chain.appRepo, chain.tag, chain.componentsRepo, chain.releasePrefix)
                ?.let { settings.setSdkShaFor(newest, it) }
    }

    companion object {
        private val THROTTLE_MS = TimeUnit.HOURS.toMillis(6)
    }
}
