/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import im.vector.app.core.extensions.startSyncing
import im.vector.app.core.session.clientinfo.UpdateMatrixClientInfoUseCase
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.devices.v2.notification.UpdateNotificationSettingsAccountDataUseCase
import im.vector.app.test.fakes.FakeContext
import im.vector.app.test.fakes.FakeNotificationsSettingUpdater
import im.vector.app.test.fakes.FakePushRulesUpdater
import im.vector.app.test.fakes.FakeSession
import im.vector.app.test.fakes.FakeVectorPreferences
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConfigureAndStartSessionUseCaseTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val fakeContext = FakeContext()
    private val fakeUpdateMatrixClientInfoUseCase = mockk<UpdateMatrixClientInfoUseCase>()
    private val fakeVectorPreferences = FakeVectorPreferences()
    private val fakeNotificationsSettingUpdater = FakeNotificationsSettingUpdater()
    private val fakePushRulesUpdater = FakePushRulesUpdater()
    private val fakeUpdateNotificationSettingsAccountDataUseCase = mockk<UpdateNotificationSettingsAccountDataUseCase>()
    private val fakeAccountInfoCache = mockk<AccountInfoCache>().also {
        coJustRun { it.storeForActive(any()) }
    }

    private val configureAndStartSessionUseCase = ConfigureAndStartSessionUseCase(
            context = fakeContext.instance,
            updateMatrixClientInfoUseCase = fakeUpdateMatrixClientInfoUseCase,
            vectorPreferences = fakeVectorPreferences.instance,
            notificationsSettingUpdater = fakeNotificationsSettingUpdater.instance,
            updateNotificationSettingsAccountDataUseCase = fakeUpdateNotificationSettingsAccountDataUseCase,
            pushRulesUpdater = fakePushRulesUpdater.instance,
            accountInfoCache = fakeAccountInfoCache,
            quickReactionsDataSource = mockk(relaxed = true),
            redactionPreservationService = mockk(relaxed = true),
            mediaPreviewConfigDataSource = mockk(relaxed = true),
    )

    @Before
    fun setup() {
        // observeOwnProfileForCache collects getUserLive().asFlow(), which dispatches on Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic("im.vector.app.core.extensions.SessionKt")
        mockkStatic("im.vector.app.features.session.SessionCoroutineScopesKt")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `given start sync needed and client info recording enabled when execute then it should be configured properly`() = runTest {
        // Given
        val aSession = givenASession()
        every { aSession.coroutineScope } returns this
        coJustRun { fakeUpdateMatrixClientInfoUseCase.execute(any()) }
        coJustRun { fakeUpdateNotificationSettingsAccountDataUseCase.execute(any()) }
        fakeVectorPreferences.givenIsClientInfoRecordingEnabled(isEnabled = true)
        fakeNotificationsSettingUpdater.givenOnSessionStarted(aSession)
        fakePushRulesUpdater.givenOnSessionStarted(aSession)

        // When
        configureAndStartSessionUseCase.execute(aSession, startSyncing = true)
        advanceUntilIdle()

        // Then
        verify { aSession.startSyncing(fakeContext.instance) }
        aSession.fakePushersService.verifyRefreshPushers()
        coVerify {
            fakeUpdateMatrixClientInfoUseCase.execute(aSession)
            fakeUpdateNotificationSettingsAccountDataUseCase.execute(aSession)
        }

        // Stop the long-lived profile observer so runTest doesn't see an uncompleted coroutine.
        configureAndStartSessionUseCase.cancelProfileObserver()
    }

    @Test
    fun `given start sync needed and client info recording disabled when execute then it should be configured properly`() = runTest {
        // Given
        val aSession = givenASession()
        every { aSession.coroutineScope } returns this
        coJustRun { fakeUpdateNotificationSettingsAccountDataUseCase.execute(any()) }
        fakeVectorPreferences.givenIsClientInfoRecordingEnabled(isEnabled = false)
        fakeNotificationsSettingUpdater.givenOnSessionStarted(aSession)
        fakePushRulesUpdater.givenOnSessionStarted(aSession)

        // When
        configureAndStartSessionUseCase.execute(aSession, startSyncing = true)
        advanceUntilIdle()

        // Then
        verify { aSession.startSyncing(fakeContext.instance) }
        aSession.fakePushersService.verifyRefreshPushers()
        coVerify(inverse = true) {
            fakeUpdateMatrixClientInfoUseCase.execute(aSession)
        }
        coVerify {
            fakeUpdateNotificationSettingsAccountDataUseCase.execute(aSession)
        }

        // Stop the long-lived profile observer so runTest doesn't see an uncompleted coroutine.
        configureAndStartSessionUseCase.cancelProfileObserver()
    }

    @Test
    fun `given a session and no start sync needed when execute then it should be configured properly`() = runTest {
        // Given
        val aSession = givenASession()
        every { aSession.coroutineScope } returns this
        coJustRun { fakeUpdateMatrixClientInfoUseCase.execute(any()) }
        coJustRun { fakeUpdateNotificationSettingsAccountDataUseCase.execute(any()) }
        fakeVectorPreferences.givenIsClientInfoRecordingEnabled(isEnabled = true)
        fakeNotificationsSettingUpdater.givenOnSessionStarted(aSession)
        fakePushRulesUpdater.givenOnSessionStarted(aSession)

        // When
        configureAndStartSessionUseCase.execute(aSession, startSyncing = false)
        advanceUntilIdle()

        // Then
        verify(inverse = true) { aSession.startSyncing(fakeContext.instance) }
        aSession.fakePushersService.verifyRefreshPushers()
        coVerify {
            fakeUpdateMatrixClientInfoUseCase.execute(aSession)
            fakeUpdateNotificationSettingsAccountDataUseCase.execute(aSession)
        }

        // Stop the long-lived profile observer so runTest doesn't see an uncompleted coroutine.
        configureAndStartSessionUseCase.cancelProfileObserver()
    }

    private fun givenASession(): FakeSession {
        val fakeSession = FakeSession()
        every { fakeSession.open() } just runs
        every { fakeSession.startSyncing(any()) } just runs
        fakeSession.fakePushersService.givenRefreshPushersSucceeds()
        return fakeSession
    }
}
