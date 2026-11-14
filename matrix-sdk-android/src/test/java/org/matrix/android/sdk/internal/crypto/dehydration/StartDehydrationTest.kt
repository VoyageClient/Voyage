/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.crypto.dehydration.DehydratedDeviceService.RehydrationResult
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageService

class StartDehydrationTest {

    private val api = mockk<DehydratedDeviceApi> {
        coEvery { getDehydratedDevice() } throws Failure.ServerError(MatrixError(MatrixError.M_NOT_FOUND, "No dehydrated device"), 404)
    }
    private val secretStorage = mockk<SharedSecretStorageService> {
        every { getCachedKeySpec() } returns ("key" to mockk())
    }
    private val creator = mockk<DehydratedDeviceCreator>()

    private val service = DefaultDehydratedDeviceService(
            dehydratedDeviceApi = api,
            globalErrorReceiver = mockk(relaxed = true),
            coroutineDispatchers = Dispatchers.Unconfined.let { MatrixCoroutineDispatchers(it, it, it, it, it) },
            sharedSecretStorageService = Lazy { secretStorage },
            accountDataService = Lazy { mockk() },
            cryptoService = Lazy { mockk() },
            creator = creator,
            rehydrator = mockk(),
    )

    @Test
    fun `no dehydrated device is uploaded when this session cannot cross sign it`() = runTest {
        every { creator.canCrossSign() } returns false

        service.startDehydration() shouldBe RehydrationResult.NothingToRehydrate

        coVerify(exactly = 0) { api.putDehydratedDevice(any()) }
    }
}
