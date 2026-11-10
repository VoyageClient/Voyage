/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.voice

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceDraftCaptionTest {

    private val recording = ContentAttachmentData(
            size = 4_096,
            duration = 7_000,
            name = "Voice message.ogg",
            queryUri = "content://app.fileProvider/voice.ogg",
            mimeType = "audio/ogg",
            type = ContentAttachmentData.Type.AUDIO,
            waveform = listOf(1, 5, 9),
    )

    @Test
    fun `a caption survives the draft and leaves the recording readable`() {
        val draft = VoiceDraftCaption.write(recording.toJsonString(), "Train's delayed")

        VoiceDraftCaption.read(draft) shouldBeEqualTo "Train's delayed"
        ContentAttachmentData.fromJsonString(draft) shouldBeEqualTo recording
    }

    @Test
    fun `no caption leaves the draft untouched`() {
        val json = recording.toJsonString()

        VoiceDraftCaption.write(json, "  ") shouldBeEqualTo json
        VoiceDraftCaption.read(json) shouldBeEqualTo null
    }
}
