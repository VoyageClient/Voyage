/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.mentions

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.eventindex.EventIndexService
import org.matrix.android.sdk.api.session.eventindex.EventIndexStats
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.room.mentions.MentionKind
import org.matrix.android.sdk.api.session.room.mentions.MentionsQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.PushRuleEntity
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.session.pushers.GetNotificationsResponse
import org.matrix.android.sdk.internal.session.pushers.NotificationResult
import org.matrix.android.sdk.internal.session.pushers.PushRulesApi
import org.matrix.android.sdk.internal.session.search.index.EventIndexStore
import org.matrix.android.sdk.internal.session.search.index.IndexableEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"
private const val OTHER_ROOM_ID = "!other:hs"
private const val LEFT_ROOM_ID = "!left:hs"
private const val ME = "@me:hs"
private const val THEM = "@them:hs"
private const val HOUR = 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
class MentionsFinderTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private lateinit var indexStore: EventIndexStore
    private lateinit var finder: MentionsFinder
    private var indexEnabled = true

    /** What `GET /notifications` returns; null makes the request fail, as being offline would. */
    private var serverNotifications: List<NotificationResult>? = null
    private var serverNextToken: String? = null

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
        indexStore = EventIndexStore(RuntimeEnvironment.getApplication().filesDir, ME, InMemoryDriverFactory())
        finder = MentionsFinder(ME, Dispatchers.Unconfined, stores, indexStore, { indexService() }, FakePushRulesApi(), FakeGlobalErrorReceiver)
        room(ROOM_ID, Membership.JOIN)
        room(OTHER_ROOM_ID, Membership.JOIN)
        room(LEFT_ROOM_ID, Membership.LEAVE)
    }

    @After
    fun tearDown() {
        indexStore.onSessionReleased()
        driver.close()
    }

    /** Only [EventIndexService.isEnabled] is consulted; the rest would need the whole indexer. */
    private fun indexService() = object : EventIndexService {
        override fun isEnabled() = indexEnabled
        override fun setEnabled(enabled: Boolean) = Unit
        override fun setUnencryptedRoomsEnabled(enabled: Boolean) = Unit
        override suspend fun getStats() = EventIndexStats(0, 0)
        override suspend fun clearIndex() = Unit
    }

    private object FakeGlobalErrorReceiver : GlobalErrorReceiver {
        override fun handleGlobalError(globalError: org.matrix.android.sdk.api.failure.GlobalError) = Unit
    }

    /** Only the notifications endpoint is reachable; the rest of the interface is never called here. */
    private inner class FakePushRulesApi : PushRulesApi by mockk(relaxed = true) {
        override suspend fun getNotifications(from: String?, limit: Int?, only: String?) =
                GetNotificationsResponse(nextToken = serverNextToken, notifications = serverNotifications ?: error("offline"))
    }

    private class InMemoryDriverFactory : SqlDriverFactory {
        override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseName: String) =
                FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = schema)

        override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, databaseFile: java.io.File) =
                FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = schema)
    }

    @Test
    fun `a message mentioning us is listed`() {
        add(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)

        val mentions = find()

        mentions.map { it.eventId } shouldContainSame listOf("\$a")
        mentions.first().kind shouldBeEqualTo MentionKind.USER
    }

    @Test
    fun `mentions from every room come back newest first`() {
        add(ROOM_ID, "\$old", ts = HOUR, mentionsMe = true)
        add(OTHER_ROOM_ID, "\$new", ts = 2 * HOUR, mentionsMe = true)

        find().map { it.eventId } shouldBeEqualTo listOf("\$new", "\$old")
    }

    /** Most messages carry an empty `m.mentions` block, so the newest of those must not crowd us out. */
    @Test
    fun `newer messages carrying an empty mentions block do not hide an older mention`() {
        add(ROOM_ID, "\$mention", ts = HOUR, mentionsMe = true)
        repeat(20) { i -> add(ROOM_ID, "\$noise$i", ts = (2 + i) * HOUR, emptyMentions = true) }

        find(MentionsQueryParams(limit = 5)).map { it.eventId } shouldContainSame listOf("\$mention")
    }

    @Test
    fun `a mention of somebody else is not ours`() {
        add(ROOM_ID, "\$a", ts = HOUR, mentioned = THEM)

        find().shouldBeEmpty()
    }

    @Test
    fun `our own message never mentions us`() {
        add(ROOM_ID, "\$a", ts = HOUR, sender = ME, mentionsMe = true)

        find().shouldBeEmpty()
    }

    @Test
    fun `an edit is not a separate mention`() {
        add(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true, relationType = "m.replace")

        find().shouldBeEmpty()
    }

    @Test
    fun `a room we left contributes nothing`() {
        add(LEFT_ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)

        find().shouldBeEmpty()
    }

    @Test
    fun `the user mention rule being off does not hide mentions`() {
        add(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)
        disableRule(RuleIds.RULE_ID_IS_USER_MENTION)

        find().map { it.eventId } shouldContainSame listOf("\$a")
    }

    @Test
    fun `the room mention rule being off does not hide room mentions`() {
        allowRoomMention(ROOM_ID)
        add(ROOM_ID, "\$a", ts = HOUR, roomMention = true)
        disableRule(RuleIds.RULE_ID_IS_ROOM_MENTION)

        val mentions = find()

        mentions.map { it.eventId } shouldContainSame listOf("\$a")
        mentions.first().kind shouldBeEqualTo MentionKind.ROOM
    }

    @Test
    fun `a keyword rule matches the body`() {
        keyword("release")
        add(ROOM_ID, "\$a", ts = HOUR, body = "the release is out")
        add(ROOM_ID, "\$b", ts = 2 * HOUR, body = "nothing to see")

        val mentions = find()

        mentions.map { it.eventId } shouldContainSame listOf("\$a")
        mentions.first().kind shouldBeEqualTo MentionKind.KEYWORD
    }

    @Test
    fun `keywords can be filtered out`() {
        keyword("release")
        add(ROOM_ID, "\$a", ts = HOUR, body = "the release is out")

        find(MentionsQueryParams(includeKeywords = false)).shouldBeEmpty()
    }

    /** The gap case: the client was offline, so the crawler indexed it but sync never stored it. */
    @Test
    fun `a mention only the search index holds is listed`() {
        index(ROOM_ID, "\$crawled", ts = HOUR, mentionsMe = true)

        find().map { it.eventId } shouldContainSame listOf("\$crawled")
    }

    @Test
    fun `an indexed mention is not listed twice when sync also stored it`() {
        add(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)
        index(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)

        find().map { it.eventId } shouldBeEqualTo listOf("\$a")
    }

    @Test
    fun `indexed keyword hits are listed too`() {
        keyword("release")
        index(ROOM_ID, "\$a", ts = HOUR, body = "the release is out")

        find().map { it.eventId } shouldContainSame listOf("\$a")
    }

    @Test
    fun `nothing comes from the index while indexing is off`() {
        indexEnabled = false
        index(ROOM_ID, "\$crawled", ts = HOUR, mentionsMe = true)

        find().shouldBeEmpty()
    }

    @Test
    fun `an indexed mention from a room we left is skipped`() {
        index(LEFT_ROOM_ID, "\$crawled", ts = HOUR, mentionsMe = true)

        find().shouldBeEmpty()
    }

    private fun index(
            roomId: String,
            eventId: String,
            ts: Long,
            sender: String = THEM,
            body: String = "hi",
            mentionsMe: Boolean = false,
    ) = runBlocking {
        val mentions = if (mentionsMe) ""","m.mentions":{"user_ids":["$ME"]}""" else ""
        indexStore.putEvent(IndexableEvent(
                eventId = eventId,
                roomId = roomId,
                sender = sender,
                originServerTs = ts,
                contentText = body.lowercase(),
                eventJson = """{"type":"m.room.message","event_id":"$eventId","room_id":"$roomId","sender":"$sender",""" +
                        """"origin_server_ts":$ts,"content":{"msgtype":"m.text","body":"$body"$mentions}}""",
                msgtype = "m.text",
                mentions = if (mentionsMe) ME.lowercase() else null,
        ))
    }

    /** The server sees what neither sync nor the crawler ever received. */
    @Test
    fun `a mention only the server reports is listed`() {
        serverNotifications = listOf(notification(ROOM_ID, "\$notified", ts = HOUR))

        find().map { it.eventId } shouldContainSame listOf("\$notified")
    }

    @Test
    fun `a server notification is not listed twice when it is also stored`() {
        add(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)
        serverNotifications = listOf(notification(ROOM_ID, "\$a", ts = HOUR))

        find().map { it.eventId } shouldBeEqualTo listOf("\$a")
    }

    /** Offline is the normal case for this screen, so the local sources have to stand on their own. */
    @Test
    fun `the local sources still answer when the server cannot be reached`() {
        serverNotifications = null
        add(ROOM_ID, "\$a", ts = HOUR, mentionsMe = true)

        find().map { it.eventId } shouldContainSame listOf("\$a")
    }

    @Test
    fun `a server notification from a room we left is skipped`() {
        serverNotifications = listOf(notification(LEFT_ROOM_ID, "\$notified", ts = HOUR))

        find().shouldBeEmpty()
    }

    /** The server cannot read `m.mentions` through encryption either, so the local sources own those. */
    @Test
    fun `an encrypted server notification is left to the local sources`() {
        serverNotifications = listOf(
                NotificationResult(
                        roomId = ROOM_ID,
                        ts = HOUR,
                        event = Event(
                                type = EventType.ENCRYPTED,
                                eventId = "\$secret",
                                roomId = ROOM_ID,
                                senderId = THEM,
                                originServerTs = HOUR,
                                content = mapOf("ciphertext" to "nope"),
                        ),
                )
        )

        find().shouldBeEmpty()
    }

    @Test
    fun `the server token is handed back so the list can page`() {
        serverNextToken = "page2"
        serverNotifications = listOf(notification(ROOM_ID, "\$notified", ts = HOUR))

        page().nextToken shouldBeEqualTo "page2"
    }

    /** A continuation reads the server alone: the local sources gave everything on the first page. */
    @Test
    fun `paging past the first page does not repeat the local sources`() {
        add(ROOM_ID, "\$local", ts = 2 * HOUR, mentionsMe = true)
        serverNotifications = listOf(notification(ROOM_ID, "\$older", ts = HOUR))

        page(MentionsQueryParams(from = "page2")).events.map { it.eventId } shouldContainSame listOf("\$older")
    }

    private fun notification(roomId: String, eventId: String, ts: Long, sender: String = THEM) = NotificationResult(
            roomId = roomId,
            ts = ts,
            event = Event(
                    type = EventType.MESSAGE,
                    eventId = eventId,
                    roomId = roomId,
                    senderId = sender,
                    originServerTs = ts,
                    content = mapOf(
                            "msgtype" to "m.text",
                            "body" to "hi",
                            "m.mentions" to mapOf("user_ids" to listOf(ME)),
                    ),
            ),
    )

    private fun find(params: MentionsQueryParams = MentionsQueryParams()) = page(params).events

    private fun page(params: MentionsQueryParams = MentionsQueryParams()) = runBlocking { finder.getMentions(params) }

    private fun room(roomId: String, membership: Membership) {
        stores.roomSummary.upsert(RoomSummaryEntity(roomId = roomId).apply { this.membership = membership })
    }

    private fun allowRoomMention(roomId: String) {
        val eventId = "\$power"
        stores.event.insert(EventEntity(
                eventId = eventId,
                roomId = roomId,
                type = EventType.STATE_ROOM_POWER_LEVELS,
                stateKey = "",
                sender = ME,
                content = """{"users":{"$THEM":100}}""",
        ))
        stores.currentStateEvent.upsert(roomId, EventType.STATE_ROOM_POWER_LEVELS, "", eventId, eventId)
    }

    private fun disableRule(ruleId: String) {
        saveRules(RuleKind.OVERRIDE, PushRuleEntity(ruleId = ruleId, enabled = false, default = true))
    }

    private fun keyword(pattern: String) {
        saveRules(RuleKind.CONTENT, PushRuleEntity(ruleId = pattern, enabled = true, default = false, pattern = pattern))
    }

    private fun saveRules(kind: RuleKind, vararg rules: PushRuleEntity) {
        stores.pushRules.upsert(
                PushRulesEntity(scope = RuleScope.GLOBAL, pushRules = rules.toMutableList()).also { it.kind = kind }
        )
    }

    private fun add(
            roomId: String,
            eventId: String,
            ts: Long,
            sender: String = THEM,
            body: String = "hi",
            mentionsMe: Boolean = false,
            mentioned: String? = null,
            emptyMentions: Boolean = false,
            roomMention: Boolean = false,
            relationType: String? = null,
    ) {
        val relatesTo = relationType?.let { ""","m.relates_to":{"rel_type":"$it","event_id":"${'$'}target"}""" }.orEmpty()
        val target = mentioned ?: ME.takeIf { mentionsMe }
        val mentions = target?.let { ""","m.mentions":{"user_ids":["$it"]}""" }
                ?: ""","m.mentions":{"room":true}""".takeIf { roomMention }
                ?: ""","m.mentions":{}""".takeIf { emptyMentions }.orEmpty()
        val entity = EventEntity(
                eventId = eventId,
                roomId = roomId,
                type = EventType.MESSAGE,
                sender = sender,
                originServerTs = ts,
                content = """{"msgtype":"m.text","body":"$body"$relatesTo$mentions}""",
        )
        stores.event.insert(entity)
    }
}
