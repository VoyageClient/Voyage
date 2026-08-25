/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.crypto.quads

import android.content.Context
import android.content.Intent
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.EncryptedAccountDataService
import org.matrix.android.sdk.api.session.securestorage.KeyInfoResult

object AdkFlows {

    /**
     * Intent for the recovery-key flow that yields the MSC4483 ADK: reads it from 4S when stored
     * there, otherwise generates one and stores it. Null when secure backup is not set up at all.
     */
    fun buildAdkIntent(context: Context, session: Session): Intent? {
        return buildAdkReadIntent(context, session) ?: run {
            if (session.sharedSecretStorageService().getDefaultKey() !is KeyInfoResult.Success) return null
            val adk = session.encryptedAccountDataService().generateAccountDataKey()
            SharedSecureStorageActivity.newWriteIntent(
                    context = context,
                    writeSecrets = EncryptedAccountDataService.ADK_SECRET_NAMES.map { it to adk },
            )
        }
    }

    /**
     * Intent to read the ADK from 4S, or null when it is not stored there. Only one secret name is
     * requested — the 4S flow's integrity precheck fails if any requested secret is absent.
     */
    fun buildAdkReadIntent(context: Context, session: Session): Intent? {
        val existingAdkName = EncryptedAccountDataService.ADK_SECRET_NAMES
                .firstOrNull { session.accountDataService().getUserAccountDataEvent(it) != null }
        return existingAdkName?.let {
            SharedSecureStorageActivity.newReadIntent(
                    context = context,
                    requestedSecrets = listOf(it),
            )
        }
    }
}
