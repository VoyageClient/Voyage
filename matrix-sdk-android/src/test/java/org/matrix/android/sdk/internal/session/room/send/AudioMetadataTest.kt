/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.AudioInfo
import org.matrix.android.sdk.api.session.room.model.message.AudioMetadata
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.internal.session.content.FakeThumbnailExtractor
import org.matrix.android.sdk.test.fakes.internal.session.permalinks.FakePermalinkFactory
import org.matrix.android.sdk.test.fakes.internal.session.room.send.FakeLocalEchoRepository
import org.matrix.android.sdk.test.fakes.internal.session.room.send.FakeMarkdownParser
import org.matrix.android.sdk.test.fakes.internal.session.room.send.pills.FakeTextPillsUtils

/** MSC4549: audio metadata on the wire and on the way out. */
class AudioMetadataTest {

    private val metadata = AudioMetadata(
            title = "Moonwalker",
            artist = "Jake Chudnow",
            album = "The Moon",
            coverArtBlurhash = "LBEpAr~VM{x[004:oyM|9GM|xtIU",
    )

    private val adapter = MoshiProvider.providesMoshi().adapter(AudioInfo::class.java)

    @Test
    fun `stable audio_metadata is read`() {
        val info = adapter.fromJson(
                """{"duration":180000,"audio_metadata":{"title":"Moonwalker","artist":"Jake Chudnow"}}"""
        )!!

        info.metadata?.title shouldBeEqualTo "Moonwalker"
        info.metadata?.artist shouldBeEqualTo "Jake Chudnow"
    }

    @Test
    fun `the unstable prefix is read when the stable key is absent`() {
        val info = adapter.fromJson(
                """{"org.matrix.msc4549.audio_metadata":{"title":"Moonwalker"}}"""
        )!!

        info.metadata?.title shouldBeEqualTo "Moonwalker"
    }

    @Test
    fun `the stable key wins when both are present`() {
        val info = adapter.fromJson(
                """{
                    "audio_metadata":{"title":"stable"},
                    "org.matrix.msc4549.audio_metadata":{"title":"unstable"}
                }"""
        )!!

        info.metadata?.title shouldBeEqualTo "stable"
    }

    @Test
    fun `an empty metadata object reads as nothing`() {
        adapter.fromJson("""{"audio_metadata":{}}""")!!.metadata.shouldBeNull()
        adapter.fromJson("""{"duration":1000}""")!!.metadata.shouldBeNull()
    }

    @Test
    fun `cover_art_blurhash is read`() {
        val info = adapter.fromJson("""{"audio_metadata":{"cover_art_blurhash":"LBEpAr~VM{x[004:oyM|9GM|xtIU"}}""")!!

        info.metadata?.coverArtBlurhash shouldBeEqualTo "LBEpAr~VM{x[004:oyM|9GM|xtIU"
        info.metadata?.coverArtUrl.shouldBeNull()
    }

    @Test
    fun `an unencrypted cover art image is read with its info`() {
        val metadata = adapter.fromJson(
                """{"audio_metadata":{
                    "cover_art":"mxc://example.org/def456",
                    "cover_art_info":{"w":600,"h":600,"mimetype":"image/jpeg","size":48000}
                }}"""
        )!!.metadata!!

        metadata.coverArtUrl shouldBeEqualTo "mxc://example.org/def456"
        metadata.coverArtElementToDecrypt.shouldBeNull()
        metadata.coverArtInfo?.mimeType shouldBeEqualTo "image/jpeg"
        metadata.coverArtInfo?.size shouldBeEqualTo 48000L
    }

    @Test
    fun `an encrypted cover art image is read`() {
        val metadata = adapter.fromJson(
                """{"audio_metadata":{"cover_art_file":{
                    "url":"mxc://example.org/enc789",
                    "key":{"alg":"A256CTR","ext":true,"k":"aWF6Mzk2Mm1idzZ2NG5vM3pzZWFpcmRlMGxzd3ZnY2I","key_ops":["encrypt","decrypt"],"kty":"oct"},
                    "iv":"w+sE15fzSc0AAAAAAAAAAA",
                    "hashes":{"sha256":"fdSLu/YkRx3Wyh3KQabP3rd6+SFiKg5lsJZQHtkSAYA"},
                    "v":"v2"
                }}}"""
        )!!.metadata!!

        metadata.coverArtUrl shouldBeEqualTo "mxc://example.org/enc789"
        metadata.coverArtElementToDecrypt?.iv shouldBeEqualTo "w+sE15fzSc0AAAAAAAAAAA"
    }

