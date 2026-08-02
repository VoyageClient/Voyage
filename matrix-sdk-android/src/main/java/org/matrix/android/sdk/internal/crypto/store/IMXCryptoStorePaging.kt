/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.crypto.OutgoingKeyRequest
import org.matrix.android.sdk.api.session.crypto.model.AuditTrail
import org.matrix.android.sdk.api.session.crypto.model.TrailType

// The PagedList devtools surface of the crypto store; kept out of IMXCryptoStore so that stays
// android-free. SqlCryptoStore implements both; DefaultCryptoService casts to reach these.
internal interface IMXCryptoStorePaging {
    fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingKeyRequest>>
    fun getGossipingEventsTrail(): LiveData<PagedList<AuditTrail>>
    fun <T> getGossipingEventsTrail(type: TrailType, mapper: ((AuditTrail) -> T)): LiveData<PagedList<T>>
}
