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
 * LiveData view over [PushRuleService.getKeywordsFlow], for consumers that observe via a lifecycle
 * owner.
 */
fun PushRuleService.getKeywordsLive(): LiveData<Set<String>> = getKeywordsFlow().asLiveData()
