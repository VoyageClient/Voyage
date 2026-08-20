/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import dagger.Module
import dagger.Provides
import im.vector.matrixcli.platform.AssumeOnlineNetworkCallbackStrategyFactory
import im.vector.matrixcli.platform.DesktopSecureStorage
import im.vector.matrixcli.platform.FileKeyValueStoreFactory
import im.vector.matrixcli.platform.JdbcSqlDriverFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.di.CacheDirectory
import org.matrix.android.sdk.internal.di.FilesDirectory
import org.matrix.android.sdk.internal.di.MatrixScope
import org.matrix.android.sdk.internal.network.ComputeUserAgentUseCase
import org.matrix.android.sdk.internal.platform.KeyValueStoreFactory
import org.matrix.android.sdk.internal.platform.NetworkCallbackStrategyFactory
import org.matrix.android.sdk.internal.platform.SecureStorage
import org.matrix.android.sdk.internal.session.SessionComponentFactory
import org.matrix.olm.OlmManager
import java.io.File
import java.util.concurrent.Executors

/**
 * The desktop counterpart of the android MatrixModule: everything the SDK asks the platform for
 * before a session exists.
 */
@Module
internal class DesktopMatrixModule(private val dataDir: File) {

    init {
        // The crypto graph builds OlmUtility in field initializers, before anything injects OlmManager,
        // so the native library has to be loaded before a session is created — the android Matrix does
        // the same in its init.
        OlmManager()
    }

    @Provides
    @MatrixScope
    fun providesMatrixCoroutineDispatchers(): MatrixCoroutineDispatchers {
        return MatrixCoroutineDispatchers(
                io = Dispatchers.IO,
                computation = Dispatchers.Default,
                // No UI thread here; a single thread keeps the ordering the android main looper gave.
                main = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
                crypto = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
                dmVerif = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
        )
    }

    @Provides
    fun providesSessionComponentFactory(factory: DesktopSessionComponentFactory): SessionComponentFactory = factory

    @Provides
    @FilesDirectory
    fun providesFilesDir(): File = File(dataDir, "files").also { it.mkdirs() }

    @Provides
    @CacheDirectory
    fun providesCacheDir(): File = File(dataDir, "cache").also { it.mkdirs() }

    @Provides
    @MatrixScope
    fun providesOlmManager(): OlmManager = OlmManager()

    @Provides
    @MatrixScope
    fun providesSqlDriverFactory(): SqlDriverFactory = JdbcSqlDriverFactory(File(dataDir, "db").also { it.mkdirs() })

    @Provides
    @MatrixScope
    fun providesKeyValueStoreFactory(): KeyValueStoreFactory =
            FileKeyValueStoreFactory(File(dataDir, "prefs").also { it.mkdirs() })

    @Provides
    @MatrixScope
    fun providesNetworkCallbackStrategyFactory(): NetworkCallbackStrategyFactory = AssumeOnlineNetworkCallbackStrategyFactory()

    @Provides
    @MatrixScope
    fun providesSecureStorage(): SecureStorage = DesktopSecureStorage(File(dataDir, "secrets.key"))

    @Provides
    fun providesComputeUserAgentUseCase(): ComputeUserAgentUseCase = object : ComputeUserAgentUseCase {
        override fun execute(flavorDescription: String) = "MatrixCli/0.1 ($flavorDescription)"
    }
}
