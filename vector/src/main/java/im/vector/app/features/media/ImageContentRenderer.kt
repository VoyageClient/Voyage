/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.Companion.PRIVATE
import androidx.core.view.updateLayoutParams
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.core.glide.AnimatedContentImageViewTarget
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.GlideRequest
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.glide.RestartAnimationListener
import im.vector.app.core.ui.model.Size
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import javax.inject.Inject
import kotlin.math.min

interface AttachmentData : Parcelable {
    val eventId: String
    val filename: String
    val mimeType: String?
    val url: String?
    val elementToDecrypt: ElementToDecrypt?

    // If true will load non mxc url, be careful to set it only for attachments sent by you
    val allowNonMxcUrls: Boolean
}

private const val URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX = 600
private const val URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX = 315

class ImageContentRenderer @Inject constructor(
        private val localFilesHelper: LocalFilesHelper,
        private val activeSessionHolder: ActiveSessionHolder,
        private val dimensionConverter: DimensionConverter,
        private val vectorPreferences: VectorPreferences,
) {

    @Parcelize
    data class Data(
            override val eventId: String,
            override val filename: String,
            override val mimeType: String?,
            override val url: String?,
            override val elementToDecrypt: ElementToDecrypt?,
            val height: Int?,
            val maxHeight: Int,
            val width: Int?,
            val maxWidth: Int,
            // If true will load non mxc url, be careful to set it only for images sent by you
            override val allowNonMxcUrls: Boolean = false,
            val blurHash: String? = null,
    ) : AttachmentData

    enum class Mode {
        FULL_SIZE,
        ANIMATED_THUMBNAIL,
        THUMBNAIL,
        STICKER
    }

    /**
     * For url preview.
     */
    fun render(previewUrlData: PreviewUrlData, imageView: ImageView): Boolean {
        val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()
        val imageUrl = contentUrlResolver.resolveFullSize(previewUrlData.mxcUrl) ?: return false
        val maxHeight = dimensionConverter.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.preview_url_view_image_max_height)
        val height = previewUrlData.imageHeight ?: URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX
        val width = previewUrlData.imageWidth ?: URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX
        if (height < URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX || width < URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX) {
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        } else {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        GlideApp.with(imageView)
                .load(imageUrl)
                .override(width, height.coerceAtMost(maxHeight))
                .into(imageView)
        return true
    }

    /**
     * For gallery.
     */
    fun render(data: Data, imageView: ImageView, size: Int) {
        // a11y
        imageView.contentDescription = data.filename

        createGlideRequest(data, Mode.THUMBNAIL, imageView, Size(size, size))
                .dontAnimate()
                .placeholder(R.drawable.ic_image)
                .intoView(imageView, animate = false)
    }

    fun render(
            data: Data,
            mode: Mode,
            imageView: ImageView,
            cornerTransformation: Transformation<Bitmap> = RoundedCorners(dimensionConverter.dpToPx(8)),
            crossFade: Boolean = false,
    ) {
        val size = processSize(data, mode)
        if (data.hasKnownDimensions()) {
            imageView.adjustViewBounds = false
            imageView.updateLayoutParams {
                width = size.width
                height = size.height
            }
        } else {
            // Unknown dimensions (e.g. a sticker whose info has no w/h): wrap the view to the loaded
            // image, bounded by the max size, so it isn't letterboxed in a max-size box (gaps around it).
            imageView.adjustViewBounds = true
            imageView.maxWidth = data.maxWidth
            imageView.maxHeight = data.maxHeight
            imageView.updateLayoutParams {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        // a11y
        imageView.contentDescription = data.filename

        val animate = animates(mode)
        createGlideRequest(data, mode, imageView, size)
                .let { if (animate) it else it.dontAnimate() }
                .let { if (crossFade) it.transition(DrawableTransitionOptions.withCrossFade(REVEAL_CROSSFADE_MS)) else it }
                // A Bitmap RoundedCorners would round GIF frames at their small native resolution and
                // upscale the result, giving over-rounded, pixelated corners; animated content is
                // clipped at the view level instead (clipToOutline / RoundedCornerImageView).
                .let { if (mode == Mode.ANIMATED_THUMBNAIL) it else it.optionalTransform(cornerTransformation) }
                .intoView(imageView, animate)
    }

    @VisibleForTesting(otherwise = PRIVATE)
    internal fun animates(mode: Mode) = when (mode) {
        // The timeline asks for these only when autoplay is on, and the viewer is opened deliberately.
        Mode.ANIMATED_THUMBNAIL,
        Mode.FULL_SIZE -> true
        // Stickers have no still variant to fall back on, so autoplay is all there is to go on.
        Mode.STICKER -> vectorPreferences.autoplayAnimatedImages()
        // An encrypted room has no server-rendered still, so this decodes the whole animated file.
        Mode.THUMBNAIL -> false
    }

    @VisibleForTesting(otherwise = PRIVATE)
    internal fun GlideRequest<Drawable>.intoView(imageView: ImageView, animate: Boolean) {
        if (animate) {
            // A bare ImageView target is the only one Glide derives a scale-type transformation for,
            // so the rewind rides along as a listener rather than as a target of our own.
            addListener(RestartAnimationListener).into(imageView)
        } else {
            into(AnimatedContentImageViewTarget(imageView, animate = false))
        }
    }

    /**
     * Sizes the view and shows only the static blurhash (or a neutral placeholder) without
     * triggering any network/thumbnail download. Used while media is hidden behind a tap-to-reveal
     * placeholder, so nothing is fetched until the user reveals it.
     */
    fun renderHidden(data: Data, mode: Mode, imageView: ImageView, forceSolidColor: Boolean) {
        val size = processSize(data, mode)
        imageView.updateLayoutParams {
            width = size.width
            height = size.height
        }
        imageView.contentDescription = data.filename
        tryOrNull { GlideApp.with(imageView).clear(imageView) }
        val placeholder = if (forceSolidColor) null else data.blurHash?.let { BlurHashDrawable.from(it, data.width, data.height, pulse = false) }
        if (placeholder != null) {
            imageView.setImageDrawable(placeholder)
        } else {
            // No blurhash to preview: fall back to a flat neutral fill, like Element Web.
            imageView.setImageDrawable(ColorDrawable(ThemeUtils.getColor(imageView.context, im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)))
        }
    }

    companion object {
        private const val BLURHASH_CROSSFADE_MS = 200L
        private val BLURHASH_FADE_FACTORY = BlurFadeOutTransitionFactory(BLURHASH_CROSSFADE_MS)
        private const val REVEAL_CROSSFADE_MS = 220

        private val ALPHA_CAPABLE_MIME_TYPES = setOf("image/png", "image/webp", "image/gif", "image/apng")

        /**
         * Mode to use for a small *preview* (reply header, message-actions sheet, composer reply). Server
         * thumbnails can bake transparency onto an opaque background, so for stickers / transparent-capable
         * images we render the full original (STICKER mode) — a preview is a single image, so this is cheap.
         */
        fun previewMode(isSticker: Boolean, mimeType: String?): Mode =
                if (isSticker || mimeType in ALPHA_CAPABLE_MIME_TYPES) Mode.STICKER else Mode.THUMBNAIL
    }

    fun clear(imageView: ImageView) {
        // It can be called after recycler view is destroyed, just silently catch
        // We'd better keep ref to requestManager, but we don't have it
        tryOrNull {
            GlideApp
                    .with(imageView).clear(imageView)
        }
    }

    /**
     * Used by Attachment Viewer. Decodes at the source's native resolution rather than the
     * target view's bounds — the viewer supports pinch-to-zoom, so a screen-sized bitmap
     * gets visibly blurry once magnified.
     */
    fun render(data: Data, contextView: View, target: CustomViewTarget<*, Drawable>) {
        val isLocalContentUri = data.allowNonMxcUrls && data.url?.startsWith("content://") == true
        val req = if (data.elementToDecrypt != null) {
            // Encrypted image
            GlideApp
                    .with(contextView)
                    .load(data)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else if (isLocalContentUri) {
            // Local-echo content URI — load as Uri so Glide can use a ParcelFileDescriptor and
            // run VideoBitmapDecoder for video frame thumbnails, instead of the InputStream
            // path which only decodes still images.
            GlideApp
                    .with(contextView)
                    .load(android.net.Uri.parse(data.url))
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else {
            // Clear image
            val resolvedUrl = resolveUrl(data)
            GlideApp
                    .with(contextView)
                    .load(resolvedUrl)
        }

        req
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .optionalFitCenter()
                // Whatever the mime type claimed: the still-image target would never start it otherwise.
                .addListener(RestartAnimationListener)
                .into(target)
    }

    fun renderForSharedElementTransition(data: Data, imageView: ImageView, callback: ((Boolean) -> Unit)? = null) {
        // a11y
        imageView.contentDescription = data.filename

        val req = if (data.elementToDecrypt != null) {
            // Encrypted image
            GlideApp
                    .with(imageView)
                    .load(data)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else {
            // Clear image
            val resolvedUrl = resolveUrl(data)
            GlideApp
                    .with(imageView)
                    .load(resolvedUrl)
        }

        req.listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
            ): Boolean {
                callback?.invoke(false)
                return false
            }

            override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
            ): Boolean {
                callback?.invoke(true)
                return false
            }
        })
                // Appended, not listener(): that one drops whatever was registered before it.
                .addListener(RestartAnimationListener)
                .optionalFitCenter()
                .into(imageView)
    }

    private fun createGlideRequest(data: Data, mode: Mode, imageView: ImageView, size: Size): GlideRequest<Drawable> {
        return createGlideRequest(data, mode, GlideApp.with(imageView), size)
    }

    fun createGlideRequest(data: Data, mode: Mode, glideRequests: GlideRequests, size: Size = processSize(data, mode)): GlideRequest<Drawable> {
        val isLocalContentUri = data.allowNonMxcUrls && data.url?.startsWith("content://") == true
        val isLocalVideoContentUri = isLocalContentUri && data.mimeType?.startsWith("video/") == true
        val request = if (isLocalVideoContentUri) {
            // Local-echo video — load the content URI directly so Glide can use a
            // ParcelFileDescriptor and run VideoBitmapDecoder to extract a frame as the thumbnail.
            // The InputStream-based path used below only decodes still images.
            // frame(0): pin to t=0 (OPTION_CLOSEST_SYNC) to match the upload worker's thumbnail
            // (ThumbnailExtractor uses getFrameAtTime(0, …)); Glide's default representative frame
            // otherwise differs, so the poster visibly jumps when the server thumbnail swaps in.
            glideRequests
                    .load(android.net.Uri.parse(data.url))
                    .frame(0)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else if (data.elementToDecrypt != null || isLocalContentUri) {
            // Encrypted image, or local-echo content URI — go through our custom data loader so
            // Glide always sees an InputStream. The default content-URI loader otherwise prefers a
            // ParcelFileDescriptor, which routes to Downsampler (still bitmap) and skips the
            // animated decoder chain — so APNG/animated WebP previews freeze on the first frame
            // until the upload completes and we switch to an HTTP URL.
            glideRequests
                    .load(data)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else {
            // Clear image
            val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()
            val resolvedUrl = when (mode) {
                Mode.FULL_SIZE,
                Mode.ANIMATED_THUMBNAIL,
                Mode.STICKER -> resolveUrl(data)
                Mode.THUMBNAIL -> contentUrlResolver.resolveThumbnail(data.url, size.width, size.height, ContentUrlResolver.ThumbnailMethod.SCALE)
            }
            // Fallback to base url
                    ?: data.url.takeIf { it?.startsWith("content://") == true }

            glideRequests
                    .load(resolvedUrl)
                    .apply {
                        if (mode == Mode.THUMBNAIL) {
                            error(
                                    decorateWithBlurHash(glideRequests.load(resolveUrl(data)), data)
                            )
                        }
                    }
        }
        return decorateWithBlurHash(request, data)
    }

    private fun decorateWithBlurHash(request: GlideRequest<Drawable>, data: Data): GlideRequest<Drawable> {
        val placeholder = data.blurHash?.let { BlurHashDrawable.from(it, data.width, data.height) } ?: return request
        return request.placeholder(placeholder)
                .transition(DrawableTransitionOptions.with(BLURHASH_FADE_FACTORY))
    }

    private fun resolveUrl(data: Data) =
            (activeSessionHolder.getActiveSession().contentUrlResolver().resolveFullSize(data.url)
                    ?: data.url?.takeIf { localFilesHelper.isLocalFile(data.url) && data.allowNonMxcUrls })

    private fun Data.hasKnownDimensions(): Boolean = (width ?: 0) > 0 && (height ?: 0) > 0

    private fun processSize(data: Data, mode: Mode): Size {
        val maxImageWidth = data.maxWidth
        val maxImageHeight = data.maxHeight
        val width = data.width ?: maxImageWidth
        val height = data.height ?: maxImageHeight
        var finalWidth = -1
        var finalHeight = -1

        // if the image size is known
        // compute the expected height
        if (width > 0 && height > 0) {
            when (mode) {
                Mode.FULL_SIZE -> {
                    finalHeight = height
                    finalWidth = width
                }
                Mode.ANIMATED_THUMBNAIL,
                Mode.THUMBNAIL -> {
                    finalHeight = min(maxImageWidth * height / width, maxImageHeight)
                    finalWidth = finalHeight * width / height
                }
                Mode.STICKER -> {
                    // limit on width
                    finalWidth = min(dimensionConverter.dpToPx(width), maxImageWidth * 3 / 4)
                    finalHeight = finalWidth * height / width
                    // Also cap the height: a tall alpha-capable image (routed here by previewMode) would
                    // otherwise render at full height in the reply header / composer / long-press previews.
                    if (finalHeight > maxImageHeight) {
                        finalHeight = maxImageHeight
                        finalWidth = finalHeight * width / height
                    }
                }
            }
        }
        // ensure that some values are properly initialized
        if (finalHeight < 0) {
            finalHeight = maxImageHeight
        }
        if (finalWidth < 0) {
            finalWidth = maxImageWidth
        }
        return Size(finalWidth, finalHeight)
    }
}
