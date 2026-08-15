/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import app.cash.sqldelight.db.SqlDriver
import org.matrix.android.sdk.internal.di.SessionDatabase
import javax.inject.Inject

/**
 * Final teardown hook, dispatched when the session component itself is dropped
 * ([org.matrix.android.sdk.internal.SessionManager.releaseSession]). Unlike
 * [org.matrix.android.sdk.api.session.SessionLifecycleObserver.onSessionStopped], a released
 * session is never reopened, so held resources (DB connections, dedicated threads) must go too —
 * otherwise re-creating the component opens a second connection to the same files.
 */
internal interface SessionReleasable {
    fun onSessionReleased()
}

internal class SessionDatabaseReleaser @Inject constructor(
        @SessionDatabase private val driver: SqlDriver,
) : SessionReleasable {
    override fun onSessionReleased() {
        runCatching { driver.close() }
    }
}
