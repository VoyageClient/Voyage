/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImagePackDisambiguationTest {

    private val provider = ImagePackProvider(mockk(), mockk(), EmojiShortNames(RuntimeEnvironment.getApplication().resources))

    private fun anEmote(shortcode: String) = ResolvedImage(
            shortcode = shortcode,
            mxcUrl = "mxc://example.org/$shortcode",
            body = null,
            info = null,
            usages = setOf(ImagePackUsage.EMOTICON),
            packDisplayName = null,
    )

    private fun aPack(source: ImagePackSource, name: String?, vararg shortcodes: String) = ResolvedImagePack(
            source = source,
            roomId = if (source == ImagePackSource.ACCOUNT) null else "!room:example.org",
            stateKey = name,
            displayName = name,
            avatarUrl = null,
            images = shortcodes.map { anEmote(it) },
    )

    private fun shortcodesOf(vararg packs: ResolvedImagePack) = provider.enabledPacksOf(packs.toList()).flatMap { pack -> pack.images.map { it.shortcode } }

    @Test
    fun `an emote sharing a unicode emoji shortcode is disambiguated like a duplicate`() {
        shortcodesOf(aPack(ImagePackSource.GLOBAL_ROOM, "Cats", "fire", "blobcat")) shouldBeEqualTo listOf("fire/cats", "blobcat")
        shortcodesOf(aPack(ImagePackSource.ACCOUNT, null, "fire")) shouldBeEqualTo listOf("fire@personal")
    }

    @Test
    fun `an alias counts as taken too`() {
        shortcodesOf(aPack(ImagePackSource.GLOBAL_ROOM, "Hands", "thumbs_up", "crossed_fingers")) shouldBeEqualTo listOf("thumbs_up/hands", "crossed_fingers")
    }
}
