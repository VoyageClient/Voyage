/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError

/**
 * Servers without a stabilised route answer 404 (often without an M_NOT_FOUND body, so
 * [org.matrix.android.sdk.api.failure.is404] would not match) or M_UNRECOGNIZED.
 */
internal fun Throwable.shouldFallBackToUnstableEndpoint(): Boolean {
    return this is Failure.ServerError &&
            (httpCode == 404 || error.code == MatrixError.M_UNRECOGNIZED)
}
