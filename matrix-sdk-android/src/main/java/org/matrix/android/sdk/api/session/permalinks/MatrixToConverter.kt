/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.session.permalinks

/**
 * Mapping of an input URI to a matrix.to compliant URI.
 */
object MatrixToConverter {

    /**
     * Try to convert a URL from an element web instance or from a client permalink to a matrix.to url.
     * To be successfully converted, URL path should contain one of the [SUPPORTED_PATHS].
     * Examples:
     * - https://riot.im/develop/#/room/#element-android:matrix.org  ->  https://matrix.to/#/#element-android:matrix.org
     * - https://app.element.io/#/room/#element-android:matrix.org   ->  https://matrix.to/#/#element-android:matrix.org
     * - https://www.example.org/#/room/#element-android:matrix.org  ->  https://matrix.to/#/#element-android:matrix.org
     */
    fun convert(uriString: String): String? {
        return when {
            // URL is already a matrix.to
            uriString.startsWith(PermalinkService.MATRIX_TO_URL_BASE) -> uriString
            // MSC2312 matrix: URI
            uriString.startsWith(PermalinkService.MATRIX_URI_SCHEME_PREFIX, ignoreCase = true) -> convertMatrixUri(uriString)
            // Web or client url
            SUPPORTED_PATHS.any { it in uriString } -> {
                val path = SUPPORTED_PATHS.first { it in uriString }
                PermalinkService.MATRIX_TO_URL_BASE + uriString.substringAfter(path)
            }
            // URL is not supported
            else -> null
        }
    }

    /**
     * Convert a matrix: URI to its matrix.to equivalent, e.g.
     * - matrix:u/alice:example.org                            ->  https://matrix.to/#/@alice:example.org
     * - matrix:r/room:example.org/e/$abcdef?via=example.org   ->  https://matrix.to/#/#room:example.org/$abcdef?via=example.org
     */
    private fun convertMatrixUri(uriString: String): String? {
        // Fragments are reserved by MSC2312 and an authority carries no meaning for us, but neither may break parsing.
        val body = uriString.substringAfter(':').substringBefore('#')
        val path = body.substringBefore('?')
        val query = body.substringAfter('?', "")
        var segments = path.split('/').filter { it.isNotEmpty() }
        // matrix://u/alice:example.org, written without an authority, is common enough to accept.
        if (path.startsWith("//") && segments.firstOrNull() !in ENTITY_SIGILS.keys) {
            segments = segments.drop(1)
        }
        if (segments.size != 2 && segments.size != 4) return null
        val entity = segments[1].withSigil(ENTITY_SIGILS[segments[0]] ?: return null)
        val child = if (segments.size == 4) {
            // An event only lives under a room, never under a user.
            if (segments[2] !in EVENT_QUALIFIERS || entity.startsWith("@")) return null
            "/" + segments[3].withSigil("$")
        } else {
            ""
        }
        return PermalinkService.MATRIX_TO_URL_BASE + entity + child + if (query.isEmpty()) "" else "?$query"
    }

    // Sigils are dropped in matrix: URIs, but tolerate a sender that kept them.
    private fun String.withSigil(sigil: String) = if (startsWith(sigil)) this else sigil + this

    // The `user`/`room`/`event` qualifiers are deprecated by MSC2312: parsed, never generated.
    private val ENTITY_SIGILS = mapOf(
            "u" to "@",
            "user" to "@",
            "r" to "#",
            "room" to "#",
            "roomid" to "!"
    )

    private val EVENT_QUALIFIERS = setOf("e", "event")

    private val SUPPORTED_PATHS = listOf(
            "/#/room/",
            "/#/user/",
            "/#/group/"
    )
}
