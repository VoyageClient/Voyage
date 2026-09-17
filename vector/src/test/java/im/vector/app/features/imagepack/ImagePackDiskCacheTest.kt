/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImagePackDiskCacheTest {

    private val context = RuntimeEnvironment.getApplication()
    private val cache = ImagePackDiskCache(context)

    private val pack = ResolvedImagePack(
            source = ImagePackSource.CURRENT_ROOM,
            roomId = "!room:hs",
            stateKey = "pack",
            displayName = "Pack",
            avatarUrl = "mxc://hs/avatar",
            images = listOf(
                    ResolvedImage(
                            shortcode = "wave",
                            mxcUrl = "mxc://hs/wave",
                            body = "wave",
                            info = ImageInfo(width = 128, height = 128, mimeType = "image/webp"),
                            usages = setOf(ImagePackUsage.STICKER, ImagePackUsage.EMOTICON),
                            packDisplayName = "Pack",
                    )
            ),
    )

    @Test
    fun `packs survive a round trip`() {
        cache.write("session|!room:hs", listOf(pack))

        cache.read("session|!room:hs") shouldBeEqualTo listOf(pack)
    }

    @Test
    fun `nothing stored reads as null`() {
        cache.read("session|!never:hs").shouldBeNull()
    }

    /** Two accounts, or two rooms, must not read each other's packs. */
    @Test
    fun `each key keeps its own packs`() {
        cache.write("session-a|!room:hs", listOf(pack))
        cache.write("session-b|!room:hs", emptyList())

        cache.read("session-a|!room:hs") shouldBeEqualTo listOf(pack)
        cache.read("session-b|!room:hs") shouldBeEqualTo emptyList()
    }

    @Test
    fun `a rewrite replaces what was there`() {
        cache.write("session|!room:hs", listOf(pack))
        cache.write("session|!room:hs", listOf(pack.copy(displayName = "Renamed")))

        cache.read("session|!room:hs") shouldBeEqualTo listOf(pack.copy(displayName = "Renamed"))
    }

    @Test
    fun `a corrupt file reads as null rather than throwing`() {
        cache.write("session|!room:hs", listOf(pack))
        File(context.cacheDir, "imagepacks").listFiles()?.forEach { it.writeText("{not json") }

        cache.read("session|!room:hs").shouldBeNull()
    }
}
