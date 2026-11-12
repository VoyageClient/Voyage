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

fun String.isTappableLink(): Boolean = isValidUrl() || isMatrixUri() || VectorAutoLinkPatterns.SCHEME_URI.matches(this)

fun String.isExpectedMscLink(title: String): Boolean {
    val proposal = VectorAutoLinkPatterns.MSC.matchEntire(title)?.groupValues?.get(1) ?: return false
    return this == "${VectorAutoLinkPatterns.MSC_PULL_URL}$proposal"
}

private val GITHUB_REF = Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+(?:#\\d+)?")

fun String.isExpectedGitHubLink(title: String): Boolean {
    if (!GITHUB_REF.matches(title)) return false
    val url = try {
        URL(this)
    } catch (t: Throwable) {
        return false
    }
    return url.protocol in setOf("http", "https") && url.host.equals("github.com", ignoreCase = true) && url.userInfo == null
}

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
