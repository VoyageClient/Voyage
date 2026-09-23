/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.room.send.SendState

class SendOutcomeTrackerTest {

    @Test
    fun `await resolves with the terminal send state`() = runTest {
        val tracker = SendOutcomeTracker()
        tracker.track("$1")

        val outcome = async { tracker.await("$1", 10_000) }
        tracker.onSendStateChanged("$1", SendState.SENDING)
        tracker.onSendStateChanged("$1", SendState.SENT)

        outcome.await() shouldBe SendState.SENT
    }

    @Test
    fun `await resolves with the failure`() = runTest {
        val tracker = SendOutcomeTracker()
        tracker.track("$1")

        val outcome = async { tracker.await("$1", 10_000) }
        tracker.onSendStateChanged("$1", SendState.UNDELIVERED)

        outcome.await() shouldBe SendState.UNDELIVERED
    }

    @Test
    fun `await resolves when the echo is deleted`() = runTest {
        val tracker = SendOutcomeTracker()
        tracker.track("$1")

        val outcome = async { tracker.await("$1", 10_000) }
        tracker.onEchoDeleted("$1")

        outcome.await() shouldBe SendState.UNKNOWN
    }

    @Test
    fun `an outcome landing before the await is not missed`() = runTest {
        val tracker = SendOutcomeTracker()
        tracker.track("$1")
        tracker.onSendStateChanged("$1", SendState.SENT)

        tracker.await("$1", 10_000) shouldBe SendState.SENT
    }

    @Test
    fun `await of an untracked event returns immediately`() = runTest {
        SendOutcomeTracker().await("$1", 10_000) shouldBe SendState.UNKNOWN
    }

    @Test
    fun `await times out while the send stays pending`() = runTest {
        val tracker = SendOutcomeTracker()
        tracker.track("$1")
        tracker.onSendStateChanged("$1", SendState.SENDING)

        tracker.await("$1", 10_000) shouldBe null
    }

    @Test
    fun `a second resend batch for the same room is refused until the first ends`() {
        val tracker = SendOutcomeTracker()

        tracker.startResendBatch("!a") shouldBe true
        tracker.startResendBatch("!a") shouldBe false
        tracker.startResendBatch("!b") shouldBe true

        tracker.endResendBatch("!a")
        tracker.startResendBatch("!a") shouldBe true
    }
}
