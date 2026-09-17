/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val A_SESSION_ID = "@alice:example.org"
private const val ANOTHER_SESSION_ID = "@bob:example.org"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PushRequestStoreTest {

    private lateinit var store: PushRequestStore

    @Before
    fun setup() {
        store = PushRequestStore(RuntimeEnvironment.getApplication())
        store.clear()
    }

    private fun aRequest(
            eventId: String = "\$anEvent",
            sessionId: String = A_SESSION_ID,
            pushDate: Long = 1_000L,
            status: PushRequestStatus = PushRequestStatus.PENDING,
            retries: Int = 0,
    ) = PushRequest(
            eventId = eventId,
            roomId = "!aRoom:example.org",
            sessionId = sessionId,
            pushDate = pushDate,
            providerInfo = "UnifiedPush - ntfy",
            status = status,
            retries = retries,
    )

    @Test
    fun `given a pending request, when reading the pending ones, then it is returned`() {
        store.insertOrUpdate(aRequest())

        store.getPending(A_SESSION_ID, since = 0L).map { it.eventId } shouldBeEqualTo listOf("\$anEvent")
    }

    @Test
    fun `given a request for another session, when reading the pending ones, then it is not returned`() {
        store.insertOrUpdate(aRequest(sessionId = ANOTHER_SESSION_ID))

        store.getPending(A_SESSION_ID, since = 0L) shouldBeEqualTo emptyList()
    }

    @Test
    fun `given a delivered request, when reading the pending ones, then it is not returned`() {
        store.insertOrUpdate(aRequest(status = PushRequestStatus.SUCCESS))

        store.getPending(A_SESSION_ID, since = 0L) shouldBeEqualTo emptyList()
    }

    @Test
    fun `given a request older than the window, when reading the pending ones, then it is not returned`() {
        store.insertOrUpdate(aRequest(pushDate = 500L))

        store.getPending(A_SESSION_ID, since = 1_000L) shouldBeEqualTo emptyList()
    }

    @Test
    fun `given the same event twice, when reading the history, then the last write wins`() {
        store.insertOrUpdate(aRequest())
        store.insertOrUpdate(aRequest(status = PushRequestStatus.SUCCESS, retries = 2))

        val history = store.getRecent(10)
        history.size shouldBeEqualTo 1
        history.first().status shouldBeEqualTo PushRequestStatus.SUCCESS
        history.first().retries shouldBeEqualTo 2
    }

    @Test
    fun `given several requests, when reading the history, then the newest comes first`() {
        store.insertOrUpdate(aRequest(eventId = "\$older", pushDate = 1_000L))
        store.insertOrUpdate(aRequest(eventId = "\$newer", pushDate = 2_000L))

        store.getRecent(10).map { it.eventId } shouldBeEqualTo listOf("\$newer", "\$older")
    }

    @Test
    fun `given old requests, when removing them, then only the recent ones remain`() {
        store.insertOrUpdate(aRequest(eventId = "\$older", pushDate = 1_000L))
        store.insertOrUpdate(aRequest(eventId = "\$newer", pushDate = 2_000L))

        store.removeOlderThan(1_500L)

        store.getRecent(10).map { it.eventId } shouldBeEqualTo listOf("\$newer")
    }
}
