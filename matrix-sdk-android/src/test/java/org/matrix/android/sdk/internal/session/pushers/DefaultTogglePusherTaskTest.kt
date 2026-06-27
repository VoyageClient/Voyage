/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.pushers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.test.fakes.FakeGlobalErrorReceiver
import org.matrix.android.sdk.test.fakes.FakePushersAPI
import org.matrix.android.sdk.test.fakes.FakeRequestExecutor
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.matrix.android.sdk.test.fixtures.JsonPusherFixture.aJsonPusher
import org.matrix.android.sdk.test.fixtures.PusherEntityFixture.aPusherEntity
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultTogglePusherTaskTest {

    private val pushersAPI = FakePushersAPI()
    private val db = FakeSessionDatabase()
    private val requestExecutor = FakeRequestExecutor()
    private val globalErrorReceiver = FakeGlobalErrorReceiver()

    private val togglePusherTask = DefaultTogglePusherTask(pushersAPI, db.database, db.dispatcher, db.stores, requestExecutor, globalErrorReceiver)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `execution toggles enable on both local and remote`() = runTest {
        val jsonPusher = aJsonPusher(enabled = false)
        val params = TogglePusherTask.Params(aJsonPusher(), true)
        db.stores.pushers.insert(aPusherEntity(enabled = false))

        togglePusherTask.execute(params)

        val expectedPayload = jsonPusher.copy(enabled = true)
        pushersAPI.verifySetPusher(expectedPayload)
        db.stores.pushers.getByPushKey(jsonPusher.pushKey).single().enabled shouldBeEqualTo true
    }
}
