/*
 * Copyright (c) 2019 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.actions

import androidx.annotation.WorkerThread
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.MegolmSessionData
import org.matrix.android.sdk.internal.crypto.OutgoingKeyRequestManager
import org.matrix.android.sdk.internal.crypto.RoomDecryptorProvider
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("MegolmSessionDataImporter", LoggerTag.CRYPTO)

internal class MegolmSessionDataImporter @Inject constructor(
        private val olmDevice: MXOlmDevice,
        private val roomDecryptorProvider: RoomDecryptorProvider,
        private val outgoingKeyRequestManager: OutgoingKeyRequestManager,
        private val cryptoStore: IMXCryptoStore,
        private val clock: Clock,
) {

    /**
     * Import a list of megolm session keys.
     * Must be call on the crypto coroutine thread
     *
     * @param megolmSessionsData megolm sessions.
     * @param fromBackup true if the imported keys are already backed up on the server.
     * @param progressListener the progress listener
     * @param sharedByUserId the user who shared these keys in an MSC4268 bundle, if that is where they came from
     * @return import room keys result
     */
    @WorkerThread
    fun handle(
            megolmSessionsData: List<MegolmSessionData>,
            fromBackup: Boolean,
            progressListener: ProgressListener?,
            sharedByUserId: String? = null,
    ): ImportRoomKeysResult {
        val t0 = clock.epochMillis()
        val importedSession = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

        val totalNumbersOfKeys = megolmSessionsData.size
        var lastProgress = 0
        var totalNumbersOfImportedKeys = 0

        progressListener?.onProgress(0, totalNumbersOfKeys)
        val olmInboundGroupSessionWrappers = olmDevice.importInboundGroupSessions(megolmSessionsData, sharedByUserId)
        val tUnpickle = clock.epochMillis()
        Timber.tag(loggerTag.value).v("## importMegolmSessionsData : unpickle ${tUnpickle - t0} ms ($totalNumbersOfKeys sessions)")

        // Cancelling an outgoing key request requires a per-session DB lookup. For a large import
        // most imported sessions were never requested, so fetch the outstanding requests once and
        // only attempt cancellation for sessions that actually have one.
        val requestedSessionIds = cryptoStore.getOutgoingRoomKeyRequests().mapNotNullTo(HashSet()) { it.sessionId }

        // Precompute first-known-index per session once (each sessionIdentifier()/firstKnownIndex is a
        // native call); otherwise the per-session lookup below is O(requested * imported).
        val firstKnownIndexBySessionId = olmInboundGroupSessionWrappers.mapNotNull { wrapper ->
            tryOrNull { wrapper.session.sessionIdentifier() to wrapper.session.firstKnownIndex.toInt() }
        }.toMap()

        megolmSessionsData.forEachIndexed { cpt, megolmSessionData ->
            val sessionId = megolmSessionData.sessionId
            val senderKey = megolmSessionData.senderKey
            val roomId = megolmSessionData.roomId
            if (sessionId != null && senderKey != null && roomId != null) {
                importedSession.getOrPut(roomId) { mutableMapOf() }
                        .getOrPut(senderKey) { mutableListOf() }
                        .add(sessionId)
                totalNumbersOfImportedKeys++

                // cancel any outstanding room key requests for this session
                if (sessionId in requestedSessionIds) {
                    outgoingKeyRequestManager.postCancelRequestForSessionIfNeeded(sessionId, roomId, senderKey, firstKnownIndexBySessionId[sessionId] ?: 0)
                }

                // Only pay for the room decryptor + the retry notification for sessions that were actually
                // (re)imported (firstKnownIndexBySessionId is keyed by the imported wrappers). On a re-import
                // where nothing changed this is 0 sessions, so we skip 12k+ no-op decryptor lookups/retries —
                // the real key-import slowness. The retry itself is async (decryptors have their own workers).
                if (sessionId in firstKnownIndexBySessionId) {
                    // Fan out to the decryptors directly instead of building a per-room decryptor just to
                    // call onNewSession — instantiating one MXMegolmDecryption per room was the real cost.
                    roomDecryptorProvider.notifyNewSession(roomId, sessionId)
                }
            }

            if (progressListener != null) {
                // Report real key counts so the UI can show "(current/total)", updating every 10 keys.
                val progress = cpt + 1
                if (progress == totalNumbersOfKeys || progress - lastProgress >= 10) {
                    lastProgress = progress
                    progressListener.onProgress(progress, totalNumbersOfKeys)
                }
            }
        }

        // Do not back up the key if it comes from a backup recovery
        if (fromBackup) {
            cryptoStore.markBackupDoneForInboundGroupSessions(olmInboundGroupSessionWrappers)
        }

        val t1 = clock.epochMillis()

        Timber.tag(loggerTag.value).v(
                "## importMegolmSessionsData : sessions import " + (t1 - t0) + " ms (unpickle " + (tUnpickle - t0) +
                        " ms, loop " + (t1 - tUnpickle) + " ms, " + megolmSessionsData.size + " sessions, requested " + requestedSessionIds.size + ")"
        )

        return ImportRoomKeysResult(totalNumbersOfKeys, totalNumbersOfImportedKeys, importedSession)
    }
}
