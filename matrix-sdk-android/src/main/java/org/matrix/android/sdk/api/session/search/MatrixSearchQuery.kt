/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.search

import org.matrix.android.sdk.api.session.events.model.Event

/**
 * A parsed search term, ready to match events already in hand. Lets a list that was fetched once be
 * narrowed as the user types without going back to the database.
 */
interface MatrixSearchQuery {

    /** Whether [event] satisfies every token and filter of the term. A blank term matches everything. */
    fun matches(event: Event): Boolean
}
