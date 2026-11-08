/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.getUser
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.room.model.message.ForwardContentBuilder
import org.matrix.android.sdk.api.session.room.model.message.getForwardedInfo

/**
 * The name of [userId] in the first of [roomIds] they're a member of, falling back to their profile. A
 * user we share no room with is only known once their profile is fetched, so that fetch starts here.
 */
fun Session.forwardedSenderDisplayName(userId: String, vararg roomIds: String?): String? {
    roomIds.firstNotNullOfOrNull { roomId ->
        roomId?.let { getRoom(it)?.membershipService()?.getRoomMember(userId)?.displayName }
    }?.let { return it }
    getUser(userId)?.displayName?.let { return it }
    val profile = profileService().getCachedProfile(userId)
    if (profile == null) profileService().prefetchProfileFields(userId)
    return profile?.get(ProfileKeys.DISPLAY_NAME) as? String
}

/**
 * Join candidates for the room a forward came from. Our own server may know nothing about a room we were
 * never in, so the original sender's server goes in too: it was in the room when the message was sent.
 */
fun Session.forwardViaServers(roomId: String, senderId: String?): List<String> {
    val known = if (getRoomSummary(roomId) != null) permalinkService().computeViaServers(roomId) else emptyList()
    return (known + listOfNotNull(senderId?.getServerName()?.takeIf { it.isNotEmpty() })).distinct().take(MAX_VIA_SERVERS)
}

/**
 * Builds the content to send for forward payloads, filling in what only the session knows. Each sender's
 * name and each room's via servers are looked up once per batch.
 */
class ForwardContentBatch(private val session: Session) {
    private val names = HashMap<Pair<String, String?>, String?>()
    private val vias = HashMap<Pair<String, String?>, List<String>>()

    fun build(eventType: String, payload: Map<String, Any>, silent: Boolean): Map<String, Any> {
        val info = payload.getForwardedInfo()
        val sender = info?.sender
        val roomId = info?.roomId
        val senderName = sender?.let { names.getOrPutNullable(it to roomId) { session.forwardedSenderDisplayName(it, roomId) } }
        return ForwardContentBuilder.build(
                eventType = eventType,
                payload = payload,
                silent = silent,
                senderDisplayName = senderName,
                viaServers = roomId?.let { vias.getOrPut(it to sender) { session.forwardViaServers(it, sender) } }.orEmpty(),
        )
    }

    private fun <K, V> HashMap<K, V?>.getOrPutNullable(key: K, compute: () -> V?): V? =
            if (containsKey(key)) get(key) else compute().also { put(key, it) }
}

private const val MAX_VIA_SERVERS = 3
