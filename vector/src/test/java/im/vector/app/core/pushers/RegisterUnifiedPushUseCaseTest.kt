/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import im.vector.app.test.fakes.FakeContext
import im.vector.app.test.fakes.FakeStringProvider
import im.vector.app.test.fakes.FakeVectorFeatures
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.unifiedpush.android.connector.UnifiedPush

private const val AN_INSTANCE = "an-instance"

class RegisterUnifiedPushUseCaseTest {

    private val fakeContext = FakeContext()
    private val fakeVectorFeatures = FakeVectorFeatures()
    private val fakeStringProvider = FakeStringProvider()
    private val unifiedPushHelper = mockk<UnifiedPushHelper>()
    private val pushHealthCheckScheduler = mockk<PushHealthCheckScheduler>(relaxed = true)

    private val registerUnifiedPushUseCase = RegisterUnifiedPushUseCase(
            context = fakeContext.instance,
            vectorFeatures = fakeVectorFeatures,
            unifiedPushHelper = unifiedPushHelper,
            stringProvider = fakeStringProvider.instance,
            pushHealthCheckScheduler = pushHealthCheckScheduler,
    )

    @Before
    fun setup() {
        mockkStatic(UnifiedPush::class)
        justRun { UnifiedPush.register(any(), any(), any(), any()) }
        justRun { UnifiedPush.saveDistributor(any(), any()) }
        every { unifiedPushHelper.getCurrentInstance() } returns AN_INSTANCE
        every { unifiedPushHelper.getCurrentDistributor() } returns ""
        every { unifiedPushHelper.isBackgroundSync() } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `given non empty distributor when execute then distributor is saved and app is registered`() = runTest {
        val aDistributor = "distributor"

        val result = registerUnifiedPushUseCase.execute(aDistributor)

        result shouldBe RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.Success
        verifyOrder {
            UnifiedPush.saveDistributor(fakeContext.instance, aDistributor)
            UnifiedPush.register(fakeContext.instance, AN_INSTANCE, any(), any())
        }
    }

    @Test
    fun `given external distributors are not allowed when execute then internal distributor is saved and app is registered`() = runTest {
        val aPackageName = "packageName"
        fakeContext.givenPackageName(aPackageName)
        fakeVectorFeatures.givenExternalDistributorsAreAllowed(false)

        val result = registerUnifiedPushUseCase.execute()

        result shouldBe RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.Success
        verifyOrder {
            UnifiedPush.saveDistributor(fakeContext.instance, aPackageName)
            UnifiedPush.register(fakeContext.instance, AN_INSTANCE, any(), any())
        }
    }

    @Test
    fun `given a saved distributor and external distributors are allowed when execute then app is registered`() = runTest {
        every { unifiedPushHelper.getCurrentDistributor() } returns "distributor"
        fakeVectorFeatures.givenExternalDistributorsAreAllowed(true)

        val result = registerUnifiedPushUseCase.execute()

        result shouldBe RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.Success
        verify { UnifiedPush.register(fakeContext.instance, AN_INSTANCE, any(), any()) }
        verify(inverse = true) { UnifiedPush.saveDistributor(any(), any()) }
    }

    @Test
    fun `given no saved distributor and a unique distributor available when execute then the distributor is saved and app is registered`() = runTest {
        fakeVectorFeatures.givenExternalDistributorsAreAllowed(true)
        val aDistributor = "distributor"
        every { UnifiedPush.getDistributors(any()) } returns listOf(aDistributor)

        val result = registerUnifiedPushUseCase.execute()

        result shouldBe RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.Success
        verifyOrder {
            UnifiedPush.getDistributors(fakeContext.instance)
            UnifiedPush.saveDistributor(fakeContext.instance, aDistributor)
            UnifiedPush.register(fakeContext.instance, AN_INSTANCE, any(), any())
        }
    }

    @Test
    fun `given no saved distributor and multiple distributors available when execute then result is to ask user`() = runTest {
        fakeVectorFeatures.givenExternalDistributorsAreAllowed(true)
        every { UnifiedPush.getDistributors(any()) } returns listOf("distributor1", "distributor2")

        val result = registerUnifiedPushUseCase.execute()

        result shouldBe RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.NeedToAskUserForDistributor
        verify(inverse = true) {
            UnifiedPush.saveDistributor(any(), any())
            UnifiedPush.register(any(), any(), any(), any())
        }
    }

    @Test
    fun `given no session yet when execute then the default instance is registered`() = runTest {
        every { unifiedPushHelper.getCurrentInstance() } returns null

        registerUnifiedPushUseCase.execute("distributor")

        verify { UnifiedPush.register(fakeContext.instance, UnifiedPushStore.DEFAULT_INSTANCE, any(), any()) }
    }
}
