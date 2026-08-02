/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.util.Optional

internal fun UserAccountDataDataSource.getLiveAccountDataEvent(type: String): LiveData<Optional<UserAccountDataEvent>> =
        getAccountDataEventFlow(type).asLiveData()

internal fun UserAccountDataDataSource.getLiveAccountDataEvents(types: Set<String>): LiveData<List<UserAccountDataEvent>> =
        getAccountDataEventsFlow(types).asLiveData()
