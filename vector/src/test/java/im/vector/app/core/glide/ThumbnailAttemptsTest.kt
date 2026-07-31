/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

private const val STILL_URL = "https://example.org/thumbnail/media?width=250&height=250&method=scale"
private const val ANIMATED_URL = "$STILL_URL&animated=true"

class ThumbnailAttemptsTest {

    private val resolved = mutableListOf<Boolean>()

    private fun urlFor(animated: Boolean): String {
        resolved.add(animated)
        return if (animated) ANIMATED_URL else STILL_URL
    }

    @Test
    fun `autoplay downloads the animated thumbnail, and only that one`() {
        thumbnailAttempts(autoplay = true, urlFor = ::urlFor) shouldBeEqualTo listOf(
                ThumbnailAttempt(ANIMATED_URL, cacheOnly = false)
        )
    }

    @Test
    fun `without autoplay a cached thumbnail of either variant is reused before downloading`() {
        thumbnailAttempts(autoplay = false, urlFor = ::urlFor) shouldBeEqualTo listOf(
                ThumbnailAttempt(STILL_URL, cacheOnly = true),
                ThumbnailAttempt(ANIMATED_URL, cacheOnly = true),
                ThumbnailAttempt(STILL_URL, cacheOnly = false),
        )
    }

    @Test
    fun `without autoplay the still thumbnail wins over the first frame of the animated one`() {
        val attempts = thumbnailAttempts(autoplay = false, urlFor = ::urlFor)!!

        attempts.first() shouldBeEqualTo ThumbnailAttempt(STILL_URL, cacheOnly = true)
    }

    @Test
    fun `without autoplay the animated thumbnail is never downloaded`() {
        thumbnailAttempts(autoplay = false, urlFor = ::urlFor)!!
                .none { it.url == ANIMATED_URL && !it.cacheOnly } shouldBeEqualTo true
    }

    @Test
    fun `the last attempt is always allowed to download`() {
        listOf(true, false).forEach { autoplay ->
            thumbnailAttempts(autoplay, ::urlFor)!!.last().cacheOnly shouldBeEqualTo false
        }
    }

    @Test
    fun `autoplay never builds the still url it cannot use`() {
        thumbnailAttempts(autoplay = true, urlFor = ::urlFor)

        resolved shouldBeEqualTo listOf(true)
    }

    @Test
    fun `each variant is resolved once, however many attempts use it`() {
        thumbnailAttempts(autoplay = false, urlFor = ::urlFor)

        resolved.sorted() shouldBeEqualTo listOf(false, true)
    }

    @Test
    fun `an unresolvable variant leaves nothing to load`() {
        thumbnailAttempts(autoplay = false) { animated -> if (animated) null else STILL_URL }.shouldBeNull()
        thumbnailAttempts(autoplay = false) { animated -> if (animated) ANIMATED_URL else null }.shouldBeNull()
        thumbnailAttempts(autoplay = true) { null }.shouldBeNull()
    }

    @Test
    fun `an attempt falls back to the whole remainder of the chain, not just to its neighbour`() {
        val attempts = thumbnailAttempts(autoplay = false, urlFor = ::urlFor)!!

        val chain = chainAttempts(attempts, load = { it.url }, fallingBackTo = { url, fallback -> "($url or $fallback)" })

        // Right-associated: Glide's error() is a single slot, so a left fold would drop the middle attempt.
        chain shouldBeEqualTo "($STILL_URL or ($ANIMATED_URL or $STILL_URL))"
    }

    @Test
    fun `every attempt is loaded once, in order`() {
        val attempts = thumbnailAttempts(autoplay = false, urlFor = ::urlFor)!!
        val loaded = mutableListOf<String>()

        chainAttempts(attempts, load = { loaded.add(it.url) }, fallingBackTo = { _, _ -> true })

        loaded shouldBeEqualTo listOf(STILL_URL, ANIMATED_URL, STILL_URL)
    }

    @Test
    fun `a single attempt has nothing to fall back to`() {
        val attempts = thumbnailAttempts(autoplay = true, urlFor = ::urlFor)!!

        chainAttempts(attempts, load = { it.url }, fallingBackTo = { _, _ -> "unexpected fallback" }) shouldBeEqualTo ANIMATED_URL
    }
}
