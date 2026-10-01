/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class TelegramLinksTest {

    @Test
    fun `web links of every form give the set name`() {
        TelegramLinks.parseSetName("https://t.me/addstickers/AniColle") shouldBeEqualTo "AniColle"
        TelegramLinks.parseSetName("http://telegram.me/addstickers/AniColle/") shouldBeEqualTo "AniColle"
        TelegramLinks.parseSetName("t.me/addemoji/Some_Emoji_by_bot") shouldBeEqualTo "Some_Emoji_by_bot"
        TelegramLinks.parseSetName("  https://www.t.me/addstickers/AniColle?ref=x  ") shouldBeEqualTo "AniColle"
        TelegramLinks.parseSetName("https://telegram.dog/addstickers/AniColle") shouldBeEqualTo "AniColle"
    }

    @Test
    fun `tg scheme links give the set name`() {
        TelegramLinks.parseSetName("tg://addstickers?set=AniColle") shouldBeEqualTo "AniColle"
        TelegramLinks.parseSetName("tg://addemoji?foo=1&set=Pack_2") shouldBeEqualTo "Pack_2"
    }

    @Test
    fun `a bare name is accepted but other text is not`() {
        TelegramLinks.parseSetName("AniColle") shouldBeEqualTo "AniColle"
        TelegramLinks.parseSetName("https://example.org/addstickers/AniColle") shouldBeEqualTo null
        TelegramLinks.parseSetName("two words") shouldBeEqualTo null
        TelegramLinks.parseSetName("") shouldBeEqualTo null
        TelegramLinks.parseSetName(null) shouldBeEqualTo null
    }

    @Test
    fun `clipboard detection only takes actual links`() {
        TelegramLinks.parseLink("https://t.me/addstickers/AniColle") shouldBeEqualTo "AniColle"
        TelegramLinks.parseLink("AniColle") shouldBeEqualTo null
        TelegramLinks.parseLink("hello https://t.me/addstickers/AniColle") shouldBeEqualTo null
    }
}
