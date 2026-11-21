/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.typing

import android.text.SpannableStringBuilder
import im.vector.app.core.resources.BidiSafeStringProvider
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import javax.inject.Inject

class TypingHelper @Inject constructor(stringProvider: StringProvider) {

    private val stringProvider = BidiSafeStringProvider(stringProvider)

    /**
     * Returns a human readable String of currently typing users in the room (excluding yourself).
     */
    fun getTypingMessage(typingUsers: List<SenderInfo>): String {
        return when {
            typingUsers.isEmpty() ->
                ""
            typingUsers.size == 1 ->
                stringProvider.getString(CommonStrings.room_one_user_is_typing, typingUsers[0].disambiguatedDisplayName)
            typingUsers.size == 2 ->
                stringProvider.getString(
                        CommonStrings.room_two_users_are_typing,
                        typingUsers[0].disambiguatedDisplayName,
                        typingUsers[1].disambiguatedDisplayName
                )
            else ->
                stringProvider.getString(
                        CommonStrings.room_many_users_are_typing,
                        typingUsers[0].disambiguatedDisplayName,
                        typingUsers[1].disambiguatedDisplayName
                )
        }
    }

    fun getTypingLine(names: List<CharSequence>): CharSequence {
        val template = when (names.size) {
            0 -> return ""
            1 -> stringProvider.getString(CommonStrings.typing_indicator_one, SENTINELS[0])
            2 -> stringProvider.getString(CommonStrings.typing_indicator_two, SENTINELS[0], SENTINELS[1])
            3 -> stringProvider.getString(CommonStrings.typing_indicator_three, SENTINELS[0], SENTINELS[1], SENTINELS[2])
            else -> return stringProvider.getString(CommonStrings.typing_indicator_many)
        }
        // Names are spliced in after formatting so their spans survive; the sentinels sit inside the bidi isolates.
        val slots = names.indices
                .map { template.indexOf(SENTINELS[it]) to names[it] }
                .filter { it.first >= 0 }
                .sortedByDescending { it.first }
        return SpannableStringBuilder(template).apply {
            slots.forEach { (index, name) -> replace(index, index + 1, name) }
        }
    }

    private companion object {
        private val SENTINELS = (0xE000..0xE002).map { Char(it).toString() }
    }
}
