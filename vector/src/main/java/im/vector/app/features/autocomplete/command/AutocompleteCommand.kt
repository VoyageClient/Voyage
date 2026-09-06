/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.command

import org.matrix.android.sdk.api.util.MatrixItem

data class AutocompleteCommand(
        val id: String,
        val command: String,
        val aliases: List<String> = emptyList(),
        val sourceUserId: String? = null,
        val source: MatrixItem.UserItem? = null,
        val insertionCommand: String = command,
        val parameters: String,
        val description: String,
)
