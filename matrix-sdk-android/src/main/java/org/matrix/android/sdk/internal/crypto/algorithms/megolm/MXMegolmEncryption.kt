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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.crypto.MEGOLM_DEFAULT_ROTATION_MSGS
import org.matrix.android.sdk.api.crypto.MEGOLM_DEFAULT_ROTATION_PERIOD_MS
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.api.session.crypto.model.forEach
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.RoomKeyWithHeldContent
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.internal.crypto.DeviceListManager
import org.matrix.android.sdk.internal.crypto.E2eDebug
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.actions.EnsureOlmSessionsForDevicesAction
import org.matrix.android.sdk.internal.crypto.actions.MessageEncrypter
import org.matrix.android.sdk.internal.crypto.actions.OlmSessionFailures
import org.matrix.android.sdk.internal.crypto.algorithms.IMXEncrypting
import org.matrix.android.sdk.internal.crypto.algorithms.IMXGroupEncryption
import org.matrix.android.sdk.internal.crypto.keysbackup.DefaultKeysBackupService
import org.matrix.android.sdk.internal.crypto.model.toDebugCount
import org.matrix.android.sdk.internal.crypto.model.toDebugString
import org.matrix.android.sdk.internal.crypto.repository.WarnOnUnknownDeviceRepository
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.util.JsonCanonicalizer
import org.matrix.android.sdk.internal.util.convertToUTF8
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber

private val loggerTag = LoggerTag("MXMegolmEncryption", LoggerTag.CRYPTO)

