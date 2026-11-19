/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import java.util.Base64

class E2eDebugTest {

    private fun megolmMessage(vararg indexVarint: Int): String {
        val bytes = byteArrayOf(0x03, 0x08) + indexVarint.map { it.toByte() } + byteArrayOf(0x12, 0x10) + ByteArray(24) { (it * 7).toByte() }
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }

    @Test
    fun `reads the message index from a megolm ciphertext header`() {
        E2eDebug.megolmMessageIndex(megolmMessage(0x00)) shouldBeEqualTo 0L
        E2eDebug.megolmMessageIndex(megolmMessage(0x05)) shouldBeEqualTo 5L
        E2eDebug.megolmMessageIndex(megolmMessage(0xAC, 0x02)) shouldBeEqualTo 300L
        E2eDebug.megolmMessageIndex(megolmMessage(0xFF, 0xFF, 0x03)) shouldBeEqualTo 65_535L
    }

    @Test
    fun `anything that is not a megolm header gives no index`() {
        E2eDebug.megolmMessageIndex(null) shouldBe null
        E2eDebug.megolmMessageIndex("") shouldBe null
        E2eDebug.megolmMessageIndex("not base64 at all!") shouldBe null
        E2eDebug.megolmMessageIndex(Base64.getEncoder().withoutPadding().encodeToString(ByteArray(16) { 0x04 })) shouldBe null
    }
}
