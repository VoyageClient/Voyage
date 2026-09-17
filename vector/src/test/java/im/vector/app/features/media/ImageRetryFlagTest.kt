/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.os.SystemClock
import android.widget.ImageView
import im.vector.app.R
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageRetryFlagTest {

    private val imageView = ImageView(RuntimeEnvironment.getApplication())

    private val imageContentRenderer = ImageContentRenderer(
            context = RuntimeEnvironment.getApplication(),
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            failedMediaTracker = mockk(relaxed = true),
    )

    @Test
    fun `a view with no retry in flight is tappable`() {
        imageContentRenderer.isRetrying(imageView) shouldBeEqualTo false
    }

    @Test
    fun `a retry just asked for holds taps`() {
        markRetrying()

        imageContentRenderer.isRetrying(imageView) shouldBeEqualTo true
    }

    @Test
    fun `a flag left behind stops holding taps once it cannot be in flight`() {
        markRetrying()

        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))

        imageContentRenderer.isRetrying(imageView) shouldBeEqualTo false
    }

    /** Asking twice must not resurrect it: the stale flag is cleared as it is read. */
    @Test
    fun `a stale flag is cleared, not just ignored`() {
        markRetrying()
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(30))

        imageContentRenderer.isRetrying(imageView)

        imageView.getTag(R.id.image_renderer_retrying) shouldBeEqualTo null
    }

    private fun markRetrying() {
        imageView.setTag(R.id.image_renderer_retrying, SystemClock.uptimeMillis())
    }
}
