/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar

import androidx.annotation.StringRes
import im.vector.lib.strings.CommonStrings

/** What to draw in an avatar when the user or room has none. */
enum class DefaultAvatarStyle(@StringRes val titleRes: Int) {
    ELEMENT(CommonStrings.avatar_style_element),
    GENERIC(CommonStrings.avatar_style_generic),
    TWITTER_EGG(CommonStrings.avatar_style_twitter_egg),
    HASHTAG(CommonStrings.avatar_style_hashtag);

    companion object {
        val PEOPLE = listOf(ELEMENT, GENERIC, TWITTER_EGG)
        val ROOM = listOf(ELEMENT, HASHTAG)

        fun of(name: String?, among: List<DefaultAvatarStyle>) = among.firstOrNull { it.name == name } ?: ELEMENT
    }
}
