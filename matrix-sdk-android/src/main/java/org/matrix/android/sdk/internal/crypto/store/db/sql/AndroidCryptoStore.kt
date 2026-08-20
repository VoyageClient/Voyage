/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.crypto.OutgoingKeyRequest
import org.matrix.android.sdk.api.session.crypto.model.AuditTrail
import org.matrix.android.sdk.api.session.crypto.model.TrailType
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStorePaging
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import org.matrix.android.sdk.internal.di.CryptoDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

/**
 * The crypto store the android app gets: [SqlCryptoStore] (plain-JVM) plus the paged key-request and
 * audit-trail views the devtools screens read, which need androidx.paging.
 */
@SessionScope
internal class AndroidCryptoStore @Inject constructor(
        val delegate: SqlCryptoStore,
        @CryptoDatabase private val database: CryptoSqlDatabase,
        clock: Clock,
) : IMXCryptoStore by delegate, IMXCryptoStorePaging {

    private val keyRequestStore = KeyRequestSqlStore(database, clock)

    override fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingKeyRequest>> =
            livePaged(database.cryptoKeyRequestQueries.okrSelectAll()) { keyRequestStore.getOutgoingRoomKeyRequests() }

    override fun getGossipingEventsTrail(): LiveData<PagedList<AuditTrail>> =
            livePaged(database.cryptoBackupAuditQueries.auditSelectAllOrdered()) { keyRequestStore.getOrderedAuditTrails() }

    override fun <T> getGossipingEventsTrail(type: TrailType, mapper: (AuditTrail) -> T): LiveData<PagedList<T>> =
            livePaged(database.cryptoBackupAuditQueries.auditSelectByTypeOrdered(type.name)) { keyRequestStore.getOrderedAuditTrailsByType(type).map(mapper) }
}
