/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

object TelegramLinks {

    private val SET_NAME = Regex("[A-Za-z0-9_]{1,64}")
    private val WEB_LINK = Regex(
            "^(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)/(?:addstickers|addemoji)/([A-Za-z0-9_]{1,64})/?(?:[?#].*)?$",
            RegexOption.IGNORE_CASE,
    )
    private val TG_LINK = Regex("^tg://(?:addstickers|addemoji)\\?(?:.*&)?set=([A-Za-z0-9_]{1,64})(?:&.*)?$", RegexOption.IGNORE_CASE)

    /** The sticker set name in a Telegram pack link, or a bare set name; null when [text] is neither. */
    fun parseSetName(text: CharSequence?): String? {
        val trimmed = text?.toString()?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        WEB_LINK.matchEntire(trimmed)?.let { return it.groupValues[1] }
        TG_LINK.matchEntire(trimmed)?.let { return it.groupValues[1] }
        return trimmed.takeIf { SET_NAME.matches(it) }
    }

    /** Like [parseSetName] but only for actual links, so arbitrary clipboard words aren't mistaken for packs. */
    fun parseLink(text: CharSequence?): String? {
        val trimmed = text?.toString()?.trim().orEmpty()
        return WEB_LINK.matchEntire(trimmed)?.groupValues?.get(1)
                ?: TG_LINK.matchEntire(trimmed)?.groupValues?.get(1)
    }
}
