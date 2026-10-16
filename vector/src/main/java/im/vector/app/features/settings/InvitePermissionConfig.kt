/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content

/** MSC4380 invite blocking: `default_action: "block"` in the invite permission config. */
object InvitePermissionConfig {

    private const val DEFAULT_ACTION = "default_action"
    private const val BLOCK = "block"

    private val TYPES = listOf(UserAccountDataTypes.TYPE_INVITE_PERMISSION_CONFIG, UserAccountDataTypes.TYPE_INVITE_PERMISSION_CONFIG_UNSTABLE)

    fun areInvitesBlocked(accountData: (String) -> Content?): Boolean =
            (accountData(UserAccountDataTypes.TYPE_INVITE_PERMISSION_CONFIG) ?: accountData(UserAccountDataTypes.TYPE_INVITE_PERMISSION_CONFIG_UNSTABLE))
                    .blocksByDefault()

    /** The account data to write, by type, for invites to be [block]ed. Empty when nothing has to change. */
    fun changesFor(block: Boolean, accountData: (String) -> Content?): Map<String, Content> =
            TYPES.mapNotNull { type ->
                val content = accountData(type)
                when {
                    block && !content.blocksByDefault() -> type to content.orEmpty() + (DEFAULT_ACTION to BLOCK)
                    !block && content.blocksByDefault() -> type to content.orEmpty() - DEFAULT_ACTION
                    else -> null
                }
            }.toMap()

    private fun Content?.blocksByDefault() = this?.get(DEFAULT_ACTION) == BLOCK
}
