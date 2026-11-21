/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.room.peeking.PeekedRoomManager
import org.matrix.android.sdk.internal.session.search.index.EventIndexer
import org.matrix.android.sdk.internal.session.search.index.LocalEventSearchTask
import timber.log.Timber

class SearchDiagnosticsTest {
    private val messages = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
        }
    }

    @Before
    fun setUp() {
        DebugLog.enabled = true
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        DebugLog.enabled = false
        Timber.uproot(tree)
    }

    @Test
    fun `encrypted search records the local backend and completion without exposing the query`() = runBlocking {
        val server = mockk<SearchTask>()
        val local = mockk<LocalEventSearchTask>()
        val indexer = mockk<EventIndexer>()
        val stores = mockk<SessionStores>(relaxed = true)
        val peeks = mockk<PeekedRoomManager>()
        every { peeks.get(any()) } returns null
        every { indexer.isEnabled() } returns true
        every { stores.roomSummary.isEncrypted(any()) } returns true
        coEvery { local.search(any(), any(), any(), any(), any()) } returns SearchResult(null, emptyList(), emptyList())
        val service = DefaultSearchService(server, local, indexer, stores, peeks, mockk())
        service.search("syntheticPrivateSearch", "!search-room:example.org", null, true, 20, 0, 0, true)
        coVerify(exactly = 0) { server.execute(any()) }
        assertTrue(messages.any { "backend=local encrypted=true" in it })
        assertTrue(messages.any { "stage=search outcome=completed" in it })
        assertFalse(messages.any { "syntheticPrivateSearch" in it })
    }
}
