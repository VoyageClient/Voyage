/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.pushrules

import org.matrix.android.sdk.api.session.events.model.Event

/**
 * Exact match on an event property, e.g. `content.m\.mentions.room` is `true` for `.m.rule.is_room_mention`.
 */
class EventPropertyIsCondition(
        /** The dot-separated field of the event to match; a backslash escapes a literal dot. */
        val key: String,
        /** The value the property must equal, as it appears in JSON. */
        val value: Any?
) : Condition {

    override fun isSatisfied(event: Event, conditionResolver: ConditionResolver): Boolean {
        return conditionResolver.resolveEventPropertyIsCondition(event, this)
    }

    override fun technicalDescription() = "'$key' is '$value'"

    fun isSatisfied(event: Event): Boolean {
        val json = event.pushRuleJson() ?: return false
        return jsonValueEquals(json.propertyAtPath(key), value)
    }
}
