/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.pushrules.Kind
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.internal.database.model.PushConditionEntity
import org.matrix.android.sdk.internal.database.model.PushRuleEntity
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PushConditionValueRoundTripTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var store: PushRulesSqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        store = PushRulesSqlStore(SessionSqlDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `an event_property_is value survives the round trip`() {
        val stored = roundTrip(
                PushConditionEntity(kind = Kind.EventPropertyIs.value, key = "content.m\\.relates_to.rel_type", value = "m.replace")
        )

        stored.value shouldBeEqualTo "m.replace"
    }

    @Test
    fun `a boolean value survives the round trip`() {
        val stored = roundTrip(
                PushConditionEntity(kind = Kind.EventPropertyIs.value, key = "content.m\\.mentions.room", value = true)
        )

        stored.value shouldBeEqualTo true
    }

    @Test
    fun `an event_property_contains value survives the round trip`() {
        val stored = roundTrip(
                PushConditionEntity(kind = Kind.EventPropertyContains.value, key = "content.m\\.mentions.user_ids", value = "@alice:hs")
        )

        stored.value shouldBeEqualTo "@alice:hs"
    }

    @Test
    fun `an absent value stays absent rather than becoming a match-anything null`() {
        val stored = roundTrip(
                PushConditionEntity(kind = Kind.EventPropertyIs.value, key = "content.m\\.mentions.room", value = null)
        )

        stored.value shouldBe null
    }

    @Test
    fun `an event_match pattern is untouched`() {
        val stored = roundTrip(
                PushConditionEntity(kind = Kind.EventMatch.value, key = "content.body", pattern = "cake")
        )

        stored.pattern shouldBeEqualTo "cake"
        stored.value shouldBe null
    }

    private fun roundTrip(condition: PushConditionEntity): PushConditionEntity {
        val rule = PushRuleEntity(
                actionsStr = null,
                default = true,
                enabled = true,
                ruleId = "a.rule",
                conditions = arrayListOf(condition),
                pattern = null,
        )
        store.upsert(
                PushRulesEntity(scope = RuleScope.GLOBAL, pushRules = arrayListOf(rule)).apply { kind = RuleKind.OVERRIDE }
        )
        return store.get(RuleScope.GLOBAL, RuleKind.OVERRIDE)!!.pushRules.single().conditions!!.single()
    }
}
