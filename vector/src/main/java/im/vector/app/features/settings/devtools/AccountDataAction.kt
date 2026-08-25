/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.devtools

import im.vector.app.core.platform.VectorViewModelAction

sealed class AccountDataAction : VectorViewModelAction {
    data class DeleteAccountData(val type: String) : AccountDataAction()
    data class UpdateAccountData(val type: String, val content: String, val encrypt: Boolean = false) : AccountDataAction()
    data class DraftTypeChange(val type: String) : AccountDataAction()
    data class DraftContentChange(val content: String) : AccountDataAction()
    data class DraftEncryptChange(val encrypt: Boolean) : AccountDataAction()

    /**
     * The 4S flow handed back its result cipher: extract the ADK from it, cache it, and when
     * [thenUpdate] is set carry out the account data update that was waiting on the key.
     */
    data class GotAdkFromSsss(val cipher: String, val alias: String, val thenUpdate: UpdateAccountData? = null) : AccountDataAction()

    /** Try to acquire the ADK without user interaction (cached 4S key). */
    object EnsureAdk : AccountDataAction()
}
