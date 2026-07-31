/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.session.identity.ThreePid

/**
 * Android-only LiveData view over [ProfileService.getThreePidsFlow], for consumers that combine
 * three-pids via LiveData transformations. The service itself exposes a platform-neutral Flow so it
 * can live in the shared core. Not part of the core module.
 */
fun ProfileService.getThreePidsLive(refreshData: Boolean): LiveData<List<ThreePid>> =
        getThreePidsFlow(refreshData).asLiveData()
