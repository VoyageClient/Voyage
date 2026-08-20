/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import android.util.Patterns
import javax.inject.Inject

internal class AndroidWebUrlPattern @Inject constructor() : WebUrlPattern {

    override val regex: Regex by lazy { Patterns.WEB_URL.toRegex() }
}
