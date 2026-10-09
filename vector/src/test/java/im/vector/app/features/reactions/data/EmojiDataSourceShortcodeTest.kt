/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import im.vector.app.features.emoji.TwemojiProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmojiDataSourceShortcodeTest {

    private val twemojiProvider = mockk<TwemojiProvider> {
        every { enabled } returns true
        every { hasEmoji(any()) } returns true
    }
    private val dataSource = EmojiDataSource(CoroutineScope(Dispatchers.Unconfined), RuntimeEnvironment.getApplication().resources, twemojiProvider)

    @Test
    fun `every emoji carries a shortcode`() = runBlocking<Unit> {
        val emojis = dataSource.rawData.await().emojis.values
        emojis.all { it.shortcode.matches(Regex("[a-z0-9_+-]+")) } shouldBeEqualTo true
        emojis.flatMap { it.shortcodes }.let { it.size shouldBeEqualTo it.toSet().size }
    }

    @Test
    fun `the first shortcode is the one shown`() = runBlocking<Unit> {
        dataSource.itemFor("👍")?.shortcode shouldBeEqualTo "thumbsup"
    }

    @Test
    fun `any shortcode finds its emoji, ignoring underscores and the emoji suffix`() = runBlocking<Unit> {
        dataSource.filterWith("+1").map { it.emoji } shouldContain "👍️"
        dataSource.filterWith("fingerscross").map { it.emoji } shouldContain "🤞"
        dataSource.filterWith("fire@emoji").map { it.emoji } shouldContain "🔥"
    }
}
