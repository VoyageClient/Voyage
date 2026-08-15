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
import im.vector.app.core.glide.GlideApp
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
        if (enabled) {
            val shape = if (matrixItem is MatrixItem.SpaceItem) AvatarShape.ROUNDED else vectorPreferences.avatarShape()
            val cornerFraction = when (shape) {
                AvatarShape.CIRCLE -> 0.5f
                AvatarShape.ROUNDED -> AvatarRenderer.ROUNDED_CORNER_PERCENT
                AvatarShape.SQUARE -> 0f
            }
            val size = imageView.layoutParams.width
            imageView.background = GradientDrawable().apply {
                setColor(ThemeUtils.getColor(imageView.context, android.R.attr.colorBackground))
                cornerRadius = cornerFraction * size
            }
            val stroke = (imageView.resources.displayMetrics.density * 4).toInt()
            imageView.setPadding(stroke, stroke, stroke, stroke)
        } else {
            imageView.background = null
            imageView.setPadding(0, 0, 0, 0)
        }
    }

    companion object {
        private const val CROSSFADE_MS = 220

        // Cross-fading, not Glide's default: it otherwise keeps the pulsing placeholder as an opaque
        // layer under the banner, which a transparent image then shows through.
        private val FADE_FACTORY = DrawableCrossFadeFactory.Builder(CROSSFADE_MS)
                .setCrossFadeEnabled(true)
                .build()
    }
}
