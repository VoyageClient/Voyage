/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.resources

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import im.vector.lib.core.utils.text.bidiIsolated
import im.vector.lib.core.utils.text.withUiDirection

/**
 * [StringProvider] for UI sentences that embed user text: every argument is bidi-isolated and the result is
 * pinned to the UI direction, so an RTL name can't flip "Name joined the room" in an LTR UI (or vice versa).
 */
class BidiSafeStringProvider(private val stringProvider: StringProvider) {

    fun getString(@StringRes resId: Int): String = stringProvider.getString(resId)

    fun getString(@StringRes resId: Int, vararg formatArgs: Any?): String =
            stringProvider.getString(resId, *formatArgs.bidiIsolated()).withUiDirection()

    fun getQuantityString(@PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any?): String =
            stringProvider.getQuantityString(resId, quantity, *formatArgs.bidiIsolated()).withUiDirection()
}
