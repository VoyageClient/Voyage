/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

/**
 * An MSC4426 user status: text the user set about themselves, with an emoji summarizing it.
 * The emoji is empty when the status came from a client whose field carries text alone.
 */
data class UserStatus(
        val text: String,
        val emoji: String = "",
) {
    fun isEmpty() = text.isBlank() && emoji.isBlank()

    /** The status as one line, e.g. "🌴 On holiday". Empty when nothing is set. */
    fun display() = listOf(emoji, text).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        // MSC4426 caps the UTF-8 encoded fields; a server rejects anything longer with M_TOO_LARGE.
        const val MAX_TEXT_BYTES = 256
        const val MAX_EMOJI_BYTES = 32
    }
}

/**
 * An MSC4440 biography. [formattedBody] is the HTML representation, in the same subset as a
 * formatted message; it is preferred over [body] for display when present.
 */
data class UserBio(
        val body: String,
        val formattedBody: String? = null,
) {
    fun isEmpty() = body.isBlank() && formattedBody.isNullOrBlank()
}
