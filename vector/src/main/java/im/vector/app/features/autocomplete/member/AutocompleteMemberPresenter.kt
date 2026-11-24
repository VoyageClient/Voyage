/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.member

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.RecyclerViewPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.profile.IdentityPresentation
import org.matrix.android.sdk.api.session.pushrules.SenderNotificationPermissionCondition
import org.matrix.android.sdk.api.session.room.members.RoomMemberQueryParams
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.MatrixItem

class AutocompleteMemberPresenter @AssistedInject constructor(
        context: Context,
        @Assisted val roomId: String,
        private val session: Session,
        private val mentionFrequencyDataSource: MentionFrequencyDataSource,
        private val controller: AutocompleteMemberController
) : RecyclerViewPresenter<AutocompleteMemberItem>(context), AutocompleteClickListener<AutocompleteMemberItem> {

    /* ==========================================================================================
     * Fields
     * ========================================================================================== */

    private val room by lazy { session.getRoom(roomId)!! }

    // Use a SupervisorJob so a failed query doesn't kill the scope.
    private val queryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queryJob: Job? = null

    /* ==========================================================================================
     * Init
     * ========================================================================================== */

    init {
        controller.listener = this
        controller.roomId = roomId
    }

    /* ==========================================================================================
     * Public api
     * ========================================================================================== */

    fun clear() {
        controller.listener = null
        queryJob?.cancel()
        queryScope.coroutineContext.cancelChildren()
    }

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): AutocompleteMemberPresenter
    }

    /* ==========================================================================================
     * Specialization
     * ========================================================================================== */

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun clearData() {
        controller.setData(emptyList())
    }

    override fun instantiateRecyclerView(): RecyclerView = dividedRecyclerView(MAX_VISIBLE_MEMBERS)

    override fun onViewShown() = slideContentUpOnShow()

    override fun onViewHidden() {
        super.onViewHidden()
        queryJob?.cancel()
        anonymizedCache = null
    }

    override fun animateViewOut(onEnd: Runnable) = slideContentDownOnHide(onEnd)

    override fun onItemClick(t: AutocompleteMemberItem) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        // Each keystroke triggers a fresh Realm query + sort + disambiguation; on the main
        // thread in big rooms that was visibly stalling typing. Debounce briefly and run
        // the work on a background dispatcher, swapping the result in on the main thread.
        queryJob?.cancel()
        queryJob = queryScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            val queryString = query?.toString()
            val items = withContext(Dispatchers.Default) {
                im.vector.app.core.utils.PerfTrace.time("autocomplete.members.query") {
                    val queryParams = createQueryParams(queryString)
                    val members = createMemberItems(queryParams, queryString)
                    val everyone = createEveryoneItem(queryString)
                    buildList {
                        addAll(members)
                        everyone?.let { add(it) }
                    }
                }
            }
            // Keep the current rows on screen so they are what animates away, rather than collapsing first.
            if (items.isEmpty()) {
                requestDismiss()
                return@launch
            }
            controller.setData(items)
        }
    }

    /* ==========================================================================================
     * Helper methods
     * ========================================================================================== */

    private fun createQueryParams(query: CharSequence?) = roomMemberQueryParams {
        displayNameOrUserId = if (query.isNullOrBlank()) {
            QueryStringValue.NoCondition
        } else {
            QueryStringValue.Contains(query.toString(), QueryStringValue.Case.INSENSITIVE)
        }
        memberships = listOf(Membership.JOIN)
        // Allow mentioning yourself (creates a self mention pill) like other members.
        excludeSelf = false
    }

    private fun createMemberItems(queryParams: RoomMemberQueryParams, query: CharSequence?): List<AutocompleteMemberItem.RoomMember> {
        // Most-mentioned first, alphabetical for members with no mention history.
        val frequencies = mentionFrequencyDataSource.frequencies(roomId)
        return membersMatching(queryParams, query)
                .asSequence()
                .sortedWith(
                        compareByDescending<RoomMemberSummary> { frequencies[it.userId] ?: 0 }
                                .thenBy { it.displayName }
                )
                .map { AutocompleteMemberItem.RoomMember(it) }
                .toList()
    }

    // The query matches real names and ids in the database; an anonymized member is found by what is shown of them instead.
    private fun membersMatching(queryParams: RoomMemberQueryParams, query: CharSequence?): List<RoomMemberSummary> {
        val members = room.membershipService().getRoomMembers(queryParams)
        if (query.isNullOrBlank() || !IdentityPresentation.config().ruleFor(roomId).enabled) return members
        val anonymized = anonymizedMembers()
        val shownMatches = anonymized.values.filter { (member, shownId) ->
            member.displayName?.contains(query, ignoreCase = true) == true || shownId.contains(query, ignoreCase = true)
        }.map { it.first }
        return members.filterNot { it.userId in anonymized } + shownMatches
    }

    // Scanning every member is too slow per keystroke in a big room, so it's done once per popup and settings change.
    @Volatile private var anonymizedCache: Pair<Long, Map<String, Pair<RoomMemberSummary, String>>>? = null

    private fun anonymizedMembers(): Map<String, Pair<RoomMemberSummary, String>> {
        val revision = IdentityPresentation.changes.value
        anonymizedCache?.takeIf { it.first == revision }?.let { return it.second }
        return room.membershipService().getRoomMembers(createQueryParams(null))
                .filter { IdentityPresentation.isAnonymous(it.userId, roomId) }
                .associate { it.userId to (it to IdentityPresentation.displayedId(it.userId, roomId)) }
                .also { anonymizedCache = revision to it }
    }

    private fun createEveryoneItem(query: CharSequence?) =
            room.roomSummary()
                    ?.takeIf { canNotifyEveryone() }
                    ?.takeIf {
                        query.isNullOrBlank() ||
                                SUGGEST_ROOM_KEYWORDS.any {
                                    it.startsWith("@$query")
                                }
                    }
                    ?.let {
                        AutocompleteMemberItem.Everyone(it)
                    }

    private fun canNotifyEveryone() = session.pushRuleService().resolveSenderNotificationPermissionCondition(
            Event(
                    senderId = session.myUserId,
                    roomId = roomId
            ),
            SenderNotificationPermissionCondition(PowerLevelsContent.NOTIFICATIONS_ROOM_KEY)
    )

    /* ==========================================================================================
     * Const
     * ========================================================================================== */

    companion object {
        private const val QUERY_DEBOUNCE_MS = 100L
        private const val MAX_VISIBLE_MEMBERS = 3
        private val SUGGEST_ROOM_KEYWORDS = setOf(MatrixItem.NOTIFY_EVERYONE, "@channel", "@everyone", "@here")
    }
}
