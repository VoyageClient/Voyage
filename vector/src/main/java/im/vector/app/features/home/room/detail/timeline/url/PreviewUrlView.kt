/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.url

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.databinding.ViewUrlPreviewBinding
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.view.TimelineMessageLayoutRenderer
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.session.media.PreviewUrlData

/**
 * A View to display a PreviewUrl and some other state.
 */
class PreviewUrlView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : CardView(context, attrs, defStyleAttr), View.OnClickListener, TimelineMessageLayoutRenderer {

    private lateinit var views: ViewUrlPreviewBinding

    var delegate: TimelineEventController.PreviewUrlCallback? = null

    // Echo-swap-stable identity of the message currently hosting this view, for stale-update guards.
    var boundStableId: String? = null

    init {
        setupView()
        radius = resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.preview_url_view_corner_radius).toFloat()
        cardElevation = 0f
    }

    private var state: PreviewUrlUiState = PreviewUrlUiState.Unknown

    /**
     * This methods is responsible for rendering the view according to the newState.
     *
     * @param newState the newState representing the view
     * @param imageContentRenderer the tool to render the image
     * @param force true to force refresh
     */
    fun render(
            newState: PreviewUrlUiState,
            imageContentRenderer: ImageContentRenderer,
            force: Boolean = false
    ) {
        if (newState == state && !force) {
            return
        }

        state = newState

        hideAll()
        when (newState) {
            PreviewUrlUiState.Unknown,
            PreviewUrlUiState.NoUrl -> renderHidden()
            is PreviewUrlUiState.Loading -> renderLoading(newState.url)
            is PreviewUrlUiState.Error -> renderHidden()
            is PreviewUrlUiState.Data -> renderData(newState.previewUrlData, imageContentRenderer)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Remember what this card measures to, so the next message previewing the same link reserves
        // its space while the preview is still being fetched instead of appearing under the reader.
        val url = (state as? PreviewUrlUiState.Data)?.url ?: return
        if (measuredHeight > 0) {
            measuredHeights.put(url, measuredHeight)
        }
    }

    // Goes through [state] rather than touching visibility, so a later render of the same state cannot
    // early-return onto a view left hidden behind its back.
    fun hide() {
        state = PreviewUrlUiState.NoUrl
        hideAll()
        renderHidden()
    }

    override fun renderMessageLayout(messageLayout: TimelineMessageLayout) {
        when (messageLayout) {
            is TimelineMessageLayout.Default -> {
                val backgroundColor = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_system)
                setCardBackgroundColor(backgroundColor)
                val guidelineBegin = DimensionConverter(resources).dpToPx(8)
                views.urlPreviewStartGuideline.setGuidelineBegin(guidelineBegin)
            }
            is TimelineMessageLayout.Bubble -> {
                setCardBackgroundColor(Color.TRANSPARENT)
                views.urlPreviewStartGuideline.setGuidelineBegin(0)
            }
            is TimelineMessageLayout.ScBubble -> {
                setCardBackgroundColor(Color.TRANSPARENT)
                views.urlPreviewStartGuideline.setGuidelineBegin(0)
            }
        }
    }

    override fun onClick(v: View?) {
        when (val finalState = state) {
            is PreviewUrlUiState.Data -> delegate?.onPreviewUrlClicked(finalState.url)
            else -> Unit
        }
    }

    private fun onImageClick() {
        when (val finalState = state) {
            is PreviewUrlUiState.Data -> {
                val mxcUrl = finalState.previewUrlData.mxcUrl
                if (mxcUrl == null) {
                    // An encrypted thumbnail (MSC4095) is not something the image viewer can open, so the
                    // tap does what tapping the rest of the card does.
                    delegate?.onPreviewUrlClicked(finalState.url)
                } else {
                    delegate?.onPreviewUrlImageClicked(
                            sharedView = views.urlPreviewImage,
                            mxcUrl = mxcUrl,
                            title = finalState.previewUrlData.title
                    )
                }
            }
            else -> Unit
        }
    }

    private fun onCloseClick() {
        when (val finalState = state) {
            is PreviewUrlUiState.Data -> delegate?.onPreviewUrlCloseClicked(finalState.eventId, finalState.url)
            else -> Unit
        }
    }

    // PRIVATE METHODS ****************************************************************************************************************************************

    private fun setupView() {
        inflate(context, R.layout.view_url_preview, this)
        views = ViewUrlPreviewBinding.bind(this)

        setOnClickListener(this)
        views.urlPreviewImage.setOnClickListener { onImageClick() }
        views.urlPreviewClose.setOnClickListener { onCloseClick() }
    }

    private fun renderHidden() {
        setReservedHeight(null)
        isVisible = false
    }

    private fun renderLoading(url: String) {
        val reserved = measuredHeights.get(url)
        if (reserved == null) {
            setReservedHeight(null)
            isVisible = false
        } else {
            // Held empty at the height it will take, so the answer landing doesn't move everything
            // below it — including a message the reader has just been sent to.
            setReservedHeight(reserved)
            visibility = View.INVISIBLE
        }
    }

    private fun setReservedHeight(height: Int?) {
        val params = layoutParams ?: return
        val target = height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        if (params.height != target) {
            params.height = target
            layoutParams = params
        }
    }

    private fun renderData(previewUrlData: PreviewUrlData, imageContentRenderer: ImageContentRenderer) {
        setReservedHeight(null)
        isVisible = true

        views.urlPreviewTitle.setTextOrHide(previewUrlData.title)
        val hasImage = imageContentRenderer.render(previewUrlData, views.urlPreviewImage)
        views.urlPreviewImage.isVisible = hasImage
        views.urlPreviewDescription.setTextOrHide(previewUrlData.description)
        views.urlPreviewDescription.maxLines = when {
            hasImage -> 2
            previewUrlData.title != null -> 3
            else -> 5
        }
        views.urlPreviewSite.setTextOrHide(previewUrlData.siteName.takeIf { it != previewUrlData.title })
    }

    /**
     * Hide all views that are not visible in all state.
     */
    private fun hideAll() {
        views.urlPreviewTitle.isVisible = false
        views.urlPreviewImage.isVisible = false
        views.urlPreviewDescription.isVisible = false
        views.urlPreviewSite.isVisible = false
    }

    companion object {
        // Card height per previewed url, shared by every message linking it.
        private val measuredHeights = LruCache<String, Int>(128)
    }
}
