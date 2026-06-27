/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.pushers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.pushers.PusherState
import org.matrix.android.sdk.internal.database.model.PusherEntity
import org.matrix.android.sdk.test.fakes.FakeGlobalErrorReceiver
import org.matrix.android.sdk.test.fakes.FakePushersAPI
import org.matrix.android.sdk.test.fakes.FakeRequestExecutor
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner
import java.net.SocketException

private val A_JSON_PUSHER = JsonPusher(
        pushKey = "push-key",
        kind = "http",
        appId = "m.email",
        appDisplayName = "Element",
        deviceDisplayName = null,
        profileTag = "",
        lang = "en-GB",
        data = JsonPusherData(brand = "Element")
)

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class DefaultAddPusherTaskTest {

    private val pushersAPI = FakePushersAPI()
    private val db = FakeSessionDatabase()

    private val addPusherTask = DefaultAddPusherTask(
            pushersAPI = pushersAPI,
            database = db.database,
            dispatcher = db.dispatcher,
            stores = db.stores,
            requestExecutor = FakeRequestExecutor(),
            globalErrorReceiver = FakeGlobalErrorReceiver()
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `given no persisted pusher when adding Pusher then updates api and inserts result with Registered state`() {
        runTest { addPusherTask.execute(AddPusherTask.Params(A_JSON_PUSHER)) }

        pushersAPI.verifySetPusher(A_JSON_PUSHER)
        db.stores.pushers.getByPushKey(A_JSON_PUSHER.pushKey).single().state shouldBeEqualTo PusherState.REGISTERED
    }

    @Test
    fun `given a persisted pusher, when adding Pusher, then updates api and replaces persisted result with Registered state`() {
        db.stores.pushers.insert(PusherEntity(pushKey = A_JSON_PUSHER.pushKey, appDisplayName = "stale"))

        runTest { addPusherTask.execute(AddPusherTask.Params(A_JSON_PUSHER)) }

        pushersAPI.verifySetPusher(A_JSON_PUSHER)
        db.stores.pushers.getByPushKey(A_JSON_PUSHER.pushKey).single().let {
            it.appDisplayName shouldBeEqualTo A_JSON_PUSHER.appDisplayName
            it.state shouldBeEqualTo PusherState.REGISTERED
        }
    }

    @Test
    fun `given a persisted push entity and SetPush API fails, when adding Pusher, then mutates persisted result with Failed registration state and rethrows`() {
        db.stores.pushers.insert(PusherEntity(pushKey = A_JSON_PUSHER.pushKey))
        pushersAPI.givenSetPusherErrors(SocketException())

        assertFailsWith<SocketException> {
            runTest { addPusherTask.execute(AddPusherTask.Params(A_JSON_PUSHER)) }
        }

        db.stores.pushers.getByPushKey(A_JSON_PUSHER.pushKey).single().state shouldBeEqualTo PusherState.FAILED_TO_REGISTER
    }

    @Test
    fun `given no persisted push entity and SetPush API fails, when adding Pusher, then rethrows error`() {
        pushersAPI.givenSetPusherErrors(SocketException())

        assertFailsWith<SocketException> {
            runTest { addPusherTask.execute(AddPusherTask.Params(A_JSON_PUSHER)) }
        }

        db.stores.pushers.getByPushKey(A_JSON_PUSHER.pushKey).isEmpty() shouldBe true
    }
}
