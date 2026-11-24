/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.core.linkify

import org.matrix.android.sdk.api.MatrixPatterns

/**
 * Better support for geo URI.
 */
object VectorAutoLinkPatterns {

    // geo:
    private const val LAT_OR_LONG_OR_ALT_NUMBER = "-?\\d+(?:\\.\\d+)?"
    private const val COORDINATE_SYSTEM = ";crs=[\\w-]+"

    val MSC: Regex = MatrixPatterns.PATTERN_MSC_REFERENCE
    const val MSC_SABLE_BASE_URL = "https://msc.sable.moe/"

    val MXC_URI: Regex = Regex("mxc://(?:\\[[0-9A-Fa-f:]+\\]|[A-Za-z0-9.-]+)(?::[0-9]+)?/[A-Za-z0-9_-]+")

    val SCHEME_URI: Regex = Regex("(?<![A-Za-z0-9+._-])[A-Za-z][A-Za-z0-9+.-]*://[^\\s<>\"']+")

    val GEO_URI: Regex = Regex(
            "(?:geo:)?" +
                    "(" + LAT_OR_LONG_OR_ALT_NUMBER + ")" +
                    "," +
                    "(" + LAT_OR_LONG_OR_ALT_NUMBER + ")" +
                    "(?:" + "," + LAT_OR_LONG_OR_ALT_NUMBER + ")?" + // altitude
                    "(?:" + COORDINATE_SYSTEM + ")?" +
                    "(?:" + ";u=\\d+(?:\\.\\d+)?" + ")?" + // uncertainty in meters
                    "(?:" +
                    ";[\\w-]+=(?:[\\w-_.!~*'()]|%[\\da-f][\\da-f])+" + // dafuk
                    ")*", RegexOption.IGNORE_CASE
    )
}
