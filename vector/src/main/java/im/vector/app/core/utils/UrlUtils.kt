/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import im.vector.app.core.linkify.VectorAutoLinkPatterns
import im.vector.app.features.permalink.isMatrixUri
import org.matrix.android.sdk.api.MatrixPatterns
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

private val MSC_URL = Regex(
        "https?://(?:www\\.)?(github\\.com/matrix-org/matrix-spec-proposals/pull|msc\\.re|msc\\.sable\\.moe)/(\\d{1,6})/?(?:[?#]\\S*)?",
        RegexOption.IGNORE_CASE
)

/**
 * Where a tapped proposal link opens without confirmation, or null if it needs the usual checks.
 * An "MSC1234" label opens on [configuredBaseUrl] when the link points at that same proposal. A bare
 * msc.re link skips its redirect to GitHub; other bare proposal links open where they point.
 */
fun mscLinkTarget(url: String, label: String, configuredBaseUrl: String): String? {
    val target = MSC_URL.matchEntire(url) ?: return null
    val (site, number) = target.destructured
    VectorAutoLinkPatterns.MSC.matchEntire(label)?.let {
        return if (it.groupValues[1] == number) configuredBaseUrl + number else null
    }
    val shown = MSC_URL.matchEntire(if ("://" in label) label else "https://$label") ?: return null
    if (!shown.groupValues[1].equals(site, ignoreCase = true) || shown.groupValues[2] != number) return null
    return if (site.equals("msc.re", ignoreCase = true)) MatrixPatterns.MSC_GITHUB_BASE_URL + number else url
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
