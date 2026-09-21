/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import im.vector.app.core.ui.model.Size
import im.vector.app.core.utils.DimensionConverter
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "xhdpi")
class ImageBoxedSizeTest {

    private val application = RuntimeEnvironment.getApplication()

    private val imageContentRenderer = ImageContentRenderer(
            context = application,
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = DimensionConverter(application.resources),
            vectorPreferences = mockk(relaxed = true),
            failedMediaTracker = mockk(relaxed = true),
    )

    private val maxWidth = 756
    private val maxHeight = 960

    private fun thumbnail(width: Int, height: Int, declaredInDp: Boolean = false): Size? =
            imageContentRenderer.boxedSize(width, height, ImageContentRenderer.Mode.THUMBNAIL, maxWidth, maxHeight, declaredInDp)

    /** A picture smaller than the box is drawn at the box's width, not at its own pixel size. */
    @Test
    fun `a small picture fills the box`() {
        thumbnail(400, 300) shouldBeEqualTo Size(756, 567)
    }

    @Test
    fun `measured pixels and declared dimensions of the same shape land on the same box`() {
        thumbnail(400, 300) shouldBeEqualTo thumbnail(400, 300, declaredInDp = true)
    }

    @Test
    fun `a tall picture is capped by the box's height`() {
        thumbnail(300, 900) shouldBeEqualTo Size(320, 960)
    }

    @Test
    fun `unusable dimensions have no box`() {
        thumbnail(0, 300).shouldBeNull()
        thumbnail(400, -1).shouldBeNull()
    }

    /** A sticker declares dp, so the same numbers as measured pixels are a smaller picture. */
    @Test
    fun `a sticker's declared size is read as dp, its measured size is not`() {
        val density = application.resources.displayMetrics.density.toInt()
        density shouldBeEqualTo 2
        imageContentRenderer.boxedSize(150, 150, ImageContentRenderer.Mode.STICKER, maxWidth, maxHeight, declaredInDp = true)
                .shouldBeEqualTo(Size(300, 300))
        imageContentRenderer.boxedSize(150, 150, ImageContentRenderer.Mode.STICKER, maxWidth, maxHeight, declaredInDp = false)
                .shouldBeEqualTo(Size(192, 192))
    }

    @Test
    fun `a sticker is never wider than three quarters of the box`() {
        imageContentRenderer.boxedSize(4000, 4000, ImageContentRenderer.Mode.STICKER, maxWidth, maxHeight, declaredInDp = false)
                .shouldBeEqualTo(Size(maxWidth * 3 / 4, maxWidth * 3 / 4))
    }

    @Test
    fun `full size keeps the picture's own dimensions`() {
        imageContentRenderer.boxedSize(400, 300, ImageContentRenderer.Mode.FULL_SIZE, maxWidth, maxHeight, declaredInDp = false)
                .shouldBeEqualTo(Size(400, 300))
    }
}
