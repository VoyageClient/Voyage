/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop

import org.matrix.android.sdk.BuildConfig
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.desktop.di.DaggerDesktopMatrixComponent
import org.matrix.android.sdk.desktop.di.DesktopMatrixComponent
import org.matrix.android.sdk.desktop.di.DesktopMatrixModule
import org.matrix.olm.OlmManager
import java.io.File

/**
 * Desktop counterpart of the android `Matrix` entry point. Everything the SDK stores lives under
 * [dataDir]; one instance per data directory.
 */
class DesktopMatrix(
        dataDir: File,
        matrixConfiguration: MatrixConfiguration,
        appName: String = "MatrixDesktop",
        appVersion: String = "0.1",
) {

    internal val component: DesktopMatrixComponent = DaggerDesktopMatrixComponent.factory().create(
            DesktopMatrixModule(dataDir.also { it.mkdirs() }, appName, appVersion),
            matrixConfiguration,
    )

    fun authenticationService(): AuthenticationService = component.authenticationService()

    fun rawService(): RawService = component.rawService()

    fun lightweightSettingsStorage(): LightweightSettingsStorage = component.lightweightSettingsStorage()

    companion object {
        fun getSdkVersion(): String = BuildConfig.SDK_VERSION + " (" + BuildConfig.GIT_SDK_REVISION + ")"

        fun getCryptoVersion(longFormat: Boolean): String {
            val version = OlmManager().olmLibVersion
            return if (longFormat) "libce $version" else version
        }
    }
}
