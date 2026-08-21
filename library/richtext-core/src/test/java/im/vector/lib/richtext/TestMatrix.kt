/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import im.vector.lib.richtext.linkify.MatrixIdentifiers
import java.net.URLDecoder

/** Copy of the SDK's MatrixPatterns, so the shared module's tests need no SDK dependency. */
object TestMatrixPatterns : MatrixIdentifiers {
    private const val DOMAIN_REGEX = ":[A-Z0-9.-]+(:[0-9]{2,5})?"
    private const val BASE_64_ALPHABET = "[0-9A-Za-z/\\+=]+"
    private const val BASE_64_URL_SAFE_ALPHABET = "[0-9A-Za-z/\\-_]+"
    private val USER = "@[A-Z0-9\\x21-\\x39\\x3B-\\x7F]+$DOMAIN_REGEX".toRegex(RegexOption.IGNORE_CASE)
    private val ROOM = "^!.+$DOMAIN_REGEX$".toRegex(RegexOption.IGNORE_CASE)
    private val ROOM_DOMAINLESS = "!$BASE_64_URL_SAFE_ALPHABET".toRegex()
    private val ALIAS = "#[A-Z0-9._%#@=+-]+$DOMAIN_REGEX".toRegex(RegexOption.IGNORE_CASE)
    private val EVENT = "\\$[A-Z0-9]+$DOMAIN_REGEX".toRegex(RegexOption.IGNORE_CASE)
    private val EVENT_V3 = "\\$$BASE_64_ALPHABET".toRegex(RegexOption.IGNORE_CASE)
    private val EVENT_V4 = "\\$$BASE_64_URL_SAFE_ALPHABET".toRegex(RegexOption.IGNORE_CASE)
    private val GROUP = "\\+[A-Z0-9=_\\-./]+$DOMAIN_REGEX".toRegex(RegexOption.IGNORE_CASE)
    private val MATRIX_URI = "matrix:(//)?(u|r|roomid)/[A-Z0-9._~%!$&'*+;=:@/?-]+".toRegex(RegexOption.IGNORE_CASE)
    private val MATRIX_TO = "https://matrix\\.to/#/".toRegex(RegexOption.IGNORE_CASE)
    private val APP = "https://[A-Z0-9.-]+\\.[A-Z]{2,}/#/(room|user)/".toRegex(RegexOption.IGNORE_CASE)

    override val patterns = listOf(USER, ALIAS, ROOM, EVENT, GROUP, MATRIX_URI)
    override val matrixToUrlBase = "https://matrix.to/#/"

    fun isUserId(s: String) = s matches USER
    fun isRoomId(s: String) = s matches ROOM || s matches ROOM_DOMAINLESS
    fun isRoomAlias(s: String) = s matches ALIAS
    fun isEventId(s: String) = s matches EVENT || s matches EVENT_V3 || s matches EVENT_V4
    fun isGroupId(s: String) = s matches GROUP

    override fun isPermalink(text: String) = MATRIX_TO.containsMatchIn(text) || APP.containsMatchIn(text) || MATRIX_URI.containsMatchIn(text)
    override fun isIdentifier(text: String) = isUserId(text) || isRoomAlias(text) || isRoomId(text) || isGroupId(text) || isEventId(text)
}

/** Mirrors the golden harness's session stubs (see GOLDEN.md): matrix.to only, `@alice` = "Alice", no rooms. */
class TestPillResolver(private val roomId: String? = "!roomid:example.org") : PillResolver {

    private sealed class Parsed {
        data class User(val userId: String) : Parsed()
        data class Room(val roomIdOrAlias: String, val isAlias: Boolean, val eventId: String?) : Parsed()
        object Fallback : Parsed()
    }

    private val supportedHosts = listOf("app.element.io", "develop.element.io", "staging.element.io", "riot.im")

    private fun isSupported(url: String): Boolean =
            url.startsWith("https://matrix.to/#/") ||
                    url.startsWith("matrix:", ignoreCase = true) ||
                    supportedHosts.any { runCatching { java.net.URI(url).host }.getOrNull() == it }

