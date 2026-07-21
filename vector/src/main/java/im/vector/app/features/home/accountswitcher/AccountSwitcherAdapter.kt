/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.accountswitcher

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.R
import im.vector.app.core.session.AccountInfoCache
import im.vector.app.features.home.AvatarRenderer
import org.matrix.android.sdk.api.util.MatrixItem

data class AccountSwitcherEntry(
        val sessionId: String,
        val userId: String,
        val displayName: String?,
        /**
         * Non-null only when the suffix should be rendered next to the user-id (i.e. more than
         * one signed-in account shares this exact MXID). Null in all other cases.
         */
        val homeServerHost: String?,
        val isActive: Boolean,
        /**
         * Live avatar MXC URL — only ever populated for the [isActive] row. Used so the active
         * row renders immediately on first activation (before the on-disk avatar cache has been
         * seeded) and stays fresh while sync is running. Non-active rows MUST leave this null
         * to keep their avatar lookups offline-only.
         */
        val liveAvatarUrl: String?,
)

class AccountSwitcherAdapter(
        private val avatarRenderer: AvatarRenderer,
        private val accountInfoCache: AccountInfoCache,
        private val onAccountClick: (AccountSwitcherEntry) -> Unit,
        private val onLogoutClick: (AccountSwitcherEntry) -> Unit,
        private val onAddAccountClick: () -> Unit,
) : ListAdapter<AccountSwitcherAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    sealed class Item {
        data class Account(val entry: AccountSwitcherEntry) : Item()
        object Add : Item()
    }

    fun submit(entries: List<AccountSwitcherEntry>) {
        submitList(entries.map<AccountSwitcherEntry, Item> { Item.Account(it) } + Item.Add)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Item.Account -> VIEW_TYPE_ACCOUNT
        Item.Add -> VIEW_TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ACCOUNT -> AccountVH(inflater.inflate(R.layout.item_account_switcher, parent, false))
            else -> AddVH(inflater.inflate(R.layout.item_account_switcher_add, parent, false)).also { holder ->
                holder.itemView.throttledClicks { onAddAccountClick() }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Account -> (holder as AccountVH).bind(item.entry)
            Item.Add -> Unit
        }
    }

    private inner class AccountVH(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar = view.findViewById<ImageView>(R.id.accountSwitcherItemAvatar)
        private val activeMark = view.findViewById<ImageView>(R.id.accountSwitcherItemActiveIndicator)
        private val displayName = view.findViewById<TextView>(R.id.accountSwitcherItemDisplayName)
        private val userId = view.findViewById<TextView>(R.id.accountSwitcherItemUserId)
        private val logout = view.findViewById<ImageView>(R.id.accountSwitcherItemLogout)

        fun bind(entry: AccountSwitcherEntry) {
            if (entry.isActive) {
                // Active row: render via the live session. The MXC URL belongs to *this*
                // account's own homeserver, so AuthenticatedGlideUrlLoader hitting the active
                // session is no leak — and it removes the first-activation "raw MXID" flicker
                // before the cache has been seeded.
                avatarRenderer.render(MatrixItem.UserItem(entry.userId, entry.displayName, entry.liveAvatarUrl), avatar)
            } else {
                // Non-active row: read the cached binary that was written while *this* account
                // was active, never the network. Guarantees we never ask another account's
                // homeserver to resolve/proxy this account's media.
                val matrixItem = MatrixItem.UserItem(entry.userId, entry.displayName, null)
                val cached = accountInfoCache.avatarFileFor(entry.sessionId).takeIf { it.exists() && it.length() > 0 }
                avatarRenderer.render(matrixItem, cached?.let { Uri.fromFile(it) }, avatar)
            }
            displayName.text = (entry.displayName?.takeIf { it.isNotBlank() } ?: entry.userId).prepareForDisplay()
            userId.text = entry.homeServerHost?.let { "${entry.userId} — $it" } ?: entry.userId
            activeMark.isVisible = entry.isActive
            logout.isVisible = !entry.isActive
            itemView.throttledClicks { onAccountClick(entry) }
            logout.throttledClicks { onLogoutClick(entry) }
        }
    }

    private class AddVH(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val VIEW_TYPE_ACCOUNT = 0
        private const val VIEW_TYPE_ADD = 1
        private const val CLICK_THROTTLE_MS = 500L

        // Shared across every clickable element in the switcher: tapping two different rows
        // back-to-back (e.g. two account rows, or a row and the "+" footer) still only fires
        // the first one within the throttle window. We use a long instead of AtomicLong since
        // these clicks are main-thread only.
        private var lastClickAt = 0L

        private fun View.throttledClicks(onClicked: () -> Unit) {
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastClickAt >= CLICK_THROTTLE_MS) {
                    lastClickAt = now
                    onClicked()
                }
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = when {
                oldItem is Item.Account && newItem is Item.Account -> oldItem.entry.sessionId == newItem.entry.sessionId
                oldItem === Item.Add && newItem === Item.Add -> true
                else -> false
            }

            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }
    }
}
