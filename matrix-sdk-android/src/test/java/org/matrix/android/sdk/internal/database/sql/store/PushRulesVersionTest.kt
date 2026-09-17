/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.internal.database.model.PushRuleEntity
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PushRulesVersionTest {

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
    fun `reading does not move the version`() {
        store.upsert(rules("keyword"))
        val after = store.version

        store.get(RuleScope.GLOBAL, RuleKind.CONTENT)
        store.findRule(RuleScope.GLOBAL, "keyword")

        store.version shouldBeEqualTo after
    }

    @Test
    fun `an upsert moves the version`() {
        val before = store.version

        store.upsert(rules("keyword"))

        store.version shouldBeGreaterThan before
    }

    @Test
    fun `a delete moves the version`() {
        store.upsert(rules("keyword"))
        val before = store.version

        store.deleteByScopeAndKind(RuleScope.GLOBAL, RuleKind.CONTENT)

        store.version shouldBeGreaterThan before
    }

    /** Two writes in a row must be distinguishable, or the second is read as the first. */
    @Test
    fun `each write moves the version again`() {
        store.upsert(rules("first"))
        val afterFirst = store.version

        store.upsert(rules("second"))

        store.version shouldBeGreaterThan afterFirst
        store.get(RuleScope.GLOBAL, RuleKind.CONTENT)?.pushRules?.map { it.ruleId } shouldBeEqualTo listOf("second")
    }

    private fun rules(ruleId: String) = PushRulesEntity(
            scope = RuleScope.GLOBAL,
            pushRules = arrayListOf(
                    PushRuleEntity(
                            actionsStr = null,
                            default = false,
                            enabled = true,
                            ruleId = ruleId,
                            conditions = ArrayList(),
                            pattern = ruleId,
                    )
            ),
    ).also { it.kind = RuleKind.CONTENT }
}