    @Test
    fun `a cover art image that is not a well-formed mxc URI is ignored`() {
        listOf(
                "https://example.org/cover.jpg",
                "mxc://",
                "mxc://example.org",
                "mxc://example.org/",
                "mxc:///def456",
                "mxc://example.org/def/456",
                "mxc://example.org/def456?download=1",
                "mxc://exa mple.org/def456",
        ).forEach { url ->
            adapter.fromJson("""{"audio_metadata":{"cover_art":"$url"}}""")!!.metadata.shouldBeNull()
            adapter.fromJson(encryptedCoverArt(url))!!.metadata.shouldBeNull()
        }
    }

    @Test
    fun `server names with ports and IP literals are accepted`() {
        listOf("mxc://example.org:8448/def456", "mxc://[::1]:8448/a_b-C", "mxc://127.0.0.1/x").forEach { url ->
            adapter.fromJson("""{"audio_metadata":{"cover_art":"$url"}}""")!!.metadata?.coverArtUrl shouldBeEqualTo url
            adapter.fromJson(encryptedCoverArt(url))!!.metadata?.coverArtUrl shouldBeEqualTo url
        }
    }

    private fun encryptedCoverArt(url: String) = """{"audio_metadata":{"cover_art_file":{
            "url":"$url",
            "key":{"alg":"A256CTR","ext":true,"k":"aWF6Mzk2Mm1idzZ2NG5vM3pzZWFpcmRlMGxzd3ZnY2I","key_ops":["encrypt","decrypt"],"kty":"oct"},
            "iv":"w+sE15fzSc0AAAAAAAAAAA",
            "hashes":{"sha256":"fdSLu/YkRx3Wyh3KQabP3rd6+SFiKg5lsJZQHtkSAYA"},
            "v":"v2"
        }}}"""

    @Test
    fun `only the cover art BlurHash goes on the wire`() {
        val json = MoshiProvider.providesMoshi().adapter(AudioMetadata::class.java).toJson(metadata)

        json.contains("\"cover_art_blurhash\":\"LBEpAr~VM{x[004:oyM|9GM|xtIU\"") shouldBeEqualTo true
        json.contains("\"cover_art\"") shouldBeEqualTo false
        json.contains("coverArtUrl") shouldBeEqualTo false
    }

    @Test
    fun `sending an audio file writes both prefixes`() {
        val content = anAudioEvent(factory()).content.toModel<MessageAudioContent>()!!

        content.audioInfo?.audioMetadata shouldBeEqualTo metadata
        content.audioInfo?.unstableAudioMetadata shouldBeEqualTo metadata
    }

    /**
     * Scrubbing only takes location off audio, deliberately leaving the tags and the cover on the
     * file, so what the MSC carries is still true of the bytes that go out.
     */
    @Test
    fun `metadata survives a scrubbed send`() {
        val content = anAudioEvent(factory(), stripMetadata = true).content.toModel<MessageAudioContent>()!!

        content.audioInfo?.audioMetadata shouldBeEqualTo metadata
    }

    @Test
    fun `a voice message carries no metadata`() {
        val event = factory().createMediaEvent(
                roomId = A_ROOM_ID,
                attachment = anAttachment(ContentAttachmentData.Type.VOICE_MESSAGE),
                rootThreadEventId = null,
                relatesTo = null,
        )

        event.content.toModel<MessageAudioContent>()!!.audioInfo?.audioMetadata.shouldBeNull()
    }

    private fun anAudioEvent(factory: LocalEchoEventFactory, stripMetadata: Boolean? = null) = factory.createMediaEvent(
            roomId = A_ROOM_ID,
            attachment = anAttachment(ContentAttachmentData.Type.AUDIO, stripMetadata),
            rootThreadEventId = null,
            relatesTo = null,
    )

    private fun anAttachment(type: ContentAttachmentData.Type, stripMetadata: Boolean? = null) = ContentAttachmentData(
            size = 1024,
            name = "moonwalker.flac",
            queryUri = "content://media/1",
            mimeType = "audio/flac",
            type = type,
            stripMetadata = stripMetadata,
    )

    private fun factory() = LocalEchoEventFactory(
            videoMetadataExtractor = VideoMetadataExtractor { 0 to 0 },
            audioMetadataExtractor = AudioMetadataExtractor { metadata },
            userId = A_USER_ID,
            markdownParser = FakeMarkdownParser().instance,
            textPillsUtils = FakeTextPillsUtils().instance,
            thumbnailExtractor = FakeThumbnailExtractor().instance,
            waveformSanitizer = mockk { every { sanitize(any()) } returns null },
            localEchoRepository = FakeLocalEchoRepository().instance,
            permalinkFactory = FakePermalinkFactory().instance,
            clock = FakeClock().also { it.givenEpoch(1655210176L) },
    )

    companion object {
        private const val A_USER_ID = "@user_1:matrix.org"
        private const val A_ROOM_ID = "!sUeOGZKsBValPTUMax:matrix.org"
    }
}
