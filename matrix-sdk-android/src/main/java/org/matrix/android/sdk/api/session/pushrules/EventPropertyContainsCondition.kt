/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.pushrules

import org.matrix.android.sdk.api.session.events.model.Event

/**
 * The event property must be an array holding the value, e.g. our user id in
 * `content.m\.mentions.user_ids` for `.m.rule.is_user_mention`.
 */
class EventPropertyContainsCondition(
        /** The dot-separated field of the event to match; a backslash escapes a literal dot. */
        val key: String,
        /** The value the array must hold, as it appears in JSON. */
        val value: Any?
) : Condition {

    override fun isSatisfied(event: Event, conditionResolver: ConditionResolver): Boolean {
        return conditionResolver.resolveEventPropertyContainsCondition(event, this)
    }

    override fun technicalDescription() = "'$key' contains '$value'"

    fun isSatisfied(event: Event): Boolean {
        val json = event.pushRuleJson() ?: return false
        val array = json.propertyAtPath(key) as? List<*> ?: return false
        return array.any { jsonValueEquals(it, value) }
    }
}
