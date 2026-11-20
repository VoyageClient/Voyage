/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.core.extensions.backgroundCompat
import java.util.Locale

/** The sampler's colors as the image editor's round swatches; tap to select, long-press and drag to reorder. */
class GradientSamplerSwatchAdapter(
        private val onSelected: (Int) -> Unit,
) : RecyclerView.Adapter<GradientSamplerSwatchAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    private var colors: List<Int> = emptyList()
    private var selected = -1

    fun submit(newColors: List<Int>, newSelected: Int) {
        val previous = colors
        val previousSelected = selected
        colors = newColors
        selected = newSelected
        val common = minOf(previous.size, newColors.size)
        (0 until common).forEach { index ->
            val wasSelected = index == previousSelected
            if (previous[index] != newColors[index] || wasSelected != (index == newSelected)) notifyItemChanged(index)
        }
        when {
            newColors.size > common -> notifyItemRangeInserted(common, newColors.size - common)
            previous.size > common -> notifyItemRangeRemoved(common, previous.size - common)
        }
    }

    /** Mirrors a drag in the row ahead of the sampler's own update, so that update finds nothing to redo. */
    fun move(from: Int, to: Int) {
        colors = colors.toMutableList().apply { add(to, removeAt(from)) }
        selected = when (selected) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> if (from < to) selected - 1 else selected + 1
            else -> selected
        }
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = colors.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val view = View(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams((30 * density).toInt(), (30 * density).toInt()).apply {
                leftMargin = (6 * density).toInt()
                rightMargin = (6 * density).toInt()
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        }
        return Holder(view).also { holder ->
            view.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onSelected(position)
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val color = colors[position]
        val density = holder.itemView.resources.displayMetrics.density
        // The same swatch as the image editor's color row.
        holder.itemView.backgroundCompat = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (position == selected) setStroke((3 * density).toInt(), Color.WHITE) else setStroke((1 * density).toInt(), UNSELECTED_RING)
        }
        holder.itemView.contentDescription = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }

    private companion object {
        const val UNSELECTED_RING = 0x80FFFFFF.toInt()
    }
}