internal class MXMegolmEncryption(
        // The id of the room we will be sending to.
        private val roomId: String,
        private val olmDevice: MXOlmDevice,
        private val defaultKeysBackupService: DefaultKeysBackupService,
        private val cryptoStore: IMXCryptoStore,
        private val deviceListManager: DeviceListManager,
        private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction,
        private val olmSessionFailures: OlmSessionFailures,
        private val myUserId: String,
        private val myDeviceId: String,
        private val sendToDeviceTask: SendToDeviceTask,
        private val messageEncrypter: MessageEncrypter,
        private val warnOnUnknownDevicesRepository: WarnOnUnknownDeviceRepository,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val cryptoCoroutineScope: CoroutineScope,
        private val clock: Clock,
) : IMXEncrypting, IMXGroupEncryption {

    // OutboundSessionInfo. Null if we haven't yet started setting one up. Note
    // that even if this is non-null, it may not be ready for use (in which
    // case outboundSession.shareOperation will be non-null.)
    private var outboundSession: MXOutboundSessionInfo? = null

    init {
        // restore existing outbound session if any
        outboundSession = olmDevice.restoreOutboundGroupSessionForRoom(roomId)
    }

    // Sharing suspends on the network; without this a second send could share the same session again or
    // encrypt with one that a concurrent rotation or /discardsession just released.
    private val shareMutex = Mutex()

    override suspend fun encryptEventContent(
            eventContent: Content,
            eventType: String,
            userIds: List<String>,
            stateKey: String?,
    ): Content {
        val ts = clock.epochMillis()
        Timber.tag(loggerTag.value).v("encryptEventContent : getDevicesInRoom")

        /**
         * When using in-room messages and the room has encryption enabled,
         * clients should ensure that encryption does not hinder the verification.
         * For example, if the verification messages are encrypted, clients must ensure that all the recipient’s
         * unverified devices receive the keys necessary to decrypt the messages,
         * even if they would normally not be given the keys to decrypt messages in the room.
         */
        val shouldSendToUnverified = isVerificationEvent(eventType, eventContent)

        val devices = getDevicesInRoom(userIds, forceDistributeToUnverified = shouldSendToUnverified)

        Timber.tag(loggerTag.value).d("encrypt event in room=$roomId - devices count in room ${devices.allowedDevices.toDebugCount()}")
        Timber.tag(loggerTag.value).v("encryptEventContent ${clock.epochMillis() - ts}: getDevicesInRoom ${devices.allowedDevices.toDebugString()}")
        DebugLog.i {
            "E2EDBG stage=encrypt-start room=$roomId type=$eventType users=${userIds.size} " +
                    "allowed=${devices.allowedDevices.toDebugCount()} withheld=${withheldDebug(devices.withHeldDevices)}"
        }
        return shareMutex.withLock {
            val outboundSession = ensureOutboundSession(devices.allowedDevices)

            encryptContent(outboundSession, eventType, eventContent, stateKey)
                    .also {
                        notifyWithheldForSession(devices.withHeldDevices, outboundSession)
                        // annoyingly we have to serialize again the saved outbound session to store message index :/
                        // if not we would see duplicate message index errors
                        olmDevice.storeOutboundGroupSessionForRoom(roomId, outboundSession.sessionId)
                        Timber.tag(loggerTag.value).d("encrypt event in room=$roomId Finished in ${clock.epochMillis() - ts} millis")
                    }
        }
    }

    private fun withheldDebug(devices: MXUsersDevicesMap<WithHeldCode>): String {
        val entries = mutableListOf<String>()
        devices.forEach { userId, deviceId, code -> entries.add("$userId|$deviceId=${code.value}") }
        return entries.toString()
    }

    private fun isVerificationEvent(eventType: String, eventContent: Content) =
            EventType.isVerificationEvent(eventType) ||
                    (eventType == EventType.MESSAGE &&
                            eventContent.get(MessageContent.MSG_TYPE_JSON_KEY) == MessageType.MSGTYPE_VERIFICATION_REQUEST)

    private fun notifyWithheldForSession(devices: MXUsersDevicesMap<WithHeldCode>, outboundSession: MXOutboundSessionInfo) {
        val toNotify = mutableListOf<Pair<UserDevice, WithHeldCode>>()
        devices.forEach { userId, deviceId, withheldCode ->
            if (outboundSession.takeWithheldNotice(userId, deviceId, withheldCode.value)) {
                toNotify.add(UserDevice(userId, deviceId) to withheldCode)
            }
        }
        if (toNotify.isEmpty()) return
        // offload to computation thread
        cryptoCoroutineScope.launch(coroutineDispatchers.computation) {
            toNotify.groupBy(
                    { it.second },
                    { it.first }
            ).forEach { (code, targets) ->
                notifyKeyWithHeld(targets, outboundSession.sessionId, olmDevice.deviceCurve25519Key, code)
            }
        }
    }

    override suspend fun discardSessionKey() {
        shareMutex.withLock {
            DebugLog.i { "E2EDBG stage=session-discarded room=$roomId session=${outboundSession?.sessionId} useCount=${outboundSession?.useCount}" }
            outboundSession = null
            olmDevice.discardOutboundGroupSessionForRoom(roomId)
        }
    }

    override suspend fun preshareKey(userIds: List<String>) {
        val ts = clock.epochMillis()
        Timber.tag(loggerTag.value).d("preshareKey started in $roomId ...")
        val devices = getDevicesInRoom(userIds)
        shareMutex.withLock {
            val outboundSession = ensureOutboundSession(devices.allowedDevices)

            notifyWithheldForSession(devices.withHeldDevices, outboundSession)
        }

        Timber.tag(loggerTag.value).d("preshareKey in $roomId done in  ${clock.epochMillis() - ts} millis")
    }

    /**
     * Prepare a new session.
     *
     * @return the session description
     */
    private fun prepareNewSessionInRoom(): MXOutboundSessionInfo {
        Timber.tag(loggerTag.value).v("prepareNewSessionInRoom() ")
        val sessionId = olmDevice.createOutboundGroupSessionForRoom(roomId)

        val keysClaimedMap = mapOf(
                "ed25519" to olmDevice.deviceEd25519Key!!
        )

        val sharedHistory = cryptoStore.shouldShareHistory(roomId)
        Timber.tag(loggerTag.value).v("prepareNewSessionInRoom() as sharedHistory $sharedHistory")
        olmDevice.addInboundGroupSession(
                sessionId = sessionId!!,
                sessionKey = olmDevice.getSessionKey(sessionId)!!,
                roomId = roomId,
                senderKey = olmDevice.deviceCurve25519Key!!,
                forwardingCurve25519KeyChain = emptyList(),
                keysClaimed = keysClaimedMap,
                exportFormat = false,
                sharedHistory = sharedHistory,
                trusted = true
        )

        cryptoCoroutineScope.launch {
            defaultKeysBackupService.maybeBackupKeys()
        }

        return MXOutboundSessionInfo(
                sessionId = sessionId,
                sharedWithHelper = SharedWithHelper(roomId, sessionId, cryptoStore),
                clock = clock,
                sharedHistory = sharedHistory
        )
    }

    /**
     * Ensure the outbound session.
     *
     * @param devicesInRoom the devices list
     */
    private suspend fun ensureOutboundSession(devicesInRoom: MXUsersDevicesMap<CryptoDeviceInfo>): MXOutboundSessionInfo {
        Timber.tag(loggerTag.value).v("ensureOutboundSession roomId:$roomId")
        val current = outboundSession
        val roomInfo = cryptoStore.getRoomCryptoInfo(roomId)
        // Clamped as the Rust SDK does, so a room state event can't force a new session per message.
        val rotationPeriodMsgs = (roomInfo?.rotationPeriodMsgs ?: MEGOLM_DEFAULT_ROTATION_MSGS).coerceIn(1, MAX_ROTATION_MSGS)
        val rotationPeriodMs = (roomInfo?.rotationPeriodMs ?: MEGOLM_DEFAULT_ROTATION_PERIOD_MS).coerceAtLeast(MIN_ROTATION_PERIOD_MS)
        val rotationReason = if (current == null) {
            "no current session"
        } else {
            current.rotationReason(rotationPeriodMsgs, rotationPeriodMs)
                    ?: "history visibility changed".takeIf { cryptoStore.shouldShareHistory(roomId) != current.sharedHistory }
                    ?: current.sharedWithDeviceNoLongerInRoom(devicesInRoom)?.let { "shared with $it, no longer in the room" }
        }
        val session = if (rotationReason == null && current != null) {
            current
        } else {
            Timber.tag(loggerTag.value).d("roomId:$roomId Starting new megolm session because we need to rotate.")
            prepareNewSessionInRoom().also {
                outboundSession = it
                DebugLog.i { "E2EDBG stage=session-new room=$roomId session=${it.sessionId} previous=${current?.sessionId} reason=$rotationReason" }
            }
        }
        val shareMap = HashMap<String, MutableList<CryptoDeviceInfo>>()/* userId */
        val reshares = mutableListOf<CryptoDeviceInfo>()
        val userIds = devicesInRoom.userIds
        for (userId in userIds) {
            val deviceIds = devicesInRoom.getUserDeviceIds(userId)
            for (deviceId in deviceIds!!) {
                val deviceInfo = devicesInRoom.getObject(userId, deviceId) ?: continue
                val shared = cryptoStore.getSharedSessionInfo(roomId, session.sessionId, deviceInfo)
                if (!shared.found || shared.needsReshare) {
                    shareMap.getOrPut(userId) { ArrayList() }.add(deviceInfo)
                    if (shared.found) reshares.add(deviceInfo)
                }
            }
        }
        val devicesCount = shareMap.entries.fold(0) { acc, new -> acc + new.value.size }
        Timber.tag(loggerTag.value).d("roomId:$roomId found $devicesCount devices without megolm session(${session.sessionId})")
        if (devicesCount > 0) {
            DebugLog.i {
                "E2EDBG stage=share-plan room=$roomId session=${session.sessionId} index=${olmDevice.getMessageIndex(session.sessionId)} " +
                        "devices=${E2eDebug.devicesByUser(shareMap)} resending=${E2eDebug.devices(reshares)}"
            }
        }
        shareKey(session, shareMap)
        return session
    }

    /**
     * Share the device key to a list of users.
     *
     * @param session the session info
     * @param devicesByUsers the devices map
     */
    private suspend fun shareKey(
            session: MXOutboundSessionInfo,
            devicesByUsers: Map<String, List<CryptoDeviceInfo>>
    ) {
        // nothing to send, the task is done
        if (devicesByUsers.isEmpty()) {
            Timber.tag(loggerTag.value).v("shareKey() : nothing more to do")
            return
        }
        // reduce the map size to avoid request timeout when there are too many devices (Users size  * devices per user)
        val subMap = HashMap<String, List<CryptoDeviceInfo>>()
        var devicesCount = 0
        for ((userId, devices) in devicesByUsers) {
            subMap[userId] = devices
            devicesCount += devices.size
            if (devicesCount > 100) {
                break
            }
        }
        Timber.tag(loggerTag.value).v("shareKey() ; sessionId<${session.sessionId}> userId ${subMap.keys}")
        shareUserDevicesKey(session, subMap)
        val remainingDevices = devicesByUsers - subMap.keys
        shareKey(session, remainingDevices)
    }

    /**
     * Share the device keys of a an user.
     *
     * @param sessionInfo the session info
     * @param devicesByUser the devices map
     */
    private suspend fun shareUserDevicesKey(
            sessionInfo: MXOutboundSessionInfo,
            devicesByUser: Map<String, List<CryptoDeviceInfo>>
    ) {
        val sessionKey = olmDevice.getSessionKey(sessionInfo.sessionId) ?: run {
            Timber.tag(loggerTag.value).v("shareUserDevicesKey() Failed to share session, failed to export")
            DebugLog.e { "E2EDBG stage=share-export-failed room=$roomId session=${sessionInfo.sessionId}" }
            throw Failure.CryptoError(MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_ENCRYPT, "Failed to export the outbound session key"))
        }
        val chainIndex = olmDevice.getMessageIndex(sessionInfo.sessionId)

        val payload = mapOf(
                "type" to EventType.ROOM_KEY,
                "content" to mapOf(
                        "algorithm" to MXCRYPTO_ALGORITHM_MEGOLM,
                        "room_id" to roomId,
                        "session_id" to sessionInfo.sessionId,
                        "session_key" to sessionKey,
                        "chain_index" to chainIndex,
                        "org.matrix.msc3061.shared_history" to sessionInfo.sharedHistory
                )
        )

        var t0 = clock.epochMillis()
        Timber.tag(loggerTag.value).v("shareUserDevicesKey() : starts")

        // Recorded as pending before anything is sent: a key request from these devices is then answered
        // from this index, and the flag keeps them queued for the next message until delivery is confirmed.
        devicesByUser.values.flatten().forEach { sessionInfo.sharedWithHelper.markedSessionAsShared(it, chainIndex, needsReshare = true) }

        val results = ensureOlmSessionsForDevicesAction.handle(devicesByUser)
        Timber.tag(loggerTag.value).v(
                """shareUserDevicesKey(): ensureOlmSessionsForDevices succeeds after ${clock.epochMillis() - t0} ms"""
                        .trimMargin()
        )
        val contentMap = MXUsersDevicesMap<Any>()
        val targets = mutableListOf<CryptoDeviceInfo>()
        val noOlm = mutableListOf<CryptoDeviceInfo>()
        for ((userId, devicesToShareWith) in devicesByUser) {
            for (deviceInfo in devicesToShareWith) {
                val deviceID = deviceInfo.deviceId
                val sessionResult = results.getObject(userId, deviceID)
                if (sessionResult?.sessionId == null) {
                    // no session with this device, probably because there
                    // were no one-time keys.
                    Timber.tag(loggerTag.value).v("shareUserDevicesKey() : No Olm Session for $userId:$deviceID mark for withheld")
                    noOlm.add(deviceInfo)
                    continue
                }
                Timber.tag(loggerTag.value).v("shareUserDevicesKey() : Add to share keys contentMap for $userId:$deviceID")
                contentMap.setObject(userId, deviceID, messageEncrypter.encryptMessage(payload, listOf(sessionResult.deviceInfo)))
                targets.add(deviceInfo)
            }
        }

        if (targets.isNotEmpty()) {
            t0 = clock.epochMillis()
            Timber.tag(loggerTag.value).i("shareUserDevicesKey() ${sessionInfo.sessionId} : has target")
            Timber.tag(loggerTag.value).d("sending to device room key for ${sessionInfo.sessionId} to ${contentMap.toDebugString()}")
            val sendToDeviceParams = SendToDeviceTask.Params(EventType.ENCRYPTED, contentMap)
            try {
                withContext(coroutineDispatchers.io) {
                    sendToDeviceTask.execute(sendToDeviceParams)
                }
                Timber.tag(loggerTag.value).i("shareUserDevicesKey() : sendToDevice succeeds after ${clock.epochMillis() - t0} ms")
            } catch (failure: Throwable) {
                // Sending the message anyway would leave these devices unable to read it for good, since a
                // later share starts past its index; failing lets the send queue retry at the same index.
                Timber.tag(loggerTag.value).e("shareUserDevicesKey() : Failed to share <${sessionInfo.sessionId}>")
                DebugLog.e {
                    "E2EDBG stage=share-send-failed room=$roomId session=${sessionInfo.sessionId} index=$chainIndex " +
                            "devices=${E2eDebug.devices(targets)} afterMs=${clock.epochMillis() - t0} error=${E2eDebug.error(failure)} " +
                            "-> message not sent, devices stay queued"
                }
                throw failure
            }
            targets.forEach { sessionInfo.sharedWithHelper.markedSessionAsShared(it, chainIndex, needsReshare = false) }
            DebugLog.i {
                "E2EDBG stage=share-sent room=$roomId session=${sessionInfo.sessionId} index=$chainIndex " +
                        "devices=${E2eDebug.devices(targets)} tookMs=${clock.epochMillis() - t0}"
            }
        } else {
            Timber.tag(loggerTag.value).i("shareUserDevicesKey() : no need to share key")
        }

        if (noOlm.isNotEmpty()) {
            DebugLog.w {
                "E2EDBG stage=share-no-olm room=$roomId session=${sessionInfo.sessionId} index=$chainIndex devices=${E2eDebug.devices(noOlm)} " +
                        "-> cannot read from this index until an Olm session exists, retried on later messages"
            }
            // MSC2399 m.no_olm, once per device rather than once per message while it stays unreachable.
            val noOlmToNotify = noOlm.filter { olmSessionFailures.takeNoOlmNotice(it) }.map { UserDevice(it.userId, it.deviceId) }
            if (noOlmToNotify.isNotEmpty()) {
                notifyKeyWithHeld(
                        noOlmToNotify,
                        sessionInfo.sessionId,
                        olmDevice.deviceCurve25519Key,
                        WithHeldCode.NO_OLM
                )
            }
        }
    }

    private suspend fun notifyKeyWithHeld(
            targets: List<UserDevice>,
            sessionId: String,
            senderKey: String?,
            code: WithHeldCode
    ) {
        Timber.tag(loggerTag.value).d(
                "notifyKeyWithHeld() :sending withheld for session:$sessionId and code $code to" +
                        " ${targets.joinToString { "${it.userId}|${it.deviceId}" }}"
        )
        val withHeldContent = RoomKeyWithHeldContent(
                roomId = roomId,
                senderKey = senderKey,
                algorithm = MXCRYPTO_ALGORITHM_MEGOLM,
                sessionId = sessionId,
                codeString = code.value,
                fromDevice = myDeviceId
        )
        val params = SendToDeviceTask.Params(
                EventType.ROOM_KEY_WITHHELD.stable,
                MXUsersDevicesMap<Any>().apply {
                    targets.forEach {
                        setObject(it.userId, it.deviceId, withHeldContent)
                    }
                }
        )
        try {
            withContext(coroutineDispatchers.io) {
                sendToDeviceTask.execute(params)
            }
            DebugLog.i {
                "E2EDBG stage=withheld-sent room=$roomId session=$sessionId code=${code.value} devices=${targets.map { "${it.userId}|${it.deviceId}" }}"
            }
        } catch (failure: Throwable) {
            Timber.tag(loggerTag.value)
                    .e("notifyKeyWithHeld() :$sessionId Failed to send withheld  ${targets.map { "${it.userId}|${it.deviceId}" }}")
            DebugLog.w {
                "E2EDBG stage=withheld-send-failed room=$roomId session=$sessionId code=${code.value} " +
                        "devices=${targets.map { "${it.userId}|${it.deviceId}" }} error=${E2eDebug.error(failure)}"
            }
        }
    }

    /**
     * process the pending encryptions.
     */
    private fun encryptContent(session: MXOutboundSessionInfo, eventType: String, eventContent: Content, stateKey: String?): Content {
        // Everything is in place, encrypt all pending events
        val payloadJson = HashMap<String, Any>()
        payloadJson["room_id"] = roomId
        payloadJson["type"] = eventType
        payloadJson["content"] = eventContent
        stateKey?.let { payloadJson["state_key"] = it }

        // Get canonical Json from

        val payloadString = convertToUTF8(JsonCanonicalizer.getCanonicalJson(Map::class.java, payloadJson))
        val messageIndex = olmDevice.getMessageIndex(session.sessionId)
        val ciphertext = olmDevice.encryptGroupMessage(session.sessionId, payloadString)
        DebugLog.i {
            "E2EDBG stage=encrypted room=$roomId type=$eventType session=${session.sessionId} index=$messageIndex " +
                    "useCount=${session.useCount + 1} ok=${ciphertext != null}"
        }

        val map = HashMap<String, Any>()
        map["algorithm"] = MXCRYPTO_ALGORITHM_MEGOLM
        map["sender_key"] = olmDevice.deviceCurve25519Key!!
        map["ciphertext"] = ciphertext!!
        map["session_id"] = session.sessionId

        // Include our device ID so that recipients can send us a
        // m.new_device message if they don't have our session key.
        map["device_id"] = myDeviceId
        session.useCount++
        return map
    }

    /**
     * Get the list of devices which can encrypt data to.
     * This method must be called in getDecryptingThreadHandler() thread.
     *
     * @param userIds the user ids whose devices must be checked.
     * @param forceDistributeToUnverified If true the unverified devices will be included in valid recipients even if
     * such devices are blocked in crypto settings
     */
    private suspend fun getDevicesInRoom(userIds: List<String>, forceDistributeToUnverified: Boolean = false): DeviceInRoomInfo {
        // We are happy to use a cached version here: we assume that if we already
        // have a list of the user's devices, then we already share an e2e room
        // with them, which means that they will have announced any new devices via
        // an m.new_device.
        val keys = deviceListManager.downloadKeys(userIds, false)
        val encryptToVerifiedDevicesOnly = cryptoStore.getGlobalBlacklistUnverifiedDevices() ||
                cryptoStore.getBlockUnverifiedDevices(roomId)

        val devicesInRoom = DeviceInRoomInfo()
        val unknownDevices = MXUsersDevicesMap<CryptoDeviceInfo>()

        for (userId in keys.userIds) {
            val deviceIds = keys.getUserDeviceIds(userId) ?: continue
            for (deviceId in deviceIds) {
                val deviceInfo = keys.getObject(userId, deviceId) ?: continue
                if (warnOnUnknownDevicesRepository.warnOnUnknownDevices() && deviceInfo.isUnknown) {
                    // The device is not yet known by the user
                    unknownDevices.setObject(userId, deviceId, deviceInfo)
                    continue
                }
                if (deviceInfo.isBlocked) {
                    // Remove any blocked devices
                    devicesInRoom.withHeldDevices.setObject(userId, deviceId, WithHeldCode.BLACKLISTED)
                    continue
                }

                if (!deviceInfo.isVerified && encryptToVerifiedDevicesOnly && !forceDistributeToUnverified) {
                    devicesInRoom.withHeldDevices.setObject(userId, deviceId, WithHeldCode.UNVERIFIED)
                    continue
                }

                if (deviceInfo.identityKey() == olmDevice.deviceCurve25519Key) {
                    // Don't bother sending to ourself
                    continue
                }
                devicesInRoom.allowedDevices.setObject(userId, deviceId, deviceInfo)
            }
        }
        if (unknownDevices.isEmpty) {
            return devicesInRoom
        } else {
            throw MXCryptoError.UnknownDevice(unknownDevices)
        }
    }

    data class DeviceInRoomInfo(
            val allowedDevices: MXUsersDevicesMap<CryptoDeviceInfo> = MXUsersDevicesMap(),
            val withHeldDevices: MXUsersDevicesMap<WithHeldCode> = MXUsersDevicesMap()
    )

    data class UserDevice(
            val userId: String,
            val deviceId: String
    )

    private companion object {
        const val MAX_ROTATION_MSGS = 10_000L
        const val MIN_ROTATION_PERIOD_MS = 3_600_000L
    }
}
