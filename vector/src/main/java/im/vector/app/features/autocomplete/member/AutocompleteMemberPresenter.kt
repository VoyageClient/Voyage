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
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.getRoom
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
    }

    /* ==========================================================================================
     * Public api
     * ========================================================================================== */

    fun clear() {
        controller.listener = null
        queryJob?.cancel()
        queryScope.coroutineContext[Job]?.cancel()
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
                    val members = createMemberItems(queryParams)
                    val everyone = createEveryoneItem(queryString)
                    // Headers are only shown when the user can notify everyone.
                    val canAddHeaders = canNotifyEveryone()
                    buildList {
                        if (members.isNotEmpty()) {
                            if (canAddHeaders) add(createMembersHeader())
                            addAll(members)
                        }
                        everyone?.let {
                            add(createEveryoneHeader())
                            add(it)
                        }
                    }
                }
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

    private fun createMembersHeader() =
            AutocompleteMemberItem.Header(
                    ID_HEADER_MEMBERS,
                    context.getString(CommonStrings.room_message_autocomplete_users)
            )

    private fun createMemberItems(queryParams: RoomMemberQueryParams): List<AutocompleteMemberItem.RoomMember> {
        // Most-mentioned first, alphabetical for members with no mention history.
        val frequencies = mentionFrequencyDataSource.frequencies(roomId)
        return room.membershipService()
                .getRoomMembers(queryParams)
                .asSequence()
                .sortedWith(
                        compareByDescending<RoomMemberSummary> { frequencies[it.userId] ?: 0 }
                                .thenBy { it.displayName }
                )
                .disambiguate()
                .map { AutocompleteMemberItem.RoomMember(it) }
                .toList()
    }

    private fun createEveryoneHeader() =
            AutocompleteMemberItem.Header(
                    ID_HEADER_EVERYONE,
                    context.getString(CommonStrings.room_message_autocomplete_notification)
            )

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
        private const val ID_HEADER_MEMBERS = "ID_HEADER_MEMBERS"
        private const val ID_HEADER_EVERYONE = "ID_HEADER_EVERYONE"
        private const val QUERY_DEBOUNCE_MS = 100L
        private val SUGGEST_ROOM_KEYWORDS = setOf(MatrixItem.NOTIFY_EVERYONE, "@channel", "@everyone", "@here")
    }
}

private fun Sequence<RoomMemberSummary>.disambiguate(): Sequence<RoomMemberSummary> {
    val displayNames = hashMapOf<String, Int>().also { map ->
        for (item in this) {
            item.displayName?.lowercase()?.also { displayName ->
                map[displayName] = map.getOrPut(displayName, { 0 }) + 1
            }
        }
    }

    return map { roomMemberSummary ->
        if (displayNames[roomMemberSummary.displayName?.lowercase()] ?: 0 > 1) {
            roomMemberSummary.copy(displayName = roomMemberSummary.displayName + " " + roomMemberSummary.userId)
        } else roomMemberSummary
    }
}
