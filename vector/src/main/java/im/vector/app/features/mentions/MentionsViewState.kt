/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary

data class MentionsViewState(
        val mentions: Async<List<MentionListItem>> = Uninitialized,
        val filter: MentionsFilter = MentionsFilter(),
        val searchQuery: String = "",
        val hasMore: Boolean = false,
        /** Everyone who has mentioned us, for `from:` completion. From the unfiltered list, so that
         * narrowing the results never narrows the pool the completion offers. */
        val senders: List<RoomMemberSummary> = emptyList(),
        // Event equality ignores the transient decryption result, so a decrypt-triggered reload can
        // produce an == list; bump this so Mavericks still notifies and the list re-renders.
        val mentionsTick: Int = 0,
) : MavericksState
