/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.pgp

import android.app.PendingIntent

/**
 * Result of a PGP encrypt/decrypt round-trip through OpenKeychain. [NeedsInteraction] carries
 * the [PendingIntent] OpenKeychain hands back when it needs the user (passphrase, key picker,
 * missing-recipient confirmation) before it can finish.
 */
sealed interface PgpResult {
    data class Success(val data: String) : PgpResult
    data class NeedsInteraction(val pendingIntent: PendingIntent) : PgpResult
    data class Error(val message: String) : PgpResult
}

sealed interface PgpKeyResult {
    data class Success(val keyId: Long) : PgpKeyResult
    data class NeedsInteraction(val pendingIntent: PendingIntent) : PgpKeyResult
    data class Error(val message: String) : PgpKeyResult
}
