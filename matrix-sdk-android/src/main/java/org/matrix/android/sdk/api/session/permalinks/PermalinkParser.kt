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

import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.internal.util.uriQueryParameter
import timber.log.Timber
import java.net.URI
import java.net.URLDecoder

/**
 * This class turns a uri to a [PermalinkData].
 * element-based domains (e.g. https://app.element.io/#/user/@chagai95:matrix.org) permalinks
 * or matrix.to permalinks (e.g. https://matrix.to/#/@chagai95:matrix.org)
 * or client permalinks (e.g. <clientPermalinkBaseUrl>user/@chagai95:matrix.org)
 */
object PermalinkParser {

    // The rich-text editor calls parse() for every mention-shaped token in the composer on every
    // keystroke. Parsing is non-trivial (Uri.parse + URLDecoder + UrlQuerySanitizer), and the same
    // small set of strings is parsed repeatedly while the user is typing. A tiny LRU cache absorbs
    // that without keeping references to anything large. Strings here are short URLs or mxids;
    // ~256 entries is plenty for any realistic composer state and bounded for memory.
    private const val CACHE_CAPACITY = 256
    private val parseCache: MutableMap<String, PermalinkData> = object : LinkedHashMap<String, PermalinkData>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, PermalinkData>): Boolean = size > CACHE_CAPACITY
    }

    /**
     * Turns a uri string to a [PermalinkData].
     */
    fun parse(uriString: String): PermalinkData {
        synchronized(parseCache) {
            parseCache[uriString]?.let { return it }
        }
        val result = parseInternal(uriString)
        synchronized(parseCache) {
            parseCache[uriString] = result
        }
        return result
    }

    /**
     * https://github.com/matrix-org/matrix-doc/blob/master/proposals/1704-matrix.to-permalinks.md
     */
    private fun parseInternal(uriString: String): PermalinkData {
        // the client or element-based domain permalinks (e.g. https://app.element.io/#/user/@chagai95:matrix.org) don't have the
        // mxid in the first param (like matrix.to does - https://matrix.to/#/@chagai95:matrix.org) but rather in the second after /user/ so /user/mxid
        // so convert URI to matrix.to to simplify parsing process
        val matrixToUri = MatrixToConverter.convert(uriString) ?: return PermalinkData.FallbackLink(uriString)

        // We can't decode the fragment early as it would break the parsing of parameters that
        // represent a url (like signurl), so we work on the raw string after the '#'.
        val fragment = matrixToUri.substringAfter("#")
        if (fragment.isEmpty()) {
            return PermalinkData.FallbackLink(uriString)
        }
        val safeFragment = fragment.substringBefore('?')
        val viaQueryParameters = fragment.getViaParameters()

        // we are limiting to 2 params
        val params = safeFragment
                .split(MatrixPatterns.SEP_REGEX)
                .filter { it.isNotEmpty() }
                .take(2)

        val decodedParams = params
                .map { URLDecoder.decode(it, "UTF-8") }

        val identifier = params.getOrNull(0)
        val decodedIdentifier = decodedParams.getOrNull(0)
        val extraParameter = decodedParams.getOrNull(1)
        return when {
            identifier.isNullOrEmpty() || decodedIdentifier.isNullOrEmpty() -> PermalinkData.FallbackLink(uriString)
            MatrixPatterns.isUserId(decodedIdentifier) -> PermalinkData.UserLink(userId = decodedIdentifier)
            MatrixPatterns.isRoomId(decodedIdentifier) -> {
                handleRoomIdCase(fragment, decodedIdentifier, matrixToUri, extraParameter, viaQueryParameters)
            }
            MatrixPatterns.isRoomAlias(decodedIdentifier) -> {
                PermalinkData.RoomLink(
                        roomIdOrAlias = decodedIdentifier,
                        isRoomAlias = true,
                        eventId = extraParameter.takeIf { !it.isNullOrEmpty() && MatrixPatterns.isEventId(it) },
                        viaParameters = viaQueryParameters
                )
            }
            else -> PermalinkData.FallbackLink(uriString, MatrixPatterns.isGroupId(identifier))
        }
    }

    private fun handleRoomIdCase(fragment: String, identifier: String, matrixToUriString: String, extraParameter: String?, viaQueryParameters: List<String>): PermalinkData {
        // Can't rely on built in parsing because it's messing around the signurl
        val paramList = safeExtractParams(fragment)
        val signUrl = paramList.firstOrNull { it.first == "signurl" }?.second
        val email = paramList.firstOrNull { it.first == "email" }?.second
        return if (signUrl.isNullOrEmpty().not() && email.isNullOrEmpty().not()) {
            try {
                val validSignUrl = signUrl!!
                val validEmail = email!!
                val identityServerHost = URI(validSignUrl).authority ?: throw IllegalArgumentException()
                val token = validSignUrl.uriQueryParameter("token") ?: throw IllegalArgumentException()
                val privateKey = validSignUrl.uriQueryParameter("private_key") ?: throw IllegalArgumentException()
                PermalinkData.RoomEmailInviteLink(
                        roomId = identifier,
                        email = validEmail,
                        signUrl = validSignUrl,
                        roomName = paramList.firstOrNull { it.first == "room_name" }?.second,
                        inviterName = paramList.firstOrNull { it.first == "inviter_name" }?.second,
                        roomAvatarUrl = paramList.firstOrNull { it.first == "room_avatar_url" }?.second,
                        roomType = paramList.firstOrNull { it.first == "room_type" }?.second,
                        identityServer = identityServerHost,
                        token = token,
                        privateKey = privateKey
                )
            } catch (failure: Throwable) {
                Timber.i("## Permalink: Failed to parse permalink $signUrl")
                PermalinkData.FallbackLink(matrixToUriString)
            }
        } else {
            PermalinkData.RoomLink(
                    roomIdOrAlias = identifier,
                    isRoomAlias = false,
                    eventId = extraParameter.takeIf { !it.isNullOrEmpty() && MatrixPatterns.isEventId(it) },
                    viaParameters = viaQueryParameters
            )
        }
    }

    private fun safeExtractParams(fragment: String) =
            fragment.substringAfter("?").split('&').mapNotNull {
                val splitNameValue = it.split("=")
                if (splitNameValue.size == 2) {
                    Pair(splitNameValue[0], URLDecoder.decode(splitNameValue[1], "UTF-8"))
                } else null
            }

    private fun String.getViaParameters(): List<String> {
        return substringAfter('?', "")
                .split('&')
                .mapNotNull { it.split('=', limit = 2).takeIf { part -> part.size == 2 } }
                .filter { it[0] == "via" }
                .map { URLDecoder.decode(it[1], "UTF-8") }
    }
}
