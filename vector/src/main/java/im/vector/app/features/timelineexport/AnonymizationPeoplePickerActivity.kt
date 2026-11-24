/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.SimpleFragmentActivity
import im.vector.app.features.userdirectory.PendingSelection
import im.vector.app.features.userdirectory.UserListFragment
import im.vector.app.features.userdirectory.UserListFragmentArgs
import im.vector.app.features.userdirectory.UserListSharedAction
import im.vector.app.features.userdirectory.UserListSharedActionViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** The invite screen's people picker, returning the chosen user ids instead of inviting them. Shows real identities. */
@AndroidEntryPoint
class AnonymizationPeoplePickerActivity : SimpleFragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views.toolbar.visibility = View.GONE
        viewModelProvider.get(UserListSharedActionViewModel::class.java).stream().onEach { action ->
            when (action) {
                UserListSharedAction.Close -> finish()
                UserListSharedAction.GoBack -> onBackPressedDispatcher.onBackPressed()
                is UserListSharedAction.OnMenuItemSubmitClick -> {
                    val ids = action.selections.filterIsInstance<PendingSelection.UserPendingSelection>().map { it.user.userId }
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_USER_IDS, ids.toTypedArray()))
                    finish()
                }
                UserListSharedAction.OpenPhoneBook -> Unit
            }
        }.launchIn(lifecycleScope)
        if (isFirstCreation()) {
            addFragment(views.container, UserListFragment::class.java, UserListFragmentArgs(
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    menuResId = R.menu.menu_anonymization_people,
                    submitMenuItemId = R.id.action_anonymization_people_add,
                    excludedUserIds = intent.getStringArrayExtra(EXTRA_EXCLUDED)?.toSet(),
                    showContactBookAction = false,
                    revealIdentities = true,
                    roomId = intent.getStringExtra(EXTRA_ROOM_ID),
                    includeSelf = true,
            ))
        }
    }

    companion object {
        const val EXTRA_USER_IDS = "user_ids"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_EXCLUDED = "excluded"
        private const val EXTRA_ROOM_ID = "room_id"

        /** @param roomId offers that room's members; null searches everyone. */
        fun intent(context: Context, title: String, excluded: Collection<String>, roomId: String?): Intent =
                Intent(context, AnonymizationPeoplePickerActivity::class.java)
                        .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_EXCLUDED, excluded.toTypedArray()).putExtra(EXTRA_ROOM_ID, roomId)
    }
}
