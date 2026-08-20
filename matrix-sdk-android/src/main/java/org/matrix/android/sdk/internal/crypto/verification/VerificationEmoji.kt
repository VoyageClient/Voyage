/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.android.sdk.internal.crypto.verification

import org.matrix.android.sdk.api.session.crypto.verification.EmojiRepresentation
import org.matrix.android.sdk.internal.extensions.toUnsignedInt

// The SAS emoji, in code order (index = the 6-bit code). Their string/drawable resource ids are
// android-only, so the platform installs them through [sasEmojiResourceIds].
private val SAS_EMOJIS = listOf(
        "🐶", "🐱", "🦁", "🐎", "🦄", "🐷", "🐘", "🐰",
        "🐼", "🐓", "🐧", "🐢", "🐟", "🐙", "🦋", "🌷",
        "🌳", "🌵", "🍄", "🌏", "🌙", "☁️", "🔥", "🍌",
        "🍎", "🍓", "🌽", "🍕", "🎂", "❤️", "🙂", "🤖",
        "🎩", "👓", "🔧", "🎅", "👍", "☂️", "⌛", "⏰",
        "🎁", "💡", "📕", "✏️", "📎", "✂️", "🔒", "🔑",
        "🔨", "☎️", "🏁", "🚂", "🚲", "✈️", "🚀", "🏆",
        "⚽", "🎸", "🎺", "🔔", "⚓", "🎧", "📁", "📌",
)

internal var sasEmojiResourceIds: ((Int) -> Pair<Int, Int?>)? = null

internal fun getEmojiForCode(code: Int): EmojiRepresentation {
    val index = code % 64
    val resIds = sasEmojiResourceIds?.invoke(index)
    return EmojiRepresentation(SAS_EMOJIS[index], resIds?.first ?: 0, resIds?.second)
}

/**
 * decimal: generate five bytes by using HKDF.
 * Take the first 13 bits and convert it to a decimal number (which will be a number between 0 and 8191 inclusive),
 * and add 1000 (resulting in a number between 1000 and 9191 inclusive).
 * Do the same with the second 13 bits, and the third 13 bits, giving three 4-digit numbers.
 * In other words, if the five bytes are B0, B1, B2, B3, and B4, then the first number is (B0 << 5 | B1 >> 3) + 1000,
 * the second number is ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000, and the third number is ((B3 & 0x3f) << 7 | B4 >> 1) + 1000.
 * (This method of converting 13 bits at a time is used to avoid requiring 32-bit clients to do big-number arithmetic,
 * and adding 1000 to the number avoids having clients to worry about properly zero-padding the number when displaying to the user.)
 * The three 4-digit numbers are displayed to the user either with dashes (or another appropriate separator) separating the three numbers,
 * or with the three numbers on separate lines.
 */
fun ByteArray.getDecimalCodeRepresentation(separator: String = " "): String {
    val b0 = this[0].toUnsignedInt() // need unsigned byte
    val b1 = this[1].toUnsignedInt() // need unsigned byte
    val b2 = this[2].toUnsignedInt() // need unsigned byte
    val b3 = this[3].toUnsignedInt() // need unsigned byte
    val b4 = this[4].toUnsignedInt() // need unsigned byte
    // (B0 << 5 | B1 >> 3) + 1000
    val first = (b0.shl(5) or b1.shr(3)) + 1000
    // ((B1 & 0x7) << 10 | B2 << 2 | B3 >> 6) + 1000
    val second = ((b1 and 0x7).shl(10) or b2.shl(2) or b3.shr(6)) + 1000
    // ((B3 & 0x3f) << 7 | B4 >> 1) + 1000
    val third = ((b3 and 0x3f).shl(7) or b4.shr(1)) + 1000
    return listOf(first, second, third).joinToString(separator)
}

/**
 * emoji: generate six bytes by using HKDF.
 * Split the first 42 bits into 7 groups of 6 bits, as one would do when creating a base64 encoding.
 * For each group of 6 bits, look up the emoji from Appendix A corresponding
 * to that number 7 emoji are selected from a list of 64 emoji (see Appendix A)
 */
fun ByteArray.getEmojiCodeRepresentation(): List<EmojiRepresentation> {
    val b0 = this[0].toUnsignedInt()
    val b1 = this[1].toUnsignedInt()
    val b2 = this[2].toUnsignedInt()
    val b3 = this[3].toUnsignedInt()
    val b4 = this[4].toUnsignedInt()
    val b5 = this[5].toUnsignedInt()
    return listOf(
        getEmojiForCode((b0 and 0xFC).shr(2)),
        getEmojiForCode((b0 and 0x3).shl(4) or (b1 and 0xF0).shr(4)),
        getEmojiForCode((b1 and 0xF).shl(2) or (b2 and 0xC0).shr(6)),
        getEmojiForCode((b2 and 0x3F)),
        getEmojiForCode((b3 and 0xFC).shr(2)),
        getEmojiForCode((b3 and 0x3).shl(4) or (b4 and 0xF0).shr(4)),
        getEmojiForCode((b4 and 0xF).shl(2) or (b5 and 0xC0).shr(6))
    )
}
