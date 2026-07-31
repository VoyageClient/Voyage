/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher

// LiveData query views (Android reactive layer). The neutral coroutine/transaction helpers live in
// DbReactiveCoroutines.kt so the core module can reuse them; the desktop app observes queries as
// Flow directly instead of these.

/** Replacement for `monarchy.findAllMappedWithChanges` — an auto-updating list as LiveData. */
internal fun <T : Any> Query<T>.asLiveList(dispatcher: CoroutineDispatcher): LiveData<List<T>> =
        asFlow().mapToList(dispatcher).asLiveData()

/** Replacement for a single-result managed-with-changes query. */
internal fun <T : Any> Query<T>.asLiveOneOrNull(dispatcher: CoroutineDispatcher): LiveData<T?> =
        asFlow().mapToOneOrNull(dispatcher).asLiveData()
