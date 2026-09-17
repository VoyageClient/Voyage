/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.pushrules.Kind
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.RuleSetKey
import org.matrix.android.sdk.internal.database.model.PushConditionEntity
import org.matrix.android.sdk.internal.database.model.PushRuleEntity
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.di.MoshiProvider

/** SQL access for `push_rules` → `push_rule` → `push_condition`, preserving list order. */
internal class PushRulesSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.pushRulesQueries

    /** Invalidates account-wide push facts cached by unread-count readers. */
    @Volatile
    var version: Long = 0
        private set

    fun get(scope: String, kind: RuleKind): PushRulesEntity? =
            queries.selectRulesByScopeAndKind(scope, kind.name).executeAsOneOrNull()?.let { rulesRow ->
                val rules = queries.selectRuleChildren(rulesRow.id).executeAsList().map { ruleRow ->
                    val conditions = queries.selectConditions(ruleRow.id).executeAsList().map { c ->
                        if (c.kind in VALUE_CONDITION_KINDS) {
                            PushConditionEntity(kind = c.kind, key = c.condition_key, iz = c.iz, value = decodeValue(c.pattern))
                        } else {
                            PushConditionEntity(kind = c.kind, key = c.condition_key, pattern = c.pattern, iz = c.iz)
                        }
                    }
                    PushRuleEntity(
                            actionsStr = ruleRow.actions_str,
                            default = ruleRow.is_default != 0L,
                            enabled = ruleRow.enabled != 0L,
                            ruleId = ruleRow.rule_id,
                            conditions = ArrayList<PushConditionEntity>().apply { addAll(conditions) },
                            pattern = ruleRow.pattern,
                    )
                }
                PushRulesEntity(
                        scope = rulesRow.scope,
                        pushRules = ArrayList<PushRuleEntity>().apply { addAll(rules) },
                ).also { it.kind = RuleKind.valueOf(rulesRow.kind_str) }
            }

    /** Find a single push rule by scope + ruleId (across kinds), with its kind. */
    fun findRule(scope: String, ruleId: String): Pair<RuleSetKey, PushRuleEntity>? {
        val row = queries.selectRuleByScopeAndRuleId(scope, ruleId).executeAsOneOrNull() ?: return null
        val entity = PushRuleEntity(
                actionsStr = row.actions_str,
                default = row.is_default != 0L,
                enabled = row.enabled != 0L,
                ruleId = row.rule_id,
                conditions = ArrayList(),
                pattern = row.pattern,
        )
        return RuleSetKey.valueOf(row.kind_str) to entity
    }

    fun deleteByScopeAndKind(scope: String, kind: RuleKind) {
        version++
        queries.deleteConditionsForRulesScope(scope, kind.name)
        queries.deleteRulesForScope(scope, kind.name)
        queries.deleteRulesEntityForScope(scope, kind.name)
    }

    fun upsert(entity: PushRulesEntity) {
        deleteByScopeAndKind(entity.scope, entity.kind)
        queries.insertRules(entity.scope, entity.kind.name)
        val rulesId = queries.lastInsertRowId().executeAsOne()
        entity.pushRules.forEachIndexed { ruleIndex, rule ->
            queries.insertRule(
                    push_rules_id = rulesId,
                    rule_order = ruleIndex.toLong(),
                    actions_str = rule.actionsStr,
                    is_default = if (rule.default) 1L else 0L,
                    enabled = if (rule.enabled) 1L else 0L,
                    rule_id = rule.ruleId,
                    pattern = rule.pattern,
            )
            val ruleId = queries.lastInsertRowId().executeAsOne()
            rule.conditions?.forEachIndexed { condIndex, condition ->
                queries.insertCondition(
                        push_rule_id = ruleId,
                        condition_order = condIndex.toLong(),
                        kind = condition.kind,
                        condition_key = condition.key,
                        pattern = if (condition.kind in VALUE_CONDITION_KINDS) encodeValue(condition.value) else condition.pattern,
                        iz = condition.iz,
                )
            }
        }
        version++
    }

    private companion object {
        // These condition kinds use value rather than pattern, so store JSON in the existing pattern column.
        // Adding a column would reset the session cache through the schema-version policy.
        private val VALUE_CONDITION_KINDS = setOf(Kind.EventPropertyIs.value, Kind.EventPropertyContains.value)

        private val valueAdapter = MoshiProvider.providesMoshi().adapter(Any::class.java).serializeNulls()

        private fun encodeValue(value: Any?): String? = tryOrNull { valueAdapter.toJson(value) }

        private fun decodeValue(json: String?): Any? = json?.let { tryOrNull { valueAdapter.fromJson(it) } }
    }
}
