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
 * Android-only LiveData view over [SyncService.getSyncStateFlow]. The service itself exposes a
 * platform-neutral Flow (so it can live in the shared core); consumers that still want LiveData use
 * this extension. Not part of the core module.
 */
fun SyncService.getSyncStateLive(): LiveData<SyncState> = getSyncStateFlow().asLiveData()
