/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.cleanup

import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.database.RealmKeysUtils
import org.matrix.android.sdk.internal.session.cache.ClearCacheTask

private const val A_SESSION_ID = "session-id"

class CleanupSessionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val sessionParamsStore = mockk<SessionParamsStore>(relaxed = true)
    private val clearSessionDataTask = mockk<ClearCacheTask>(relaxed = true)
    private val clearCryptoDataTask = mockk<ClearCacheTask>(relaxed = true)
    private val realmKeysUtils = mockk<RealmKeysUtils>(relaxed = true)

    @Test
    fun `cleanup clears both databases before releasing the session that owns their drivers`() = runTest {
        val sessionFiles = tmp.newFolder("files")
        val sessionCache = tmp.newFolder("cache")

        CleanupSession(
                backgroundTaskScheduler = mockk(relaxed = true),
                sessionId = A_SESSION_ID,
                sessionManager = sessionManager,
                sessionParamsStore = sessionParamsStore,
                clearSessionDataTask = clearSessionDataTask,
                clearCryptoDataTask = clearCryptoDataTask,
                sessionFiles = sessionFiles,
                sessionCache = sessionCache,
                realmKeysUtils = realmKeysUtils,
                userMd5 = "md5",
        ).cleanup()

        coVerifyOrder {
            sessionParamsStore.delete(A_SESSION_ID)
            clearSessionDataTask.execute(Unit)
            clearCryptoDataTask.execute(Unit)
            sessionManager.releaseSession(A_SESSION_ID)
        }
        verify(exactly = 2) { realmKeysUtils.clear(any()) }
        sessionFiles.exists() shouldBeEqualTo false
        sessionCache.exists() shouldBeEqualTo false
    }
}
