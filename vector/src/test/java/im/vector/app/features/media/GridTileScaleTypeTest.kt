/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.widget.ImageView
import im.vector.app.R
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GridTileScaleTypeTest {

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
            height = 300,
            maxHeight = 1080,
            width = 1000,
            maxWidth = 1080,
            stableId = "stable",
    )

    /** A failed load leaves FIT_XY on the view; the picture a later load draws must still be cropped. */
    @Test
    fun `a grid tile is cropped even after a failure stretched the view`() {
        val imageView = ImageView(RuntimeEnvironment.getApplication())
        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setTag(
                R.id.image_renderer_last_render,
                ImageContentRenderer.LastRender(data.stableId, data, ImageContentRenderer.Mode.THUMBNAIL, completed = true),
        )

        renderer.render(data, imageView, 200, 200)

        imageView.scaleType shouldBeEqualTo ImageView.ScaleType.CENTER_CROP
    }
}
