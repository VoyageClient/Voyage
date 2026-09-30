/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.test.fakes.FakeActiveSessionDataSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.SessionAccountDataService
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecentEmojiDataSourceTest {

    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun `given the homeserver is unreachable, when writing recents, then the failure does not crash the app`() {
        val attempted = CountDownLatch(1)
        val accountDataService = mockk<SessionAccountDataService> {
            coEvery { updateUserAccountData(any(), any()) } answers {
                attempted.countDown()
                throw Failure.NetworkConnection(UnknownHostException("Unable to resolve host"))
            }
        }
        val session = mockk<Session> {
            every { sessionId } returns "session"
            every { accountDataService() } returns accountDataService
        }
        val activeSession = FakeActiveSessionDataSource().apply { setActiveSession(session) }

        RecentEmojiDataSource(activeSession.instance).clear()

        attempted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        // Let the failed coroutine finish completing before checking for an escaped exception.
        Thread.sleep(200)
        uncaught shouldBeEqualTo emptyList()
    }
}
