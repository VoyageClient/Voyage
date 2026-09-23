/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import org.matrix.android.sdk.api.extensions.tryOrNull

/** Whether a dialog or bottom sheet of ours is on screen, here or under any of the fragments below. */
fun FragmentManager.hasShowingDialogFragment(): Boolean = fragments.any { fragment ->
    (fragment is DialogFragment && fragment.dialog?.isShowing == true) ||
            (fragment.isAdded && fragment.childFragmentManager.hasShowingDialogFragment())
}

inline fun androidx.fragment.app.FragmentManager.commitTransactionNow(func: FragmentTransaction.() -> FragmentTransaction) {
    // Could throw and make the app crash
    // e.g sharedActionViewModel.observe()
    tryOrNull("Failed to commitTransactionNow") {
        beginTransaction().func().commitNow()
    }
}

inline fun androidx.fragment.app.FragmentManager.commitTransaction(allowStateLoss: Boolean = false, func: FragmentTransaction.() -> FragmentTransaction) {
    val transaction = beginTransaction().func()
    if (allowStateLoss) {
        transaction.commitAllowingStateLoss()
    } else {
        transaction.commit()
    }
}
