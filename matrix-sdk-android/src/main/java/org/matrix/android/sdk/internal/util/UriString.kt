/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import java.net.URI

internal fun String.uriHost(): String? = runCatching { URI(this).host }.getOrNull()

/** @return the explicit port of the URI, or -1 (like android.net.Uri and java.net.URI). */
internal fun String.uriPort(): Int = runCatching { URI(this).port }.getOrDefault(-1)
