/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import androidx.annotation.UiThread
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.home.avatar.AvatarShapeBackgroundDrawable
import im.vector.app.features.media.MediaPlaceholderDrawable
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomBannerContent
import org.matrix.android.sdk.api.util.MatrixItem
import java.util.WeakHashMap
import javax.inject.Inject

/**
 * Resolve the room banner url from the STATE_ROOM_BANNER (MSC4221) state events of a room.
 * The stable-typed event wins if it exists at all (even emptied): changes are written under both
 * types, so a stale unstable-typed event must not resurrect a removed banner.
 */
fun List<Event>.resolveRoomBannerUrl(): String? {
    val byType = associateBy { it.type }
    val event = byType[EventType.STATE_ROOM_BANNER.stable] ?: byType[EventType.STATE_ROOM_BANNER.unstable]
    return event?.content?.toModel<RoomBannerContent>()?.url?.takeIf { it.isNotEmpty() }
}

/**
 * Loads room (MSC4221) and profile (MSC4427) banner images (plain mxc urls) into ImageViews.
 */
class BannerRenderer @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val vectorPreferences: VectorPreferences,
) {

    // Glide compares placeholders by reference when deciding whether a request is equivalent, so the
    // same view must keep the same instance or every render restarts the load.
    private val placeholders = WeakHashMap<ImageView, MediaPlaceholderDrawable>()

    @UiThread
    fun render(mxcUrl: String?, imageView: ImageView) {
        val resolved = mxcUrl
                ?.takeIf { it.isNotEmpty() }
                ?.let { activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()?.resolveFullSize(it) }
        when {
            resolved == null -> {
                GlideApp.with(imageView).clear(imageView)
                imageView.setImageDrawable(null)
            }
            vectorPreferences.autoplayAnimatedImages() -> {
                GlideApp.with(imageView)
                        .load(resolved)
                        // optionalTransform, NOT centerCrop: a required transform fails animated
                        // (GIF/WebP) loads outright; the view's own centerCrop scales those instead.
                        .optionalTransform(CenterCrop())
                        .placeholder(placeholderFor(imageView))
                        .transition(DrawableTransitionOptions.with(FADE_FACTORY))
                        .into(imageView)
            }
            else -> {
                // Bitmap decode shows the first frame of animated banners
                GlideApp.with(imageView)
                        .asBitmap()
                        .load(resolved)
                        .centerCrop()
                        .placeholder(placeholderFor(imageView))
                        .transition(BitmapTransitionOptions.withCrossFade(FADE_FACTORY))
                        .into(imageView)
            }
        }
    }

    private fun placeholderFor(imageView: ImageView): MediaPlaceholderDrawable {
        return placeholders.getOrPut(imageView) { MediaPlaceholderDrawable(imageView.context, showGlyph = false) }
                // A reused placeholder that timed out has stopped animating; re-arm it for this load.
                .also { it.setFailed(false) }
    }

    /**
     * A ring of page-background colour around an avatar that overlaps a banner (as Haven draws it),
     * shaped to match the avatar shape.
     */
    @UiThread
    fun applyAvatarStroke(imageView: ImageView, matrixItem: MatrixItem?, enabled: Boolean) {
        if (!enabled) {
            imageView.backgroundCompat = null
            imageView.setPadding(0, 0, 0, 0)
            return
        }
        val shape = if (matrixItem is MatrixItem.SpaceItem) AvatarShape.ROUNDED else vectorPreferences.avatarShape()
        val color = ThemeUtils.getColor(imageView.context, android.R.attr.colorBackground)
        val size = imageView.layoutParams.width
        val stroke = imageView.resources.displayMetrics.density * STROKE_DP
        imageView.backgroundCompat = when {
            // An animated shape tumbles, so it has no fixed silhouette to trace: it gets a circle,
            // and is inset by however far it reaches so that it stays inside that circle.
            shape.isAnimated || shape == AvatarShape.CIRCLE -> ringDrawable(color, 0.5f * size)
            shape == AvatarShape.ROUNDED -> ringDrawable(color, AvatarRenderer.ROUNDED_CORNER_PERCENT * size)
            shape == AvatarShape.SQUARE -> ringDrawable(color, 0f)
            // A GradientDrawable has no way to describe a polygon.
            else -> AvatarShapeBackgroundDrawable(shape, color)
        }
        val inset = shape.effect?.let { inscribedInset(size, stroke, it.reach) } ?: stroke.toInt()
        imageView.setPadding(inset, inset, inset, inset)
    }

    private fun ringDrawable(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    /**
     * The padding that fits an animated shape inside the ring's circle, from how far that particular
     * shape draws. A rotating sphere needs none of it and keeps the plain stroke; a shockwave pushing
     * into the corners of its frame has to come in a long way.
     */
    private fun inscribedInset(size: Int, stroke: Float, reach: Float): Int {
        val fits = (size - 2 * stroke) / reach
        return maxOf(((size - fits) / 2f).toInt(), stroke.toInt())
    }

    companion object {
        private const val CROSSFADE_MS = 220
        private const val STROKE_DP = 4f

        // Cross-fading, not Glide's default: it otherwise keeps the pulsing placeholder as an opaque
        // layer under the banner, which a transparent image then shows through.
        private val FADE_FACTORY = DrawableCrossFadeFactory.Builder(CROSSFADE_MS)
                .setCrossFadeEnabled(true)
                .build()
    }
}
