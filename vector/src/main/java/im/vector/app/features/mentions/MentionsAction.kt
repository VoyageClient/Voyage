/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import im.vector.app.core.platform.VectorViewModelAction

sealed interface MentionsAction : VectorViewModelAction {
    data class SetIncludeRoomMentions(val include: Boolean) : MentionsAction
    data class SetIncludeKeywords(val include: Boolean) : MentionsAction
    data class SetExcludeDms(val exclude: Boolean) : MentionsAction
    data class UpdateSearchQuery(val query: String) : MentionsAction
}
