/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.matrixto

import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.peeking.PeekResult
import org.matrix.android.sdk.api.session.space.JoinSpaceResult
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem

class MatrixToBottomSheetViewModel @AssistedInject constructor(
        @Assisted initialState: MatrixToBottomSheetState,
        private val session: Session,
        private val errorFormatter: ErrorFormatter,
) : VectorViewModel<MatrixToBottomSheetState, MatrixToAction, MatrixToViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<MatrixToBottomSheetViewModel, MatrixToBottomSheetState> {
        override fun create(initialState: MatrixToBottomSheetState): MatrixToBottomSheetViewModel
    }

    companion object : MavericksViewModelFactory<MatrixToBottomSheetViewModel, MatrixToBottomSheetState> by hiltMavericksViewModelFactory()

    init {
        when (initialState.linkType) {
            is PermalinkData.RoomLink -> {
                setState {
                    copy(roomPeekResult = Loading())
                }
            }
            is PermalinkData.UserLink,
            is PermalinkData.RoomEmailInviteLink,
            is PermalinkData.FallbackLink -> Unit
        }
        viewModelScope.launch(Dispatchers.IO) {
            resolveLink(initialState)
        }
    }

    private suspend fun resolveLink(initialState: MatrixToBottomSheetState) {
        val permalinkData = initialState.linkType
        when (permalinkData) {
            is PermalinkData.RoomLink -> {
                // could this room be already known
                val knownRoom = if (permalinkData.isRoomAlias) {
                    tryOrNull {
                        session.roomService().getRoomIdByAlias(permalinkData.roomIdOrAlias, false)
                    }
                            ?.getOrNull()
                            ?.roomId?.let {
                                session.getRoom(it)
                            }
                } else {
                    session.getRoom(permalinkData.roomIdOrAlias)
                }
                        ?.roomSummary()
                        // don't take if not Join, as it could be outdated
                        ?.takeIf { it.membership == Membership.JOIN }
                if (knownRoom != null) {
                    setState {
                        copy(
                                roomPeekResult = Success(
                                        RoomInfoResult.FullInfo(
                                                roomItem = knownRoom.toMatrixItem(),
                                                name = knownRoom.name,
                                                topic = knownRoom.topic,
                                                memberCount = knownRoom.joinedMembersCount,
                                                alias = knownRoom.canonicalAlias,
                                                membership = knownRoom.membership,
                                                roomType = knownRoom.roomType,
                                                viaServers = null,
                                                isPublic = knownRoom.isPublic
                                        )
                                )
                        )
                    }
                } else {
                    val result = when (val peekResult = tryOrNull { resolveSpace(permalinkData) }) {
                        is PeekResult.Success -> {
                            RoomInfoResult.FullInfo(
                                    roomItem = MatrixItem.RoomItem(peekResult.roomId, peekResult.name, peekResult.avatarUrl),
                                    name = peekResult.name ?: "",
                                    topic = peekResult.topic ?: "",
                                    memberCount = peekResult.numJoinedMembers,
                                    alias = peekResult.alias,
                                    membership = knownRoom?.membership ?: Membership.NONE,
                                    roomType = peekResult.roomType,
                                    viaServers = peekResult.viaServers.takeIf { it.isNotEmpty() } ?: permalinkData.viaParameters,
                                    isPublic = peekResult.isPublic,
                                    joinRule = peekResult.joinRule
                            ).also {
                                peekResult.someMembers?.let { checkForKnownMembers(it) }
                            }
                        }
                        is PeekResult.PeekingNotAllowed -> {
                            RoomInfoResult.PartialInfo(
                                    roomId = permalinkData.roomIdOrAlias,
                                    viaServers = permalinkData.viaParameters
                            )
                        }
                        PeekResult.UnknownAlias -> {
                            RoomInfoResult.UnknownAlias(permalinkData.roomIdOrAlias)
                        }
                        null -> {
                            RoomInfoResult.PartialInfo(
                                    roomId = permalinkData.roomIdOrAlias,
                                    viaServers = permalinkData.viaParameters
                            ).takeIf { permalinkData.isRoomAlias.not() }
                                    ?: RoomInfoResult.NotFound
                        }
                    }
                    setState {
                        copy(
                                roomPeekResult = Success(result)
                        )
                    }
                }
            }
            is PermalinkData.UserLink,
            is PermalinkData.RoomEmailInviteLink,
            is PermalinkData.FallbackLink -> {
                _viewEvents.post(MatrixToViewEvents.Dismiss)
            }
        }
    }

    private fun checkForKnownMembers(someMembers: List<MatrixItem.UserItem>) {
        viewModelScope.launch(Dispatchers.Default) {
            val knownMembers = someMembers.filter {
                session.roomService().getExistingDirectRoomWithUser(it.id) != null
            }
            // put one with avatar first, and take 5
            val finalRes = (knownMembers.filter { it.avatarUrl != null } + knownMembers.filter { it.avatarUrl == null })
                    .take(5)
            setState {
                copy(
                        peopleYouKnow = Success(finalRes)
                )
            }
        }
    }

    private suspend fun resolveSpace(permalinkData: PermalinkData.RoomLink): PeekResult {
//        try {
//            return session.spaceService().querySpaceChildren(permalinkData.roomIdOrAlias).let {
//                val roomSummary = it.first
//                PeekResult.Success(
//                        roomId = roomSummary.roomId,
//                        alias = roomSummary.canonicalAlias,
//                        avatarUrl = roomSummary.avatarUrl,
//                        name = roomSummary.name,
//                        topic = roomSummary.topic,
//                        numJoinedMembers = roomSummary.joinedMembersCount,
//                        roomType = roomSummary.roomType,
//                        viaServers = emptyList(),
//                        someMembers = null
//                )
//            }
//        } catch (failure: Throwable) {
//            if (failure is Failure.OtherServerError && failure.httpCode == HttpsURLConnection.HTTP_NOT_FOUND) {
        return resolveRoom(permalinkData.roomIdOrAlias)
//            } else {
//                throw failure
//            }
//        }
    }

    /**
     * Let's try to get some information about that room,
     * main thing is trying to see if it's a space or a room.
     */
    private suspend fun resolveRoom(roomIdOrAlias: String): PeekResult {
        return session.roomService().peekRoom(roomIdOrAlias)
    }

    override fun handle(action: MatrixToAction) {
        when (action) {
            MatrixToAction.FailedToResolveRoom -> {
                _viewEvents.post(MatrixToViewEvents.Dismiss)
            }
            is MatrixToAction.JoinSpace -> handleJoinSpace(action)
            is MatrixToAction.JoinRoom -> handleJoinRoom(action)
            is MatrixToAction.KnockRoom -> handleKnockRoom(action)
            is MatrixToAction.OpenSpace -> {
                _viewEvents.post(MatrixToViewEvents.NavigateToSpace(action.spaceID))
            }
            is MatrixToAction.OpenRoom -> {
                _viewEvents.post(MatrixToViewEvents.NavigateToRoom(action.roomId))
            }
        }
    }

    // A room id / alias can only be joined via a server that is already in the room. The peek/permalink
    // via servers sometimes omit the room's own home server (which created and hosts it), so always try the
    // alias/id domain FIRST, then any provided vias, then matrix.org — otherwise the join can 404 M_NOT_FOUND.
    private fun viaServersOrFallback(idOrAlias: String, provided: List<String>?): List<String> {
        val domain = idOrAlias.substringAfter(":", "").takeIf { it.isNotEmpty() }
        return (listOfNotNull(domain) + provided.orEmpty() + "matrix.org").distinct().take(5)
    }

    private fun handleJoinSpace(joinSpace: MatrixToAction.JoinSpace) {
        setState {
            copy(joinState = Loading())
        }
        viewModelScope.launch {
            try {
                val joinResult = session.spaceService().joinSpace(joinSpace.spaceID, null, viaServersOrFallback(joinSpace.spaceID, joinSpace.viaServers))
                if (joinResult.isSuccess()) {
                    _viewEvents.post(MatrixToViewEvents.NavigateToSpace(joinSpace.spaceID))
                } else {
                    val errMsg = errorFormatter.toHumanReadable((joinResult as? JoinSpaceResult.Fail)?.error)
                    _viewEvents.post(MatrixToViewEvents.ShowModalError(errMsg))
                }
            } catch (failure: Throwable) {
                _viewEvents.post(MatrixToViewEvents.ShowModalError(errorFormatter.toHumanReadable(failure)))
            } finally {
                setState {
                    // we can hide this button has we will navigate out
                    copy(joinState = Uninitialized)
                }
            }
        }
    }

    private fun handleJoinRoom(action: MatrixToAction.JoinRoom) {
        setState {
            copy(joinState = Loading())
        }
        viewModelScope.launch {
            try {
                session.roomService().joinRoom(
                        roomIdOrAlias = action.roomIdOrAlias,
                        reason = null,
                        viaServers = viaServersOrFallback(action.roomIdOrAlias, action.viaServers)
                )

                val roomId = getRoomIdFromRoomIdOrAlias(action.roomIdOrAlias)
                _viewEvents.post(MatrixToViewEvents.NavigateToRoom(roomId))
            } catch (failure: Throwable) {
                _viewEvents.post(MatrixToViewEvents.ShowModalError(errorFormatter.toHumanReadable(failure)))
            } finally {
                setState {
                    // we can hide this button has we will navigate out
                    copy(joinState = Uninitialized)
                }
            }
        }
    }

    private fun handleKnockRoom(action: MatrixToAction.KnockRoom) {
        setState {
            copy(joinState = Loading())
        }
        viewModelScope.launch {
            try {
                session.roomService().knock(
                        roomIdOrAlias = action.roomIdOrAlias,
                        reason = action.reason,
                        viaServers = viaServersOrFallback(action.roomIdOrAlias, action.viaServers)
                )
                // No confirmation popup: dismissing the sheet communicates that the request was sent.
                _viewEvents.post(MatrixToViewEvents.Dismiss)
            } catch (failure: Throwable) {
                _viewEvents.post(MatrixToViewEvents.ShowModalError(errorFormatter.toHumanReadable(failure)))
            } finally {
                setState {
                    copy(joinState = Uninitialized)
                }
            }
        }
    }

    private suspend fun getRoomIdFromRoomIdOrAlias(roomIdOrAlias: String): String {
        return if (MatrixPatterns.isRoomAlias(roomIdOrAlias)) {
            session.roomService().getRoomIdByAlias(roomIdOrAlias, true).get().roomId
        } else roomIdOrAlias
    }
}
