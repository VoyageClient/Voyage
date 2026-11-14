/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.session.room.RoomAPI
import java.io.IOException

class ShadowBanDetectorTest {

    private val roomAPI = mockk<RoomAPI>()
    private val localEchoRepository = mockk<LocalEchoRepository> {
        coEvery { isRemoteEventStored(any(), any()) } returns false
    }
    private val detector = ShadowBanDetector(
            roomAPI, localEchoRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), "session"
    )

    private val notFound = Failure.ServerError(MatrixError(code = MatrixError.M_NOT_FOUND, message = "Could not find event"), 404)

    @Test
    fun `only letters-only 43 character ids look fabricated`() {
        ShadowBanDetector.looksFabricated("\$AbcdEfghIjklMnopQrstUvwxYzabCdefGhijKlmnOpq") shouldBe true
        ShadowBanDetector.looksFabricated("\$AbcdEfghIjklMnopQrstUvwxYzabCdefGhijKlmnOp0") shouldBe false
        ShadowBanDetector.looksFabricated("\$AbcdEfghIjklMnopQrstUvwxYzabCdefGhijKl-nOpq") shouldBe false
        ShadowBanDetector.looksFabricated("\$AbcdEfghIjklMnopQrstUvwxYzabCdefGhijKlmnOp") shouldBe false
        ShadowBanDetector.looksFabricated("\$abcdefghijklmnop:example.org") shouldBe false
        ShadowBanDetector.looksFabricated("\$local.00000000-0000-4000-8000-000000000001") shouldBe false
    }

    @Test
    fun `an event the server keeps answering M_NOT_FOUND for is missing`() = runTest {
        coEvery { roomAPI.getEvent(any(), any(), any()) } throws notFound

        detector.lookUp("!room:example.org", "\$id") shouldBe ShadowBanDetector.Lookup.MISSING
        coVerify(exactly = 2) { roomAPI.getEvent(any(), any(), any()) }
    }

    @Test
    fun `an event that shows up on the second check is found`() = runTest {
        coEvery { roomAPI.getEvent(any(), any(), any()) } throws notFound andThen Event(eventId = "\$id")

        detector.lookUp("!room:example.org", "\$id") shouldBe ShadowBanDetector.Lookup.FOUND
    }

    @Test
    fun `an event that already came down sync is found without asking the server`() = runTest {
        coEvery { localEchoRepository.isRemoteEventStored(any(), any()) } returns true

        detector.lookUp("!room:example.org", "\$id") shouldBe ShadowBanDetector.Lookup.FOUND
        coVerify(exactly = 0) { roomAPI.getEvent(any(), any(), any()) }
    }

    @Test
    fun `a network failure leaves the verdict unknown`() = runTest {
        coEvery { roomAPI.getEvent(any(), any(), any()) } throws IOException()

        detector.lookUp("!room:example.org", "\$id") shouldBe ShadowBanDetector.Lookup.UNKNOWN
    }

    @Test
    fun `a bare 404 without M_NOT_FOUND leaves the verdict unknown`() = runTest {
        coEvery { roomAPI.getEvent(any(), any(), any()) } throws Failure.ServerError(MatrixError(code = MatrixError.M_UNRECOGNIZED, message = ""), 404)

        detector.lookUp("!room:example.org", "\$id") shouldBe ShadowBanDetector.Lookup.UNKNOWN
    }
}
