/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FailedMediaUploadRecoveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val repository = mockk<LocalEchoRepository>(relaxed = true)
    private val factory = mockk<LocalEchoEventFactory>(relaxed = true)
    private val processor = mockk<EventSenderProcessor>(relaxed = true)
    private val recovery by lazy {
        FailedMediaUploadRecovery(folder.root, repository, Lazy { factory }, Lazy { processor })
    }

    private val createdContent = slot<Content>()

    init {
        every { factory.createEvent(any(), any(), capture(createdContent), any(), any()) } answers {
            Event(eventId = "\$local.replacement", roomId = ROOM, type = secondArg())
        }
        every { factory.createRedactEvent(any(), any(), any(), any(), any()) } answers {
            Event(eventId = "\$local.redaction", roomId = ROOM, type = "m.room.redaction")
        }
        every { factory.createLocalEcho(any()) } just runs
        coEvery { repository.getUpToDateEcho(ECHO) } returns Event(
                eventId = ECHO,
                roomId = ROOM,
                type = "m.room.message",
                content = mapOf("msgtype" to "m.image", "body" to "a.png", "url" to "mxc://server/a"),
        )
    }

    private fun refuse(contentUri: String = "mxc://server/a"): File {
        val bytes = folder.newFile().apply { writeText("bytes") }
        val params = UploadMediaBytesWorkerParams(
                sessionId = "session",
                localEchoIds = listOf(LocalEchoIdentifiers(ROOM, ECHO)),
                contentUri = contentUri,
                filename = "a.png",
                mimeType = "image/png",
                isEncrypted = false,
                clearFilePath = bytes.path,
                encryptedFilePath = null,
        )
        assertTrue(runBlocking { recovery.onBytesRefused(params, bytes, Throwable("Media upload limit exceeded")) })
        return bytes
    }

    @Test
    fun `refused bytes are kept and the url points at them`() {
        every { repository.getRemoteEchoId(ECHO) } returns "\$remote"
        val bytes = refuse()
        assertFalse(bytes.exists())
        val local = createdContent.captured["url"] as String
        assertTrue(recovery.isPreserved(local))
        recovery.discardPreserved(local)
        assertFalse(recovery.isPreserved(local))
    }

    @Test
    fun `an echo not sent yet becomes the failed message itself`() {
        every { repository.getRemoteEchoId(ECHO) } returns null
        refuse()
        coVerify { repository.updateEcho(ECHO, any()) }
        verify { repository.updateSendState(ECHO, ROOM, SendState.UNDELIVERED, any()) }
        verify(exactly = 0) { factory.createEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a sent event is redacted and replaced by a failed copy`() {
        every { repository.getRemoteEchoId(ECHO) } returns "\$remote"
        refuse()
        verify { repository.updateSendState("\$local.replacement", ROOM, SendState.UNDELIVERED, any()) }
        verify { factory.createRedactEvent(ROOM, "\$remote", null, any(), any()) }
        verify { processor.postRedaction(match<Event> { it.eventId == "\$local.redaction" }, null, any()) }
        assertTrue((createdContent.captured["url"] as String).startsWith("file:"))
    }

    @Test
    fun `a send landing after the refusal is taken back`() {
        every { repository.getRemoteEchoId(ECHO) } returns null
        refuse()
        runBlocking { recovery.onEventSent(ECHO, "\$remote") }
        verify { factory.createRedactEvent(ROOM, "\$remote", null, any(), any()) }
        verify { repository.updateSendState("\$local.replacement", ROOM, SendState.UNDELIVERED, any()) }
    }

    @Test
    fun `a later refused item updates the failed copy instead of replacing again`() {
        every { repository.getRemoteEchoId(ECHO) } returns "\$remote"
        refuse("mxc://server/a")
        refuse("mxc://server/b")
        verify(exactly = 1) { factory.createEvent(any(), any(), any(), any(), any()) }
        coVerify { repository.updateEcho("\$local.replacement", any()) }
    }

    @Test
    fun `sends nobody refused are left alone`() {
        runBlocking { recovery.onEventSent(ECHO, "\$remote") }
        verify(exactly = 0) { factory.createRedactEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `refused urls are rewritten in plain and encrypted gallery items`() {
        val content: Content = mapOf(
                "msgtype" to "m.gallery",
                "itemtypes" to listOf(
                        mapOf("itemtype" to "m.image", "url" to "mxc://server/a"),
                        mapOf("itemtype" to "m.image", "file" to mapOf("url" to "mxc://server/b", "key" to mapOf("k" to "x"))),
                        mapOf("itemtype" to "m.image", "url" to "mxc://server/kept"),
                ),
        )
        val rewritten = FailedMediaUploadRecovery.withLocalUrls(
                content,
                mapOf("mxc://server/a" to "file:///kept/a", "mxc://server/b" to "file:///kept/b"),
        )!!
        @Suppress("UNCHECKED_CAST")
        val items = rewritten["itemtypes"] as List<Map<String, Any?>>
        assertEquals("file:///kept/a", items[0]["url"])
        assertEquals("file:///kept/b", items[1]["url"])
        assertFalse(items[1].containsKey("file"))
        assertEquals("mxc://server/kept", items[2]["url"])
    }

    private companion object {
        const val ROOM = "!room:server"
        const val ECHO = "\$local.echo"
    }
}
