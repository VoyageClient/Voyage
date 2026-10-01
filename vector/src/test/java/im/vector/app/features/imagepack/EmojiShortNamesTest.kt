/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmojiShortNamesTest {

    private val names = EmojiShortNames(RuntimeEnvironment.getApplication().resources)

    @Test
    fun `emoji are named from the bundled data regardless of variation selectors`() {
        names.nameFor("😠") shouldBeEqualTo "angry_face"
        names.nameFor("❤️") shouldBeEqualTo "red_heart"
        names.nameFor("❤") shouldBeEqualTo "red_heart"
        names.nameFor("not an emoji") shouldBeEqualTo null
    }

    @Test
    fun `search terms add the name of every emoji in the query`() {
        names.searchTerms("cat") shouldBeEqualTo listOf("cat")
        names.searchTerms("😠") shouldBeEqualTo listOf("😠", "angry_face")
        names.searchTerms("😠 ❤️‍🔥") shouldBeEqualTo listOf("😠 ❤️‍🔥", "angry_face", "heart_on_fire")
    }

    @Test
    fun `names in text cover emoji only`() {
        names.namesIn("😠") shouldBeEqualTo listOf("angry_face")
        names.namesIn("mad 😠!") shouldBeEqualTo listOf("angry_face")
        names.namesIn("angry_face") shouldBeEqualTo emptyList()
        names.namesIn(null) shouldBeEqualTo emptyList()
    }

    @Test
    fun `names compare case- and separator-insensitively`() {
        EmojiShortNames.nameContains("Angry-Face_2", "angry_face") shouldBeEqualTo true
        EmojiShortNames.nameContains("happy", "angry_face") shouldBeEqualTo false
        EmojiShortNames.nameContains(null, "angry_face") shouldBeEqualTo false
    }
}
