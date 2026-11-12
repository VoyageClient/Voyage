/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import android.os.SystemClock
import android.util.LruCache
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.getUser
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.room.model.message.ForwardContentBuilder
import org.matrix.android.sdk.api.session.room.model.message.getForwardedInfo

/** What forwards show about the room they came from. [known] is false for a room this session has no summary of. */
class ForwardOriginRoom(val known: Boolean, val displayName: String?, val isActive: Boolean)

/**
 * Every forward from one room asks the same questions. A busy room's summary changes on every sync, so each
 * lookup remaps it in full, ~100ms for a large room. The expiry still picks up renames.
 */
private object ForwardOriginCache {
    private const val TTL_MS = 60_000L

    private class Entry<T>(val value: T, val at: Long = SystemClock.elapsedRealtime()) {
        val fresh get() = SystemClock.elapsedRealtime() - at < TTL_MS
    }

    private val rooms = LruCache<String, Entry<ForwardOriginRoom>>(64)
    private val senderNames = LruCache<String, Entry<String>>(256)

    fun room(key: String, compute: () -> ForwardOriginRoom): ForwardOriginRoom =
            rooms.get(key)?.takeIf { it.fresh }?.value ?: compute().also { rooms.put(key, Entry(it)) }

    fun senderName(key: String, compute: () -> String?): String? =
            senderNames.get(key)?.takeIf { it.fresh }?.value ?: compute()?.also { senderNames.put(key, Entry(it)) }
}

fun Session.forwardOriginRoom(roomId: String): ForwardOriginRoom = ForwardOriginCache.room("$sessionId|$roomId") {
    val summary = getRoomSummary(roomId)
    ForwardOriginRoom(known = summary != null, displayName = summary?.displayName, isActive = summary?.membership?.isActive() == true)
}

/**
 * The name of [userId] in the first of [roomIds] they're a member of, falling back to their profile. A
 * user we share no room with is only known once their profile is fetched, so that fetch starts here; a
 * missing name is not cached, or it would hide the fetched one.
 */
fun Session.forwardedSenderDisplayName(userId: String, vararg roomIds: String?): String? =
        ForwardOriginCache.senderName("$sessionId|$userId|${roomIds.joinToString("|")}") {
            roomIds.firstNotNullOfOrNull { roomId ->
                roomId?.let { getRoom(it)?.membershipService()?.getRoomMember(userId)?.displayName }
            } ?: getUser(userId)?.displayName ?: run {
                val profile = profileService().getCachedProfile(userId)
                if (profile == null) profileService().prefetchProfileFields(userId)
                profile?.get(ProfileKeys.DISPLAY_NAME) as? String
            }
        }

/**
 * Join candidates for the room a forward came from. Our own server may know nothing about a room we were
 * never in, so the original sender's server goes in too: it was in the room when the message was sent.
 */
fun Session.forwardViaServers(roomId: String, senderId: String?): List<String> {
    val known = if (forwardOriginRoom(roomId).known) permalinkService().computeViaServers(roomId) else emptyList()
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
