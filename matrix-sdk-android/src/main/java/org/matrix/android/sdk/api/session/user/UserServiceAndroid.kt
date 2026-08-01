/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.Optional

/**
 * Android-only LiveData view over [UserService.getUserFlow], for consumers that observe via a
 * lifecycle owner. The service exposes a platform-neutral Flow so it can live in the shared core.
 */
fun UserService.getUserLive(userId: String): LiveData<Optional<User>> = getUserFlow(userId).asLiveData()
