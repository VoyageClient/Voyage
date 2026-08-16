/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.core.glide.GlideApp
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.themes.ThemeUtils

class SearchSuggestionAdapter(
        private val avatarRenderer: AvatarRenderer,
        private val onClick: (SearchSuggestion) -> Unit,
) : ListAdapter<SearchSuggestion, SearchSuggestionAdapter.ViewHolder>(DIFF) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.searchSuggestionIcon)
        val label: TextView = view.findViewById(R.id.searchSuggestionLabel)
        val hint: TextView = view.findViewById(R.id.searchSuggestionHint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val suggestion = getItem(position)
        holder.label.text = suggestion.label
        holder.hint.text = suggestion.hint
        holder.hint.isVisible = suggestion.hint != null
        val avatar = suggestion.avatar
        if (avatar != null) {
            ImageViewCompat.setImageTintList(holder.icon, null)
            avatarRenderer.render(avatar, holder.icon)
            // So picking this member draws its pill with the avatar already in hand.
            avatarRenderer.preloadAvatar(GlideApp.with(holder.itemView), avatar)
        } else {
            avatarRenderer.clear(holder.icon)
            holder.icon.setImageResource(suggestion.icon)
            val tint = ThemeUtils.getColor(holder.icon.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
            ImageViewCompat.setImageTintList(holder.icon, ColorStateList.valueOf(tint))
        }
        holder.itemView.setOnClickListener { onClick(suggestion) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchSuggestion>() {
            override fun areItemsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion) =
                    (oldItem.avatar?.id ?: oldItem.label) == (newItem.avatar?.id ?: newItem.label)
            override fun areContentsTheSame(oldItem: SearchSuggestion, newItem: SearchSuggestion) = oldItem == newItem
        }
    }
}
