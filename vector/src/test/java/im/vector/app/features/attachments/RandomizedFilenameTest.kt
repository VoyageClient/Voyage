/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.content.ContentAttachmentData

class RandomizedFilenameTest {

    private fun attachment(name: String?, type: ContentAttachmentData.Type) = ContentAttachmentData(
            name = name,
            queryUri = "content://whatever",
            mimeType = null,
            type = type,
    )

    @Test
    fun `keeps the extension of a media file`() {
        val randomized = attachment("holiday.mp4", ContentAttachmentData.Type.VIDEO).withRandomizedFilename()

        randomized.name!!.endsWith(".mp4") shouldBeEqualTo true
        randomized.name shouldNotBeEqualTo "holiday.mp4"
    }

    @Test
    fun `keeps a compound extension of a plain file`() {
        val randomized = attachment("logs.tar.gz", ContentAttachmentData.Type.FILE).withRandomizedFilename()

        randomized.name!!.endsWith(".tar.gz") shouldBeEqualTo true
    }

    @Test
    fun `keeps only the last extension of a media file`() {
        val randomized = attachment("song.tar.mp3", ContentAttachmentData.Type.AUDIO).withRandomizedFilename()

        randomized.name!!.endsWith(".mp3") shouldBeEqualTo true
        randomized.name!!.contains(".tar.") shouldBeEqualTo false
    }

    @Test
    fun `leaves a voice message alone`() {
        val voice = attachment("Voice message.ogg", ContentAttachmentData.Type.VOICE_MESSAGE)

        voice.withRandomizedFilename() shouldBeEqualTo voice
    }

    @Test
    fun `leaves an unnamed attachment alone`() {
        val unnamed = attachment(null, ContentAttachmentData.Type.FILE)

        unnamed.withRandomizedFilename() shouldBeEqualTo unnamed
    }
}
