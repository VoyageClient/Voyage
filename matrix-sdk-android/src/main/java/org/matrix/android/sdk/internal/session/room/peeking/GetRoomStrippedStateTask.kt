/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.auth.version.Versions
import org.matrix.android.sdk.internal.auth.version.doesServerSupportStrippedState
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.shouldFallBackToUnstableEndpoint
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.homeserver.CapabilitiesAPI
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

@JsonClass(generateAdapter = true)
internal data class StrippedStateBody(
        @Json(name = "state") val state: List<String>,
        @Json(name = "num_joined_members") val numJoinedMembers: Boolean,
)

@JsonClass(generateAdapter = true)
internal data class StrippedStateResponse(
        @Json(name = "state") val state: List<Event> = emptyList(),
        @Json(name = "num_joined_members") val numJoinedMembers: Int? = null,
        /** The server that generated the state: ours if it is in the room, otherwise one of the vias. */
        @Json(name = "via") val via: String? = null,
)

/**
 * Whether the homeserver advertises MSC4560. Kept in memory rather than in the persisted capabilities
 * because adding a column there resets the whole session store.
 */
@SessionScope
internal class StrippedStateSupport @Inject constructor(
        private val capabilitiesAPI: CapabilitiesAPI,
) {
    private val mutex = Mutex()

    @Volatile
    private var advertised: Boolean? = null

    @Volatile
    var stableEndpointMissing: Boolean = false

    suspend fun isAdvertised(): Boolean {
        advertised?.let { return it }
        return mutex.withLock {
            advertised
                    ?: tryOrNull("Failed to get versions for MSC4560") { executeRequest(null) { capabilitiesAPI.getVersions() } }
                            ?.doesServerSupportStrippedState()
                            ?.also { advertised = it }
                    ?: false
        }
    }

    fun update(versions: Versions) {
        advertised = versions.doesServerSupportStrippedState()
    }
}

internal interface GetRoomStrippedStateTask : Task<GetRoomStrippedStateTask.Params, StrippedStateResponse?> {
    data class Params(
            val roomIdOrAlias: String,
            val viaServers: List<String>,
            val eventTypes: List<String>,
            val numJoinedMembers: Boolean,
    )
}

/** Returns null when the homeserver does not support MSC4560. */
internal class DefaultGetRoomStrippedStateTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val support: StrippedStateSupport,
        private val globalErrorReceiver: GlobalErrorReceiver,
) : GetRoomStrippedStateTask {

    override suspend fun execute(params: GetRoomStrippedStateTask.Params): StrippedStateResponse? {
        if (!support.isAdvertised()) return null
        val via = params.viaServers.distinct().take(MAX_VIAS).takeIf { it.isNotEmpty() }
        val body = StrippedStateBody(params.eventTypes, params.numJoinedMembers)
        if (!support.stableEndpointMissing) {
            try {
                return executeRequest(globalErrorReceiver) { roomAPI.getStrippedState(params.roomIdOrAlias, via, body) }
            } catch (failure: Throwable) {
                if (!failure.shouldFallBackToUnstableEndpoint()) throw failure
                // M_NOT_FOUND may just mean the room is unknown, so only an unknown endpoint is remembered.
                if ((failure as? Failure.ServerError)?.error?.code == MatrixError.M_UNRECOGNIZED) {
                    support.stableEndpointMissing = true
                }
            }
        }
        return executeRequest(globalErrorReceiver) { roomAPI.getStrippedStateUnstable(params.roomIdOrAlias, via, body) }
    }

    companion object {
        const val MAX_VIAS = 32
    }
}
