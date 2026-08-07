/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.session.events.model.content.RoomKeyWithHeldContent
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.internal.crypto.model.HistoricRoomKey
import org.matrix.android.sdk.internal.crypto.model.RoomKeyBundle
import org.matrix.android.sdk.internal.crypto.model.toHistoricRoomKey
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.di.DeviceId
import javax.inject.Inject

/**
 * Assembles the MSC4268 room key bundle: every megolm session we hold for a room, split into the ones whose creator
 * agreed to share history and the ones that must be withheld.
 */
internal class RoomKeyBundleBuilder @Inject constructor(
        private val cryptoStore: IMXCryptoStore,
        @DeviceId private val deviceId: String?,
) {

    fun build(roomId: String): RoomKeyBundle {
        val roomKeys = mutableListOf<HistoricRoomKey>()
        val withheld = mutableListOf<RoomKeyWithHeldContent>()

        cryptoStore.getInboundGroupSessions(roomId).forEach { session ->
            if (session.sessionData.sharedHistory) {
                session.exportKeys()?.toHistoricRoomKey()?.let { roomKeys.add(it) }
            } else {
                withheld.add(
                        RoomKeyWithHeldContent(
                                roomId = roomId,
                                algorithm = MXCRYPTO_ALGORITHM_MEGOLM,
                                sessionId = session.safeSessionId,
                                senderKey = session.senderKey,
                                codeString = WithHeldCode.HISTORY_NOT_SHARED.value,
                                reason = "History not shared",
                                fromDevice = deviceId
                        )
                )
            }
        }

        // Pass on the history-not-shared markers we were told about ourselves, so the new member learns about
        // sessions we never held either. A session must never appear in both sections.
        val known = (roomKeys.map { it.sessionId } + withheld.mapNotNull { it.sessionId }).toSet()
        cryptoStore.getWithHeldMegolmSessions(roomId)
                .filter { it.code == WithHeldCode.HISTORY_NOT_SHARED && it.sessionId !in known }
                .forEach { withheld.add(it) }

        return RoomKeyBundle(roomKeys = roomKeys, withheld = withheld)
    }
}
