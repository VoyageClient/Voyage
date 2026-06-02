/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.auth.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * This is a subset of the server metadata discovery for the OAuth 2.0 API
 * https://spec.matrix.org/v1.16/client-server-api/#get_matrixclientv1auth_metadata
 *
 * Includes the values from MSC4191: https://github.com/matrix-org/matrix-spec-proposals/pull/4191
 *
 * <pre>
 * {
 *     "issuer": "https://id.server.org",
 *     "account_management_uri": "https://id.server.org/my-account",
 *     "account_management_actions_supported": ["org.matrix.profile", "org.matrix.devices_list"],
 * }
 * </pre>
 * .
 */

@JsonClass(generateAdapter = true)
data class AuthMetadata(
        @Json(name = "issuer")
        val issuer: String,

        @Json(name = "account_management_uri")
        val accountManagementUri: String?,

        @Json(name = "account_management_actions_supported")
        val accountManagementActionsSupported: List<String>?,
)
