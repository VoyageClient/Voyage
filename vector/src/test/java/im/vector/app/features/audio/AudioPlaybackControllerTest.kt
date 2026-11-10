/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.settings.VectorPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.RoomService
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

private const val A_ROOM_ID = "!room:example.org"
private const val A_TRANSACTION_ID = "\$local.0123"
private const val A_REMOTE_EVENT_ID = "\$remote"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioPlaybackControllerTest {

    private val timelineService = mockk<TimelineService> {
        every { getTimelineEvent(any()) } returns null
        every { createTimeline(any(), any()) } returns mockk<Timeline>(relaxed = true)
    }
    private val fileService = mockk<FileService>(relaxed = true)
    private val session = mockk<Session> {
        every { roomService() } returns mockk<RoomService> {
            every { getRoom(A_ROOM_ID) } returns mockk<Room> { every { timelineService() } returns timelineService }
        }
        every { fileService() } returns fileService
    }
    private lateinit var controller: AudioPlaybackController

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        controller = AudioPlaybackController(
                RuntimeEnvironment.getApplication(),
                AudioMessagePlaybackTracker(),
                mockk<ActiveSessionHolder> { coEvery { getOrInitializeSession() } returns session },
                mockk<VectorPreferences> { every { loopVideos() } returns false },
                mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        controller.stop()
        Dispatchers.resetMain()
    }

    private fun voice(url: String) = MessageAudioContent(
            msgType = MessageType.MSGTYPE_AUDIO,
            body = "Voice message.ogg",
            url = url,
            voiceMessageIndicator = emptyMap(),
    )

    private fun awaitState(predicate: (AudioPlaybackController.State?) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate(controller.state.value)) {
            check(System.currentTimeMillis() < deadline) { "State never matched: ${controller.state.value}" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a local echo opens the playlist at the live edge`() {
        controller.playOrPause(A_ROOM_ID, A_TRANSACTION_ID, voice("mxc://server/media"), A_TRANSACTION_ID)

        verify { timelineService.createTimeline(null, any()) }
    }

    @Test
    fun `a voice message still uploading does not play`() {
        controller.playOrPause(A_ROOM_ID, A_TRANSACTION_ID, voice("content://app.fileProvider/voice.ogg"), A_TRANSACTION_ID)

        controller.state.value shouldBeEqualTo null
        verify(exactly = 0) { timelineService.createTimeline(any(), any()) }
    }

    @Test
    fun `a sent message anchors the playlist on itself`() {
        controller.playOrPause(A_ROOM_ID, A_TRANSACTION_ID, voice("mxc://server/media"), A_REMOTE_EVENT_ID)

        verify { timelineService.createTimeline(A_REMOTE_EVENT_ID, any()) }
    }

    @Test
    fun `retrying a failed track plays the content it was tapped with`() {
        coEvery { fileService.downloadFile(any<MessageWithAttachmentContent>()) } throws IOException("offline")
        controller.playOrPause(A_ROOM_ID, A_TRANSACTION_ID, voice("mxc://server/old"), A_TRANSACTION_ID)
        awaitState { it?.error == true }

        val uploaded = voice("mxc://server/new")
        controller.playOrPause(A_ROOM_ID, A_TRANSACTION_ID, uploaded, A_TRANSACTION_ID)

        coVerify(timeout = 5_000) { fileService.downloadFile(uploaded) }
        controller.state.value?.track?.content shouldBeEqualTo uploaded
    }
}
