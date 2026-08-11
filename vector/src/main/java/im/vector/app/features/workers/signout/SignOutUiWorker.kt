/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.workers.signout

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.extensions.cannotLogoutSafely
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session

class SignOutUiWorker(private val activity: FragmentActivity) {

    fun perform(localOnly: Boolean = false) {
        val session = activity.singletonEntryPoint().activeSessionHolder().getSafeActiveSession() ?: return
        activity.lifecycleScope.perform(session, localOnly)
    }

    private fun CoroutineScope.perform(session: Session, localOnly: Boolean) = launch {
        if (session.cannotLogoutSafely()) {
            // The backup check on logout flow has to be displayed if there are keys in the store, and the keys backup state is not Ready
            val signOutDialog = SignOutBottomSheetDialogFragment.newInstance()
            signOutDialog.onSignOut = Runnable {
                // The sheet only settles the key-backup question, so a local sign-out still has to
                // state that the server session is being left behind.
                if (localOnly) confirm(localOnly = true) else doSignOut(localOnly = false)
            }
            signOutDialog.show(activity.supportFragmentManager, "SO")
        } else {
            confirm(localOnly)
        }
    }

    private fun confirm(localOnly: Boolean) {
        val action = if (localOnly) CommonStrings.action_sign_out_locally else CommonStrings.action_sign_out
        MaterialAlertDialogBuilder(activity)
                .setTitle(action)
                .setMessage(if (localOnly) CommonStrings.action_sign_out_locally_confirmation else CommonStrings.action_sign_out_confirmation_simple)
                .setPositiveButton(action) { _, _ -> doSignOut(localOnly) }
                .setNegativeButton(CommonStrings.action_cancel, null)
                .show()
    }

    private fun doSignOut(localOnly: Boolean) {
        MainActivity.restartApp(activity, MainActivityArgs(clearCredentials = true, keepServerSession = localOnly))
    }
}
