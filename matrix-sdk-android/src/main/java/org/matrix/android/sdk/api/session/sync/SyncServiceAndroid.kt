/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.sync

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData

/**
 * LiveData view over [SyncService.getSyncStateFlow].
 */
fun SyncService.getSyncStateLive(): LiveData<SyncState> = getSyncStateFlow().asLiveData()
