/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions.data

import com.vanniktech.emoji.Emoji
import com.vanniktech.emoji.emojis
import com.vanniktech.emoji.recent.RecentEmoji

/**
 * Backs the vanniktech emoji keyboard's "recent" tab with the remote `io.element.recent_emoji`
 * history: reads frequents for display and records each tapped emoji.
 */
class AccountDataRecentEmoji(
        private val recentEmojiDataSource: RecentEmojiDataSource,
) : RecentEmoji {

    override fun getRecentEmojis(): Collection<Emoji> {
        return recentEmojiDataSource.getRecentEmojisSnapshot()
                .sortedByDescending { it.second }
                .mapNotNull { it.first.emojis().firstOrNull()?.emoji }
                .distinct()
    }

    override fun addEmoji(emoji: Emoji) {
        recentEmojiDataSource.recordEmojiUse(listOf(emoji.unicode))
    }

    override fun persist() = Unit
}
