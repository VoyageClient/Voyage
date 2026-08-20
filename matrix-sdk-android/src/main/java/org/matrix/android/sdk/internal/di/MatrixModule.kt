/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.di

import android.content.Context
import android.content.res.Resources
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqlDriverFactory
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.network.AndroidComputeUserAgentUseCase
import org.matrix.android.sdk.internal.network.ComputeUserAgentUseCase
import org.matrix.android.sdk.internal.platform.AndroidNetworkCallbackStrategyFactory
import org.matrix.android.sdk.internal.platform.KeyValueStoreFactory
import org.matrix.android.sdk.internal.platform.NetworkCallbackStrategyFactory
import org.matrix.android.sdk.internal.platform.SharedPreferencesKeyValueStoreFactory
import org.matrix.android.sdk.internal.session.AndroidSessionComponentFactory
import org.matrix.android.sdk.internal.session.SessionComponentFactory
import org.matrix.android.sdk.internal.util.createBackgroundHandler
import org.matrix.olm.OlmManager
import java.io.File
import java.util.concurrent.Executors

@Module
internal object MatrixModule {

    @JvmStatic
    @Provides
    @MatrixScope
    fun providesMatrixCoroutineDispatchers(): MatrixCoroutineDispatchers {
        return MatrixCoroutineDispatchers(
                io = Dispatchers.IO,
                computation = Dispatchers.Default,
                main = Dispatchers.Main,
                crypto = createBackgroundHandler("Matrix-Crypto_Thread").asCoroutineDispatcher(),
                dmVerif = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        )
    }

    @JvmStatic
    @Provides
    fun providesSessionComponentFactory(factory: AndroidSessionComponentFactory): SessionComponentFactory = factory

    @JvmStatic
    @Provides
    fun providesResources(context: Context): Resources {
        return context.resources
    }

    @JvmStatic
    @Provides
    @FilesDirectory
    fun providesFilesDir(context: Context): File {
        return context.filesDir
    }

    @JvmStatic
    @Provides
    @CacheDirectory
    fun providesCacheDir(context: Context): File {
        return context.cacheDir
    }

    @JvmStatic
    @Provides
    @MatrixScope
    fun providesOlmManager(): OlmManager {
        return OlmManager()
    }

    @JvmStatic
    @Provides
    @MatrixScope
    fun providesSqlDriverFactory(context: Context): SqlDriverFactory {
        return FrameworkSqlDriverFactory(context)
    }

    @JvmStatic
    @Provides
    @MatrixScope
    fun providesKeyValueStoreFactory(context: Context): KeyValueStoreFactory {
        return SharedPreferencesKeyValueStoreFactory(context)
    }

    @JvmStatic
    @Provides
    @MatrixScope
    fun providesNetworkCallbackStrategyFactory(context: Context): NetworkCallbackStrategyFactory {
        return AndroidNetworkCallbackStrategyFactory(context)
    }

    @JvmStatic
    @Provides
    fun providesComputeUserAgentUseCase(context: Context): ComputeUserAgentUseCase {
        return AndroidComputeUserAgentUseCase(context)
    }
}