    private fun parse(url: String): Parsed {
        if (!isSupported(url)) return Parsed.Fallback
        val matrixTo = TestMatrixToConverter.convert(url) ?: return Parsed.Fallback
        val fragment = matrixTo.substringAfter("#")
        if (fragment.isEmpty()) return Parsed.Fallback
        val params = fragment.substringBefore('?').split("/").filter { it.isNotEmpty() }.take(2)
        val decoded = params.map { URLDecoder.decode(it, "UTF-8") }
        val id = decoded.getOrNull(0) ?: return Parsed.Fallback
        if (id.isEmpty()) return Parsed.Fallback
        val extra = decoded.getOrNull(1)
        val eventId = extra?.takeIf { it.isNotEmpty() && TestMatrixPatterns.isEventId(it) }
        return when {
            TestMatrixPatterns.isUserId(id) -> Parsed.User(id)
            TestMatrixPatterns.isRoomId(id) -> Parsed.Room(id, false, eventId)
            TestMatrixPatterns.isRoomAlias(id) -> Parsed.Room(id, true, eventId)
            else -> Parsed.Fallback
        }
    }

    private fun user(userId: String) = PillTarget(PillKind.USER, userId, if (userId == "@alice:example.org") "Alice" else null, null)

    override fun resolveLink(url: String): PillTarget? = when (val parsed = parse(url)) {
        is Parsed.User -> user(parsed.userId)
        is Parsed.Room -> when {
            parsed.eventId != null -> null
            parsed.isAlias -> PillTarget(PillKind.ROOM_ALIAS, parsed.roomIdOrAlias, null, null)
            else -> PillTarget(PillKind.ROOM, parsed.roomIdOrAlias, null, null)
        }
        Parsed.Fallback -> null
    }

    override fun resolvePermalink(url: String): PillTarget? = when (val parsed = parse(url)) {
        is Parsed.User -> user(parsed.userId)
        is Parsed.Room -> {
            val id = parsed.roomIdOrAlias
            if (parsed.eventId.isNullOrEmpty()) {
                if (parsed.isAlias) PillTarget(PillKind.ROOM_ALIAS, id, null, null) else PillTarget(PillKind.ROOM, id, "Room/Space", null)
            } else {
                val targetRoomId = id.takeUnless { parsed.isAlias }
                when {
                    targetRoomId != null && targetRoomId == roomId -> PillTarget(PillKind.ROOM, targetRoomId, "Message", null)
                    parsed.isAlias -> PillTarget(PillKind.ROOM_ALIAS, id, "Message in $id", null)
                    else -> PillTarget(PillKind.ROOM, id, "Message in room", null)
                }
            }
        }
        Parsed.Fallback -> null
    }

    override fun notifyEveryone(): PillTarget? = roomId?.let { PillTarget(PillKind.EVERYONE, it, NOTIFY_EVERYONE, null) }
}

/** Copy of the SDK's MatrixToConverter. */
object TestMatrixToConverter {
    private const val MATRIX_TO = "https://matrix.to/#/"
    private val ENTITY_SIGILS = mapOf("u" to "@", "user" to "@", "r" to "#", "room" to "#", "roomid" to "!")
    private val EVENT_QUALIFIERS = setOf("e", "event")
    private val SUPPORTED_PATHS = listOf("/#/room/", "/#/user/", "/#/group/")

    fun convert(uriString: String): String? = when {
        uriString.startsWith(MATRIX_TO) -> uriString
        uriString.startsWith("matrix:", ignoreCase = true) -> convertMatrixUri(uriString)
        SUPPORTED_PATHS.any { it in uriString } -> MATRIX_TO + uriString.substringAfter(SUPPORTED_PATHS.first { it in uriString })
        else -> null
    }

    private fun convertMatrixUri(uriString: String): String? {
        val body = uriString.substringAfter(':').substringBefore('#')
        val path = body.substringBefore('?')
        val query = body.substringAfter('?', "")
        var segments = path.split('/').filter { it.isNotEmpty() }
        if (path.startsWith("//") && segments.firstOrNull() !in ENTITY_SIGILS.keys) segments = segments.drop(1)
        if (segments.size != 2 && segments.size != 4) return null
        val entity = segments[1].withSigil(ENTITY_SIGILS[segments[0]] ?: return null)
        val child = if (segments.size == 4) {
            if (segments[2] !in EVENT_QUALIFIERS || entity.startsWith("@")) return null
            "/" + segments[3].withSigil("$")
        } else ""
        return MATRIX_TO + entity + child + if (query.isEmpty()) "" else "?$query"
    }

    private fun String.withSigil(sigil: String) = if (startsWith(sigil)) this else sigil + this
}
