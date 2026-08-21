/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.util

import org.matrix.android.sdk.BuildConfig
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoom
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.user.model.User
import java.util.Locale

sealed class MatrixItem(
        open val id: String,
        open val displayName: String?,
        open val avatarUrl: String?
) {
    data class UserItem(
            override val id: String,
            override val displayName: String? = null,
            override val avatarUrl: String? = null,
            // The user's own name, when [displayName] is a decorated label ("Message from Bob") whose
            // first letter is not the one the avatar placeholder should draw.
            val userDisplayName: String? = null
    ) :
            MatrixItem(id, displayName?.removeSuffix(IRC_PATTERN), avatarUrl) {

        init {
            if (BuildConfig.DEBUG) checkId()
        }

        override fun updateAvatar(newAvatar: String?) = copy(avatarUrl = newAvatar)
    }

    data class EveryoneInRoomItem(
            override val id: String,
            override val displayName: String = NOTIFY_EVERYONE,
            override val avatarUrl: String? = null,
            val roomDisplayName: String? = null
    ) :
            MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        override fun updateAvatar(newAvatar: String?) = copy(avatarUrl = newAvatar)
    }

    data class EventItem(
            override val id: String,
            override val displayName: String? = null,
            override val avatarUrl: String? = null
    ) :
            MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        override fun updateAvatar(newAvatar: String?) = copy(avatarUrl = newAvatar)
    }

    data class RoomItem(
            override val id: String,
            override val displayName: String? = null,
            override val avatarUrl: String? = null,
            val roomDisplayName: String? = null
    ) :
            MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        override fun updateAvatar(newAvatar: String?) = copy(avatarUrl = newAvatar)
    }

    data class SpaceItem(
            override val id: String,
            override val displayName: String? = null,
            override val avatarUrl: String? = null
    ) :
            MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        override fun updateAvatar(newAvatar: String?) = copy(avatarUrl = newAvatar)
    }

    data class RoomAliasItem(
            override val id: String,
            override val displayName: String? = null,
            override val avatarUrl: String? = null,
            val roomDisplayName: String? = null
    ) :
            MatrixItem(id, displayName, avatarUrl) {
        init {
            if (BuildConfig.DEBUG) checkId()
        }

        override fun updateAvatar(newAvatar: String?) = copy(avatarUrl = newAvatar)
    }

    protected fun checkId() {
        if (!id.startsWith(getIdPrefix())) {
            error("Wrong usage of MatrixItem: check the id $id should start with ${getIdPrefix()}")
        }
    }

    abstract fun updateAvatar(newAvatar: String?): MatrixItem

    /**
     * Return the prefix as defined in the matrix spec (and not extracted from the id).
     */
    private fun getIdPrefix() = when (this) {
        is UserItem -> '@'
        is EventItem -> '$'
        is SpaceItem,
        is RoomItem,
        is EveryoneInRoomItem -> '!'
        is RoomAliasItem -> '#'
    }

    fun firstLetterOfDisplayName(): String {
        val displayName = when (this) {
            // use the room display name for the notify everyone item
            is EveryoneInRoomItem -> roomDisplayName
            is RoomItem -> roomDisplayName ?: displayName
            is RoomAliasItem -> roomDisplayName ?: displayName
            is UserItem -> userDisplayName ?: displayName
            else -> displayName
        }
        return (displayName?.takeIf { it.isNotBlank() } ?: id)
                .let { dn ->
                    var startIndex = 0
                    val initial = dn[startIndex]

                    if (initial in listOf('@', '#', '+') && dn.length > 1) {
                        startIndex++
                    }

                    var length = 1
                    var first = dn[startIndex]

                    // LEFT-TO-RIGHT MARK
                    if (dn.length >= 2 && 0x200e == first.code) {
                        startIndex++
                        first = dn[startIndex]
                    }

                    // check if it’s the start of a surrogate pair
                    if (first.code in 0xD800..0xDBFF && dn.length > startIndex + 1) {
                        val second = dn[startIndex + 1]
                        if (second.code in 0xDC00..0xDFFF) {
                            length++
                        }
                    }

                    dn.substring(startIndex, startIndex + length)
                }
                .uppercase(Locale.ROOT)
    }

    companion object {
        private const val IRC_PATTERN = " (IRC)"
        const val NOTIFY_EVERYONE = "@room"
    }
}

/* ==========================================================================================
 * Extensions to create MatrixItem
 * ========================================================================================== */

fun User.toMatrixItem() = MatrixItem.UserItem(userId, displayName, avatarUrl)

fun RoomSummary.toMatrixItem() = if (roomType == RoomType.SPACE) {
    MatrixItem.SpaceItem(roomId, displayName, avatarUrl)
} else {
    MatrixItem.RoomItem(roomId, displayName, avatarUrl)
}

// A DM has no room identity of its own, so colour/identify it from the other user (a UserItem, tinted by
// user id) rather than the room (tinted by room id) — this makes its placeholder avatar match that user's
// name colour. The other user is the inviter for an inbound invite, else directUserId (outbound invite or a
// joined DM). The room's avatarUrl is kept, so a DM that has its own avatar still shows it. Non-DM rooms keep
// their own room item so they show their own avatar.
fun RoomSummary.toDisplayMatrixItem(): MatrixItem {
    if (!isDirect) return toMatrixItem()
    val otherUserId = if (membership == Membership.INVITE) inviterId else directUserId
    return otherUserId?.let { MatrixItem.UserItem(it, displayName, avatarUrl) } ?: toMatrixItem()
}

fun RoomSummary.toRoomAliasMatrixItem() = MatrixItem.RoomAliasItem(canonicalAlias ?: roomId, displayName, avatarUrl)

fun RoomSummary.toEveryoneInRoomMatrixItem() = MatrixItem.EveryoneInRoomItem(id = roomId, avatarUrl = avatarUrl, roomDisplayName = displayName)

// If no name is available, use room alias as Riot-Web does
fun PublicRoom.toMatrixItem() = MatrixItem.RoomItem(roomId, name ?: getPrimaryAlias() ?: "", avatarUrl)

fun RoomMemberSummary.toMatrixItem() = MatrixItem.UserItem(userId, displayName, avatarUrl)

fun SenderInfo.toMatrixItem() = MatrixItem.UserItem(userId, disambiguatedDisplayName, avatarUrl)

fun SenderInfo.toMatrixItemOrNull() = tryOrNull { MatrixItem.UserItem(userId, disambiguatedDisplayName, avatarUrl) }

fun SpaceChildInfo.toMatrixItem() = if (roomType == RoomType.SPACE) {
    MatrixItem.SpaceItem(childRoomId, name ?: canonicalAlias, avatarUrl)
} else {
    MatrixItem.RoomItem(childRoomId, name ?: canonicalAlias, avatarUrl)
}
