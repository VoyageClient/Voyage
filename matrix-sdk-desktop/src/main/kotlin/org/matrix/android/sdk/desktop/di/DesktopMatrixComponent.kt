/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.di

import com.squareup.moshi.Moshi
import dagger.BindsInstance
import dagger.Component
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.auth.AuthModule
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.debug.DebugModule
import org.matrix.android.sdk.internal.di.CacheDirectory
import org.matrix.android.sdk.internal.di.FilesDirectory
import org.matrix.android.sdk.internal.di.MatrixScope
import org.matrix.android.sdk.internal.di.NetworkModule
import org.matrix.android.sdk.internal.di.NoOpTestModule
import org.matrix.android.sdk.internal.di.Unauthenticated
import org.matrix.android.sdk.internal.platform.KeyValueStoreFactory
import org.matrix.android.sdk.internal.platform.NetworkCallbackStrategyFactory
import org.matrix.android.sdk.internal.platform.SecureStorage
import org.matrix.android.sdk.internal.raw.RawModule
import org.matrix.android.sdk.internal.session.MockHttpInterceptor
import org.matrix.android.sdk.internal.session.TestInterceptor
import org.matrix.android.sdk.internal.session.user.accountdata.PendingUnIgnoreStore
import org.matrix.android.sdk.internal.settings.SettingsModule
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.BackgroundDetectionObserver
import org.matrix.android.sdk.internal.util.system.SystemModule
import org.matrix.olm.OlmManager
import java.io.File

@Component(
        modules = [
            DesktopMatrixModule::class,
            NetworkModule::class,
            AuthModule::class,
            RawModule::class,
            DebugModule::class,
            SettingsModule::class,
            SystemModule::class,
            NoOpTestModule::class,
        ]
)
@MatrixScope
internal interface DesktopMatrixComponent {

    fun matrixCoroutineDispatchers(): MatrixCoroutineDispatchers

    fun moshi(): Moshi

    @Unauthenticated
    fun okHttpClient(): OkHttpClient

    @MockHttpInterceptor
    fun testInterceptor(): TestInterceptor?

    fun authenticationService(): AuthenticationService

    fun rawService(): RawService

    fun lightweightSettingsStorage(): LightweightSettingsStorage

    fun matrixConfiguration(): MatrixConfiguration

    @CacheDirectory
    fun cacheDir(): File

    @FilesDirectory
    fun filesDir(): File

    fun taskExecutor(): TaskExecutor

    fun olmManager(): OlmManager

    fun sessionParamsStore(): SessionParamsStore

    fun sessionManager(): SessionManager

    fun backgroundDetectionObserver(): BackgroundDetectionObserver

    fun pendingUnIgnoreStore(): PendingUnIgnoreStore

    fun secureStorage(): SecureStorage

    fun sqlDriverFactory(): SqlDriverFactory

    fun keyValueStoreFactory(): KeyValueStoreFactory

    fun networkCallbackStrategyFactory(): NetworkCallbackStrategyFactory

    @Component.Factory
    interface Factory {
        fun create(
                matrixModule: DesktopMatrixModule,
                @BindsInstance matrixConfiguration: MatrixConfiguration
        ): DesktopMatrixComponent
    }
}
