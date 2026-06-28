/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoints
import im.vector.app.core.di.SingletonEntryPoint
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A TestRule to reset and clear the current Session.
 * If a Session is active it will be signed out and cleared from the ActiveSessionHolder.
 * VectorPreferences is also cleared in an attempt to recreate a fresh base.
 */
class ClearCurrentSessionRule : TestWatcher() {
    override fun apply(base: Statement, description: Description): Statement {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            runCatching {
                val entryPoint = EntryPoints.get(context.applicationContext, SingletonEntryPoint::class.java)
                val sessionHolder = entryPoint.activeSessionHolder()
                sessionHolder.getSafeActiveSession()?.signOutService()?.signOut(true)
                entryPoint.vectorPreferences().clearPreferences()
                sessionHolder.clearActiveSession()
            }
        }
        return super.apply(base, description)
    }
}
