/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.core.contacts.MappedContact
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.ClippedDrawableImageViewTarget
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.glide.GlideRequest
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.glide.RoundedCornersPercent
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.emoji.TwemojiProvider
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.auth.login.LoginProfileInfo
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.util.MatrixItem
import java.io.File
import javax.inject.Inject

/**
 * This helper centralise ways to retrieve avatar into ImageView or even generic Target<Drawable>.
 */
class AvatarRenderer @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val dimensionConverter: DimensionConverter,
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
        private val twemojiProvider: TwemojiProvider,
) {

    companion object {
        private const val THUMBNAIL_SIZE = 250
        // Rounded-square corner radius as a fraction of the avatar's shorter side, so the rounding
        // looks the same whether the avatar is a tiny read receipt or a large profile header.
        internal const val ROUNDED_CORNER_PERCENT = 0.20f
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView) {
        imageView.setContentDescription(matrixItem)
        render(
                GlideApp.with(imageView),
                matrixItem,
                avatarTarget(imageView, matrixItem),
        )
    }

    // Clips avatars to the configured shape (circle / rounded square / square) for animated drawables
    // and placeholders too — a cross-version replacement for clipToOutline (API 21+). Static images are
    // already shaped by the Glide transforms below and pass through untouched.
    private fun avatarTarget(imageView: ImageView, matrixItem: MatrixItem): DrawableImageViewTarget {
        val oval = shapeFor(matrixItem) == AvatarShape.CIRCLE
        return ClippedDrawableImageViewTarget(imageView, cornerPercent(matrixItem), oval = oval)
    }

    // Spaces always render as rounded squares, regardless of the avatar-shape setting.
    private fun shapeFor(matrixItem: MatrixItem): AvatarShape =
            if (matrixItem is MatrixItem.SpaceItem) AvatarShape.ROUNDED else vectorPreferences.avatarShape()

    private fun cornerPercent(matrixItem: MatrixItem): Float =
            if (shapeFor(matrixItem) == AvatarShape.ROUNDED) ROUNDED_CORNER_PERCENT else 0f

    private fun avatarTransform(matrixItem: MatrixItem): Transformation<Bitmap> = when (shapeFor(matrixItem)) {
        AvatarShape.CIRCLE -> CircleCrop()
        AvatarShape.ROUNDED -> MultiTransformation(CenterCrop(), RoundedCornersPercent(ROUNDED_CORNER_PERCENT))
        AvatarShape.SQUARE -> CenterCrop()
    }

//    fun renderSpace(matrixItem: MatrixItem, imageView: ImageView) {
//        renderSpace(
//                matrixItem,
//                imageView,
//                GlideApp.with(imageView)
//        )
//    }
//
//    @UiThread
//    private fun renderSpace(matrixItem: MatrixItem, imageView: ImageView, glideRequests: GlideRequests) {
//        val placeholder = getSpacePlaceholderDrawable(matrixItem)
//        val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
//        glideRequests
//                .load(resolvedUrl)
//                .transform(MultiTransformation(CenterCrop(), RoundedCorners(dimensionConverter.dpToPx(8))))
//                .placeholder(placeholder)
//                .into(DrawableImageViewTarget(imageView))
//    }

    fun clear(imageView: ImageView) {
        // It can be called after recycler view is destroyed, just silently catch
        tryOrNull { GlideApp.with(imageView).clear(imageView) }
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView, glideRequests: GlideRequests) {
        imageView.setContentDescription(matrixItem)
        render(
                glideRequests,
                matrixItem,
                avatarTarget(imageView, matrixItem),
        )
    }

    @UiThread
    fun render(matrixItem: MatrixItem, localUri: Uri?, imageView: ImageView) {
        imageView.setContentDescription(matrixItem)
        val placeholder = getPlaceholderDrawable(matrixItem)
        GlideApp.with(imageView)
                .load(localUri?.let { File(localUri.path!!) })
                .transform(avatarTransform(matrixItem))
                .placeholder(placeholder)
                .into(avatarTarget(imageView, matrixItem))
    }

    @UiThread
    fun render(mappedContact: MappedContact, imageView: ImageView) {
        // Create a Fake MatrixItem, for the placeholder
        val matrixItem = MatrixItem.UserItem(
                // Need an id starting with @
                id = "@${mappedContact.displayName}",
                displayName = mappedContact.displayName,
        )

        val placeholder = getPlaceholderDrawable(matrixItem)
        GlideApp.with(imageView)
                .load(mappedContact.photoURI)
                .transform(avatarTransform(matrixItem))
                .placeholder(placeholder)
                .into(avatarTarget(imageView, matrixItem))
    }

    @UiThread
    fun render(profileInfo: LoginProfileInfo, imageView: ImageView) {
        // Create a Fake MatrixItem, for the placeholder
        val matrixItem = MatrixItem.UserItem(
                // Need an id starting with @
                id = profileInfo.matrixId,
                displayName = profileInfo.displayName,
        )

        val placeholder = getPlaceholderDrawable(matrixItem)
        GlideApp.with(imageView)
                .load(profileInfo.fullAvatarUrl)
                .transform(avatarTransform(matrixItem))
                .placeholder(placeholder)
                .into(avatarTarget(imageView, matrixItem))
    }

    @UiThread
    fun render(
            glideRequests: GlideRequests,
            matrixItem: MatrixItem,
            target: Target<Drawable>
    ) {
        val placeholder = getPlaceholderDrawable(matrixItem)
        glideRequests.loadResolvedUrl(matrixItem.avatarUrl)
                .transform(avatarTransform(matrixItem))
                .placeholder(placeholder)
                .into(target)
    }

    @AnyThread
    @Throws
    fun shortcutDrawable(glideRequests: GlideRequests, matrixItem: MatrixItem, iconSize: Int): Bitmap {
        return glideRequests
                .asBitmap()
                .avatarOrText(matrixItem, iconSize)
                .apply(RequestOptions.centerCropTransform())
                .submit(iconSize, iconSize)
                .get()
    }

    @AnyThread
    @Throws
    fun adaptiveShortcutDrawable(
            glideRequests: GlideRequests,
            matrixItem: MatrixItem, iconSize: Int,
            adaptiveIconSize: Int,
            adaptiveIconOuterSides: Float
    ): Bitmap {
        return glideRequests
                .asBitmap()
                .avatarOrText(matrixItem, iconSize)
                .transform(CenterCrop(), AdaptiveIconTransformation(adaptiveIconSize, adaptiveIconOuterSides))
                .signature(ObjectKey("adaptive-icon"))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .submit(iconSize, iconSize)
                .get()
    }

    private fun GlideRequest<Bitmap>.avatarOrText(matrixItem: MatrixItem, iconSize: Int): GlideRequest<Bitmap> {
        return this.let {
            val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
            if (resolvedUrl != null) {
                it.load(resolvedUrl)
            } else {
                val avatarColor = matrixItemColorProvider.getColor(matrixItem)
                val letter = matrixItem.firstLetterOfDisplayName()
                val placeholder = twemojiLetterDrawable(letter, avatarColor, AvatarShape.SQUARE)
                        ?: TextDrawable.builder()
                                .beginConfig()
                                .bold()
                                .endConfig()
                                .buildRect(letter, avatarColor)
                it.load(placeholder.toBitmap(width = iconSize, height = iconSize))
            }
        }
    }

    @AnyThread
    fun getCachedDrawable(glideRequests: GlideRequests, matrixItem: MatrixItem): Drawable {
        return glideRequests.loadResolvedUrl(matrixItem.avatarUrl)
                .onlyRetrieveFromCache(true)
                .transform(avatarTransform(matrixItem))
                .submit()
                .get()
    }

    @AnyThread
    fun getPlaceholderDrawable(matrixItem: MatrixItem): Drawable {
        val avatarColor = matrixItemColorProvider.getColor(matrixItem)
        val letter = matrixItem.firstLetterOfDisplayName()
        val shape = shapeFor(matrixItem)
        twemojiLetterDrawable(letter, avatarColor, shape)?.let { return it }
        // Self-shape the letter avatar (proportional corners) so it matches photo avatars at any size.
        return TextDrawable.builder()
                .beginConfig()
                .bold()
                .endConfig()
                .let {
                    when (shape) {
                        AvatarShape.CIRCLE -> it.buildRound(letter, avatarColor)
                        AvatarShape.ROUNDED -> it.buildRoundRectPercent(letter, avatarColor, ROUNDED_CORNER_PERCENT)
                        AvatarShape.SQUARE -> it.buildRect(letter, avatarColor)
                    }
                }
    }

    // A name can start with an emoji, making the "initial" an emoji cluster; TextDrawable renders it
    // via Paint.drawText, which has no glyph under Twemoji — draw the sprite over the coloured shape.
    private fun twemojiLetterDrawable(letter: String, avatarColor: Int, shape: AvatarShape): Drawable? {
        if (!twemojiProvider.enabled) return null
        val sprite = twemojiProvider.bitmapForEmoji(letter) ?: return null
        return TwemojiLetterDrawable(sprite, avatarColor, shape)
    }

    // PRIVATE API *********************************************************************************

    private fun GlideRequests.loadResolvedUrl(avatarUrl: String?): GlideRequest<Drawable> {
        val resolvedUrl = resolvedUrl(avatarUrl)
        return load(resolvedUrl)
    }

    private fun resolvedUrl(avatarUrl: String?): String? {
        return activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
                ?.resolveThumbnail(
                        avatarUrl, THUMBNAIL_SIZE, THUMBNAIL_SIZE, ContentUrlResolver.ThumbnailMethod.SCALE,
                        animated = vectorPreferences.autoplayAnimatedImages()
                )
    }

    /**
     * Accessibility management.
     */
    private fun ImageView.setContentDescription(matrixItem: MatrixItem) {
        // Do not set contentDescription if the ImageView should be ignored regarding accessibility.
        if (ViewCompat.isImportantForAccessibility(this).not()) return
        when (matrixItem) {
            is MatrixItem.SpaceItem -> {
                contentDescription = stringProvider.getString(CommonStrings.avatar_of_space, matrixItem.getBestName())
            }
            is MatrixItem.RoomAliasItem,
            is MatrixItem.RoomItem -> {
                contentDescription = stringProvider.getString(CommonStrings.avatar_of_room, matrixItem.getBestName())
            }
            is MatrixItem.UserItem -> {
                contentDescription = stringProvider.getString(CommonStrings.avatar_of_user, matrixItem.getBestName())
            }
            is MatrixItem.EveryoneInRoomItem,
            is MatrixItem.EventItem -> {
                // NA
            }
        }
    }
}

