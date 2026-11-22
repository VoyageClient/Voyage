/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

class AttachmentsAdapter : RecyclerView.Adapter<BaseViewHolder>() {

    private var attachmentIds: List<String> = emptyList()
    private val stableIds = HashMap<String, Long>()
    private var nextStableId = 0L
    private val playbackPool = VideoPlaybackPool()
    private val videoHolders = WeakHashMap<VideoViewHolder, Unit>()

    init {
        setHasStableIds(true)
    }

    var attachmentSourceProvider: AttachmentSourceProvider? = null
        set(value) {
            val started = SystemClock.elapsedRealtime()
            val previous = attachmentIds
            val previousProvider = field
            val next = value?.let { provider -> (0 until provider.getItemCount()).map { provider.getAttachmentIdAt(it) } }.orEmpty()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        previous[oldItemPosition] == next[newItemPosition]

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = previousProvider?.getAttachmentInfoAt(oldItemPosition)
                    val new = value?.getAttachmentInfoAt(newItemPosition)
                    return if (old is AttachmentInfo.Video && new is AttachmentInfo.Video) {
                        old.url == new.url && old.resourceKey == new.resourceKey
                    } else {
                        old == new
                    }
                }
            })
            field = value
            attachmentIds = next
            diff.dispatchUpdatesTo(this)
            AttachmentViewerDebug.i { "stage=adapter-update previous=${previous.size} next=${next.size} elapsedMs=${SystemClock.elapsedRealtime() - started}" }
        }

    var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val itemView = inflater.inflate(viewType, parent, false)
        return when (viewType) {
            R.layout.item_image_attachment -> ZoomableImageViewHolder(itemView)
            R.layout.item_animated_image_attachment -> AnimatedImageViewHolder(itemView)
            R.layout.item_video_attachment -> VideoViewHolder(itemView).also {
                it.playbackPool = playbackPool
                videoHolders[it] = Unit
            }
            else -> UnsupportedViewHolder(itemView)
        }
    }

    override fun getItemViewType(position: Int): Int {
        val info = attachmentSourceProvider!!.getAttachmentInfoAt(position)
        return when (info) {
            is AttachmentInfo.Image -> R.layout.item_image_attachment
            is AttachmentInfo.Video -> R.layout.item_video_attachment
            is AttachmentInfo.AnimatedImage -> R.layout.item_animated_image_attachment
//            is AttachmentInfo.Audio         -> TODO()
//            is AttachmentInfo.File          -> TODO()
        }
    }

    override fun getItemCount(): Int {
        return attachmentIds.size
    }

    override fun getItemId(position: Int): Long = stableIds.getOrPut(attachmentIds[position]) { nextStableId++ }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        attachmentSourceProvider?.getAttachmentInfoAt(position)?.let {
            holder.bind(it)
            when (it) {
                is AttachmentInfo.Image -> {
                    attachmentSourceProvider?.loadImage((holder as ZoomableImageViewHolder).target, it)
                }
                is AttachmentInfo.AnimatedImage -> {
                    attachmentSourceProvider?.loadImage((holder as AnimatedImageViewHolder).target, it)
                }
                is AttachmentInfo.Video -> {
                    attachmentSourceProvider?.loadVideo((holder as VideoViewHolder).target, it)
                }
//                else                            -> {
// //                }
            }
        }
    }

    override fun onViewAttachedToWindow(holder: BaseViewHolder) {
        holder.onAttached()
    }

    override fun onViewRecycled(holder: BaseViewHolder) {
        holder.onRecycled()
    }

    override fun onViewDetachedFromWindow(holder: BaseViewHolder) {
        holder.onDetached()
    }

    fun isScaled(position: Int): Boolean {
        return when (val holder = recyclerView?.findViewHolderForAdapterPosition(position)) {
            is ZoomableImageViewHolder -> holder.views.touchImageView.attacher.scale > 1f
            is AnimatedImageViewHolder -> holder.views.imageView.attacher.scale > 1f
            is VideoViewHolder -> holder.isScaled()
            else -> false
        }
    }

    fun onPause(position: Int) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? BaseViewHolder
        holder?.entersBackground()
    }

    fun onResume(position: Int) {
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? BaseViewHolder
        holder?.entersForeground()
    }

    fun releasePlayers() {
        playbackPool.close()
        videoHolders.keys.toList().forEach { it.dispose() }
        videoHolders.clear()
    }
}
