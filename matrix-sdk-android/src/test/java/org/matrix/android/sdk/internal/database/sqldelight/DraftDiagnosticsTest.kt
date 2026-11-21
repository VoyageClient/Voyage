/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.DraftSqlStore
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.room.draft.DraftRepository
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class DraftDiagnosticsTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val messages = CopyOnWriteArrayList<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
        }
    }
    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var repository: DraftRepository

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), null, SessionSqlDatabase.Schema)
        val database = SessionSqlDatabase(driver)
        val stores = mockk<SessionStores>(relaxed = true)
        every { stores.draft } returns DraftSqlStore(database)
        repository = DraftRepository(database, dispatcher, stores, mockk<RoomSummaryPreviewInvalidation>(relaxed = true))
        DebugLog.enabled = true
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        DebugLog.enabled = false
        driver.close()
        dispatcher.close()
    }

    @Test
    fun `draft saves restore increasing persistent revisions without logging text`() = runBlocking {
        val room = "!draft-room:example.org"
        repository.saveDraft(room, UserDraft.Regular("synthetic-private-first"))
        assertEquals(UserDraft.Regular("synthetic-private-first"), repository.getDraft(room))
        repository.saveDraft(room, UserDraft.Regular("synthetic-private-second"))
        assertEquals(UserDraft.Regular("synthetic-private-second"), repository.getDraft(room))
        val revisions = messages.filter { "DRAFTDBG restored" in it }
                .map { it.substringAfter("revision=").substringBefore(' ').toLong() }
        assertEquals(2, revisions.size)
        assertTrue(revisions[1] > revisions[0])
        repository.deleteDraft(room)
        assertNull(repository.getDraft(room))
        assertTrue(messages.any { "reason=explicit-clear" in it && "outcome=completed" in it })
        assertFalse(messages.any { "synthetic-private" in it })
    }
}
