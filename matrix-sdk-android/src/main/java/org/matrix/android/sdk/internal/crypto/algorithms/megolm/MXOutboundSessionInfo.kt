/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.crypto.algorithms.megolm

import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber

internal class MXOutboundSessionInfo(
        // The id of the session
        val sessionId: String,
        val sharedWithHelper: SharedWithHelper,
        private val clock: Clock,
        // When the session was created
        private val creationTime: Long = clock.epochMillis(),
        val sharedHistory: Boolean = false
) {

    // Number of times this session has been used
    var useCount: Int = 0

    private val withheldNoticesSent = HashSet<String>()

    /** @return why the session must be replaced, or null if it can still be used. */
    fun rotationReason(rotationPeriodMsgs: Long, rotationPeriodMs: Long): String? {
        val sessionLifetime = clock.epochMillis() - creationTime
        return when {
            useCount >= rotationPeriodMsgs -> "used $useCount/$rotationPeriodMsgs times"
            sessionLifetime >= rotationPeriodMs -> "${sessionLifetime}ms old (limit ${rotationPeriodMs}ms)"
            else -> null
        }?.also { Timber.v("## rotationReason() : Rotating megolm session, $it") }
    }

    /**
     * Determine if this session has been shared with devices which it shouldn't have been.
     *
     * @param devicesInRoom the devices map
     * @return the first user or user|device we shared the session with that is no longer in devicesInRoom, or null.
     */
    fun sharedWithDeviceNoLongerInRoom(devicesInRoom: MXUsersDevicesMap<CryptoDeviceInfo>): String? {
        val sharedWithDevices = sharedWithHelper.sharedWithDevices()
        val userIds = sharedWithDevices.userIds

        for (userId in userIds) {
            if (null == devicesInRoom.getUserDeviceIds(userId)) {
                Timber.v("## sharedWithDeviceNoLongerInRoom() : Starting new session because we shared with $userId")
                return userId
            }

            val deviceIds = sharedWithDevices.getUserDeviceIds(userId)

            for (deviceId in deviceIds!!) {
                if (null == devicesInRoom.getObject(userId, deviceId)) {
                    Timber.v("## sharedWithDeviceNoLongerInRoom() : Starting new session because we shared with $userId:$deviceId")
                    return "$userId|$deviceId"
                }
            }
        }

        return null
    }

    /** True the first time a withheld code is reported for this device in this session. */
    fun takeWithheldNotice(userId: String, deviceId: String, code: String): Boolean = withheldNoticesSent.add("$userId|$deviceId|$code")
}
