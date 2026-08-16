/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import im.vector.app.R
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.ui.views.GalleryGridLayout
import im.vector.app.core.ui.views.RoundedCornerImageView
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.galleryPageId
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageInfoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.model.message.getThumbnailUrl
import kotlin.math.roundToInt

/** The tile render data of a gallery, in item order, as [GalleryGridBinder] takes it. */
fun MessageGalleryContent.toGalleryTiles(
        eventId: String,
        stableId: String,
        maxWidth: Int,
        maxHeight: Int,
        allowNonMxcUrls: Boolean = false,
        items: List<MessageWithAttachmentContent> = galleryItems(),
): List<MessageGalleryItem.TileData> {
    return items.mapIndexed { index, item ->
        when (item) {
            is MessageVideoContent -> {
                val info = item.videoInfo
                val aspect = if (info != null && info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 1f
                MessageGalleryItem.TileData(
                        MessageGalleryItem.TileType.VIDEO,
                        galleryTileThumbnailData(item, index, eventId, stableId, maxWidth, maxHeight, allowNonMxcUrls),
                        aspect,
                        item.getFileName(),
                )
            }
            is MessageImageInfoContent -> {
                val info = item.info
                val aspect = if (info != null && info.width > 0 && info.height > 0) info.width.toFloat() / info.height else 1f
                MessageGalleryItem.TileData(
                        MessageGalleryItem.TileType.IMAGE,
                        galleryTileThumbnailData(item, index, eventId, stableId, maxWidth, maxHeight, allowNonMxcUrls),
                        aspect,
                        item.getFileName(),
                )
            }
            is MessageAudioContent -> MessageGalleryItem.TileData(MessageGalleryItem.TileType.AUDIO, null, 1f, item.getFileName())
            else -> MessageGalleryItem.TileData(MessageGalleryItem.TileType.FILE, null, 1f, item.getFileName())
        }
    }
}

fun galleryTileThumbnailData(
        item: MessageWithAttachmentContent,
        index: Int,
        eventId: String,
        stableId: String,
        maxWidth: Int,
        maxHeight: Int,
        allowNonMxcUrls: Boolean,
): ImageContentRenderer.Data {
    val video = item as? MessageVideoContent
    val imageInfo = (item as? MessageImageInfoContent)?.info
    val thumbnailInfo = video?.videoInfo?.thumbnailInfo?.takeIf { it.width > 0 && it.height > 0 }
    return ImageContentRenderer.Data(
            eventId = eventId,
            stableId = galleryPageId(stableId, index),
            galleryIndex = index,
            filename = item.getFileName(),
            mimeType = item.mimeType,
            url = if (video != null) video.videoInfo?.getThumbnailUrl() else item.getFileUrl(),
            elementToDecrypt = (video?.videoInfo?.thumbnailFile ?: item.encryptedFileInfo.takeIf { video == null })?.toElementToDecrypt(),
            height = thumbnailInfo?.height ?: video?.videoInfo?.height ?: imageInfo?.height,
            maxHeight = maxHeight,
            width = thumbnailInfo?.width ?: video?.videoInfo?.width ?: imageInfo?.width,
            maxWidth = maxWidth,
            allowNonMxcUrls = allowNonMxcUrls,
            blurHash = imageInfo?.blurHash ?: video?.videoInfo?.blurHash,
    )
}

/**
 * Fills a [GalleryGridLayout] with tile views — one shared implementation for every surface
 * that shows a gallery (timeline message, reply header, long-press sheet).
 */
object GalleryGridBinder {

    fun bind(
            grid: GalleryGridLayout,
            tiles: List<MessageGalleryItem.TileData>,
            maxWidth: Int,
            cornerRadiusPx: Int,
            imageContentRenderer: ImageContentRenderer,
            hideMedia: Boolean = false,
            hideMediaSolidColor: Boolean = false,
            tileClickListener: ((Int, View) -> Unit)? = null,
            tileLongClickListener: ((Int) -> Boolean)? = null,
    ) {
        val context = grid.context
        val layout = GalleryLayoutHelper.layout(tiles.map { it.aspect })
        grid.maxContentWidth = maxWidth

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val r = cornerRadiusPx.toFloat()
            grid.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            grid.clipToOutline = true
        }

        val inflater = LayoutInflater.from(context)
        while (grid.childCount < tiles.size) {
            grid.addView(inflater.inflate(R.layout.item_timeline_gallery_tile, grid, false))
        }
        if (grid.childCount > tiles.size) {
            grid.removeViews(tiles.size, grid.childCount - tiles.size)
        }
        grid.setLayoutSpec(layout.tiles, layout.totalHeight)

        val placeholderColor = ThemeUtils.getColor(context, im.vector.lib.ui.styles.R.attr.vctr_system)
        tiles.forEachIndexed { index, tile ->
            val tileView = grid.getChildAt(index)
            val imageView = tileView.findViewById<RoundedCornerImageView>(R.id.galleryTileImage)
            val placeholderIcon = tileView.findViewById<ImageView>(R.id.galleryTilePlaceholderIcon)
            val playIcon = tileView.findViewById<ImageView>(R.id.galleryTilePlayIcon)
            val spec = layout.tiles.getOrNull(index)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                val flags = spec?.flags ?: 0
                val r = cornerRadiusPx.toFloat()
                imageView.setCornerRadii(
                        if (flags and GalleryLayoutHelper.FLAG_LEFT != 0 && flags and GalleryLayoutHelper.FLAG_TOP != 0) r else 0f,
                        if (flags and GalleryLayoutHelper.FLAG_RIGHT != 0 && flags and GalleryLayoutHelper.FLAG_TOP != 0) r else 0f,
                        if (flags and GalleryLayoutHelper.FLAG_RIGHT != 0 && flags and GalleryLayoutHelper.FLAG_BOTTOM != 0) r else 0f,
                        if (flags and GalleryLayoutHelper.FLAG_LEFT != 0 && flags and GalleryLayoutHelper.FLAG_BOTTOM != 0) r else 0f,
                )
            }

            when (tile.type) {
                MessageGalleryItem.TileType.IMAGE, MessageGalleryItem.TileType.VIDEO -> {
                    placeholderIcon.isVisible = false
                    playIcon.isVisible = !hideMedia && tile.type == MessageGalleryItem.TileType.VIDEO
                    imageView.contentDescription = tile.filename
                    val data = tile.mediaData
                    val tileW = spec?.let { (it.w * maxWidth).roundToInt().coerceAtLeast(1) } ?: 0
                    val tileH = spec?.let { (it.h * maxWidth).roundToInt().coerceAtLeast(1) } ?: 0
                    when {
                        data == null || maxWidth <= 0 || spec == null -> imageContentRenderer.clear(imageView)
                        hideMedia -> imageContentRenderer.renderHidden(data, ImageContentRenderer.Mode.THUMBNAIL, imageView, hideMediaSolidColor)
                        else -> imageContentRenderer.render(data, imageView, tileW, tileH)
                    }
                }
                MessageGalleryItem.TileType.FILE, MessageGalleryItem.TileType.AUDIO -> {
                    // clear(), not a bare Glide clear: it also drops the render tag, without which a
                    // tile recycled file -> image keeps this placeholder.
                    imageContentRenderer.clear(imageView)
                    imageView.setImageDrawable(ColorDrawable(placeholderColor))
                    placeholderIcon.setImageResource(
                            if (tile.type == MessageGalleryItem.TileType.FILE) R.drawable.ic_paperclip else R.drawable.ic_music_note
                    )
                    // The note's artwork sits inside its viewport; the paperclip fills it.
                    val iconDp = if (tile.type == MessageGalleryItem.TileType.AUDIO) 48 else 40
                    val iconPx = (iconDp * context.resources.displayMetrics.density).roundToInt()
                    placeholderIcon.updateLayoutParams {
                        width = iconPx
                        height = iconPx
                    }
                    placeholderIcon.isVisible = true
                    playIcon.isVisible = false
                    imageView.contentDescription = tile.filename
                }
            }

            if (tileClickListener != null) {
                tileView.onClick { tileClickListener.invoke(index, imageView) }
            } else {
                // Non-interactive surface (reply header, sheet preview): taps must fall through
                // to the container's own click handling.
                tileView.setOnClickListener(null)
                tileView.isClickable = false
            }
            if (tileLongClickListener != null) {
                tileView.setOnLongClickListener { tileLongClickListener.invoke(index) }
            } else {
                tileView.setOnLongClickListener(null)
                tileView.isLongClickable = false
            }
        }
    }

    fun unbind(grid: GalleryGridLayout, imageContentRenderer: ImageContentRenderer) {
        for (i in 0 until grid.childCount) {
            val tileView = grid.getChildAt(i)
            val imageView = tileView.findViewById<RoundedCornerImageView>(R.id.galleryTileImage)
            imageContentRenderer.clear(imageView)
            tileView.setOnClickListener(null)
            tileView.setOnLongClickListener(null)
        }
    }
}
