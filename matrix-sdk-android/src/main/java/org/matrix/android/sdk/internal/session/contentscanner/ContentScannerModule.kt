/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.contentscanner

import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.contentscanner.ContentScannerService
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.di.ContentScannerDatabase
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.contentscanner.data.ContentScannerStore
import org.matrix.android.sdk.internal.session.contentscanner.db.ContentScannerSqlDatabase
import org.matrix.android.sdk.internal.session.contentscanner.db.SqlContentScannerStore
import org.matrix.android.sdk.internal.session.contentscanner.tasks.DefaultDownloadEncryptedTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.DefaultGetServerPublicKeyTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.DefaultScanEncryptedTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.DefaultScanMediaTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.DownloadEncryptedTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.GetServerPublicKeyTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.ScanEncryptedTask
import org.matrix.android.sdk.internal.session.contentscanner.tasks.ScanMediaTask
import java.io.File

@Module
internal abstract class ContentScannerModule {
    @Module
    companion object {

        @JvmStatic
        @Provides
        @ContentScannerDatabase
        @SessionScope
        fun providesContentScannerSqlDatabase(
                @SessionFilesDirectory directory: File,
                driverFactory: SqlDriverFactory,
        ): ContentScannerSqlDatabase {
            return ContentScannerSqlDatabase(
                    driverFactory.create(ContentScannerSqlDatabase.Schema, File(directory, "matrix-sdk-content-scanning.db"))
            )
        }

        @JvmStatic
        @Provides
        @ContentScannerDatabase
        @SessionScope
        fun providesContentScannerDbDispatcher(): CoroutineDispatcher {
            return newDatabaseDispatcher("matrix-content-scanner-db")
        }
    }

    @Binds
    abstract fun bindContentScannerService(service: DisabledContentScannerService): ContentScannerService

    @Binds
    abstract fun bindContentScannerStore(store: SqlContentScannerStore): ContentScannerStore

    @Binds
    abstract fun bindDownloadEncryptedTask(task: DefaultDownloadEncryptedTask): DownloadEncryptedTask

    @Binds
    abstract fun bindGetServerPublicKeyTask(task: DefaultGetServerPublicKeyTask): GetServerPublicKeyTask

    @Binds
    abstract fun bindScanMediaTask(task: DefaultScanMediaTask): ScanMediaTask

    @Binds
    abstract fun bindScanEncryptedTask(task: DefaultScanEncryptedTask): ScanEncryptedTask
}
