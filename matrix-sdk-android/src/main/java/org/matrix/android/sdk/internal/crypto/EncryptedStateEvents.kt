/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

/** MSC4362 "Simplified Encrypted State Events". */
internal object EncryptedStateEvents {

    /** State the homeserver needs in the clear to run the protocol; never encrypted. */
    val UNENCRYPTABLE_TYPES = setOf(
            EventType.STATE_ROOM_CREATE,
            EventType.STATE_ROOM_MEMBER,
            EventType.STATE_ROOM_JOIN_RULES,
            EventType.STATE_ROOM_POWER_LEVELS,
            EventType.STATE_ROOM_THIRD_PARTY_INVITE,
            EventType.STATE_ROOM_HISTORY_VISIBILITY,
            EventType.STATE_ROOM_GUEST_ACCESS,
            EventType.STATE_ROOM_ENCRYPTION,
    )

    /** What a decrypted state event invalidates beyond itself, once its content becomes readable. */
    enum class Refresh { NONE, ROOM_SUMMARY, SPACE_GRAPH }

    /** State whose content the room summary is built from. */
    private val SUMMARY_TYPES = setOf(
            EventType.STATE_ROOM_NAME,
            EventType.STATE_ROOM_TOPIC,
            EventType.STATE_ROOM_AVATAR,
            EventType.STATE_ROOM_CANONICAL_ALIAS,
    )

    /**
     * The sync handler flags these for revalidation from their plaintext slot, but the parent/child
     * graph is built from their content — via servers and order — which stays unreadable until the
     * event decrypts, long after that pass has run.
     */
    private val SPACE_GRAPH_TYPES = setOf(EventType.STATE_SPACE_CHILD, EventType.STATE_SPACE_PARENT)

    fun refreshFor(type: String): Refresh = when (type) {
        in SUMMARY_TYPES -> Refresh.ROOM_SUMMARY
        in SPACE_GRAPH_TYPES -> Refresh.SPACE_GRAPH
        else -> Refresh.NONE
    }

    fun packStateKey(type: String, stateKey: String) = "$type:$stateKey"

    /**
     * The (type, state key) slot an event occupies in room state. The packed state key of an
     * encrypted state event is plaintext, so the slot is known before it can be decrypted — keying
     * it off the wire type instead would make the same event occupy two slots once it decrypts.
     *
     * Protocol-critical state is never unpacked: it is always sent in the clear, so honouring a
     * packed key that claims one would let any sender with `state_default` shadow a membership or
     * power-level event with a blob nobody can read.
     */
    fun stateSlot(event: Event): Pair<String, String>? {
        val stateKey = event.stateKey ?: return null
        if (!event.isEncrypted()) return (event.type ?: return null) to stateKey
        event.getDecryptedType()?.let { clearType ->
            return clearType to (event.getClearStateKey() ?: return null)
        }
        if (!stateKey.contains(':')) return EventType.ENCRYPTED to stateKey
        val packedType = stateKey.substringBefore(':')
        if (packedType in UNENCRYPTABLE_TYPES) return EventType.ENCRYPTED to stateKey
        return packedType to stateKey.substringAfter(':')
    }

    /**
     * The packed state key must round-trip to the decrypted type and state key, and be present
     * exactly when the decrypted event is a state event — otherwise a message event could
     * masquerade as state, or the reverse. Protocol-critical state is rejected outright: it is only
     * ever sent in the clear, so an encrypted one claiming to be a membership or power-level event
     * would shadow the real state the server resolved.
     */
    fun isPackedStateKeyValid(packedStateKey: String?, clearEvent: JsonDict): Boolean {
        val clearType = clearEvent["type"] as? String
        val clearStateKey = clearEvent["state_key"] as? String
        if (packedStateKey == null) return clearStateKey == null
        if (clearType == null || clearStateKey == null || clearType in UNENCRYPTABLE_TYPES) return false
        return packedStateKey == packStateKey(clearType, clearStateKey)
    }
}

internal fun SessionStores.isStateEncryptionEnabled(roomId: String): Boolean =
        currentStateEvent.getOne(roomId, EventType.STATE_ROOM_ENCRYPTION, "")
                ?.root
                ?.asDomain()
                ?.content
                .toModel<EncryptionEventContent>()
                ?.stateEncryptionEnabled == true

/**
 * Put a just-decrypted state event in the current-state slot it really occupies. Normally it is
 * already there — [EncryptedStateEvents.stateSlot] reads the plaintext packed key — but an event
 * whose packed key could not be unpacked is held under it until decryption resolves the slot.
 *
 * Returns what now has to be recomputed from the state this event just made readable.
 */
internal fun applyDecryptedState(
        stores: SessionStores,
        event: Event,
        result: MXEventDecryptionResult,
        eventId: String,
): EncryptedStateEvents.Refresh {
    if (event.stateKey == null) return EncryptedStateEvents.Refresh.NONE
    val clearType = result.clearEvent["type"] as? String ?: return EncryptedStateEvents.Refresh.NONE
    val clearStateKey = result.clearEvent["state_key"] as? String ?: return EncryptedStateEvents.Refresh.NONE
    stores.currentStateEvent.applyDecryptedState(
            roomId = event.roomId.orEmpty(),
            packedStateKey = event.stateKey.takeIf { event.isEncrypted() },
            type = clearType,
            stateKey = clearStateKey,
            eventId = eventId,
    )
    return EncryptedStateEvents.refreshFor(clearType)
}

/**
 * `unsigned.prev_content` of an encrypted state event is itself an encrypted payload, so it has to
 * be decrypted separately from the event. Returns null when it cannot be read.
 */
internal suspend fun CryptoService.decryptStatePrevContent(event: Event, roomId: String, timelineId: String): Content? {
    if (!event.isEncrypted() || event.stateKey == null) return null
    val previousContent = event.prevContent ?: event.unsignedData?.prevContent ?: return null
    val result = runCatching {
        decryptEvent(
                Event(
                        type = EventType.ENCRYPTED,
                        eventId = event.unsignedData?.replacesState,
                        content = previousContent,
                        roomId = roomId,
                        stateKey = event.stateKey,
                ),
                timelineId,
        )
    }.getOrNull() ?: return null
    @Suppress("UNCHECKED_CAST")
    return result.clearEvent["content"] as? Content
}
