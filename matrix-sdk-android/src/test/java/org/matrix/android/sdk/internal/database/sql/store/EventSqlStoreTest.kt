/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.query.TimelineEventFilter
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EventSqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var store: EventSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        store = EventSqlStore(database)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun event(
            eventId: String,
            roomId: String = "!room:hs",
            type: String = EventType.MESSAGE,
            sender: String? = "@alice:hs",
            originServerTs: Long? = 1_000L,
            decryptionResultJson: String? = null,
    ) = EventEntity(
            eventId = eventId,
            roomId = roomId,
            type = type,
            content = """{"body":"hi"}""",
            sender = sender,
            originServerTs = originServerTs,
            decryptionResultJson = decryptionResultJson,
    )

    @Test
    fun `insert and read back an event`() {
        val dbId = store.insert(event("\$e1"))

        store.getDbId("!room:hs", "\$e1") shouldBeEqualTo dbId
        val read = store.getByEventId("\$e1")!!
        read.eventId shouldBeEqualTo "\$e1"
        read.roomId shouldBeEqualTo "!room:hs"
        read.type shouldBeEqualTo EventType.MESSAGE
        read.sender shouldBeEqualTo "@alice:hs"
        read.originServerTs shouldBeEqualTo 1_000L
    }

    @Test
    fun `applyDecryptionResult stores the clear result and clears any prior error`() {
        store.insert(event("\$enc", type = EventType.ENCRYPTED, decryptionResultJson = null))
        store.applyDecryptionError("\$enc", "UNKNOWN_INBOUND_SESSION_ID", "no session")

        store.applyDecryptionResult("\$enc", MXEventDecryptionResult(
                clearEvent = mapOf("type" to "m.room.message", "content" to mapOf("body" to "secret")),
                senderCurve25519Key = "curveKey",
                claimedEd25519Key = "edKey",
        ))

        val read = store.getByEventId("\$enc")!!
        read.decryptionResultJson!!.let {
            it shouldContainAll listOf("secret", "curveKey", "edKey")
        }
        read.decryptionErrorCode.shouldBeNull()
        read.decryptionErrorReason.shouldBeNull()
    }

    @Test
    fun `applyDecryptionError records the failure`() {
        store.insert(event("\$enc", type = EventType.ENCRYPTED))

        store.applyDecryptionError("\$enc", "UNKNOWN_INBOUND_SESSION_ID", "no session")

        val read = store.getByEventId("\$enc")!!
        read.decryptionErrorCode shouldBeEqualTo "UNKNOWN_INBOUND_SESSION_ID"
        read.decryptionErrorReason shouldBeEqualTo "no session"
    }

    // Regression test: the uploads gallery feeds the Realm glob constant {*"file":*"url":*} to a SQL LIKE.
    // Without translating the glob ('*' -> '%') it matches nothing and the gallery comes back empty.
    @Test
    fun `selectEncryptedWithUrlInRoom matches decrypted media via the translated glob`() {
        val pattern = TimelineEventFilter.DecryptedContent.URL.globToSqlLike()
        store.insert(event("\$media1", type = EventType.ENCRYPTED, originServerTs = 100L,
                decryptionResultJson = """{"payload":{"content":{"file":{"url":"mxc://hs/a"}}}}"""))
        store.insert(event("\$media2", type = EventType.ENCRYPTED, originServerTs = 200L,
                decryptionResultJson = """{"payload":{"content":{"file":{"url":"mxc://hs/b"}}}}"""))
        // encrypted but no file/url -> excluded
        store.insert(event("\$text", type = EventType.ENCRYPTED, originServerTs = 300L,
                decryptionResultJson = """{"payload":{"content":{"body":"hi"}}}"""))
        // matching content but unencrypted type -> excluded
        store.insert(event("\$plain", type = EventType.MESSAGE, originServerTs = 400L,
                decryptionResultJson = """{"payload":{"content":{"file":{"url":"mxc://hs/c"}}}}"""))
        // matching but different room -> excluded
        store.insert(event("\$other", roomId = "!other:hs", type = EventType.ENCRYPTED, originServerTs = 500L,
                decryptionResultJson = """{"payload":{"content":{"file":{"url":"mxc://hs/d"}}}}"""))

        val rows = database.eventQueries
                .selectEncryptedWithUrlInRoom("!room:hs", EventType.ENCRYPTED, pattern)
                .executeAsList()

        // newest first by origin_server_ts
        rows.map { it.event_id } shouldBeEqualTo listOf("\$media2", "\$media1")
    }

    @Test
    fun `markVerificationDirtyForSenders flags only decrypted events from the given senders`() {
        store.insert(event("\$a", type = EventType.ENCRYPTED, sender = "@alice:hs", decryptionResultJson = """{"payload":{}}"""))
        store.insert(event("\$b", type = EventType.ENCRYPTED, sender = "@bob:hs", decryptionResultJson = """{"payload":{}}"""))
        // alice but not yet decrypted (decryption_result_json IS NULL) -> untouched
        store.insert(event("\$c", type = EventType.ENCRYPTED, sender = "@alice:hs", decryptionResultJson = null))

        database.eventQueries.markVerificationDirtyForSenders(listOf("@alice:hs"), EventType.ENCRYPTED)

        store.getByEventId("\$a")!!.isVerificationStateDirty shouldBe true
        store.getByEventId("\$b")!!.isVerificationStateDirty.shouldBeNull()
        store.getByEventId("\$c")!!.isVerificationStateDirty.shouldBeNull()
    }

    @Test
    fun `markEventAsRoot and unmark toggle the thread-root flags`() {
        val id = store.insert(event("\$root"))

        store.markEventAsRoot(id, numberOfThreads = 3, latestTimelineId = null)
        store.getByEventId("\$root")!!.let {
            it.isRootThread shouldBe true
            it.numberOfThreads shouldBeEqualTo 3
        }

        store.unmarkEventAsRoot(id)
        store.getByEventId("\$root")!!.let {
            it.isRootThread shouldBe false
            it.numberOfThreads shouldBeEqualTo 0
        }
    }
}
