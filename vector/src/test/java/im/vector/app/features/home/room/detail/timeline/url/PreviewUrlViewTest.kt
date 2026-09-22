/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import im.vector.app.features.media.ImageContentRenderer
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val A_STABLE_ID = "\$event"
private const val A_KNOWN_URL = "https://matrix.org/known"
private const val AN_UNSEEN_URL = "https://matrix.org/unseen"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreviewUrlViewTest {

    private val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            im.vector.lib.ui.styles.R.style.Theme_Vector_Light
    )
    private val imageContentRenderer = mockk<ImageContentRenderer> {
        every { render(any<PreviewUrlData>(), any()) } returns false
    }

    @Test
    fun `a card of known height holds its space while the same link loads elsewhere`() {
        val rendered = aView()
        rendered.render(PreviewUrlUiState.Data(A_STABLE_ID, A_KNOWN_URL, previewOf(A_KNOWN_URL)), imageContentRenderer)
        measure(rendered)
        val renderedHeight = rendered.measuredHeight
        renderedHeight shouldBeGreaterThan 0

        val loading = aView()
        loading.render(PreviewUrlUiState.Loading(A_KNOWN_URL), imageContentRenderer)

        loading.visibility shouldBeEqualTo View.INVISIBLE
        loading.layoutParams.height shouldBeEqualTo renderedHeight
    }

    @Test
    fun `a link never previewed before takes no space while it loads`() {
        val loading = aView()

        loading.render(PreviewUrlUiState.Loading(AN_UNSEEN_URL), imageContentRenderer)

        loading.visibility shouldBeEqualTo View.GONE
        loading.layoutParams.height shouldBeEqualTo ViewGroup.LayoutParams.WRAP_CONTENT
    }

    @Test
    fun `the reserved space is given back when the preview itself arrives`() {
        val view = aView()
        view.render(PreviewUrlUiState.Data(A_STABLE_ID, A_KNOWN_URL, previewOf(A_KNOWN_URL)), imageContentRenderer)
        measure(view)
        view.render(PreviewUrlUiState.Loading(A_KNOWN_URL), imageContentRenderer)

        view.render(PreviewUrlUiState.Data(A_STABLE_ID, A_KNOWN_URL, previewOf(A_KNOWN_URL)), imageContentRenderer)

        view.visibility shouldBeEqualTo View.VISIBLE
        view.layoutParams.height shouldBeEqualTo ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun aView() = PreviewUrlView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun measure(view: PreviewUrlView) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
    }

    private fun previewOf(url: String) = PreviewUrlData(
            url = url,
            siteName = "Matrix.org",
            title = "Matrix",
            description = "A description long enough to give the card some height.",
            mxcUrl = null,
            imageWidth = null,
            imageHeight = null
    )
}
