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
 * LiveData view over [ProfileService.getThreePidsFlow], for consumers that combine three-pids via
 * LiveData transformations.
 */
fun ProfileService.getThreePidsLive(refreshData: Boolean): LiveData<List<ThreePid>> =
        getThreePidsFlow(refreshData).asLiveData()
