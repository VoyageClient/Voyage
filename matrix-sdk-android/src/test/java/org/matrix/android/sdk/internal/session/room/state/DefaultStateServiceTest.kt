/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.state

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent

class DefaultStateServiceTest {

    private val sendStateTask = mockk<SendStateTask>()

    private val stateService = DefaultStateService(
            roomId = "!room:example.org",
            userId = "@alice:example.org",
            stateEventDataSource = mockk(),
            sendStateTask = sendStateTask,
            fileUploader = mockk(),
    )

    private fun sendTopic(topic: String, formattedTopic: String?): RoomTopicContent? {
        val params = slot<SendStateTask.Params>()
        coEvery { sendStateTask.executeRetry(capture(params), any()) } returns "\$event"
        runBlocking { stateService.updateTopic(topic, formattedTopic) }
        return params.captured.body.toModel<RoomTopicContent>()
    }

    @Test
    fun `the legacy field holds a plain-text rendering, not the markdown source`() {
        val content = sendTopic(
                topic = "All about **pizza**",
                formattedTopic = "<p>All about <b>pizza</b></p>"
        )

        assertEquals("All about pizza", content?.topic)
        assertEquals("All about **pizza**", content?.getTopicSource())
        assertEquals("<p>All about <b>pizza</b></p>", content?.getBestFormattedTopic())
    }

    @Test
    fun `a topic without formatting is sent verbatim`() {
        val content = sendTopic(topic = "Kernel talk", formattedTopic = null)

        assertEquals("Kernel talk", content?.topic)
        assertEquals("Kernel talk", content?.getTopicSource())
    }

    @Test
    fun `clearing the topic sends an empty legacy field and no topic block`() {
        val content = sendTopic(topic = "", formattedTopic = null)

        assertEquals("", content?.topic)
        assertNull(content?.getBestFormattedTopic())
    }
}
