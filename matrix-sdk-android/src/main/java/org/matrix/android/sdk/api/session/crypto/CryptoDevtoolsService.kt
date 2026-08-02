/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.crypto

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.crypto.model.AuditTrail
import org.matrix.android.sdk.api.session.crypto.model.IncomingRoomKeyRequest

/**
 * Android-only paged views of the crypto key-request / gossiping audit trail, used by the settings
 * devtools screens. Kept off [CryptoService] so that interface stays plain-JVM (no androidx.paging);
 * the android CryptoService impl also implements this. Cast the CryptoService to reach it.
 */
interface CryptoDevtoolsService {

    fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingKeyRequest>>

    fun getIncomingRoomKeyRequestsPaged(): LiveData<PagedList<IncomingRoomKeyRequest>>

    fun getGossipingEventsTrail(): LiveData<PagedList<AuditTrail>>

    fun getCryptoVersion(context: Context, longFormat: Boolean): String
}
