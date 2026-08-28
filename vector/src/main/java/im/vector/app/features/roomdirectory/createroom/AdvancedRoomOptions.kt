/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.createroom

import com.squareup.moshi.Types
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomStateEvent
import org.matrix.android.sdk.api.util.MatrixJsonParser

/**
 * The advanced half of a creation form, shared by the room wizard and the space wizard.
 */
interface AdvancedRoomOptions {
    val showAdvanced: Boolean
    val disableFederation: Boolean
    val roomVersion: String?
    val defaultRoomVersion: String?
    val availableRoomVersions: List<String>
    val myPowerLevelOverride: Int?
    val isDeveloperMode: Boolean
    val initialStateJson: String
    val initialStateJsonInvalid: Boolean
}

/**
 * From room version 12, creators are immutable owners with implicit infinite power and cannot be listed
 * in m.room.power_levels, so overriding your own power level is not possible.
 */
val AdvancedRoomOptions.canOverrideOwnPowerLevel: Boolean
    get() = ((roomVersion ?: defaultRoomVersion)?.toIntOrNull() ?: 0) < 12

/** Every integer version from 1 up to the highest the server supports; never a newer one. */
fun HomeServerCapabilities.creatableRoomVersions(): List<String> {
    val maxVersion = roomVersions?.supportedVersion.orEmpty()
            .mapNotNull { it.version.toIntOrNull() }
            .plusElement(roomVersions?.defaultRoomVersion?.toIntOrNull() ?: 0)
            .maxOrNull()
            ?.coerceAtLeast(1)
            ?: 1
    return (1..maxVersion).map { it.toString() }
}

fun parseInitialStateJson(json: String): List<CreateRoomStateEvent>? {
    return tryOrNull {
        val type = Types.newParameterizedType(List::class.java, CreateRoomStateEvent::class.java)
        MatrixJsonParser.getMoshi().adapter<List<CreateRoomStateEvent>>(type).fromJson(json)
    }
}

/** A power level the user set for themselves is merged into their own power_levels initial state event,
 *  if they wrote one, rather than fighting it through the content override. */
fun CreateRoomParams.applyAdvancedRoomOptions(
        options: AdvancedRoomOptions,
        userId: String,
        customInitialStates: List<CreateRoomStateEvent>,
) {
    disableFederation = options.disableFederation

    options.roomVersion?.takeIf { it != options.defaultRoomVersion }?.let {
        roomVersion = it
    }

    val myLevel = options.myPowerLevelOverride?.takeIf { options.canOverrideOwnPowerLevel }
    val hasCustomPowerLevels = customInitialStates.any { it.type == EventType.STATE_ROOM_POWER_LEVELS }

    if (myLevel != null && !hasCustomPowerLevels) {
        powerLevelContentOverride = (powerLevelContentOverride ?: PowerLevelsContent())
                .setUserPowerLevel(userId, myLevel)
    }

    if (customInitialStates.isNotEmpty()) {
        initialStates.addAll(
                if (myLevel != null && hasCustomPowerLevels) {
                    customInitialStates.map { it.withMyPowerLevelMerged(userId, myLevel) }
                } else {
                    customInitialStates
                }
        )
    }
}

private fun CreateRoomStateEvent.withMyPowerLevelMerged(userId: String, level: Int): CreateRoomStateEvent {
    if (type != EventType.STATE_ROOM_POWER_LEVELS) return this
    @Suppress("UNCHECKED_CAST")
    val users = (content["users"] as? Map<String, Any>).orEmpty().toMutableMap().apply { put(userId, level) }
    return copy(content = content.toMutableMap().apply { put("users", users) })
}
