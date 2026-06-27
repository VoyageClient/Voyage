/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.query

import org.matrix.android.sdk.api.query.QueryStringValue

/**
 * Kotlin-side evaluation of a [QueryStringValue] against a candidate value, replacing the Realm-query
 * `QueryStringValueProcessor.process()` used before the SQLDelight migration. For NORMALIZED queries
 * the caller passes the normalized field value (both sides already normalized), so it compares exactly.
 */
internal fun QueryStringValue.matches(value: String?): Boolean = when (this) {
    QueryStringValue.NoCondition -> true
    QueryStringValue.IsNull -> value == null
    QueryStringValue.IsNotNull -> value != null
    QueryStringValue.IsEmpty -> value.isNullOrEmpty()
    QueryStringValue.IsNotEmpty -> !value.isNullOrEmpty()
    is QueryStringValue.Equals -> if (ignoreCase) value.equals(string, ignoreCase = true) else value == string
    is QueryStringValue.Contains -> value?.contains(string, ignoreCase = ignoreCase) ?: false
    is QueryStringValue.NotContains -> value?.contains(string, ignoreCase = ignoreCase)?.not() ?: true
}

private val QueryStringValue.ContentQueryStringValue.ignoreCase: Boolean
    get() = case == QueryStringValue.Case.INSENSITIVE
