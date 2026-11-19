/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.state

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.toContentOver

class SafePowerLevelContentTest {

    private val custom = mapOf("level" to 75)

    @Test
    fun `keys the model doesn't know survive the safe conversion`() {
        val safe = mapOf("ban" to "50", "org.example.custom" to custom).toSafePowerLevelsContentDict()

        (safe["ban"] as Number).toInt() shouldBeEqualTo 50
        safe["org.example.custom"] shouldBeEqualTo custom
    }

    @Test
    fun `a change laid over the current content keeps its unknown keys`() {
        val current = mapOf("users" to mapOf("@alice:example.org" to 100), "org.example.custom" to custom)

        val updated = PowerLevelsContent(users = mapOf("@bob:example.org" to 50)).toContentOver(current)

        updated["org.example.custom"] shouldBeEqualTo custom
        (updated["users"] as Map<*, *>).mapValues { (it.value as Number).toInt() } shouldBeEqualTo mapOf("@bob:example.org" to 50)
    }
}
