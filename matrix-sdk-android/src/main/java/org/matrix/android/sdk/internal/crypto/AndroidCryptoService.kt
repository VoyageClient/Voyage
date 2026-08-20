/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.crypto.CryptoDevtoolsService
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.OutgoingKeyRequest
import org.matrix.android.sdk.api.session.crypto.model.AuditTrail
import org.matrix.android.sdk.api.session.crypto.model.IncomingRoomKeyRequest
import org.matrix.android.sdk.api.session.crypto.model.TrailType
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStorePaging
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * The CryptoService the android app gets: [DefaultCryptoService] (plain-JVM) plus the paged
 * devtools views, which need androidx.paging and so cannot live on the shared impl.
 */
@SessionScope
internal class AndroidCryptoService @Inject constructor(
        val delegate: DefaultCryptoService,
        cryptoStore: IMXCryptoStore,
) : CryptoService by delegate, CryptoDevtoolsService {

    private val pagingStore = cryptoStore as IMXCryptoStorePaging

    override fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingKeyRequest>> {
        return pagingStore.getOutgoingRoomKeyRequestsPaged()
    }

    override fun getIncomingRoomKeyRequestsPaged(): LiveData<PagedList<IncomingRoomKeyRequest>> {
        return pagingStore.getGossipingEventsTrail(TrailType.IncomingKeyRequest) {
            IncomingRoomKeyRequest.fromEvent(it)
                    ?: IncomingRoomKeyRequest(localCreationTimestamp = 0L)
        }
    }

    override fun getGossipingEventsTrail(): LiveData<PagedList<AuditTrail>> {
        return pagingStore.getGossipingEventsTrail()
    }
}
