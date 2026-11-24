/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyViewHolder
import im.vector.app.features.home.room.detail.timeline.render.TimelineRenderingScope

internal class TimelineArchiveAdapter(
        private val doc: TimelineExportRenderer.Document,
        private val indices: List<Int> = doc.models.indices.toList(),
        private val onBound: (Int, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<TimelineArchiveAdapter.RowHolder>() {
    class RowHolder(val epoxy: EpoxyViewHolder) : RecyclerView.ViewHolder(epoxy.itemView)

    private val kinds = doc.models.map { it.javaClass to it.layout }.distinct()
    override fun getItemCount() = indices.size
    override fun getItemViewType(position: Int) = kinds.indexOf(doc.models[indices[position]].let { it.javaClass to it.layout })
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder = TimelineRenderingScope.render(doc.context) {
        val model = doc.models.first { (it.javaClass to it.layout) == kinds[viewType] }
        val nativeParent = FrameLayout(doc.context)
        RowHolder(EpoxyViewHolder(nativeParent, model.buildView(nativeParent), false))
    }
    override fun onBindViewHolder(holder: RowHolder, position: Int) = TimelineRenderingScope.render(doc.context) {
        val index = indices[position]
        holder.epoxy.bind(doc.models[index], null, emptyList(), position)
        doc.configureView(index, holder.itemView)
        onBound(index, holder.itemView)
    }
    override fun onViewRecycled(holder: RowHolder) { holder.epoxy.unbind(); doc.context.forgetViews(holder.itemView) }
}
