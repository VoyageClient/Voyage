/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.sync

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.measureSpan
import org.matrix.android.sdk.api.extensions.measureSpannableMetric
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.metrics.SpannableMetricPlugin
import org.matrix.android.sdk.api.metrics.SyncDurationMetricPlugin
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.pushrules.PushRuleService
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.sync.InitialSyncStep
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.store.db.CryptoStoreAggregator
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.session.SessionListeners
import org.matrix.android.sdk.internal.session.dispatchTo
import org.matrix.android.sdk.internal.session.pushrules.ProcessEventForPushTask
import org.matrix.android.sdk.internal.session.sync.handler.SyncResponsePostTreatmentAggregatorHandler
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.measureTimeMillis

internal class SyncResponseHandler @Inject constructor(
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        @SessionId private val sessionId: String,
        private val sessionManager: SessionManager,
        private val sessionListeners: SessionListeners,
        private val roomSyncHandler: org.matrix.android.sdk.internal.session.sync.handler.room.SqlRoomSyncHandler,
        private val userAccountDataSyncHandler: org.matrix.android.sdk.internal.session.sync.handler.SqlUserAccountDataSyncHandler,
        private val presenceSyncHandler: org.matrix.android.sdk.internal.session.sync.handler.SqlPresenceSyncHandler,
        private val profileSyncHandler: org.matrix.android.sdk.internal.session.sync.handler.ProfileSyncHandler,
        private val roomSummaryUpdater: org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater,
        private val aggregatorHandler: SyncResponsePostTreatmentAggregatorHandler,
        private val cryptoService: CryptoService,
        private val processEventForPushTask: ProcessEventForPushTask,
        private val pushRuleService: PushRuleService,
        private val clock: Clock,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        matrixConfiguration: MatrixConfiguration,
) {

    private val relevantPlugins = matrixConfiguration.metricPlugins.filterIsInstance<SyncDurationMetricPlugin>()

    suspend fun handleResponse(
            syncResponse: SyncResponse,
            fromToken: String?,
            afterPause: Boolean,
            reporter: ProgressReporter?,
            persistToken: Boolean = true,
            suppressPush: Boolean = false,
            deferSpaceValidation: Boolean = false,
    ): Boolean {
        val isInitialSync = fromToken == null

        var spaceValidationDeferred = false
        val aggregator = SyncResponsePostTreatmentAggregator()

        relevantPlugins.filter { it.shouldReport(isInitialSync, afterPause) }.measureSpannableMetric {
            reportSubtask(reporter, InitialSyncStep.ImportingAccountCrypto, 1, 0.1f) {
                MatrixPerf.time("resp.startCrypto") { startCryptoService(isInitialSync) }

                // Handle the to device events before the room ones
                // to ensure to decrypt them properly
                MatrixPerf.timeSuspending("resp.toDevice") { handleToDevice(syncResponse, isInitialSync) }

                val syncLocalTimestampMillis = clock.epochMillis()

                // Enter IO once for the crypto pass to avoid dispatcher hops for every state event.
                MatrixPerf.timeSuspending("resp.cryptoStateEvents") {
                    withContext(coroutineDispatchers.io) {
                        measureSpan("task", "crypto_session_event_handling") {
                            syncResponse.rooms?.invite?.forEach { (roomId, roomSync) ->
                                roomSync.inviteState?.events?.filter { it.isStateEvent() }?.forEach {
                                    cryptoService.onStateEvent(roomId, it, aggregator.cryptoStoreAggregator)
                                }
                            }
                            syncResponse.rooms?.join?.forEach { (roomId, roomSync) ->
                                // MSC4222 replaces state with state_after; crypto needs either form.
                                val isGappySync = roomSync.timeline?.limited.orFalse()
                                (roomSync.stateAfter ?: roomSync.state)?.events?.filter { it.isStateEvent() }?.forEach {
                                    MatrixPerf.timeSuspending("crypto.onStateEvent") {
                                        cryptoService.onStateEvent(roomId, it, aggregator.cryptoStoreAggregator, isGappySync)
                                    }
                                }
                                roomSync.timeline?.events?.forEach {
                                    // First deliveries are history; timeline and summary decryptors handle them later.
                                    if (it.isEncrypted() && !isInitialSync && !roomSync.isInitialDelivery) {
                                        MatrixPerf.timeSuspending("crypto.decryptIfNeeded") { decryptIfNeeded(it, roomId) }
                                    }
                                    it.ageLocalTs = syncLocalTimestampMillis - (it.unsignedData?.age ?: 0)
                                    MatrixPerf.timeSuspending("crypto.onLiveEvent") {
                                        cryptoService.onLiveEvent(
                                                roomId, it, isInitialSync || roomSync.isInitialDelivery, aggregator.cryptoStoreAggregator
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Prerequisite for thread events handling in RoomSyncHandler
            // Disabled due to the new fallback
            //        if (!lightweightSettingsStorage.areThreadMessagesEnabled()) {
            //            threadsAwarenessHandler.fetchRootThreadEventsIfNeeded(syncResponse)
            //        }

            MatrixPerf.timeSuspending("resp.dbTransaction") {
                startMonarchyTransaction(syncResponse, isInitialSync, reporter, aggregator, persistToken)
            }

            // Purely in-memory, so it stays outside the DB transaction above.
            MatrixPerf.time("resp.profiles") { profileSyncHandler.handle(syncResponse.profileUpdates) }

            MatrixPerf.timeSuspending("resp.aggregate") { aggregateSyncResponse(aggregator) }

            MatrixPerf.timeSuspending("resp.postTreatment") { postTreatmentSyncResponse(syncResponse, isInitialSync, suppressPush) }

            MatrixPerf.timeSuspending("resp.markCryptoDone") { markCryptoSyncCompleted(syncResponse, aggregator.cryptoStoreAggregator) }

            val directChanged = syncResponse.accountData?.list?.any { it.type == UserAccountDataTypes.TYPE_DIRECT_MESSAGES } == true
            val shouldValidate = isInitialSync || aggregator.spaceHierarchyChanged || directChanged
            spaceValidationDeferred = deferSpaceValidation && shouldValidate
            MatrixPerf.timeSuspending("resp.postSync") { handlePostSync(shouldValidateSpaceHierarchy = shouldValidate && !deferSpaceValidation) }

            Timber.v("On sync completed")
        }
        return spaceValidationDeferred
    }

    /** Runs the space-hierarchy pass that [handleResponse] was told to defer. */
    suspend fun validateSpaceHierarchy() {
        handlePostSync(shouldValidateSpaceHierarchy = true)
    }

    /** See [SqlUserAccountDataSyncHandler.refreshDirectChatRooms]; only the sliding-sync path needs this. */
    suspend fun refreshDirectChatRooms(limitToRooms: Collection<String>? = null) {
        database.awaitDbTransaction(sessionDbDispatcher) {
            userAccountDataSyncHandler.refreshDirectChatRooms(limitToRooms)
        }
    }

    private suspend fun decryptIfNeeded(event: Event, roomId: String) {
        try {
            val timelineId = generateTimelineId(roomId)
            // Event from sync does not have roomId, so add it to the event first
            val result = cryptoService.decryptEvent(event.copy(roomId = roomId), timelineId)
            event.mxDecryptionResult = OlmDecryptionResult(
                    payload = result.clearEvent,
                    senderKey = result.senderCurve25519Key,
                    keysClaimed = result.claimedEd25519Key?.let { k -> mapOf("ed25519" to k) },
                    forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                    verificationState = result.messageVerificationState,
                    sharedByUserId = result.sharedByUserId,
            )
        } catch (e: MXCryptoError) {
            Timber.v(e, "Failed to decrypt $roomId")
            if (e is MXCryptoError.Base) {
                event.mCryptoError = e.errorType
                event.mCryptoErrorReason = e.technicalMessage.takeIf { it.isNotEmpty() } ?: e.detailedErrorDescription
            }
        }
    }

    private fun generateTimelineId(roomId: String): String {
        return "RoomSyncHandler$roomId"
    }

    private suspend fun List<SpannableMetricPlugin>.startCryptoService(isInitialSync: Boolean) {
        measureSpan("task", "start_crypto_service") {
            measureTimeMillis {
                if (!cryptoService.isStarted()) {
                    Timber.v("Should start cryptoService")
                    cryptoService.start()
                }
                cryptoService.onSyncWillProcess(isInitialSync)
            }.also {
                Timber.v("Finish handling start cryptoService in $it ms")
            }
        }
    }

    private suspend fun List<SpannableMetricPlugin>.handleToDevice(syncResponse: SyncResponse, isInitialSync: Boolean) {
        measureSpan("task", "handle_to_device") {
            measureTimeMillis {
                Timber.v("Handle toDevice")
                cryptoService.receiveSyncChanges(
                        syncResponse.toDevice,
                        // Starting a sync from scratch has already invalidated every device list, so walking
                        // the server's per-user change list would redo that one user at a time — and sliding
                        // sync sends hundreds of them on a first response, at a database query each.
                        syncResponse.deviceLists.takeUnless { isInitialSync },
                        syncResponse.deviceOneTimeKeysCount,
                        syncResponse.deviceUnusedFallbackKeyTypes
                )
            }.also {
                Timber.v("Finish handling toDevice in $it ms")
            }
        }
    }

    private suspend fun List<SpannableMetricPlugin>.startMonarchyTransaction(
            syncResponse: SyncResponse,
            isInitialSync: Boolean,
            reporter: ProgressReporter?,
            aggregator: SyncResponsePostTreatmentAggregator,
            persistToken: Boolean,
    ) {
        val rooms = syncResponse.rooms
        val roomBatches = rooms?.splitForImport().orEmpty()

        measureSpan("task", "sql_session_transaction") {
            // Opening a transaction is not free — it queues behind every other database user and commits —
            // and a sliding-sync fill is dozens of responses that carry account data once, at the start.
            if (!syncResponse.accountData?.list.isNullOrEmpty()) {
                MatrixPerf.timeSuspending("sync.transaction accountData") {
                    database.awaitDbTransaction(sessionDbDispatcher) {
                        reportSubtask(reporter, InitialSyncStep.ImportingAccountData, 1, 0.1f) {
                            userAccountDataSyncHandler.handle(syncResponse.accountData, aggregator)
                        }
                    }
                }
            }

            // One task for the whole import, not one per batch: each batch would otherwise open a fresh
            // 0.8-weighted child whose offset is the progress the previous batch finished at, so the steps
            // replayed and the percentage ran past 100. The per-room steps inside only make sense for a
            // single batch; past that the batch index is the progress.
            val batched = roomBatches.size > 1
            reportSubtask(reporter, InitialSyncStep.ImportingAccountRoom, roomBatches.size, 0.8f) {
                roomBatches.forEachIndexed { index, batch ->
                    MatrixPerf.timeSuspending("sync.transaction rooms=${batch.join.size}j/${batch.invite.size}i presence=0") {
                        database.awaitDbTransaction(sessionDbDispatcher) {
                            MatrixPerf.time("sync.roomSyncHandler") {
                                roomSyncHandler.handle(stores, batch, isInitialSync, aggregator, reporter.takeUnless { batched })
                            }
                        }
                    }
                    if (batched) {
                        reporter?.reportProgress((index + 1).toFloat())
                        // Room-list observers share the database dispatcher. Give them a turn between
                        // commits so already visible rooms remain live while a cold response is hydrated.
                        yield()
                    }
                }
            }

            val presenceEvents = syncResponse.presence?.events.orEmpty()
            if (presenceEvents.isNotEmpty() || persistToken) {
                MatrixPerf.timeSuspending("sync.transaction metadata presence=${presenceEvents.size}") {
                    database.awaitDbTransaction(sessionDbDispatcher) {
                        MatrixPerf.time("sync.presenceHandler") {
                            presenceSyncHandler.handle(stores, syncResponse.presence)
                        }
                        if (persistToken) {
                            stores.syncToken.setNextBatch(syncResponse.nextBatch)
                        }
                    }
                }
            }
        }
    }

    private fun RoomsSyncResponse.splitForImport(): List<RoomsSyncResponse> {
        val roomCount = join.size + invite.size + leave.size + knock.size
        if (roomCount <= ROOM_IMPORT_BATCH_SIZE || join.values.none { it.isInitialDelivery }) return listOf(this)

        return buildList {
            join.entries.chunked(ROOM_IMPORT_BATCH_SIZE).forEach { add(RoomsSyncResponse(join = it.associate { entry -> entry.toPair() })) }
            invite.entries.chunked(ROOM_IMPORT_BATCH_SIZE).forEach { add(RoomsSyncResponse(invite = it.associate { entry -> entry.toPair() })) }
            leave.entries.chunked(ROOM_IMPORT_BATCH_SIZE).forEach { add(RoomsSyncResponse(leave = it.associate { entry -> entry.toPair() })) }
            knock.entries.chunked(ROOM_IMPORT_BATCH_SIZE).forEach { add(RoomsSyncResponse(knock = it.associate { entry -> entry.toPair() })) }
        }
    }

    private suspend fun List<SpannableMetricPlugin>.aggregateSyncResponse(aggregator: SyncResponsePostTreatmentAggregator) {
        measureSpan("task", "aggregator_management") {
            // Everything else we need to do outside the transaction
            measureTimeMillis {
                aggregatorHandler.handle(aggregator)
            }.also {
                Timber.v("Aggregator management took $it ms")
            }
        }
    }

    private suspend fun List<SpannableMetricPlugin>.postTreatmentSyncResponse(syncResponse: SyncResponse, isInitialSync: Boolean, suppressPush: Boolean) {
        measureSpan("task", "sync_response_post_treatment") {
            measureTimeMillis {
                syncResponse.rooms?.let {
                    checkPushRules(it, isInitialSync || suppressPush)
                    userAccountDataSyncHandler.synchronizeWithServerIfNeeded(it.invite)
                    dispatchInvitedRoom(it)
                }
            }.also {
                Timber.v("SyncResponse.rooms post treatment took $it ms")
            }
        }
    }

    private suspend fun List<SpannableMetricPlugin>.markCryptoSyncCompleted(syncResponse: SyncResponse, cryptoStoreAggregator: CryptoStoreAggregator) {
        measureSpan("task", "crypto_sync_handler_onSyncCompleted") {
            measureTimeMillis {
                cryptoService.onSyncCompleted(syncResponse, cryptoStoreAggregator)
            }.also {
                Timber.v("cryptoSyncHandler.onSyncCompleted took $it ms")
            }
        }
    }

    private suspend fun handlePostSync(shouldValidateSpaceHierarchy: Boolean) {
        // Revalidating the whole space parent/child graph is expensive; only do it when the sync actually
        // carried changes that can affect it.
        if (!shouldValidateSpaceHierarchy) return
        MatrixPerf.timeSuspending("sync.validateSpaceRelationship") {
            database.awaitDbTransaction(sessionDbDispatcher) {
                roomSummaryUpdater.validateSpaceRelationship(stores)
            }
        }
    }

    private fun dispatchInvitedRoom(roomsSyncResponse: RoomsSyncResponse) {
        val session = sessionManager.getSessionComponent(sessionId)?.session()
        roomsSyncResponse.invite.keys.forEach { roomId ->
            session.dispatchTo(sessionListeners) { session, listener ->
                listener.onNewInvitedRoom(session, roomId)
            }
        }
    }

    private suspend fun checkPushRules(roomsSyncResponse: RoomsSyncResponse, isInitialSync: Boolean) {
        Timber.v("[PushRules] --> checkPushRules")
        if (isInitialSync) {
            Timber.v("[PushRules] <-- No push rule check on initial sync")
            return
        } // nothing on initial sync

        val rules = pushRuleService.getPushRules(RuleScope.GLOBAL).getAllRules()
        // A sliding-sync connection hands rooms over gradually, and a room's first delivery brings back
        // history with it; notifying for that would fire a burst of alerts for messages already read.
        val notifiable = roomsSyncResponse.copy(join = roomsSyncResponse.join.filterValues { !it.isInitialDelivery })
        processEventForPushTask.execute(ProcessEventForPushTask.Params(notifiable, rules))
        Timber.v("[PushRules] <-- Push task scheduled")
    }

    private companion object {
        // One commit's worth of rooms. Sized so a batch holds the write lock for about as long as a
        // single sliding-sync response used to, now that a response can carry more rooms than that.
        const val ROOM_IMPORT_BATCH_SIZE = 12
    }
}
