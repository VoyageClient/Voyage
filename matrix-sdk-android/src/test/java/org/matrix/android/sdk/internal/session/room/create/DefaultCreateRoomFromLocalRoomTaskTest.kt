/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.create

import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.model.LocalRoomCreationState
import org.matrix.android.sdk.api.session.room.model.LocalRoomSummary
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.internal.database.model.LocalRoomSummaryEntity
import org.matrix.android.sdk.internal.database.sqldelight.awaitNotEmptyResult
import org.matrix.android.sdk.test.fakes.FakeRoomSummaryDataSource
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val A_LOCAL_ROOM_ID = "local.a-local-room-id"
private const val AN_EXISTING_ROOM_ID = "an-existing-room-id"
private const val A_ROOM_ID = "a-room-id"

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
internal class DefaultCreateRoomFromLocalRoomTaskTest {

    private val db = FakeSessionDatabase()
    private val createRoomTask = mockk<CreateRoomTask>()
    private val fakeRoomSummaryDataSource = FakeRoomSummaryDataSource()

    private val defaultCreateRoomFromLocalRoomTask = DefaultCreateRoomFromLocalRoomTask(
            database = db.database,
            dispatcher = db.dispatcher,
            stores = db.stores,
            createRoomTask = createRoomTask,
            roomSummaryDataSource = fakeRoomSummaryDataSource.instance,
    )

    @Before
    fun setup() {
        // The post-creation awaits observe real DB Flows; under runTest's virtual clock the timeout would
        // fire before the async emission, so short-circuit them (the orchestration is what we assert).
        mockkStatic("org.matrix.android.sdk.internal.database.sqldelight.DbReactiveCoroutinesKt")
        coJustRun { awaitNotEmptyResult<Any>(query = any(), timeoutMillis = any(), dispatcher = any()) }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun `given a local room id when execute then the existing room id is kept`() = runTest {
        // Given
        val aCreateRoomParams = mockk<CreateRoomParams>(relaxed = true)
        givenALocalRoomSummary(aCreateRoomParams = aCreateRoomParams, aCreationState = LocalRoomCreationState.CREATED, aReplacementRoomId = AN_EXISTING_ROOM_ID)

        // When
        val params = CreateRoomFromLocalRoomTask.Params(A_LOCAL_ROOM_ID)
        val result = defaultCreateRoomFromLocalRoomTask.execute(params)

        // Then
        fakeRoomSummaryDataSource.verifyGetLocalRoomSummary(A_LOCAL_ROOM_ID)
        result shouldBeEqualTo AN_EXISTING_ROOM_ID
    }

    @Test
    fun `given a local room id when execute then it is correctly executed`() = runTest {
        // Given
        val aCreateRoomParams = mockk<CreateRoomParams>(relaxed = true)
        givenALocalRoomSummary(aCreateRoomParams = aCreateRoomParams, aReplacementRoomId = null)
        givenALocalRoomSummaryEntity()

        coEvery { createRoomTask.execute(any()) } returns A_ROOM_ID

        // When
        val params = CreateRoomFromLocalRoomTask.Params(A_LOCAL_ROOM_ID)
        val result = defaultCreateRoomFromLocalRoomTask.execute(params)

        // Then
        fakeRoomSummaryDataSource.verifyGetLocalRoomSummary(A_LOCAL_ROOM_ID)
        coVerify { createRoomTask.execute(aCreateRoomParams) }
        result shouldBeEqualTo A_ROOM_ID
        db.stores.localRoomSummary.get(A_LOCAL_ROOM_ID)!!.let {
            it.replacementRoomId shouldBeEqualTo A_ROOM_ID
            it.creationState shouldBeEqualTo LocalRoomCreationState.CREATED
        }
    }

    @Test
    fun `given a local room id when execute with an exception then the creation state is correctly updated`() = runTest {
        // Given
        val aCreateRoomParams = mockk<CreateRoomParams>(relaxed = true)
        givenALocalRoomSummary(aCreateRoomParams = aCreateRoomParams, aReplacementRoomId = null)
        givenALocalRoomSummaryEntity()

        coEvery { createRoomTask.execute(any()) }.throws(mockk())

        // When
        val params = CreateRoomFromLocalRoomTask.Params(A_LOCAL_ROOM_ID)
        tryOrNull { defaultCreateRoomFromLocalRoomTask.execute(params) }

        // Then
        fakeRoomSummaryDataSource.verifyGetLocalRoomSummary(A_LOCAL_ROOM_ID)
        coVerify { createRoomTask.execute(aCreateRoomParams) }
        db.stores.localRoomSummary.get(A_LOCAL_ROOM_ID)!!.let {
            it.replacementRoomId.shouldBeNull()
            it.creationState shouldBeEqualTo LocalRoomCreationState.FAILURE
        }
    }

    private fun givenALocalRoomSummary(
            aCreateRoomParams: CreateRoomParams,
            aCreationState: LocalRoomCreationState = LocalRoomCreationState.NOT_CREATED,
            aReplacementRoomId: String? = null
    ): LocalRoomSummary {
        val aLocalRoomSummary = LocalRoomSummary(
                roomId = A_LOCAL_ROOM_ID,
                roomSummary = mockk(relaxed = true) {
                    every { invitedMembersCount } returns 0
                    every { isEncrypted } returns false
                },
                createRoomParams = aCreateRoomParams,
                creationState = aCreationState,
                replacementRoomId = aReplacementRoomId,
        )
        fakeRoomSummaryDataSource.givenGetLocalRoomSummaryReturns(A_LOCAL_ROOM_ID, aLocalRoomSummary)
        return aLocalRoomSummary
    }

    private fun givenALocalRoomSummaryEntity() {
        db.stores.localRoomSummary.upsert(LocalRoomSummaryEntity(roomId = A_LOCAL_ROOM_ID).apply {
            creationState = LocalRoomCreationState.NOT_CREATED
        })
    }
}