private class TwemojiLetterDrawable(
        private val sprite: Bitmap,
        color: Int,
        private val shape: AvatarShape,
) : Drawable() {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boundsF = RectF()
    private val spriteDst = RectF()

    override fun draw(canvas: Canvas) {
        boundsF.set(bounds)
        when (shape) {
            AvatarShape.CIRCLE -> canvas.drawOval(boundsF, backgroundPaint)
            AvatarShape.ROUNDED -> {
                val radius = minOf(boundsF.width(), boundsF.height()) * AvatarRenderer.ROUNDED_CORNER_PERCENT
                canvas.drawRoundRect(boundsF, radius, radius, backgroundPaint)
            }
            AvatarShape.SQUARE -> canvas.drawRect(boundsF, backgroundPaint)
        }
        // Match TextDrawable's letter proportions (~half the shorter side).
        val size = minOf(boundsF.width(), boundsF.height()) * SPRITE_RATIO
        spriteDst.set(0f, 0f, size, size)
        spriteDst.offset(boundsF.centerX() - size / 2, boundsF.centerY() - size / 2)
        canvas.drawBitmap(sprite, null, spriteDst, spritePaint)
    }

    override fun setAlpha(alpha: Int) {
        backgroundPaint.alpha = alpha
        spritePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        spritePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val SPRITE_RATIO = 0.55f
    }
}
