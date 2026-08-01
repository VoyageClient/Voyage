/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import java.net.URI
import java.net.URLDecoder

internal fun String.uriHost(): String? = runCatching { URI(this).host }.getOrNull()

/** @return the explicit port of the URI, or -1 (like android.net.Uri and java.net.URI). */
internal fun String.uriPort(): Int = runCatching { URI(this).port }.getOrDefault(-1)

/** First URL-decoded value of query parameter [key], or null. Mirrors android.net.Uri.getQueryParameter. */
internal fun String.uriQueryParameter(key: String): String? =
        runCatching { URI(this).rawQuery }
                .getOrNull()
                ?.split('&')
                ?.mapNotNull { it.split('=', limit = 2).takeIf { part -> part.size == 2 } }
                ?.firstOrNull { it[0] == key }
                ?.let { URLDecoder.decode(it[1], "UTF-8") }
