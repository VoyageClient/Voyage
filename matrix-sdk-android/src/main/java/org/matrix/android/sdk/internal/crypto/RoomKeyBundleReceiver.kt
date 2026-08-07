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
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.attachments.toElementToDecrypt
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.crypto.model.RoomKeyBundleContent
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.actions.MegolmSessionDataImporter
import org.matrix.android.sdk.internal.crypto.model.RoomKeyBundle
import org.matrix.android.sdk.internal.crypto.model.toMegolmSessionData
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("RoomKeyBundleReceiver", LoggerTag.CRYPTO)

/**
 * MSC4268 receiver side. A bundle announcement is only acted on once we have accepted an invite to that room from
 * that very user, on this device, recently — otherwise anyone could make us download arbitrary media.
 */
@SessionScope
internal class RoomKeyBundleReceiver @Inject constructor(
        private val cryptoStore: IMXCryptoStore,
        private val crossSigningService: Lazy<CrossSigningService>,
        private val deviceListManager: DeviceListManager,
        private val megolmSessionDataImporter: MegolmSessionDataImporter,
        private val fileService: Lazy<FileService>,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val clock: Clock,
) {

    /**
     * Record that a bundle exists, without downloading it. [event] must already be olm-decrypted.
     */
    suspend fun onBundleReceived(event: Event) {
        val senderId = event.senderId ?: return
        val senderKey = event.getSenderKey() ?: return Unit.also {
            Timber.tag(loggerTag.value).w("Ignoring a room key bundle that was not olm-encrypted")
        }
        if (!hasMatchingSenderDeviceKeys(event, senderId, senderKey)) return

        val content = event.getDecryptedContent().toModel<RoomKeyBundleContent>()
        val roomId = content?.roomId
        val file = content?.file
        if (roomId == null || file?.url == null) {
            Timber.tag(loggerTag.value).w("Ignoring a malformed room key bundle from $senderId")
            return
        }

        Timber.tag(loggerTag.value).i("Noted a room key bundle for $roomId from $senderId")
        cryptoStore.storeReceivedRoomKeyBundle(
                roomId = roomId,
                senderUserId = senderId,
                senderKey = senderKey,
                bundleJson = MoshiProvider.providesMoshi().adapter(RoomKeyBundleContent::class.java).toJson(content)
        )
        // The bundle can arrive either side of the join; try now in case we are already in.
        maybeImportBundle(roomId)
    }

    /**
     * MSC4147 `sender_device_keys` are mandatory here. Only the curve25519 key is load-bearing: it comes from the
     * authenticated olm session, so it pins the bundle to a real device. The rest of the claim is re-checked
     * against /keys/query and cross-signing before anything is imported.
     */
    private fun hasMatchingSenderDeviceKeys(event: Event, senderId: String, senderKey: String): Boolean {
        @Suppress("UNCHECKED_CAST")
        val deviceKeys = event.mxDecryptionResult?.payload?.get("sender_device_keys") as? JsonDict
        if (deviceKeys == null) {
            Timber.tag(loggerTag.value).w("Ignoring a room key bundle from $senderId with no sender_device_keys")
            return false
        }
        @Suppress("UNCHECKED_CAST")
        val keys = deviceKeys["keys"] as? Map<String, String>
        val deviceId = deviceKeys["device_id"] as? String
        val matches = deviceKeys["user_id"] == senderId &&
                deviceId != null &&
                keys?.get("curve25519:$deviceId") == senderKey
        if (!matches) {
            Timber.tag(loggerTag.value).w("Ignoring a room key bundle from $senderId whose sender_device_keys do not match the sender")
        }
        return matches
    }

    /**
     * Called once we have joined [roomId] off an invite from [inviter], which is the only thing that makes a bundle
     * from that user trustworthy.
     */
    suspend fun onInviteAccepted(roomId: String, inviter: String) {
        cryptoStore.storeInviteAccepted(roomId, inviter, clock.epochMillis())
        maybeImportBundle(roomId)
    }

    /**
     * Retry rooms whose bundle we noted but never finished importing, e.g. because the app was killed mid-download.
     */
    suspend fun retryPendingBundles() {
        cryptoStore.getAllInvitesAccepted().forEach { maybeImportBundle(it.roomId) }
    }

    private suspend fun maybeImportBundle(roomId: String) {
        val accepted = cryptoStore.getInviteAccepted(roomId) ?: return
        if (clock.epochMillis() - accepted.acceptedAt > ACCEPTANCE_WINDOW_MS) {
            Timber.tag(loggerTag.value).d("Not importing a bundle for $roomId: the invite was accepted too long ago")
            forget(roomId, accepted.inviter)
            return
        }
        val stored = cryptoStore.getReceivedRoomKeyBundle(roomId, accepted.inviter) ?: return

        val content = MoshiProvider.providesMoshi()
                .adapter(RoomKeyBundleContent::class.java)
                .fromJson(stored.bundleJson)
        val file = content?.file
        if (file?.url == null) {
            forget(roomId, accepted.inviter)
            return
        }

        // The sender vouches for every key in here, so require their device to be signed by their own identity.
        deviceListManager.downloadKeys(listOf(accepted.inviter), forceDownload = false)
        val senderDevice = cryptoStore.getUserDeviceList(accepted.inviter)
                .orEmpty()
                .firstOrNull { it.identityKey() == stored.senderKey }
        if (senderDevice == null || !crossSigningService.get().isDeviceSignedByItsOwner(senderDevice)) {
            Timber.tag(loggerTag.value).w("Not importing a bundle for $roomId: ${accepted.inviter}'s device is not cross-signed")
            return
        }

        val bundle = downloadAndParse(roomId, file)
        if (bundle != null) {
            importBundle(roomId, accepted.inviter, bundle)
        }
        // Give up either way: the media is gone or malformed, and retrying costs a download every startup.
        forget(roomId, accepted.inviter)
    }

    private fun forget(roomId: String, inviter: String) {
        cryptoStore.deleteReceivedRoomKeyBundle(roomId, inviter)
        cryptoStore.deleteInviteAccepted(roomId)
    }

    private suspend fun downloadAndParse(
            roomId: String,
            file: EncryptedFileInfo,
    ): RoomKeyBundle? {
        // Servers are free to expire media that looks unused, so a failure here is expected.
        val decrypted = tryOrNull("Failed to download the room key bundle for $roomId") {
            fileService.get().downloadFile(
                    fileName = "room_key_bundle",
                    mimeType = "application/octet-stream",
                    url = file.url,
                    elementToDecrypt = file.toElementToDecrypt()
            )
        } ?: return null

        return tryOrNull("Failed to parse the room key bundle for $roomId") {
            MoshiProvider.providesMoshi().adapter(RoomKeyBundle::class.java).fromJson(decrypted.readText())
        }
    }

    private suspend fun importBundle(roomId: String, sender: String, bundle: RoomKeyBundle) {
        val sessions = bundle.roomKeys
                .filter { it.roomId == roomId }
                .map { it.toMegolmSessionData() }
        if (sessions.size != bundle.roomKeys.size) {
            Timber.tag(loggerTag.value).w("Dropped ${bundle.roomKeys.size - sessions.size} key(s) meant for another room")
        }

        if (sessions.isNotEmpty()) {
            withContext(coroutineDispatchers.crypto) {
                megolmSessionDataImporter.handle(
                        megolmSessionsData = sessions,
                        fromBackup = false,
                        progressListener = null,
                        sharedByUserId = sender
                )
            }
        }

        val withheld = bundle.withheld.filter { it.roomId == roomId && it.code == WithHeldCode.HISTORY_NOT_SHARED }
        withheld.forEach { cryptoStore.addWithHeldMegolmSession(it) }

        Timber.tag(loggerTag.value)
                .i("Imported ${sessions.size} key(s) and ${withheld.size} withheld marker(s) for $roomId from $sender")
    }

    companion object {
        // Matches Element Web and matrix-rust-sdk.
        private const val ACCEPTANCE_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
