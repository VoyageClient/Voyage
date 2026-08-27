/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.internal.database.LiveEntityObserver
import timber.log.Timber

/**
 * SQLDelight counterpart of [org.matrix.android.sdk.internal.database.RealmLiveEntityObserver]: collects
 * a query's change Flow on the session DB dispatcher and invokes [onChange] on each emission.
 */
internal abstract class SqlLiveEntityObserver(
        protected val dispatcher: CoroutineDispatcher,
) : LiveEntityObserver {

    protected val observerScope = CoroutineScope(SupervisorJob() + dispatcher)
    protected abstract val query: Query<*>
    private var job: Job? = null

    override fun onSessionStarted(session: Session) {
        if (job == null) {
            job = observerScope.launch {
                query.asFlow().collect {
                    try {
                        onChange()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        // An observer dying here stays dead until the session restarts, silently
                        // stopping whatever pipeline it drives; log and keep collecting.
                        Timber.e(failure, "${javaClass.simpleName}.onChange failed")
                    }
                }
            }
        }
    }

    override fun onSessionStopped(session: Session) {
        job?.cancel()
        job = null
    }

    override fun onClearCache(session: Session) {
        observerScope.coroutineContext.cancelChildren()
    }

    protected abstract suspend fun onChange()
}
