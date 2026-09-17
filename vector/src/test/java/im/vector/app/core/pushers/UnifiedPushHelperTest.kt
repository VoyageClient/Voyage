/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import im.vector.app.features.mdm.NoOpMdmService
import im.vector.app.test.fakes.FakeActiveSessionHolder
import im.vector.app.test.fakes.FakeContext
import im.vector.app.test.fakes.FakeFcmHelper
import im.vector.app.test.fakes.FakeSession
import im.vector.app.test.fakes.FakeStringProvider
import im.vector.app.test.fakes.FakeUnifiedPushStore
import im.vector.app.test.fakes.FakeVectorPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Provider

private const val A_DISTRIBUTOR = "io.heckel.ntfy"
private const val AN_INSTANCE = "an-instance"
private const val AN_ENDPOINT = "https://ntfy.sh/upAbCd?up=1"
private const val A_CUSTOM_GATEWAY = "https://gateway.example.org/_matrix/push/v1/notify"
private const val A_STORED_GATEWAY = "https://ntfy.sh/_matrix/push/v1/notify"
private const val A_RESOLVED_GATEWAY = "https://ntfy.sh/_matrix/push/v1/notify"

class UnifiedPushHelperTest {

    private val fakeContext = FakeContext()
    private val fakeUnifiedPushStore = FakeUnifiedPushStore()
    private val fakeStringProvider = FakeStringProvider()
    private val fakeFcmHelper = FakeFcmHelper()
    private val fakeVectorPreferences = FakeVectorPreferences()
    private val fakeSession = FakeSession()
    private val fakeActiveSessionHolder = FakeActiveSessionHolder(fakeSession)
    private val gatewayResolver = mockk<UnifiedPushGatewayResolver>()

    private val unifiedPushHelper = UnifiedPushHelper(
            context = fakeContext.instance,
            unifiedPushStore = fakeUnifiedPushStore.instance,
            stringProvider = fakeStringProvider.instance,
            gatewayResolver = gatewayResolver,
            fcmHelper = fakeFcmHelper.instance,
            mdmService = NoOpMdmService(),
            vectorPreferences = fakeVectorPreferences.instance,
            activeSessionHolder = Provider { fakeActiveSessionHolder.instance },
    )

    @Before
    fun setup() {
        mockkStatic(UnifiedPush::class)
        every { UnifiedPush.getSavedDistributor(any()) } returns A_DISTRIBUTOR
        fakeContext.givenPackageName("im.voyage.app")
        every { fakeFcmHelper.instance.isFirebaseAvailable() } returns false
        every { fakeUnifiedPushStore.instance.getOrCreateInstance(any()) } returns AN_INSTANCE
        every { fakeVectorPreferences.instance.customPushGateway() } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `given a pinned gateway, when storing the gateway, then it is used without probing`() = runTest {
        every { fakeVectorPreferences.instance.customPushGateway() } returns A_CUSTOM_GATEWAY
        fakeUnifiedPushStore.givenStorePushGateway(AN_INSTANCE, A_CUSTOM_GATEWAY)

        unifiedPushHelper.storeGatewayForEndpoint(AN_INSTANCE, AN_ENDPOINT) shouldBeEqualTo A_CUSTOM_GATEWAY

        fakeUnifiedPushStore.verifyStorePushGateway(AN_INSTANCE, A_CUSTOM_GATEWAY)
        coVerify(inverse = true) { gatewayResolver.getGateway(any()) }
    }

    @Test
    fun `given the endpoint host is a gateway, when storing the gateway, then it is the one resolved`() = runTest {
        coEvery { gatewayResolver.getGateway(AN_ENDPOINT) } returns UnifiedPushGatewayResolverResult.Success(A_RESOLVED_GATEWAY)
        fakeUnifiedPushStore.givenStorePushGateway(AN_INSTANCE, A_RESOLVED_GATEWAY)

        unifiedPushHelper.storeGatewayForEndpoint(AN_INSTANCE, AN_ENDPOINT) shouldBeEqualTo A_RESOLVED_GATEWAY
    }

    @Test
    fun `given the endpoint host has no gateway, when storing the gateway, then the default is used`() = runTest {
        coEvery { gatewayResolver.getGateway(AN_ENDPOINT) } returns UnifiedPushGatewayResolverResult.NoMatrixGateway
        val default = unifiedPushHelper.getDefaultPushGateway()
        fakeUnifiedPushStore.givenStorePushGateway(AN_INSTANCE, default)

        unifiedPushHelper.storeGatewayForEndpoint(AN_INSTANCE, AN_ENDPOINT) shouldBeEqualTo default
    }

    @Test
    fun `given the probe failed, when storing the gateway, then the previous one is kept`() = runTest {
        coEvery { gatewayResolver.getGateway(AN_ENDPOINT) } returns UnifiedPushGatewayResolverResult.Error(A_RESOLVED_GATEWAY)
        every { fakeUnifiedPushStore.instance.getPushGateway(AN_INSTANCE) } returns A_STORED_GATEWAY
        fakeUnifiedPushStore.givenStorePushGateway(AN_INSTANCE, A_STORED_GATEWAY)

        unifiedPushHelper.storeGatewayForEndpoint(AN_INSTANCE, AN_ENDPOINT) shouldBeEqualTo A_STORED_GATEWAY
    }

    @Test
    fun `given a pinned gateway, when reading the gateway, then it wins over the stored one`() {
        every { fakeVectorPreferences.instance.customPushGateway() } returns A_CUSTOM_GATEWAY
        every { fakeUnifiedPushStore.instance.getPushGateway(AN_INSTANCE) } returns A_STORED_GATEWAY

        unifiedPushHelper.getPushGateway() shouldBeEqualTo A_CUSTOM_GATEWAY
    }

    @Test
    fun `given no pinned gateway, when reading the gateway, then the stored one is used`() {
        every { fakeUnifiedPushStore.instance.getPushGateway(AN_INSTANCE) } returns A_STORED_GATEWAY

        unifiedPushHelper.getPushGateway() shouldBeEqualTo A_STORED_GATEWAY
    }
}
