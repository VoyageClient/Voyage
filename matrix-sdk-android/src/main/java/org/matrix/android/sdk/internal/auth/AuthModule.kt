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

package org.matrix.android.sdk.internal.auth

import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.HomeServerHistoryService
import org.matrix.android.sdk.internal.auth.db.AuthSqlDatabase
import org.matrix.android.sdk.internal.auth.db.SqlPendingSessionStore
import org.matrix.android.sdk.internal.auth.db.SqlSessionParamsStore
import org.matrix.android.sdk.internal.auth.login.DefaultDirectLoginTask
import org.matrix.android.sdk.internal.auth.login.DefaultQrLoginTokenTask
import org.matrix.android.sdk.internal.auth.login.DirectLoginTask
import org.matrix.android.sdk.internal.auth.login.QrLoginTokenTask
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.di.AuthDatabase
import org.matrix.android.sdk.internal.di.MatrixScope
import org.matrix.android.sdk.internal.wellknown.WellknownModule

@Module(includes = [WellknownModule::class])
internal abstract class AuthModule {

    @Module
    companion object {
        @JvmStatic
        @Provides
        @AuthDatabase
        @MatrixScope
        fun providesAuthSqlDatabase(driverFactory: SqlDriverFactory): AuthSqlDatabase {
            return AuthSqlDatabase(driverFactory.create(AuthSqlDatabase.Schema, "matrix-sdk-auth.db"))
        }

        @JvmStatic
        @Provides
        @AuthDatabase
        @MatrixScope
        fun providesAuthDbDispatcher(): CoroutineDispatcher {
            return newDatabaseDispatcher("matrix-auth-db")
        }
    }

    @Binds
    abstract fun bindSessionParamsStore(store: SqlSessionParamsStore): SessionParamsStore

    @Binds
    abstract fun bindPendingSessionStore(store: SqlPendingSessionStore): PendingSessionStore

    @Binds
    abstract fun bindAuthenticationService(service: DefaultAuthenticationService): AuthenticationService

    @Binds
    abstract fun bindSessionCreator(creator: DefaultSessionCreator): SessionCreator

    @Binds
    abstract fun bindSessionParamsCreator(creator: DefaultSessionParamsCreator): SessionParamsCreator

    @Binds
    abstract fun bindDirectLoginTask(task: DefaultDirectLoginTask): DirectLoginTask

    @Binds
    abstract fun bindIsValidClientServerApiTask(task: DefaultIsValidClientServerApiTask): IsValidClientServerApiTask

    @Binds
    abstract fun bindHomeServerHistoryService(service: DefaultHomeServerHistoryService): HomeServerHistoryService

    @Binds
    abstract fun bindQrLoginTokenTask(task: DefaultQrLoginTokenTask): QrLoginTokenTask
}
