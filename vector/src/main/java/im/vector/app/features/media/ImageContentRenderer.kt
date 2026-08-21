/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.os.SystemClock
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
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.signature.ObjectKey
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.files.LocalFilesHelper
import im.vector.app.core.files.isLocalMediaUri
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
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import org.matrix.android.sdk.api.util.MimeTypes
import timber.log.Timber
import java.io.File
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

    /** Who sent it and when, for the viewers whose events are not in the local timeline. */
    val senderName: String? get() = null
    val timestampMs: Long? get() = null
}

private const val URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX = 600
private const val URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX = 315

class ImageContentRenderer @Inject constructor(
        @ApplicationContext private val context: Context,
        private val localFilesHelper: LocalFilesHelper,
        private val activeSessionHolder: ActiveSessionHolder,
        private val dimensionConverter: DimensionConverter,
        private val vectorPreferences: VectorPreferences,
        private val failedMediaTracker: FailedMediaTracker,
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
            // A copy kept locally because the message was redacted. Loaded directly: the mxc url it
            // came from is usually purged server-side by the time this renders, and Glide's url/uri
            // resolution only accepts mxc or content:// anyway.
            val preservedFile: File? = null,
            val blurHash: String? = null,
            // Survives the local-echo → remote-id swap (see MessageInformationData.stableId).
            val stableId: String = eventId,
            /** Which item of an MSC4274 gallery event this is, when it is one. */
            val galleryIndex: Int? = null,
            override val senderName: String? = null,
            override val timestampMs: Long? = null,
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
        val encryptedImage = previewUrlData.encryptedImage
        val contentUrlResolver = activeSessionHolder.getActiveSession().contentUrlResolver()
        val imageUrl = contentUrlResolver.resolveFullSize(previewUrlData.mxcUrl)
        if (imageUrl == null && encryptedImage == null) return false
        val maxHeight = dimensionConverter.resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.preview_url_view_image_max_height)
        val height = previewUrlData.imageHeight ?: URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX
        val width = previewUrlData.imageWidth ?: URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX
        if (height < URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX || width < URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX) {
            imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        } else {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val request = if (encryptedImage == null) {
            GlideApp.with(imageView).load(imageUrl)
        } else {
            // MSC4095 preview thumbnails of an encrypted room are attachments of their own, so they go
            // through the decrypting loader rather than a plain url.
            createGlideRequest(previewUrlData.toEncryptedImageData(encryptedImage), Mode.FULL_SIZE, GlideApp.with(imageView), Size(width, height))
        }
        request.override(width, height.coerceAtMost(maxHeight))
                .into(imageView)
        return true
    }

    private fun PreviewUrlData.toEncryptedImageData(encryptedImage: EncryptedFileInfo) = Data(
            eventId = encryptedImage.url.orEmpty(),
            filename = title ?: url,
            mimeType = imageMimeType,
            url = encryptedImage.url,
            elementToDecrypt = encryptedImage.toElementToDecrypt(),
            height = imageHeight,
            maxHeight = imageHeight ?: URL_PREVIEW_IMAGE_MIN_FULL_HEIGHT_PX,
            width = imageWidth,
            maxWidth = imageWidth ?: URL_PREVIEW_IMAGE_MIN_FULL_WIDTH_PX,
    )

    /**
     * For gallery.
     */
    fun render(data: Data, imageView: ImageView, size: Int, fromRetryTap: Boolean = false) {
        render(data, imageView, size, size, fromRetryTap)
    }

    /**
     * Fixed-box variant for grid tiles (uploads grid, MSC4274 gallery tiles). Placeholders are drawn
     * square: a tile is flush with its neighbours, and the grid rounds the outer edge itself.
     */
    fun render(data: Data, imageView: ImageView, width: Int, height: Int, fromRetryTap: Boolean = false) {
        // A plain THUMBNAIL asks the homeserver to scale the file, which it cannot do for formats it
        // has no decoder for — JPEG XL among them — leaving the grid blank. previewMode routes those
        // to the original, exactly as the timeline's previews already do.
        val mode = previewMode(isSticker = false, mimeType = data.mimeType)
        // Same keep-the-drawn-picture rule as render(data, mode, …), plus the post-send
        // allowNonMxcUrls flip, which only a tile goes through.
        val last = imageView.lastRender()
        val keepsRender = last != null && last.completed && !fromRetryTap && last.stableId == data.stableId && last.mode == mode &&
                (last.data == data ||
                        (last.data.isLocalContent && !data.isLocalContent) ||
                        (!last.data.isLocalContent && last.data.copy(allowNonMxcUrls = data.allowNonMxcUrls) == data))
        if (keepsRender) {
            return
        }
        // a11y
        imageView.contentDescription = data.filename

        val thisRender = last?.takeIf { !fromRetryTap && it.stableId == data.stableId && it.data == data && it.mode == mode }
                ?: LastRender(data.stableId, data, mode, completed = false)
        imageView.setTag(R.id.image_renderer_last_render, thisRender)
        // No explicit placeholder: it would win over the blurhash that createGlideRequest attaches.
        val retryingFailed = !fromRetryTap && failedMediaTracker.isFailed(data.url)
        imageView.setTag(R.id.image_renderer_retry) { render(data, imageView, width, height, fromRetryTap = true) }
        // Stamped like the main render, so a tap while a retry is in flight is swallowed rather
        // than opening the viewer on media that is not there yet.
        imageView.setTag(R.id.image_renderer_retrying, if (fromRetryTap) SystemClock.uptimeMillis() else null)
        if (fromRetryTap) showLoadingNow(imageView, data, showGlyph = true, square = true)
        createGlideRequest(data, mode, imageView, Size(width, height))
                .asRetry(fromRetryTap)
                .addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        if (data.isUploading()) {
                            renderStillUploading(imageView, data, e, showGlyph = true, square = true)
                            return true
                        }
                        failedMediaTracker.onLoadFailed(data.url)
                        renderFailed(imageView, data, pinSize = null, square = true)
                        return true
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        failedMediaTracker.onLoadSucceeded(data.url)
                        thisRender.completed = true
                        return false
                    }
                })
                .placeholder(
                        placeholderFor(data, showGlyph = true, square = true).also { it.setFailed(retryingFailed) }
                )
                // Animated media plays here too: a grid of stills gives no hint which of them move.
                .intoView(imageView, animate = true)
    }

    // Tagged on the view so a rebind can tell "same message, new event id" (the local-echo → remote
    // swap) apart from a recycle onto a different message.
    private data class LastRender(val stableId: String, val data: Data, val mode: Mode, var completed: Boolean)

    private fun ImageView.lastRender() = getTag(R.id.image_renderer_last_render) as? LastRender

    private val Data.isLocalContent get() = allowNonMxcUrls && url.isLocalMediaUri()

    fun render(
            data: Data,
            mode: Mode,
            imageView: ImageView,
            // null leaves the bitmap square, for callers that shape the view instead — which also rounds
            // the placeholder and animated content, neither of which a Bitmap transform can touch.
            cornerTransformation: Transformation<Bitmap>? = RoundedCorners(dimensionConverter.dpToPx(8)),
            crossFade: Boolean = false,
            // A tap asking for another go needs to look like something happened, so it drops back to
            // the loading state; a plain rebind keeps the glyph rather than flickering through it.
            fromRetryTap: Boolean = false,
            // Anything drawing a play badge over the thumbnail has no room for a second symbol.
            showFailureGlyph: Boolean = true,
    ) {
        val size = processSize(data, mode)
        // Local-echo → remote swap of a fully-rendered message: the media is byte-identical to what
        // is on screen (we just uploaded it) — reloading would only flash the blurhash and restart
        // animations. Keep the completed local render.
        val last = imageView.lastRender()
        // A rebind that asks for exactly what is already drawn starts a fresh Glide request all the
        // same, and its placeholder step wipes the finished image for the frames that takes.
        val keepsLocalRender = last != null && last.completed && !fromRetryTap && last.stableId == data.stableId && last.mode == mode &&
                (last.data == data || (last.data.isLocalContent && !data.isLocalContent))
        if (keepsLocalRender) {
            return
        }
        if (data.hasKnownDimensions()) {
            imageView.adjustViewBounds = false
            // A local echo renders the untouched source file inside a box sized from the event's
            // declared dimensions. When the sender resized the media those two disagree until the
            // upload lands, and fitting inside the box shows the old shape letterboxed within the
            // new one. Filling it instead is what the recipient will see.
            imageView.scaleType = if (data.isLocalContent) ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
            imageView.updateLayoutParams {
                width = size.width
                height = size.height
            }
        } else {
            // Unknown dimensions (e.g. a sticker whose info has no w/h): wrap the view to the loaded
            // image, bounded by the max size, so it isn't letterboxed in a max-size box (gaps around it).
            // Reset explicitly: a recycled view may carry FIT_XY over from a local echo.
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView.adjustViewBounds = true
            imageView.maxWidth = data.maxWidth
            imageView.maxHeight = data.maxHeight
            // Hold a square until the real bounds are known. Placeholders have no intrinsic size, so
            // wrapping to one measures to nothing and the row collapses to zero height — which is
            // where it stays if the load then fails. onResourceReady restores the wrap.
            val square = data.loadingSquare()
            imageView.updateLayoutParams {
                width = square.width
                height = square.height
            }
        }
        // a11y
        imageView.contentDescription = data.filename

        // An identical rebind may reuse the still-current Glide request (no new onResourceReady),
        // so keep the existing record and its completed flag.
        val thisRender = last?.takeIf { it.stableId == data.stableId && it.data == data && it.mode == mode }
                ?: LastRender(data.stableId, data, mode, completed = false)
        imageView.setTag(R.id.image_renderer_last_render, thisRender)
        val animate = animates(mode)
        // Retrying: keep the glyph up for the duration rather than dropping to the blank loading
        // fill, which reads as the failure state vanishing every time the row rebinds.
        val retryingFailed = !fromRetryTap && failedMediaTracker.isFailed(data.url)
        imageView.setTag(R.id.image_renderer_retry) {
            render(data, mode, imageView, cornerTransformation, crossFade, fromRetryTap = true, showFailureGlyph = showFailureGlyph)
        }
        // Stamped rather than flagged: renderFailed holds the loading state visible for a moment from
        // this instant. Assigned unconditionally so a request whose callbacks never fire cannot leave
        // the view stuck looking like a retry is in flight, which made every later tap a no-op.
        imageView.setTag(R.id.image_renderer_retrying, if (fromRetryTap) SystemClock.uptimeMillis() else null)
        if (fromRetryTap) showLoadingNow(imageView, data, showFailureGlyph)
        val pending = PendingRenders.startOn(imageView, data, mode)
        createGlideRequest(data, mode, imageView, size)
                .asRetry(fromRetryTap)
                .addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        PendingRenders.finish(pending, "failed: ${e?.message}")
                        if (data.isUploading()) {
                            renderStillUploading(imageView, data, e, showFailureGlyph)
                            return true
                        }
                        failedMediaTracker.onLoadFailed(data.url)
                        renderFailed(imageView, data, pinSize = failedPinSize(data, size), showGlyph = showFailureGlyph)
                        return true
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        PendingRenders.finish(pending, "ready from $dataSource")
                        imageView.setTag(R.id.image_renderer_retrying, null)
                        failedMediaTracker.onLoadSucceeded(data.url)
                        thisRender.completed = true
                        if (!data.hasKnownDimensions()) {
                            // Real bounds at last — drop the holding square so the view wraps the image.
                            imageView.updateLayoutParams {
                                width = ViewGroup.LayoutParams.WRAP_CONTENT
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                            }
                        }
                        return false
                    }
                })
                // The very object already on screen, so Glide's own placeholder step cannot cut the
                // fade short by swapping in an equivalent-looking one.
                .placeholder(
                        placeholderFor(data, showFailureGlyph).also { it.setFailed(retryingFailed) }
                )
                .let { if (crossFade) it.transition(DrawableTransitionOptions.with(REVEAL_FADE_FACTORY)) else it }
                .withDisplayOptions(data, mode, animate, cornerTransformation, size)
                .intoView(imageView, animate)
    }

    /**
     * Decode a local echo's thumbnail ahead of the timeline showing it, so the row does not appear
     * before there is a picture to put in it. Everything that Glide keys its cache on has to match
     * what [render] will ask for, or the bind decodes from scratch anyway.
     */
    fun preloadLocalEcho(data: Data, mode: Mode, cornerTransformation: Transformation<Bitmap>, onSettled: () -> Unit) {
        val size = processSize(data, mode)
        createGlideRequest(data, mode, GlideApp.with(context), size)
                .addListener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        Timber.w(e, "Failed to decode a local echo's thumbnail: ${data.url}")
                        onSettled()
                        return false
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        onSettled()
                        return false
                    }
                })
                .withDisplayOptions(data, mode, animates(mode), cornerTransformation, size)
                .preload(size.width, size.height)
    }

    /**
     * The options that decide what Glide decodes, shared so a preload and the bind that follows it
     * land on the same cache entry. A local echo pins the decode to the box the row was laid out to
     * rather than to the view's measured size, which is not known until it has been through a layout.
     */
    private fun GlideRequest<Drawable>.withDisplayOptions(
            data: Data,
            mode: Mode,
            animate: Boolean,
            cornerTransformation: Transformation<Bitmap>?,
            size: Size,
    ): GlideRequest<Drawable> {
        return this
                .let { if (animate) it else it.dontAnimate().signature(ObjectKey(STILL_FRAME_SIGNATURE)) }
                // A Bitmap RoundedCorners would round GIF frames at their small native resolution and
                // upscale the result, giving over-rounded, pixelated corners; animated content is
                // clipped at the view level instead (clipToOutline / RoundedCornerImageView).
                .let { if (mode == Mode.ANIMATED_THUMBNAIL || cornerTransformation == null) it else it.optionalTransform(cornerTransformation) }
                .let { if (data.isLocalContent) it.override(size.width, size.height) else it }
    }

    /**
     * A retry must not be byte-identical to the request it is retrying. Glide keys engine jobs by
     * model plus options, so an identical one attaches to the existing job for that key — and when
     * that job was cancelled (renderFailed clears the target) it never delivers, leaving the retry
     * outstanding with every worker thread idle. A fresh signature gives it its own key.
     */
    private fun <T> GlideRequest<T>.asRetry(fromRetryTap: Boolean): GlideRequest<T> =
            if (fromRetryTap) signature(ObjectKey("retry-${SystemClock.uptimeMillis()}")).skipMemoryCache(true) else this

    // processSize falls back to the max box when dimensions are missing, which for a failure would
    // leave a tall empty rectangle; the holding square the load already used is the honest shape.
    private fun failedPinSize(data: Data, size: Size): Size =
            if (data.hasKnownDimensions()) size else data.loadingSquare()

    /**
     * @param pinSize the box to force onto the view, for callers whose layout would otherwise
     * collapse: the placeholder has no intrinsic size, and an unknown-dimension image leaves the
     * view on WRAP_CONTENT. Null where the parent already sizes the view, as in the uploads grid.
     */
    private fun renderFailed(imageView: ImageView, data: Data, pinSize: Size?, showGlyph: Boolean = true, square: Boolean = false) {
        PendingRenders.cancelOn(imageView)
        imageView.setTag(R.id.image_renderer_last_render, null)
        tryOrNull { GlideApp.with(imageView).clear(imageView) }
        imageView.scaleType = ImageView.ScaleType.FIT_XY
        if (pinSize != null) {
            imageView.adjustViewBounds = false
            imageView.updateLayoutParams {
                width = pinSize.width
                height = pinSize.height
            }
        }

        // The same object the wait was drawn with, so the failure is a change of its parameters
        // rather than a new drawable: nothing to cross-dissolve, and the fill carries on from
        // exactly the value it was at.
        val placeholder = placeholderFor(data, showGlyph, square)
        // A retry can fail in the same frame it started — a cached error, an unresolvable url — which
        // would take the waiting state off screen before it was ever drawn, so the tap looks ignored.
        val sinceTap = (imageView.getTag(R.id.image_renderer_retrying) as? Long)
                ?.let { SystemClock.uptimeMillis() - it }
        val hold = sinceTap?.let { MIN_RETRY_FEEDBACK_MS - it }?.takeIf { it > 0 }
        val apply = Runnable {
            if (imageView.drawable !== placeholder) imageView.setImageDrawable(placeholder)
            placeholder.setFailed(true)
            // Cleared only once the verdict is on screen: until then a tap would be answered by a
            // retry that is already running, and leaving it set makes every later tap a no-op.
            imageView.setTag(R.id.image_renderer_retrying, null)
        }
        if (hold != null) imageView.postDelayed(apply, hold) else apply.run()
    }

    /**
     * Bytes of our own that have not landed on the homeserver yet: the local echo's source file, or a
     * URI reserved through MSC2246 whose upload is still running.
     */
    private fun Data.isUploading(): Boolean {
        if (allowNonMxcUrls) return true
        val session = activeSessionHolder.getSafeActiveSession() ?: return false
        return session.fileService().isUploadPending(url)
    }

    /**
     * A load that fails on media still being uploaded is not a broken download — the bytes simply are
     * not anywhere we can read them yet. Stay on the waiting state, and leave no failure recorded: the
     * tracker would put the glyph up on the next bind before the fresh load had a chance to succeed.
     */
    private fun renderStillUploading(imageView: ImageView, data: Data, e: GlideException?, showGlyph: Boolean, square: Boolean = false) {
        Timber.w(e, "Thumbnail load failed while still uploading: ${data.url}")
        imageView.setTag(R.id.image_renderer_retrying, null)
        showLoadingNow(imageView, data, showGlyph, square)
    }

    fun isFailed(data: Data): Boolean = failedMediaTracker.isFailed(data.url)

    /** A retry asked for by tapping is still running, so the media is not openable yet. */
    fun isRetrying(imageView: ImageView): Boolean = imageView.getTag(R.id.image_renderer_retrying) != null

    /**
     * Put the waiting state up before the request runs: a retry that fails synchronously — a cached
     * error, an unresolvable url — starts and finishes in one frame, so Glide's placeholder is never
     * drawn. Uses whatever a first load shows, so a retry looks like the same kind of waiting.
     */
    private fun showLoadingNow(imageView: ImageView, data: Data, showGlyph: Boolean, square: Boolean = false): Drawable {
        val placeholder = placeholderFor(data, showGlyph, square)
        placeholder.setFailed(false)
        if (imageView.drawable !== placeholder) imageView.setImageDrawable(placeholder)
        return placeholder
    }

    /**
     * Re-run whichever render last drew this view. Tapping dead media is otherwise a dead end, and a
     * failure here is often just the server having a bad minute.
     */
    fun retry(imageView: ImageView) {
        @Suppress("UNCHECKED_CAST")
        val rerender = imageView.getTag(R.id.image_renderer_retry) as? (() -> Unit) ?: return
        rerender()
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
            // A still use stops the drawable it is handed, and Glide's memory cache hands every view
            // the same instance — so without a key of its own, opening the uploads grid or binding a
            // reply preview freezes the very drawable the timeline is animating, until some later
            // rebind happens to restart it.
            signature(ObjectKey(STILL_FRAME_SIGNATURE))
                    .into(AnimatedContentImageViewTarget(imageView, animate = false))
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
        PendingRenders.cancelOn(imageView)
        imageView.setTag(R.id.image_renderer_last_render, null)
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

        // Glide's withCrossFade() leaves the placeholder as an opaque layer under the image for good,
        // which a transparent picture then shows the waiting fill through. Fading it out instead.
        private val REVEAL_FADE_FACTORY = DrawableCrossFadeFactory.Builder(REVEAL_CROSSFADE_MS)
                .setCrossFadeEnabled(true)
                .build()
        private const val MIN_RETRY_FEEDBACK_MS = 550L
        private const val STILL_FRAME_SIGNATURE = "still-frame"

        private val ALPHA_CAPABLE_MIME_TYPES = setOf(
                MimeTypes.Png,
                MimeTypes.Webp,
                MimeTypes.Gif,
                MimeTypes.Apng,
                MimeTypes.Jxl,
        )

        /**
         * Mode to use for a small *preview* (reply header, message-actions sheet, composer reply). Server
         * thumbnails can bake transparency onto an opaque background, so for stickers / transparent-capable
         * images we render the full original (STICKER mode) — a preview is a single image, so this is cheap.
         */
        fun previewMode(isSticker: Boolean, mimeType: String?): Mode =
                if (isSticker || mimeType in ALPHA_CAPABLE_MIME_TYPES) Mode.STICKER else Mode.THUMBNAIL
    }

    fun clear(imageView: ImageView) {
        PendingRenders.cancelOn(imageView)
        imageView.setTag(R.id.image_renderer_last_render, null)
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
        data.preservedFile?.let { file ->
            GlideApp.with(contextView)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(target)
            return
        }
        val isLocalUri = data.allowNonMxcUrls && data.url.isLocalMediaUri()
        val req = if (data.elementToDecrypt != null) {
            // Encrypted image
            GlideApp
                    .with(contextView)
                    .load(data)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else if (isLocalUri) {
            // Local-echo uri — load as Uri so Glide can use a ParcelFileDescriptor and
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
                // Without a size this resolves to the view's, which is a different cache key from the
                // pager's SIZE_ORIGINAL request that follows — decoding the same bytes twice. For an
                // image no larger than the screen both decodes produce the same bitmap anyway, so ask
                // for the same one and let the second load hit the memory cache. Bigger images keep
                // the view-sized decode so the transition isn't held up by a full-resolution one.
                .let { if (data.fitsOnScreen(imageView)) it.override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) else it }

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
        data.preservedFile?.let { file ->
            return glideRequests.load(file).diskCacheStrategy(DiskCacheStrategy.NONE)
        }
        val localCopy = lazy { localCopyOf(data) }
        val isLocalUri = data.allowNonMxcUrls && data.url.isLocalMediaUri()
        val isLocalVideoUri = isLocalUri && data.mimeType?.startsWith("video/") == true
        val request = if (isLocalVideoUri) {
            // Local-echo video — load the uri directly so Glide can use a
            // ParcelFileDescriptor and run VideoBitmapDecoder to extract a frame as the thumbnail.
            // The InputStream-based path used below only decodes still images.
            // frame(0): pin to t=0 (OPTION_CLOSEST_SYNC) to match the upload worker's thumbnail
            // (ThumbnailExtractor uses getFrameAtTime(0, …)); Glide's default representative frame
            // otherwise differs, so the poster visibly jumps when the server thumbnail swaps in.
            glideRequests
                    .load(android.net.Uri.parse(data.url))
                    .frame(0)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
        } else if (data.elementToDecrypt != null || isLocalUri) {
            // Encrypted image, or local-echo uri — go through our custom data loader so
            // Glide always sees an InputStream. The default local-uri loader otherwise prefers a
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
                Mode.STICKER -> resolveUrl(data, localCopy)
                // The server can't thumbnail bytes it doesn't have yet, and shouldn't be asked to
                // re-serve what we already hold.
                Mode.THUMBNAIL -> localCopy.value
                        ?: contentUrlResolver.resolveThumbnail(data.url, size.width, size.height, ContentUrlResolver.ThumbnailMethod.SCALE)
            }
            // Fallback to base url
                    ?: data.url.takeIf { it.isLocalMediaUri() }

            glideRequests
                    .load(resolvedUrl)
                    .apply {
                        if (mode == Mode.THUMBNAIL) {
                            error(
                                    decorateWithBlurHash(glideRequests.load(resolveUrl(data, localCopy)), data, localCopy)
                            )
                        }
                    }
        }
        return decorateWithBlurHash(request, data, localCopy)
    }

    // Glide's request-equivalence check compares placeholders by reference: a fresh BlurHashDrawable
    // per bind makes every rebind a "new" request, resetting to the blurhash and replaying the fade.
    private val blurHashPlaceholders = android.util.LruCache<String, BlurHashDrawable>(64)

    // Memoised for the same reason, and per message rather than shared: this one animates, and a
    // single instance driven by several visible views at once would fight over bounds.
    private val placeholders = android.util.LruCache<String, MediaPlaceholderDrawable>(64)

    /**
     * One per message, kept across rebinds: the pulse and the failure fade live in this object, so
     * handing back the same instance is what makes those continuous instead of restarting.
     */
    private fun placeholderFor(data: Data, showGlyph: Boolean, square: Boolean = false): MediaPlaceholderDrawable {
        val key = "${data.stableId}:${data.blurHash}"
        return synchronized(placeholders) {
            placeholders.get(key) ?: MediaPlaceholderDrawable(
                    context = context,
                    blurHash = data.blurHash?.let { BlurHashDrawable.from(it, data.width, data.height, pulse = false) },
                    showGlyph = showGlyph && (data.url != null || data.preservedFile != null),
            ).also { placeholders.put(key, it) }
        }.also {
            // The fade-out transition marks the blurhash finished when an image lands, after which
            // it draws nothing at all. Re-arm it for this load, or a reused placeholder shows the
            // scrim over bare transparency instead of the hash it was built with.
            it.blurHash?.reset()
            it.boundedWait = !data.isUploading()
            // Stated on every retrieval: one cached instance serves a grid tile and the same media
            // shown elsewhere, and only its current view knows which shape is right.
            it.setSquareCorners(square)
        }
    }

    private fun decorateWithBlurHash(
            request: GlideRequest<Drawable>,
            data: Data,
            localCopy: Lazy<File?> = lazy { localCopyOf(data) },
    ): GlideRequest<Drawable> {
        // A blurhash is a stand-in for a download. With the bytes already on disk there is nothing to
        // stand in for, and showing one only produces a flash on every rebind.
        if (localCopy.value != null) {
            return request
        }
        // No blurhash still means a download is in flight, and an empty box reads as nothing
        // happening. Hold the same fill the failure placeholder falls back to, so the box is visibly
        // occupied for the wait and only the glyph changes if it ends badly.
        // No blurhash — video thumbnails rarely carry one — still means going from the waiting state
        // to a picture, so it gets an ordinary crossfade rather than appearing in a single frame.
        val blurHash = data.blurHash ?: return request.transition(DrawableTransitionOptions.with(REVEAL_FADE_FACTORY))
        val key = "${data.stableId}:$blurHash:${data.width}x${data.height}"
        val placeholder = synchronized(blurHashPlaceholders) {
            // The fade-out transition marks the instance finished when the image lands, after which it
            // draws nothing at all — re-arm it rather than replacing it, so a later slow load still has
            // something to show without the swap counting as a new request.
            blurHashPlaceholders.get(key)?.also { it.reset() }
                    ?: BlurHashDrawable.from(blurHash, data.width, data.height)?.also { blurHashPlaceholders.put(key, it) }
        } ?: return request
        return request.placeholder(placeholder)
                .transition(DrawableTransitionOptions.with(BLURHASH_FADE_FACTORY))
    }

    private fun resolveUrl(data: Data, localCopy: Lazy<File?> = lazy { localCopyOf(data) }): Any? {
        localCopy.value?.let { return it }
        val session = activeSessionHolder.getActiveSession()
        return session.contentUrlResolver().resolveFullSize(data.url)
                ?: data.url?.takeIf { localFilesHelper.isLocalFile(data.url) && data.allowNonMxcUrls }
    }

    /**
     * Bytes we already hold for this content: still uploading (MSC2246), or downloaded earlier. Serving
     * these keeps us from re-fetching our own just-sent media, and from asking the homeserver for a URI
     * whose bytes have not landed yet.
     */
    private fun localCopyOf(data: Data): File? {
        return activeSessionHolder.getActiveSession().fileService()
                .getLocalFileFor(data.url, data.filename, data.mimeType, data.elementToDecrypt != null)
    }

    private fun Data.hasKnownDimensions(): Boolean = (width ?: 0) > 0 && (height ?: 0) > 0

    /** Stand-in box for media that never declared its dimensions. Square is the least wrong guess. */
    private fun Data.loadingSquare(): Size = min(maxWidth, maxHeight).let { Size(it, it) }

    private fun Data.fitsOnScreen(imageView: ImageView): Boolean {
        val metrics = imageView.resources.displayMetrics
        val w = width ?: return false
        val h = height ?: return false
        return w in 1..metrics.widthPixels && h in 1..metrics.heightPixels
    }

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
