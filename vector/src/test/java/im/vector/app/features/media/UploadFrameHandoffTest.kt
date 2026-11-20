/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import im.vector.app.core.ui.model.Size
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UploadFrameHandoffTest {

    private val context = RuntimeEnvironment.getApplication()
    private val renderer = ImageContentRenderer(
            context = context,
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            vectorPreferences = mockk(relaxed = true),
            failedMediaTracker = mockk(relaxed = true),
    )
    private val imageView = ImageView(context)
    private val frame = ColorDrawable()

    private fun data(url: String, allowNonMxcUrls: Boolean, stableId: String = "\$local.echo") = ImageContentRenderer.Data(
            eventId = "\$local.echo",
            filename = "picture.jpg",
            mimeType = "image/jpeg",
            url = url,
            elementToDecrypt = null,
            height = 886,
            maxHeight = 1080,
            width = 1130,
            maxWidth = 1080,
            allowNonMxcUrls = allowNonMxcUrls,
            stableId = stableId,
    )

    private val removedLocalFile = data("file:///data/cache/upload.tmp", allowNonMxcUrls = true)
    private val uploaded = data("mxc://example.org/picture", allowNonMxcUrls = true)

    private fun failedWithFrame() = ImageContentRenderer.LastRender(removedLocalFile.stableId, removedLocalFile, ImageContentRenderer.Mode.THUMBNAIL, completed = false)
            .apply {
                failed = true
                uploadFrame = frame
            }

    private fun handoff(last: ImageContentRenderer.LastRender?, data: ImageContentRenderer.Data = uploaded, fromRetryTap: Boolean = false) =
            renderer.uploadFrame(imageView, last, data, ImageContentRenderer.Mode.THUMBNAIL, Size(753, 591), fromRetryTap)

    @Test
    fun `the picture held through a failed local load carries on to the uploaded url`() {
        handoff(failedWithFrame()) shouldBe frame
    }

    @Test
    fun `a failure with no picture held starts the next url from the placeholder`() {
        handoff(failedWithFrame().apply { uploadFrame = null }).shouldBeNull()
    }

    @Test
    fun `a retry tap never reuses the held picture`() {
        handoff(failedWithFrame(), fromRetryTap = true).shouldBeNull()
    }

    @Test
    fun `another message never takes the held picture`() {
        handoff(failedWithFrame(), data = data("mxc://example.org/other", allowNonMxcUrls = false, stableId = "\$other")).shouldBeNull()
    }
}
