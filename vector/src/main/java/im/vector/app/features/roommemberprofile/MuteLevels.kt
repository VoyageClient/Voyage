/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import org.matrix.android.sdk.api.session.delayedevents.PowerLevelRestore
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.eventsDefaultOrDefault
import org.matrix.android.sdk.api.session.room.model.usersDefaultOrDefault

/**
 * Muting puts a user one level below what sending a message takes.
 */
object MuteLevels {

    fun sendLevel(content: PowerLevelsContent?): Int =
            content?.events?.get(EventType.MESSAGE) ?: content.eventsDefaultOrDefault()

    fun mutedLevel(content: PowerLevelsContent?): Int = sendLevel(content) - 1

    fun isMuted(content: PowerLevelsContent?, userId: String): Boolean =
            userLevel(content, userId) < sendLevel(content)

    fun userLevel(content: PowerLevelsContent?, userId: String): Int =
            content?.users?.get(userId) ?: content.usersDefaultOrDefault()

    /**
     * The level to give back on unmute: whatever a pending temporary mute would have restored, otherwise
     * the room default when that can send, otherwise the send level. Null means users_default.
     */
    fun unmuteLevel(content: PowerLevelsContent?, restore: PowerLevelRestore?): Int? = when {
        restore != null -> restore.level
        content.usersDefaultOrDefault() >= sendLevel(content) -> null
        else -> sendLevel(content)
    }

    /** What a temporary mute gives back; for someone already muted, what unmuting them would. */
    fun levelToRestore(content: PowerLevelsContent?, userId: String, restore: PowerLevelRestore?): Int? =
            if (isMuted(content, userId)) unmuteLevel(content, restore) else content?.users?.get(userId)
}
