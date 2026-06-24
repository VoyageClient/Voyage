/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.pgp

/**
 * Detection / formatting helpers for PGP-over-plaintext messages. Mirrors QuickMedia's
 * approach: a message is "PGP" purely by virtue of containing an ASCII-armored block in its
 * body — this is independent of Matrix room encryption.
 */
object PgpUtils {

    const val PGP_MESSAGE_BEGIN = "-----BEGIN PGP MESSAGE-----"
    const val PGP_MESSAGE_END = "-----END PGP MESSAGE-----"
    private const val MX_REPLY_END = "</mx-reply>"

    /** True iff this body is a message we should decrypt — i.e. [extractArmoredBlock] accepts it. */
    fun bodyContainsPgp(body: CharSequence?): Boolean = body != null && extractArmoredBlock(body.toString()) != null

    /**
     * The armored block to decrypt, including its delimiters, or null if this body isn't a message
     * we should decrypt. This is the single gate for "is this PGP?" — display, the lock shield,
     * copy, previews and send-side detection all go through here (directly or via [bodyContainsPgp]),
     * so the rules below apply uniformly:
     *  - the reply fallback is stripped first (a legacy reply quotes the original's armored block,
     *    every line prefixed with "> ", ahead of the real message — that quote must never be taken
     *    for the message, e.g. a plaintext reply to an encrypted message stays plaintext);
     *  - the block must be the **trailing** content: only whitespace may follow the END marker, so a
     *    block embedded mid-text or fenced in a ``` code block (whose closing fence / following text
     *    sits after END) is shown verbatim, not decrypted;
     *  - the BEGIN marker must not be opened inside an unclosed ``` code fence.
     */
    fun extractArmoredBlock(body: String): String? {
        val cleaned = stripReplyFallback(body)
        val start = cleaned.indexOf(PGP_MESSAGE_BEGIN)
        if (start == -1) return null
        val endMarker = cleaned.indexOf(PGP_MESSAGE_END, start + PGP_MESSAGE_BEGIN.length)
        if (endMarker == -1) return null
        val endOfBlock = endMarker + PGP_MESSAGE_END.length
        if (cleaned.substring(endOfBlock).isNotBlank()) return null
        val fencesBefore = cleaned.substring(0, start).split("```").size - 1
        if (fencesBefore % 2 == 1) return null
        return cleaned.substring(start, endOfBlock)
    }

    private val ARMOR_HEADER = Regex("^[A-Za-z][A-Za-z0-9-]*: ")

    /**
     * RFC 4880 requires a blank line between the armor headers (Comment:/Version:/…) and the base64
     * data. QuickMedia/gpg sometimes omit it, which strict parsers (OpenKeychain/Bouncy Castle)
     * reject. Re-insert the separator so those messages decrypt. Applied only at the decrypt
     * boundary, so the cached/displayed armored block stays byte-identical to what's on the wire.
     */
    fun repairArmor(armored: String): String {
        val lines = armored.split("\n").toMutableList()
        var i = 1
        while (i < lines.size && ARMOR_HEADER.containsMatchIn(lines[i])) i++
        if (i in 2 until lines.size && lines[i].isNotEmpty()) {
            lines.add(i, "")
        }
        return lines.joinToString("\n")
    }

    /**
     * Strips a reply fallback that precedes the real message — the HTML `<mx-reply>…</mx-reply>`
     * block, or the legacy `"> <@user:server> …"` quoted body. For the plain-text fallback we drop
     * all leading quoted (`>`-prefixed) and blank lines and return the first real line onward, so the
     * real message's own armor (with its required internal blank line) is preserved. This is more
     * robust than slicing at the first blank line: a reply to a *deleted* message has no quoted
     * content, so the fallback is just the mention line directly followed by the real block (no
     * blank-line separator), and a blank-line slice would leave the mangled `> -----BEGIN PGP…` quote
     * in place.
     */
    fun stripReplyFallback(body: String): String {
        val htmlEnd = body.lastIndexOf(MX_REPLY_END)
        if (htmlEnd != -1) return body.substring(htmlEnd + MX_REPLY_END.length)
        if (body.startsWith("> ")) {
            val lines = body.split("\n")
            var i = 0
            while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(">"))) i++
            if (i < lines.size) return lines.subList(i, lines.size).joinToString("\n")
        }
        return body
    }

    /** "@user:server" -> "user@server" (port of QuickMedia user_id_to_email_format). */
    fun matrixIdToEmail(userId: String): String? {
        if (userId.isEmpty() || userId[0] != '@') return null
        val colon = userId.indexOf(':')
        if (colon <= 1) return null
        val local = userId.substring(1, colon)
        val domain = userId.substring(colon + 1)
        if (local.isEmpty() || domain.isEmpty()) return null
        return "$local@$domain"
    }
}
