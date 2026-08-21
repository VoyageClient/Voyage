/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.BuildConfig
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.securestorage.SecureStorageService
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.desktop.di.DaggerDesktopMatrixComponent
import org.matrix.android.sdk.desktop.di.DesktopMatrixComponent
import org.matrix.android.sdk.desktop.di.DesktopMatrixModule
import org.matrix.olm.OlmManager
import java.io.File

/**
 * Desktop counterpart of the android `Matrix` entry point. Everything the SDK stores lives under
 * [dataDir] except evictable files, which go to [cacheDir]; one instance per data directory.
 * [userAgent] is read per request, so a consumer can change it without rebuilding the graph.
 */
class DesktopMatrix(
        dataDir: File,
        matrixConfiguration: MatrixConfiguration,
        cacheDir: File = File(dataDir, "cache"),
        mainDispatcher: CoroutineDispatcher? = null,
        userAgent: () -> String = { DEFAULT_USER_AGENT },
) {

    internal val component: DesktopMatrixComponent = DaggerDesktopMatrixComponent.factory().create(
            DesktopMatrixModule(dataDir.also { it.mkdirs() }, cacheDir, mainDispatcher, userAgent),
            matrixConfiguration,
    )

    fun authenticationService(): AuthenticationService = component.authenticationService()

    fun rawService(): RawService = component.rawService()

    fun lightweightSettingsStorage(): LightweightSettingsStorage = component.lightweightSettingsStorage()

    fun secureStorageService(): SecureStorageService = component.secureStorageService()

    /**
     * Drops every pooled connection and queued call. Session clients are `newBuilder()` copies of
     * the shared client, so without this a later account could reuse a previous account's keep-alive
     * connections to the same host.
     */
    fun evictConnections() {
        val client = component.okHttpClient()
        client.dispatcher().cancelAll()
        client.connectionPool().evictAll()
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Voyage"

        fun getSdkVersion(): String = BuildConfig.SDK_VERSION + " (" + BuildConfig.GIT_SDK_REVISION + ")"

        fun getCryptoVersion(longFormat: Boolean): String {
            val version = OlmManager().olmLibVersion
            return if (longFormat) "libce $version" else version
        }
    }
}
