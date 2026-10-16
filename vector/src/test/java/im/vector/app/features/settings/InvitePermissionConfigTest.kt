/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes.TYPE_INVITE_PERMISSION_CONFIG
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes.TYPE_INVITE_PERMISSION_CONFIG_UNSTABLE
import org.matrix.android.sdk.api.session.events.model.Content

class InvitePermissionConfigTest {

    private fun accountData(vararg entries: Pair<String, Content>): (String) -> Content? = mapOf(*entries)::get

    @Test
    fun `stable config is read before the unstable one`() {
        val unstableBlock = TYPE_INVITE_PERMISSION_CONFIG_UNSTABLE to mapOf("default_action" to "block")
        InvitePermissionConfig.areInvitesBlocked(accountData(TYPE_INVITE_PERMISSION_CONFIG to emptyMap(), unstableBlock)).shouldBeFalse()
        InvitePermissionConfig.areInvitesBlocked(accountData(unstableBlock)).shouldBeTrue()
    }

    @Test
    fun `blocking writes both types and keeps other keys`() {
        val data = accountData(TYPE_INVITE_PERMISSION_CONFIG to mapOf("future" to true))
        InvitePermissionConfig.changesFor(block = true, data) shouldBeEqualTo mapOf(
                TYPE_INVITE_PERMISSION_CONFIG to mapOf("future" to true, "default_action" to "block"),
                TYPE_INVITE_PERMISSION_CONFIG_UNSTABLE to mapOf("default_action" to "block"),
        )
    }

    @Test
    fun `allowing clears only blocking types and never creates absent config`() {
        val data = accountData(TYPE_INVITE_PERMISSION_CONFIG to mapOf("default_action" to "block", "future" to true))
        InvitePermissionConfig.changesFor(block = false, data) shouldBeEqualTo mapOf(TYPE_INVITE_PERMISSION_CONFIG to mapOf("future" to true))
        InvitePermissionConfig.changesFor(block = false, accountData()).shouldBeEmpty()
    }
}
