/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import im.vector.app.core.linkify.VectorAutoLinkPatterns
import im.vector.app.features.permalink.isMatrixUri
import java.net.URL

fun String.isValidUrl(): Boolean {
    return try {
        URL(this)
        true
    } catch (t: Throwable) {
        false
    }
}

// Java URL has no protocol handler for matrix: or mxc: URIs.
fun String.isTappableLink(): Boolean = isValidUrl() || isMatrixUri() || VectorAutoLinkPatterns.MXC_URI.matches(this)

/**
 * Ensure string starts with "http". If it is not the case, "https://" is added, only if the String is not empty
 */
fun String.ensureProtocol(): String {
    return when {
        isEmpty() -> this
        !startsWith("http") -> "https://$this"
        else -> this
    }
}

fun String.ensureTrailingSlash(): String {
    return when {
        isEmpty() -> this
        !endsWith("/") -> "$this/"
        else -> this
    }
}
