/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.view.HapticFeedbackConstants
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import im.vector.app.features.home.room.detail.timeline.item.ReadReceiptData
import im.vector.app.features.home.room.detail.timeline.readreceipts.DisplayReadReceiptsBottomSheet
import im.vector.app.features.media.shouldHideAvatars
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import javax.inject.Inject

class PollVotersFactory @Inject constructor(
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
) {

    fun create(roomId: String, pollStartEventId: String, optionId: String): List<ReadReceiptData> {
        val room = session.getRoom(roomId) ?: return emptyList()
        val votes = room.relationService().getEventAnnotationsSummary(pollStartEventId)
                ?.pollResponseSummary
                ?.aggregatedContent
                ?.votes
                ?.filter { it.option == optionId }
                .orEmpty()
        if (votes.isEmpty()) return emptyList()
        val hideAvatars = shouldHideAvatars(roomId, session, vectorPreferences)
        return votes
                .map { vote ->
                    val member = room.membershipService().getRoomMember(vote.userId)
                    ReadReceiptData(
                            userId = vote.userId,
                            avatarUrl = member?.avatarUrl.takeUnless { hideAvatars },
                            displayName = member?.displayName,
                            timestamp = vote.voteTimestamp,
                            colorOnLight = member?.colorPreference?.onLight,
                            colorOnDark = member?.colorPreference?.onDark,
                            avatarDecryption = member?.avatarDecryption,
                    )
                }
                .sortedByDescending { it.timestamp }
    }

    fun show(fragment: Fragment, roomId: String, pollStartEventId: String, optionId: String, optionAnswer: String) {
        fragment.view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val voters = withContext(Dispatchers.IO) { create(roomId, pollStartEventId, optionId) }
            if (voters.isEmpty() || !fragment.isAdded) return@launch
            DisplayReadReceiptsBottomSheet.newPollVotersInstance(optionAnswer, voters)
                    .show(fragment.requireActivity().supportFragmentManager, "DISPLAY_POLL_VOTERS")
        }
    }
}
