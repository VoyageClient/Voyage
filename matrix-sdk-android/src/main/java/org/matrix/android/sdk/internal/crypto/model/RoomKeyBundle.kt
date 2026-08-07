/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.content.RoomKeyWithHeldContent
import org.matrix.android.sdk.internal.crypto.MegolmSessionData

/**
 * The plaintext of an MSC4268 room key bundle, before it is encrypted and uploaded to the media repo.
 */
@JsonClass(generateAdapter = true)
internal data class RoomKeyBundle(
        @Json(name = "room_keys")
        val roomKeys: List<HistoricRoomKey> = emptyList(),

        @Json(name = "withheld")
        val withheld: List<RoomKeyWithHeldContent> = emptyList(),
) {
    fun isEmpty() = roomKeys.isEmpty() && withheld.isEmpty()
}

/**
 * A megolm session inside a [RoomKeyBundle]. Unlike a key received via `m.room_key`, there is no proof that
 * the claimed sender really created the session — we only have the word of whoever sent us the bundle.
 */
@JsonClass(generateAdapter = true)
internal data class HistoricRoomKey(
        @Json(name = "algorithm")
        val algorithm: String,

        @Json(name = "room_id")
        val roomId: String,

        @Json(name = "sender_key")
        val senderKey: String,

        @Json(name = "session_id")
        val sessionId: String,

        @Json(name = "session_key")
        val sessionKey: String,

        @Json(name = "sender_claimed_keys")
        val senderClaimedKeys: Map<String, String> = emptyMap(),
)

internal fun MegolmSessionData.toHistoricRoomKey(): HistoricRoomKey? {
    return HistoricRoomKey(
            algorithm = algorithm ?: return null,
            roomId = roomId ?: return null,
            senderKey = senderKey ?: return null,
            sessionId = sessionId ?: return null,
            sessionKey = sessionKey ?: return null,
            senderClaimedKeys = senderClaimedKeys.orEmpty(),
    )
}

/**
 * Keys imported from a bundle are always treated as shared-history — their presence in the bundle says so.
 */
internal fun HistoricRoomKey.toMegolmSessionData(): MegolmSessionData {
    return MegolmSessionData(
            algorithm = algorithm,
            roomId = roomId,
            senderKey = senderKey,
            sessionId = sessionId,
            sessionKey = sessionKey,
            senderClaimedKeys = senderClaimedKeys,
            senderClaimedEd25519Key = senderClaimedKeys["ed25519"],
            forwardingCurve25519KeyChain = emptyList(),
            sharedHistory = true,
    )
}
