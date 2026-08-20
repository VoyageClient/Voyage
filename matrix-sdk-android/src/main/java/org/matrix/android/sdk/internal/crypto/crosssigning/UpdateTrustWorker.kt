/*
 * Copyright (c) 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.crosssigning

import android.content.Context
import androidx.work.WorkerParameters
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.api.session.crypto.crosssigning.UserTrustResult
import org.matrix.android.sdk.api.session.crypto.crosssigning.isCrossSignedVerified
import org.matrix.android.sdk.api.session.crypto.crosssigning.isVerified
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.CryptoSessionInfoProvider
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.internal.util.logLimit
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import timber.log.Timber
import javax.inject.Inject

internal class UpdateTrustWorker(context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<UpdateTrustWorkerParams>(context, params, sessionManager, UpdateTrustWorkerParams::class.java) {

    @Inject lateinit var crossSigningService: CrossSigningService

    @Inject lateinit var cryptoStore: IMXCryptoStore

    @SessionDatabase
    @Inject lateinit var database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase

    @SessionDatabase
    @Inject lateinit var sessionDbDispatcher: kotlinx.coroutines.CoroutineDispatcher

    @Inject lateinit var stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores

    @UserId
    @Inject lateinit var myUserId: String
    @Inject lateinit var updateTrustWorkerDataRepository: UpdateTrustWorkerDataRepository
    @Inject lateinit var cryptoSessionInfoProvider: CryptoSessionInfoProvider

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: UpdateTrustWorkerParams): Result {
        val sId = myUserId.take(5)
        Timber.v("## CrossSigning - UpdateTrustWorker started..")
        val workerParams = params.filename
                ?.let { updateTrustWorkerDataRepository.getParam(it) }
                ?: return Result.success().also {
                    Timber.w("## CrossSigning - UpdateTrustWorker failed to get params")
                    cleanup(params)
                }

        Timber.v("## CrossSigning [$sId]- UpdateTrustWorker userIds:${workerParams.userIds.logLimit()}, roomIds:${workerParams.roomIds.orEmpty().logLimit()}")
        val userList = workerParams.userIds

        // List should not be empty, but let's avoid go further in case of empty list
        if (userList.isNotEmpty()) {
            // Unfortunately we don't have much info on what did exactly changed (is it the cross signing keys of that user,
            // or a new device?) So we check all again :/
            Timber.v("## CrossSigning [$sId]- Updating trust for users: ${userList.logLimit()}")
            updateTrust(userList)
        }

        val roomsToCheck = workerParams.roomIds ?: cryptoSessionInfoProvider.getRoomsWhereUsersAreParticipating(userList)
        Timber.v("## CrossSigning [$sId]- UpdateTrustWorker roomShield to check:${roomsToCheck.logLimit()}")
        val myCrossSigningInfo = cryptoStore.getMyCrossSigningInfo()
        // So Cross Signing keys trust is updated, device trust is updated
        // We can now update room shields? in the session DB?
        updateRoomShieldInSummaries(roomsToCheck, myCrossSigningInfo)

        cleanup(params)
        return Result.success()
    }

    private fun updateTrust(userListParam: List<String>) {
        val sId = myUserId.take(5)
        var userList = userListParam
        var myCrossSigningInfo = cryptoStore.getMyCrossSigningInfo()
        var myTrustResult: UserTrustResult? = null

        if (userList.contains(myUserId)) {
            Timber.d("## CrossSigning [$sId]- Clear all trust as a change on my user was detected")
            // If it's my cross signing keys we should refresh all trust.
            userList = cryptoStore.getCrossSigningInfoUserIds()

            // check right now my keys and mark it as trusted as other trust depends on it
            val myDevices = cryptoStore.getUserDeviceList(myUserId)
            myTrustResult = crossSigningService.checkSelfTrust(myCrossSigningInfo, myDevices)
            cryptoStore.setUserKeysAsTrusted(myUserId, myTrustResult.isVerified())
            myCrossSigningInfo = cryptoStore.getMyCrossSigningInfo()
        }

        val otherInfos = userList.associateWith { userId -> cryptoStore.getCrossSigningInfo(userId) }

        val trusts = otherInfos.mapValues { entry ->
            when (entry.key) {
                myUserId -> myTrustResult
                else -> crossSigningService.checkOtherMSKTrusted(myCrossSigningInfo, entry.value)
            }
        }

        trusts.forEach {
            val verified = it.value?.isVerified() == true
            cryptoStore.setUserKeysAsTrusted(it.key, verified)
        }

        // Now check device trust for all these users.
        Timber.v("## CrossSigning [$sId]- Updating devices cross trust users: ${trusts.keys.logLimit()}")
        trusts.keys.forEach { userId ->
            cryptoStore.getUserDeviceList(userId).orEmpty().forEach { device ->
                val crossSignedVerified = crossSigningService
                        .checkDeviceTrust(myCrossSigningInfo, otherInfos[userId], device)
                        .isCrossSignedVerified()
                if (device.trustLevel?.crossSigningVerified != crossSignedVerified) {
                    cryptoStore.setDeviceTrust(userId, device.deviceId, crossSignedVerified, locallyVerified = null)
                }
            }
        }
    }

    private suspend fun updateRoomShieldInSummaries(roomList: List<String>, myCrossSigningInfo: MXCrossSigningInfo?) {
        val sId = myUserId.take(5)
        Timber.d("## CrossSigning [$sId]- Updating shields for impacted rooms... ${roomList.logLimit()}")
        database.awaitDbTransaction(sessionDbDispatcher) {
            Timber.d("## CrossSigning - Updating shields for impacted rooms - in transaction")
            roomList.forEach forEachRoom@{ roomId ->
                Timber.v("## CrossSigning [$sId]- Checking room $roomId")
                val roomSummary = stores.roomSummary.get(roomId) ?: return@forEachRoom
                // Non-encrypted rooms must never carry an encryption shield. computeRoomShield
                // always returns a non-null level, so only compute it for encrypted rooms and
                // clear any stale value otherwise.
                if (!roomSummary.isEncrypted) {
                    if (roomSummary.roomEncryptionTrustLevel != null) {
                        stores.roomSummary.updateEncryptionTrustLevel(roomId, null)
                    }
                    return@forEachRoom
                }
                Timber.v("## CrossSigning [$sId]- Check shield state for room $roomId")
                // Sliding sync only sends the members who spoke recently, so until the full list has been
                // fetched the members present are a subset — and judging the room by them would show a
                // reassuring shield for a room whose unverified member simply has not spoken lately.
                if (stores.room.get(roomId)?.membersLoadStatus != RoomMembersLoadStatusType.LOADED) {
                    Timber.v("## CrossSigning [$sId]- Members not fully loaded for $roomId, leaving shield as is")
                    return@forEachRoom
                }
                val allActiveRoomMembers = SqlRoomMemberHelper(stores, roomId).getActiveRoomMemberIds()
                try {
                    val updatedTrust = computeRoomShield(myCrossSigningInfo, allActiveRoomMembers, roomSummary)
                    if (roomSummary.roomEncryptionTrustLevel != updatedTrust) {
                        Timber.d("## CrossSigning [$sId]- Shield change detected for $roomId -> $updatedTrust")
                        stores.roomSummary.updateEncryptionTrustLevel(roomId, updatedTrust.name)
                    } else {
                        Timber.v("## CrossSigning [$sId]- Shield unchanged for $roomId -> $updatedTrust")
                    }
                } catch (failure: Throwable) {
                    Timber.e(failure)
                }
            }
        }
        Timber.d("## CrossSigning - Updating shields for impacted rooms - END")
    }

    private fun cleanup(params: UpdateTrustWorkerParams) {
        params.filename
                ?.let { updateTrustWorkerDataRepository.delete(it) }
    }

    private fun computeRoomShield(
            myCrossSigningInfo: MXCrossSigningInfo?,
            activeMemberUserIds: List<String>,
            roomSummaryEntity: RoomSummaryEntity
    ): RoomEncryptionTrustLevel {
        Timber.v("## CrossSigning - computeRoomShield ${roomSummaryEntity.roomId} -> ${activeMemberUserIds.logLimit()}")
        // The set of “all users” depends on the type of room:
        // For regular / topic rooms which have more than 2 members (including yourself) are considered when decorating a room
        // For 1:1 and group DM rooms, all other users (i.e. excluding yourself) are considered when decorating a room
        val listToCheck = if (roomSummaryEntity.isDirect || activeMemberUserIds.size <= 2) {
            activeMemberUserIds.filter { it != myUserId }
        } else {
            activeMemberUserIds
        }

        val allTrustedUserIds = listToCheck
                .filter { userId ->
                    cryptoStore.getCrossSigningInfo(userId)?.isTrusted() == true
                }

        val resetTrust = listToCheck
                .filter { userId ->
                    val crossSigningInfo = cryptoStore.getCrossSigningInfo(userId)
                    crossSigningInfo?.isTrusted() != true && crossSigningInfo?.wasTrustedOnce == true
                }

        return if (allTrustedUserIds.isEmpty()) {
            if (resetTrust.isEmpty()) {
                RoomEncryptionTrustLevel.Default
            } else {
                RoomEncryptionTrustLevel.Warning
            }
        } else {
            // If one of the verified user as an untrusted device -> warning
            // If all devices of all verified users are trusted -> green
            // else -> black
            allTrustedUserIds
                    .mapNotNull { userId -> cryptoStore.getUserDeviceList(userId) }
                    .flatten()
                    .let { allDevices ->
                        Timber.v("## CrossSigning - computeRoomShield ${roomSummaryEntity.roomId} devices ${allDevices.map { it.deviceId }.logLimit()}")
                        if (myCrossSigningInfo != null) {
                            allDevices.any { !it.trustLevel?.crossSigningVerified.orFalse() }
                        } else {
                            // Legacy method
                            allDevices.any { !it.isVerified }
                        }
                    }
                    .let { hasWarning ->
                        if (hasWarning) {
                            RoomEncryptionTrustLevel.Warning
                        } else {
                            if (resetTrust.isEmpty()) {
                                if (listToCheck.size == allTrustedUserIds.size) {
                                    // all users are trusted and all devices are verified
                                    RoomEncryptionTrustLevel.Trusted
                                } else {
                                    RoomEncryptionTrustLevel.Default
                                }
                            } else {
                                RoomEncryptionTrustLevel.Warning
                            }
                        }
                    }
        }
    }

    override fun buildErrorParams(params: UpdateTrustWorkerParams, message: String): UpdateTrustWorkerParams {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }
}
