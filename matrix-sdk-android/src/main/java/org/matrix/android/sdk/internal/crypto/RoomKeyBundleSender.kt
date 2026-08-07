/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import dagger.Lazy
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.api.session.crypto.model.RoomKeyBundleContent
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.internal.crypto.actions.EnsureOlmSessionsForDevicesAction
import org.matrix.android.sdk.internal.crypto.actions.MessageEncrypter
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.crypto.model.RoomKeyBundle
import org.matrix.android.sdk.internal.crypto.model.toRest
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.util.JsonCanonicalizer
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("RoomKeyBundleSender", LoggerTag.CRYPTO)

/**
 * MSC4268 sender side: uploads the room's shareable megolm sessions as a single encrypted blob and tells the
 * invitee's devices where to find it.
 */
@SessionScope
internal class RoomKeyBundleSender @Inject constructor(
        private val cryptoStore: IMXCryptoStore,
        private val crossSigningService: Lazy<CrossSigningService>,
        private val bundleBuilder: RoomKeyBundleBuilder,
        private val deviceListManager: DeviceListManager,
        private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction,
        private val messageEncrypter: MessageEncrypter,
        private val sendToDeviceTask: SendToDeviceTask,
        private val fileUploader: FileUploader,
        private val myDeviceInfoHolder: Lazy<MyDeviceInfoHolder>,
        private val objectSigner: ObjectSigner,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val clock: Clock,
) {

    suspend fun shareRoomHistory(roomId: String, userId: String) {
        val bundle = withContext(coroutineDispatchers.crypto) {
            // We have no way to prove which device really created a session, so the recipient has to take our word
            // for it. Don't ask that of them unless cross-signing can identify us.
            when {
                !crossSigningService.get().isCrossSigningInitialized() -> null.also {
                    Timber.tag(loggerTag.value).w("Not sharing history: cross-signing is not set up")
                }
                // Also gated on the room's current m.room.history_visibility.
                !cryptoStore.shouldShareHistory(roomId) -> null.also {
                    Timber.tag(loggerTag.value).d("Not sharing history: room history visibility does not allow it")
                }
                else -> bundleBuilder.build(roomId)
            }
        } ?: return

        if (bundle.isEmpty()) {
            Timber.tag(loggerTag.value).d("Not sharing history: no keys to share")
            return
        }

        val file = withContext(coroutineDispatchers.computation) { encryptAndUpload(bundle) }

        deviceListManager.downloadKeys(listOf(userId), forceDownload = true)
        val devices = cryptoStore.getUserDeviceList(userId).orEmpty()
                .filter { crossSigningService.get().isDeviceSignedByItsOwner(it) }
        if (devices.isEmpty()) {
            Timber.tag(loggerTag.value).w("Not sharing history: $userId has no cross-signed devices")
            return
        }

        sendBundleTo(roomId, userId, devices, file)
    }

    private suspend fun encryptAndUpload(bundle: RoomKeyBundle): EncryptedFileInfo {
        val json = MoshiProvider.providesMoshi()
                .adapter(RoomKeyBundle::class.java)
                .toJson(bundle)
        val encrypted = MXEncryptedAttachments.encryptAttachment(json.byteInputStream(), clock)
        val response = fileUploader.uploadByteArray(
                byteArray = encrypted.encryptedByteArray,
                filename = null,
                mimeType = "application/octet-stream"
        )
        Timber.tag(loggerTag.value)
                .i("Uploaded key bundle: ${bundle.roomKeys.size} shared, ${bundle.withheld.size} withheld")
        return encrypted.encryptedFileInfo.copy(url = response.contentUri)
    }

    private suspend fun sendBundleTo(
            roomId: String,
            userId: String,
            devices: List<CryptoDeviceInfo>,
            file: EncryptedFileInfo,
    ) {
        val olmSessions = withContext(coroutineDispatchers.crypto) {
            ensureOlmSessionsForDevicesAction.handle(mapOf(userId to devices))
        }
        val reachable = devices.filter { olmSessions.getObject(userId, it.deviceId)?.sessionId != null }
        if (reachable.isEmpty()) {
            Timber.tag(loggerTag.value).w("Could not establish an olm session with any device of $userId")
            return
        }

        val payload = mapOf(
                "type" to EventType.ROOM_KEY_BUNDLE.unstable,
                "content" to RoomKeyBundleContent(roomId = roomId, file = file).toContent()
        )
        val sendToDeviceMap = MXUsersDevicesMap<Any>()
        withContext(coroutineDispatchers.computation) {
            val senderDeviceKeys = signedOwnDeviceKeys()
            reachable.forEach { device ->
                sendToDeviceMap.setObject(userId, device.deviceId, messageEncrypter.encryptMessage(payload, listOf(device), senderDeviceKeys))
            }
        }

        Timber.tag(loggerTag.value).d("Sending key bundle for $roomId to ${reachable.size} device(s) of $userId")
        withContext(coroutineDispatchers.io) {
            sendToDeviceTask.execute(SendToDeviceTask.Params(EventType.ENCRYPTED, sendToDeviceMap))
        }
    }

    /** MSC4147 `sender_device_keys`, which MSC4268 requires on bundle messages. */
    private fun signedOwnDeviceKeys() =
            myDeviceInfoHolder.get().myDevice.let { device ->
                device.toRest().copy(
                        signatures = objectSigner.signObject(
                                JsonCanonicalizer.getCanonicalJson(Map::class.java, device.signalableJSONDictionary())
                        )
                )
            }
}
