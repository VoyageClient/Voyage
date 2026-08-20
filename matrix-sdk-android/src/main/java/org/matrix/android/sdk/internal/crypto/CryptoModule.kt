/*
 * Copyright (c) 2019 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.crypto

import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupService
import org.matrix.android.sdk.api.session.crypto.verification.VerificationService
import org.matrix.android.sdk.internal.crypto.api.CryptoApi
import org.matrix.android.sdk.internal.crypto.crosssigning.DefaultCrossSigningService
import org.matrix.android.sdk.internal.crypto.keysbackup.DefaultKeysBackupService
import org.matrix.android.sdk.internal.crypto.keysbackup.api.RoomKeysApi
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.CreateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultCreateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultDeleteBackupTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultDeleteRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultDeleteRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultDeleteSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultGetKeysBackupLastVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultGetKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultGetRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultGetRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultGetSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultStoreRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultStoreRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultStoreSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DefaultUpdateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteBackupTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetKeysBackupLastVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.UpdateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.store.db.sql.CryptoSqlDatabase
import org.matrix.android.sdk.internal.crypto.store.db.sql.SqlClearCacheTask
import org.matrix.android.sdk.internal.crypto.tasks.ClaimOneTimeKeysForUsersDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultClaimOneTimeKeysForUsersDevice
import org.matrix.android.sdk.internal.crypto.tasks.DefaultDeleteDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultDownloadKeysForUsers
import org.matrix.android.sdk.internal.crypto.tasks.DefaultEncryptEventTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultGetDeviceInfoTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultGetDevicesTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultInitializeCrossSigningTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultSendEventTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultSendToDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultSendVerificationMessageTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultSetDeviceNameTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultUploadKeysTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultUploadSignaturesTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultUploadSigningKeysTask
import org.matrix.android.sdk.internal.crypto.tasks.DeleteDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.DownloadKeysForUsersTask
import org.matrix.android.sdk.internal.crypto.tasks.EncryptEventTask
import org.matrix.android.sdk.internal.crypto.tasks.GetDeviceInfoTask
import org.matrix.android.sdk.internal.crypto.tasks.GetDevicesTask
import org.matrix.android.sdk.internal.crypto.tasks.InitializeCrossSigningTask
import org.matrix.android.sdk.internal.crypto.tasks.SendEventTask
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.SendVerificationMessageTask
import org.matrix.android.sdk.internal.crypto.tasks.SetDeviceNameTask
import org.matrix.android.sdk.internal.crypto.tasks.UploadKeysTask
import org.matrix.android.sdk.internal.crypto.tasks.UploadSignaturesTask
import org.matrix.android.sdk.internal.crypto.tasks.UploadSigningKeysTask
import org.matrix.android.sdk.internal.crypto.verification.DefaultVerificationService
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.di.CryptoDatabase
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.cache.ClearCacheTask
import retrofit2.Retrofit
import java.io.File

@Module
internal abstract class CryptoModule {

    @Module
    companion object {
        internal fun getKeyAlias(userMd5: String) = "crypto_module_$userMd5"

        @JvmStatic
        @Provides
        @CryptoDatabase
        @SessionScope
        fun providesCryptoSqlDatabase(
                @SessionFilesDirectory directory: File,
                driverFactory: SqlDriverFactory,
        ): CryptoSqlDatabase {
            val driver = driverFactory.create(CryptoSqlDatabase.Schema, File(directory, "crypto_store.db"))
            // The driver drops-and-recreates on a schema version bump (no migrations), so tables added
            // after the initial schema are created idempotently here to avoid wiping crypto keys on
            // databases that predate them.
            listOf(
                    "CREATE TABLE IF NOT EXISTS identity_pin (user_id TEXT NOT NULL PRIMARY KEY, pinned_master_key TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS received_room_key_bundle (room_id TEXT NOT NULL, sender_user_id TEXT NOT NULL, " +
                            "sender_key TEXT, bundle_json TEXT NOT NULL, PRIMARY KEY (room_id, sender_user_id))",
                    "CREATE TABLE IF NOT EXISTS room_pending_key_bundle (room_id TEXT NOT NULL PRIMARY KEY, inviter TEXT NOT NULL, " +
                            "accepted_at INTEGER NOT NULL)",
            ).forEach { driver.execute(null, it, 0) }
            return CryptoSqlDatabase(driver)
        }

        @JvmStatic
        @Provides
        @CryptoDatabase
        @SessionScope
        fun providesCryptoDbDispatcher(): CoroutineDispatcher {
            return newDatabaseDispatcher("matrix-crypto-db")
        }

        @JvmStatic
        @Provides
        @SessionScope
        fun providesCryptoCoroutineScope(coroutineDispatchers: MatrixCoroutineDispatchers): CoroutineScope {
            return CoroutineScope(SupervisorJob() + coroutineDispatchers.crypto)
        }

        @JvmStatic
        @Provides
        @CryptoDatabase
        fun providesClearCacheTask(@CryptoDatabase database: CryptoSqlDatabase): ClearCacheTask {
            return SqlClearCacheTask(database)
        }

        @JvmStatic
        @Provides
        @SessionScope
        fun providesCryptoAPI(retrofit: Retrofit): CryptoApi {
            return retrofit.create(CryptoApi::class.java)
        }

        @JvmStatic
        @Provides
        @SessionScope
        fun providesRoomKeysAPI(retrofit: Retrofit): RoomKeysApi {
            return retrofit.create(RoomKeysApi::class.java)
        }
    }

    @Binds
    abstract fun bindKeysBackupService(service: DefaultKeysBackupService): KeysBackupService

    @Binds
    abstract fun bindDeleteDeviceTask(task: DefaultDeleteDeviceTask): DeleteDeviceTask

    @Binds
    abstract fun bindGetDevicesTask(task: DefaultGetDevicesTask): GetDevicesTask

    @Binds
    abstract fun bindGetDeviceInfoTask(task: DefaultGetDeviceInfoTask): GetDeviceInfoTask

    @Binds
    abstract fun bindSetDeviceNameTask(task: DefaultSetDeviceNameTask): SetDeviceNameTask

    @Binds
    abstract fun bindUploadKeysTask(task: DefaultUploadKeysTask): UploadKeysTask

    @Binds
    abstract fun bindUploadSigningKeysTask(task: DefaultUploadSigningKeysTask): UploadSigningKeysTask

    @Binds
    abstract fun bindUploadSignaturesTask(task: DefaultUploadSignaturesTask): UploadSignaturesTask

    @Binds
    abstract fun bindDownloadKeysForUsersTask(task: DefaultDownloadKeysForUsers): DownloadKeysForUsersTask

    @Binds
    abstract fun bindCreateKeysBackupVersionTask(task: DefaultCreateKeysBackupVersionTask): CreateKeysBackupVersionTask

    @Binds
    abstract fun bindDeleteBackupTask(task: DefaultDeleteBackupTask): DeleteBackupTask

    @Binds
    abstract fun bindDeleteRoomSessionDataTask(task: DefaultDeleteRoomSessionDataTask): DeleteRoomSessionDataTask

    @Binds
    abstract fun bindDeleteRoomSessionsDataTask(task: DefaultDeleteRoomSessionsDataTask): DeleteRoomSessionsDataTask

    @Binds
    abstract fun bindDeleteSessionsDataTask(task: DefaultDeleteSessionsDataTask): DeleteSessionsDataTask

    @Binds
    abstract fun bindGetKeysBackupLastVersionTask(task: DefaultGetKeysBackupLastVersionTask): GetKeysBackupLastVersionTask

    @Binds
    abstract fun bindGetKeysBackupVersionTask(task: DefaultGetKeysBackupVersionTask): GetKeysBackupVersionTask

    @Binds
    abstract fun bindGetRoomSessionDataTask(task: DefaultGetRoomSessionDataTask): GetRoomSessionDataTask

    @Binds
    abstract fun bindGetRoomSessionsDataTask(task: DefaultGetRoomSessionsDataTask): GetRoomSessionsDataTask

    @Binds
    abstract fun bindGetSessionsDataTask(task: DefaultGetSessionsDataTask): GetSessionsDataTask

    @Binds
    abstract fun bindStoreRoomSessionDataTask(task: DefaultStoreRoomSessionDataTask): StoreRoomSessionDataTask

    @Binds
    abstract fun bindStoreRoomSessionsDataTask(task: DefaultStoreRoomSessionsDataTask): StoreRoomSessionsDataTask

    @Binds
    abstract fun bindStoreSessionsDataTask(task: DefaultStoreSessionsDataTask): StoreSessionsDataTask

    @Binds
    abstract fun bindUpdateKeysBackupVersionTask(task: DefaultUpdateKeysBackupVersionTask): UpdateKeysBackupVersionTask

    @Binds
    abstract fun bindSendToDeviceTask(task: DefaultSendToDeviceTask): SendToDeviceTask

    @Binds
    abstract fun bindEncryptEventTask(task: DefaultEncryptEventTask): EncryptEventTask

    @Binds
    abstract fun bindSendVerificationMessageTask(task: DefaultSendVerificationMessageTask): SendVerificationMessageTask

    @Binds
    abstract fun bindClaimOneTimeKeysForUsersDeviceTask(task: DefaultClaimOneTimeKeysForUsersDevice): ClaimOneTimeKeysForUsersDeviceTask

    @Binds
    abstract fun bindCrossSigningService(service: DefaultCrossSigningService): CrossSigningService

    @Binds
    abstract fun bindVerificationService(service: DefaultVerificationService): VerificationService

    @Binds
    abstract fun bindSendEventTask(task: DefaultSendEventTask): SendEventTask

    @Binds
    abstract fun bindInitalizeCrossSigningTask(task: DefaultInitializeCrossSigningTask): InitializeCrossSigningTask
}
