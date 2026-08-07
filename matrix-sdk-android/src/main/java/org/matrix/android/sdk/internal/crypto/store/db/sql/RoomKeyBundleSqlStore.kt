/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore

/**
 * SQL layer for MSC4268: the key bundles other users have told us about, and the invites this client accepted.
 */
internal class RoomKeyBundleSqlStore(private val database: CryptoSqlDatabase) {

    private val queries get() = database.cryptoRoomKeyBundleQueries

    fun storeReceivedBundle(roomId: String, senderUserId: String, senderKey: String?, bundleJson: String) {
        queries.receivedBundleUpsert(roomId, senderUserId, senderKey, bundleJson)
    }

    fun getReceivedBundle(roomId: String, senderUserId: String): IMXCryptoStore.ReceivedBundle? =
            queries.receivedBundleSelect(roomId, senderUserId).executeAsOneOrNull()?.let {
                IMXCryptoStore.ReceivedBundle(it.sender_user_id, it.sender_key, it.bundle_json)
            }

    fun deleteReceivedBundle(roomId: String, senderUserId: String) {
        queries.receivedBundleDelete(roomId, senderUserId)
    }

    fun storeInviteAccepted(roomId: String, inviter: String, acceptedAt: Long) {
        queries.pendingBundleUpsert(roomId, inviter, acceptedAt)
    }

    fun getInviteAccepted(roomId: String): IMXCryptoStore.InviteAccepted? =
            queries.pendingBundleSelect(roomId).executeAsOneOrNull()?.let {
                IMXCryptoStore.InviteAccepted(it.room_id, it.inviter, it.accepted_at)
            }

    fun getAllInvitesAccepted(): List<IMXCryptoStore.InviteAccepted> =
            queries.pendingBundleSelectAll().executeAsList().map {
                IMXCryptoStore.InviteAccepted(it.room_id, it.inviter, it.accepted_at)
            }

    fun deleteInviteAccepted(roomId: String) {
        queries.pendingBundleDelete(roomId)
    }
}
