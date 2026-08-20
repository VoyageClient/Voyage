/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

/**
 * Platform seam for the pattern that finds links in a message. Android has a long-standing,
 * well-tested one in `Patterns.WEB_URL`; matching it by hand would change which links are detected.
 */
internal interface WebUrlPattern {

    val regex: Regex
}
