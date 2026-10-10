/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.resources

import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject

class UserPreferencesProvider @Inject constructor(private val vectorPreferences: VectorPreferences) {

    fun shouldShowHiddenEvents(roomId: String? = null): Boolean {
        return vectorPreferences.shouldShowHiddenEvents(roomId)
    }

    fun shouldShowHiddenThreadEvents(roomId: String? = null): Boolean {
        return vectorPreferences.shouldShowHiddenThreadEvents(roomId)
    }

    fun shouldShowReadReceipts(): Boolean {
        return vectorPreferences.showReadReceipts()
    }

    fun shouldShowRedactedMessages(roomId: String? = null): Boolean {
        return vectorPreferences.showRedactedMessages(roomId)
    }

    fun shouldShowLongClickOnRoomHelp(): Boolean {
        return vectorPreferences.shouldShowLongClickOnRoomHelp()
    }

    fun neverShowLongClickOnRoomHelpAgain() {
        vectorPreferences.neverShowLongClickOnRoomHelpAgain()
    }

    fun shouldShowMembershipEvents(roomId: String? = null): Boolean {
        return vectorPreferences.showMembershipEvents(roomId)
    }

    fun shouldShowAvatarDisplayNameChanges(roomId: String? = null): Boolean {
        return vectorPreferences.showAvatarDisplayNameChangeMessages(roomId)
    }

    fun shouldShowRoomSettingsEvents(roomId: String? = null): Boolean {
        return vectorPreferences.showRoomSettingsEvents(roomId)
    }

    fun shouldShowAclEvents(roomId: String? = null): Boolean {
        return vectorPreferences.showAclEvents(roomId)
    }

    fun shouldShowReactions(roomId: String? = null): Boolean {
        return vectorPreferences.showReactions(roomId)
    }

    fun areThreadMessagesEnabled(): Boolean {
        return vectorPreferences.areThreadMessagesEnabled()
    }

    fun showLiveSenderInfo(): Boolean {
        return vectorPreferences.showLiveSenderInfo()
    }

    fun autoplayAnimatedImages(): Boolean {
        return vectorPreferences.autoplayAnimatedImages()
    }
}
