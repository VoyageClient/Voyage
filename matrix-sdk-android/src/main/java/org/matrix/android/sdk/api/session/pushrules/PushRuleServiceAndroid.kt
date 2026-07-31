/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.pushrules

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData

/**
 * Android-only LiveData view over [PushRuleService.getKeywordsFlow], for consumers that observe via a
 * lifecycle owner. The service itself exposes a platform-neutral Flow so it can live in the shared
 * core. Not part of the core module.
 */
fun PushRuleService.getKeywordsLive(): LiveData<Set<String>> = getKeywordsFlow().asLiveData()
