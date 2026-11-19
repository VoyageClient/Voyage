/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.read

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class MarkAllRoomsReadTaskTest {

    private val sent = mutableListOf<SetReadMarkersTask.Params>()
    private val readMarkersTask = mockk<SetReadMarkersTask> {
        coEvery { execute(any()) } answers { sent += firstArg<SetReadMarkersTask.Params>() }
    }

    @Test
    fun `only the listed rooms get a public receipt`() = runTest {
        DefaultMarkAllRoomsReadTask(readMarkersTask).execute(
                MarkAllRoomsReadTask.Params(
                        roomIds = listOf("!a:example.org", "!b:example.org"),
                        publicReceiptRoomIds = setOf("!b:example.org"),
                )
        )

        sent.associate { it.roomId to it.publicReadReceipt } shouldBeEqualTo mapOf(
                "!a:example.org" to false,
                "!b:example.org" to true,
        )
        sent.all { it.forceReadMarker && it.forceReadReceipt } shouldBeEqualTo true
    }
}
