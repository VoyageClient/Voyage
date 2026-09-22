/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

import android.view.ContextThemeWrapper
import androidx.core.view.isVisible
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.media.ImageContentRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val A_STABLE_ID = "txn-1"
private const val ANOTHER_STABLE_ID = "\$other"
private const val URL = "https://matrix.org"

private val PREVIEW_DATA = PreviewUrlData(
        url = URL,
        siteName = "Matrix.org",
        title = "Matrix",
        description = null,
        mxcUrl = null,
        imageWidth = null,
        imageHeight = null
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreviewUrlViewUpdaterTest {

    private val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            im.vector.lib.ui.styles.R.style.Theme_Vector_Light
    )
    private val view = PreviewUrlView(context)
    private val imageContentRenderer = mockk<ImageContentRenderer> {
        every { render(any<PreviewUrlData>(), any()) } returns false
    }
    private val messageLayout = TimelineMessageLayout.Default(showAvatar = true, showDisplayName = true, showTimestamp = true)

    private fun anUpdater(stableId: String, retriever: PreviewUrlRetriever? = null) = PreviewUrlViewUpdater().also {
        it.bind(
                view = view,
                retriever = retriever ?: mockk(relaxed = true),
                callback = null,
                imageContentRenderer = imageContentRenderer,
                stableId = stableId,
                messageLayout = messageLayout,
        )
    }

    @Test
    fun `a late update for the message the view still hosts is rendered`() {
        val updater = anUpdater(A_STABLE_ID)

        updater.onStateUpdated(PreviewUrlUiState.Data(A_STABLE_ID, URL, PREVIEW_DATA))

        view.isVisible shouldBeEqualTo true
    }

    @Test
    fun `a late update from a model whose view was reused for another message is ignored`() {
        val orphan = anUpdater(A_STABLE_ID)
        // The view is recycled onto another message, which does have a preview to show.
        anUpdater(ANOTHER_STABLE_ID).onStateUpdated(PreviewUrlUiState.Data(ANOTHER_STABLE_ID, URL, PREVIEW_DATA))

        orphan.onStateUpdated(PreviewUrlUiState.NoUrl)

        view.isVisible shouldBeEqualTo true
    }

    @Test
    fun `an ignored update deregisters the orphan so they cannot pile up`() {
        val retriever = mockk<PreviewUrlRetriever>(relaxed = true)
        val orphan = anUpdater(A_STABLE_ID, retriever)
        anUpdater(ANOTHER_STABLE_ID)

        orphan.onStateUpdated(PreviewUrlUiState.NoUrl)

        verify { retriever.removeListener(A_STABLE_ID, orphan) }
    }

    @Test
    fun `binding without a retriever hides the view without leaving a stale state behind`() {
        PreviewUrlViewUpdater().bind(
                view = view,
                retriever = null,
                callback = null,
                imageContentRenderer = imageContentRenderer,
                stableId = A_STABLE_ID,
                messageLayout = messageLayout,
        )
        view.isVisible shouldBeEqualTo false

        // The same NoUrl state must not early-return onto a view hidden behind the renderer's back.
        anUpdater(A_STABLE_ID).onStateUpdated(PreviewUrlUiState.Data(A_STABLE_ID, URL, PREVIEW_DATA))

        view.isVisible shouldBeEqualTo true
    }
}
