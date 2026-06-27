/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

import androidx.test.platform.app.InstrumentationRegistry
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.db.mapper.CrossSigningKeysMapper
import org.matrix.android.sdk.internal.crypto.store.db.mapper.MyDeviceLastSeenInfoEntityMapper
import org.matrix.android.sdk.internal.crypto.store.db.sql.CryptoSqlDatabase
import org.matrix.android.sdk.internal.crypto.store.db.sql.SqlCryptoStore
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.newDatabaseDispatcher
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.util.time.DefaultClock
import kotlin.random.Random

internal class CryptoStoreHelper {

    fun createStore(): IMXCryptoStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val driver = FrameworkSqliteDriver.create(context, null, CryptoSqlDatabase.Schema)
        return SqlCryptoStore(
                database = CryptoSqlDatabase(driver),
                dispatcher = newDatabaseDispatcher("test-crypto-db-" + Random.nextInt()),
                crossSigningKeysMapper = CrossSigningKeysMapper(MoshiProvider.providesMoshi()),
                userId = "userId_" + Random.nextInt(),
                deviceId = "deviceId_sample",
                clock = DefaultClock(),
                myDeviceLastSeenInfoEntityMapper = MyDeviceLastSeenInfoEntityMapper()
        )
    }
}
