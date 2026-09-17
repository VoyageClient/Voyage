/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingRenderKeepTest {

    private val renderer = ImageContentRenderer(
            context = RuntimeEnvironment.getApplication(),
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            failedMediaTracker = mockk(relaxed = true),
    )

    private val data = ImageContentRenderer.Data(
            eventId = "\$event",
            filename = "picture.jpg",
            mimeType = "image/jpeg",
            url = "mxc://hs/picture",
            elementToDecrypt = null,
            height = 217,
            maxHeight = 1080,
            width = 1080,
            maxWidth = 1080,
            stableId = "stable",
    )

    private fun pending(
            data: ImageContentRenderer.Data = this.data,
            mode: ImageContentRenderer.Mode = ImageContentRenderer.Mode.THUMBNAIL,
            completed: Boolean = false,
    ) = ImageContentRenderer.LastRender(data.stableId, data, mode, completed)

    private fun keeps(
            last: ImageContentRenderer.LastRender?,
            data: ImageContentRenderer.Data = this.data,
            mode: ImageContentRenderer.Mode = ImageContentRenderer.Mode.THUMBNAIL,
            fromRetryTap: Boolean = false,
    ) = renderer.keepsPendingRender(last, data, mode, fromRetryTap)

    /** Restarting it drops the result it is about to deliver, leaving the placeholder up. */
    @Test
    fun `an identical rebind leaves a render in flight alone`() {
        keeps(pending()) shouldBeEqualTo true
    }

    @Test
    fun `a render that has had time to wedge is restarted`() {
        val last = pending()

        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))

        keeps(last) shouldBeEqualTo false
    }

    @Test
    fun `a finished render is not a pending one`() {
        keeps(pending(completed = true)) shouldBeEqualTo false
    }

    @Test
    fun `nothing rendered yet is not a pending render`() {
        keeps(null) shouldBeEqualTo false
    }

    @Test
    fun `a retry tap restarts whatever is in flight`() {
        keeps(pending(), fromRetryTap = true) shouldBeEqualTo false
    }

    @Test
    fun `another picture in the same view restarts`() {
        keeps(pending(data = data.copy(url = "mxc://hs/other"))) shouldBeEqualTo false
    }

    @Test
    fun `another mode restarts`() {
        keeps(pending(mode = ImageContentRenderer.Mode.STICKER)) shouldBeEqualTo false
    }

    /** The local echo swap keeps the picture and the stable id, so its request is still the right one. */
    @Test
    fun `the local echo to remote swap leaves the render alone`() {
        keeps(pending(), data = data.copy(eventId = "\$remote")) shouldBeEqualTo true
    }

    @Test
    fun `a different message in a recycled view restarts`() {
        keeps(pending(data = data.copy(stableId = "other"))) shouldBeEqualTo false
    }
}
