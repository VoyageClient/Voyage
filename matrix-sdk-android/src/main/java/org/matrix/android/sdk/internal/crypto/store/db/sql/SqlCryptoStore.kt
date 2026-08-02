/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.store.db.sql

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.GlobalCryptoConfig
import org.matrix.android.sdk.api.session.crypto.NewSessionListener
import org.matrix.android.sdk.api.session.crypto.OutgoingKeyRequest
import org.matrix.android.sdk.api.session.crypto.OutgoingRoomKeyRequestState
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.api.session.crypto.crosssigning.PrivateKeysInfo
import org.matrix.android.sdk.api.session.crypto.crosssigning.UserIdentity
import org.matrix.android.sdk.api.session.crypto.keysbackup.SavedKeyBackupKeyInfo
import org.matrix.android.sdk.api.session.crypto.model.AuditTrail
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.CryptoRoomInfo
import org.matrix.android.sdk.api.session.crypto.model.DeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.api.session.crypto.model.RoomKeyRequestBody
import org.matrix.android.sdk.api.session.crypto.model.TrailType
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.api.session.events.model.content.RoomKeyWithHeldContent
import org.matrix.android.sdk.api.session.events.model.content.WithHeldCode
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.crypto.model.MXInboundMegolmSessionWrapper
import org.matrix.android.sdk.internal.crypto.model.OlmSessionWrapper
import org.matrix.android.sdk.internal.crypto.model.OutboundGroupSessionWrapper
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStorePaging
import org.matrix.android.sdk.internal.crypto.store.UserDataToStore
import org.matrix.android.sdk.internal.crypto.store.db.CryptoStoreAggregator
import org.matrix.android.sdk.internal.crypto.store.db.deserializeFromRealm
import org.matrix.android.sdk.internal.crypto.store.db.mapper.CrossSigningKeysMapper
import org.matrix.android.sdk.internal.crypto.store.db.mapper.MyDeviceLastSeenInfoEntityMapper
import org.matrix.android.sdk.internal.crypto.store.db.model.KeysBackupDataEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.OlmInboundGroupSessionEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.OlmSessionEntity
import org.matrix.android.sdk.internal.crypto.store.db.model.createPrimaryKey
import org.matrix.android.sdk.internal.crypto.store.db.serializeForRealm
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import org.matrix.android.sdk.internal.di.CryptoDatabase
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.time.Clock
import org.matrix.olm.OlmAccount
import org.matrix.olm.OlmOutboundGroupSession
import org.matrix.olm.OlmSession
import javax.inject.Inject

@SessionScope
internal class SqlCryptoStore @Inject constructor(
        @CryptoDatabase private val database: CryptoSqlDatabase,
        @CryptoDatabase private val dispatcher: CoroutineDispatcher,
        crossSigningKeysMapper: CrossSigningKeysMapper,
        @UserId private val userId: String,
        @DeviceId private val deviceId: String,
        private val clock: Clock,
        myDeviceLastSeenInfoEntityMapper: MyDeviceLastSeenInfoEntityMapper,
) : IMXCryptoStore, IMXCryptoStorePaging {

    private val metadataStore = CryptoMetadataStore(database)
    private val olmSessionStore = OlmSessionSqlStore(database)
    private val megolmStore = MegolmInboundSqlStore(database)
    private val deviceStore = DeviceSqlStore(database, myDeviceLastSeenInfoEntityMapper)
    private val crossSigningStore = CrossSigningSqlStore(database, crossSigningKeysMapper)
    private val keyRequestStore = KeyRequestSqlStore(database, clock)
    private val roomStore = CryptoRoomSqlStore(database)

    private var olmAccount: OlmAccount? = null
    private val newSessionListeners = ArrayList<NewSessionListener>()

    init {
        metadataStore.ensureExists(userId, deviceId)
    }

    // ==================== Lifecycle ====================

    override fun open() {}

    override fun close() {
        olmAccount?.releaseAccount()
    }

    override fun hasData(): Boolean = metadataStore.hasData()

    override fun deleteStore() {
        database.transaction {
            with(database.cryptoDeleteAllQueries) {
                deleteAllMetadata(); deleteAllOlmSession(); deleteAllInbound(); deleteAllRoom()
                deleteAllUser(); deleteAllDevice(); deleteAllMyDevice(); deleteAllCrossSigning()
                deleteAllKeyInfo(); deleteAllOutgoingRequest(); deleteAllKeyRequestReply()
                deleteAllWithheld(); deleteAllShared(); deleteAllKeysBackup(); deleteAllAudit()
            }
        }
    }

    override fun tidyUpDataBase() {
        val prevWeekTs = clock.epochMillis() - 7 * 24 * 60 * 60 * 1_000L
        val prevMonthTs = clock.epochMillis() - 4 * 7 * 24 * 60 * 60 * 1_000L
        keyRequestStore.tidyUp(prevWeekTs, prevMonthTs)
    }

    // ==================== Account / metadata ====================

    override fun getDeviceId(): String = metadataStore.getDeviceId()

    override fun storeDeviceId(deviceId: String) = metadataStore.storeDeviceId(deviceId)

    @Synchronized
    override fun getOrCreateOlmAccount(): OlmAccount {
        val existing = metadataStore.getOlmAccountData()?.let { deserializeFromRealm<OlmAccount>(it) }
        olmAccount = existing ?: OlmAccount().also { metadataStore.setOlmAccountData(serializeForRealm(it)) }
        return olmAccount!!
    }

    override fun <T> doWithOlmAccount(block: (OlmAccount) -> T): T =
            olmAccount!!.let { account -> synchronized(account) { block(account) } }

    override fun saveOlmAccount() = metadataStore.setOlmAccountData(serializeForRealm(olmAccount))

    override fun enableKeyGossiping(enable: Boolean) = metadataStore.enableKeyGossiping(enable)
    override fun isKeyGossipingEnabled(): Boolean = metadataStore.isKeyGossipingEnabled()
    override fun enableShareKeyOnInvite(enable: Boolean) = metadataStore.enableShareKeyOnInvite(enable)
    override fun isShareKeysOnInviteEnabled(): Boolean = metadataStore.isShareKeysOnInviteEnabled()
    override fun setGlobalBlacklistUnverifiedDevices(block: Boolean) = metadataStore.setGlobalBlacklistUnverifiedDevices(block)
    override fun getGlobalBlacklistUnverifiedDevices(): Boolean = metadataStore.getGlobalBlacklistUnverifiedDevices()
    override fun getGlobalCryptoConfig(): GlobalCryptoConfig = metadataStore.getGlobalCryptoConfig()
    override fun setDeviceKeysUploaded(uploaded: Boolean) = metadataStore.setDeviceKeysUploaded(uploaded)
    override fun areDeviceKeysUploaded(): Boolean = metadataStore.areDeviceKeysUploaded()

    override fun getKeyBackupVersion(): String? = metadataStore.getKeyBackupVersion()
    override fun setKeyBackupVersion(keyBackupVersion: String?) = metadataStore.setKeyBackupVersion(keyBackupVersion)
    override fun getKeysBackupData(): KeysBackupDataEntity? = metadataStore.getKeysBackupData()
    override fun setKeysBackupData(keysBackupData: KeysBackupDataEntity?) = metadataStore.setKeysBackupData(keysBackupData)
    override fun saveBackupRecoveryKey(recoveryKey: String?, version: String?) = metadataStore.saveBackupRecoveryKey(recoveryKey, version)
    override fun getKeyBackupRecoveryKeyInfo(): SavedKeyBackupKeyInfo? = metadataStore.getKeyBackupRecoveryKeyInfo()

    override fun storePrivateKeysInfo(msk: String?, usk: String?, ssk: String?) = metadataStore.storePrivateKeysInfo(msk, usk, ssk)
    override fun storeMSKPrivateKey(msk: String?) = metadataStore.storeMSKPrivateKey(msk)
    override fun storeSSKPrivateKey(ssk: String?) = metadataStore.storeSSKPrivateKey(ssk)
    override fun storeUSKPrivateKey(usk: String?) = metadataStore.storeUSKPrivateKey(usk)
    override fun getCrossSigningPrivateKeys(): PrivateKeysInfo? = metadataStore.getCrossSigningPrivateKeys()

    override fun getGlobalCryptoConfigFlow(): Flow<GlobalCryptoConfig> =
            database.cryptoMetadataQueries.selectFirst().asFlow().mapToOneOrNull(dispatcher)
                    .map { row -> row?.let { GlobalCryptoConfig(it.global_blacklist_unverified_devices == 1L, it.global_enable_key_gossiping == 1L, it.enable_key_forwarding_on_invite == 1L) } ?: GlobalCryptoConfig(false, false, false) }
                    .flowOn(dispatcher)

    override fun getCrossSigningPrivateKeysFlow(): Flow<Optional<PrivateKeysInfo>> =
            database.cryptoMetadataQueries.selectFirst().asFlow().mapToOneOrNull(dispatcher)
                    .map { row -> row?.let { PrivateKeysInfo(it.x_sign_master_private_key, it.x_sign_self_signed_private_key, it.x_sign_user_private_key) }.toOptional() }
                    .flowOn(dispatcher)

    // ==================== Olm sessions ====================

    override fun storeSession(olmSessionWrapper: OlmSessionWrapper, deviceKey: String) {
        val sessionId = tryOrNull("storeSession sessionIdentifier") { olmSessionWrapper.olmSession.sessionIdentifier() } ?: return
        olmSessionStore.upsert(
                OlmSessionEntity.createPrimaryKey(sessionId, deviceKey),
                sessionId,
                deviceKey,
                serializeForRealm(olmSessionWrapper.olmSession),
                olmSessionWrapper.lastReceivedMessageTs,
        )
    }

    override fun getDeviceSession(sessionId: String, deviceKey: String): OlmSessionWrapper? {
        val row = olmSessionStore.get(OlmSessionEntity.createPrimaryKey(sessionId, deviceKey)) ?: return null
        val olmSession = deserializeFromRealm<OlmSession>(row.olm_session_data) ?: return null
        return if (row.session_id != null) OlmSessionWrapper(olmSession, row.last_received_message_ts) else null
    }

    override fun getDeviceSessionIds(deviceKey: String): List<String> = olmSessionStore.getDeviceSessionIds(deviceKey)
    override fun getLastUsedSessionId(deviceKey: String): String? = olmSessionStore.getLastUsedSessionId(deviceKey)

    // ==================== Megolm inbound sessions ====================

    override fun storeInboundGroupSessions(sessions: List<MXInboundMegolmSessionWrapper>) {
        if (sessions.isEmpty()) return
        database.transaction {
            sessions.forEach { wrapper ->
                val sessionId = tryOrNull("storeInboundGroupSession sessionIdentifier") { wrapper.session.sessionIdentifier() } ?: return@forEach
                val key = OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, wrapper.sessionData.senderKey)
                val backedUp = megolmStore.get(key)?.backed_up == 1L
                val entity = OlmInboundGroupSessionEntity().apply {
                    primaryKey = key
                    store(wrapper)
                    this.backedUp = backedUp
                }
                megolmStore.upsert(key, entity.sessionId, entity.senderKey, entity.roomId, entity.inboundGroupSessionDataJson, entity.serializedOlmInboundGroupSession, entity.sharedHistory, entity.backedUp)
            }
        }
    }

    override fun getInboundGroupSessionKeys(): Set<String> = megolmStore.getAllPrimaryKeys()

    override fun getInboundGroupSession(sessionId: String, senderKey: String): MXInboundMegolmSessionWrapper? =
            megolmStore.get(OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, senderKey))?.toModelWrapper()

    override fun getInboundGroupSession(sessionId: String, senderKey: String, sharedHistory: Boolean): MXInboundMegolmSessionWrapper? =
            megolmStore.getWithSharedHistory(OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, senderKey), sharedHistory)?.toModelWrapper()

    override fun getInboundGroupSessions(): List<MXInboundMegolmSessionWrapper> = megolmStore.getAll().mapNotNull { it.toModelWrapper() }
    override fun getInboundGroupSessions(roomId: String): List<MXInboundMegolmSessionWrapper> = megolmStore.getByRoomId(roomId).mapNotNull { it.toModelWrapper() }

    override fun removeInboundGroupSession(sessionId: String, senderKey: String) =
            megolmStore.delete(OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, senderKey))

    override fun resetBackupMarkers() = megolmStore.markAllNotBackedUp()

    override fun markBackupDoneForInboundGroupSessions(olmInboundGroupSessionWrappers: List<MXInboundMegolmSessionWrapper>) {
        if (olmInboundGroupSessionWrappers.isEmpty()) return
        database.transaction {
            // These were just persisted by storeInboundGroupSessions, so a targeted UPDATE is enough — no
            // need to read each one back first (that per-session lookup dominated a large backup restore).
            olmInboundGroupSessionWrappers.forEach { wrapper ->
                val sessionId = tryOrNull("markBackupDone sessionIdentifier") { wrapper.session.sessionIdentifier() } ?: return@forEach
                megolmStore.markBackedUp(OlmInboundGroupSessionEntity.createPrimaryKey(sessionId, wrapper.sessionData.senderKey))
            }
        }
    }

    override fun inboundGroupSessionsToBackup(limit: Int): List<MXInboundMegolmSessionWrapper> = megolmStore.getNotBackedUp(limit).mapNotNull { it.toModelWrapper() }
    override fun inboundGroupSessionsCount(onlyBackedUp: Boolean): Int = megolmStore.count(onlyBackedUp)

    // ==================== Megolm outbound sessions ====================

    override fun getCurrentOutboundGroupSessionForRoom(roomId: String): OutboundGroupSessionWrapper? {
        val info = roomStore.getOutboundInfo(roomId) ?: return null
        val session = deserializeFromRealm<OlmOutboundGroupSession>(info.serialized) ?: return null
        return OutboundGroupSessionWrapper(session, info.creationTime ?: 0, info.shouldShareHistory)
    }

    override fun storeCurrentOutboundGroupSessionForRoom(roomId: String, outboundGroupSession: OlmOutboundGroupSession?) {
        if (outboundGroupSession != null) {
            roomStore.storeOutbound(roomId, serializeForRealm(outboundGroupSession), clock.epochMillis(), roomStore.getRoomShouldShareHistory(roomId))
        } else {
            roomStore.clearOutbound(roomId)
        }
    }

    // ==================== Devices ====================

    override fun getUserDevice(userId: String, deviceId: String): CryptoDeviceInfo? = deviceStore.getUserDevice(userId, deviceId)
    override fun deviceWithIdentityKey(identityKey: String): CryptoDeviceInfo? = deviceStore.deviceWithIdentityKey(identityKey)
    override fun deviceWithIdentityKey(userId: String, identityKey: String): CryptoDeviceInfo? = deviceStore.deviceWithIdentityKey(userId, identityKey)
    override fun storeUserDevices(userId: String, devices: Map<String, CryptoDeviceInfo>?) = deviceStore.storeUserDevices(userId, devices, clock.epochMillis())
    override fun getUserDevices(userId: String): Map<String, CryptoDeviceInfo>? = deviceStore.getUserDevices(userId)
    override fun getUserDeviceList(userId: String): List<CryptoDeviceInfo>? = deviceStore.getUserDeviceList(userId)

    override fun getDeviceListFlow(userId: String): Flow<List<CryptoDeviceInfo>> =
            database.cryptoDevicesQueries.deviceSelectByUserId(userId).asFlow().mapToList(dispatcher).map { it.map(deviceStore::mapDeviceRow) }.flowOn(dispatcher)

    override fun getDeviceListFlow(userIds: List<String>): Flow<List<CryptoDeviceInfo>> =
            database.cryptoDevicesQueries.deviceSelectByUserIds(userIds.distinct()).asFlow().mapToList(dispatcher).map { it.map(deviceStore::mapDeviceRow) }.flowOn(dispatcher)

    override fun getDeviceListFlow(): Flow<List<CryptoDeviceInfo>> =
            database.cryptoDevicesQueries.deviceSelectAll().asFlow().mapToList(dispatcher).map { it.map(deviceStore::mapDeviceRow) }.flowOn(dispatcher)

    override fun getDeviceWithIdFlow(deviceId: String): Flow<Optional<CryptoDeviceInfo>> =
            getDeviceListFlow().map { devices -> devices.firstOrNull { it.deviceId == deviceId }.toOptional() }

    override fun getDeviceTrackingStatuses(): Map<String, Int> = deviceStore.getDeviceTrackingStatuses()
    override fun saveDeviceTrackingStatuses(deviceTrackingStatuses: Map<String, Int>) = deviceStore.saveDeviceTrackingStatuses(deviceTrackingStatuses)
    override fun getDeviceTrackingStatus(userId: String, defaultValue: Int): Int = deviceStore.getDeviceTrackingStatus(userId, defaultValue)

    override fun saveMyDevicesInfo(info: List<DeviceInfo>) = deviceStore.saveMyDevicesInfo(info)
    override fun getMyDevicesInfo(): List<DeviceInfo> = deviceStore.getMyDevicesInfo()

    override fun getMyDevicesInfoFlow(): Flow<List<DeviceInfo>> =
            database.cryptoDevicesQueries.myDeviceSelectAll().asFlow().mapToList(dispatcher).map { it.map(deviceStore::mapMyDeviceRow) }.flowOn(dispatcher)

    override fun getMyDevicesInfoFlow(deviceId: String): Flow<Optional<DeviceInfo>> =
            database.cryptoDevicesQueries.myDeviceSelectByDeviceId(deviceId).asFlow().mapToOneOrNull(dispatcher).map { it?.let(deviceStore::mapMyDeviceRow).toOptional() }.flowOn(dispatcher)

    // ==================== Room crypto ====================

    override fun storeRoomAlgorithm(roomId: String, algorithm: String?) = roomStore.storeRoomAlgorithm(roomId, algorithm)
    override fun getRoomAlgorithm(roomId: String): String? = roomStore.getRoomAlgorithm(roomId)
    override fun getRoomCryptoInfo(roomId: String): CryptoRoomInfo? = roomStore.getRoomCryptoInfo(roomId)
    override fun setAlgorithmInfo(roomId: String, encryption: EncryptionEventContent?) = roomStore.setAlgorithmInfo(roomId, encryption)
    override fun roomWasOnceEncrypted(roomId: String): Boolean = roomStore.roomWasOnceEncrypted(roomId)
    override fun shouldEncryptForInvitedMembers(roomId: String): Boolean = roomStore.shouldEncryptForInvitedMembers(roomId)
    override fun shouldShareHistory(roomId: String): Boolean = isShareKeysOnInviteEnabled() && roomStore.getRoomShouldShareHistory(roomId)
    override fun setShouldEncryptForInvitedMembers(roomId: String, shouldEncryptForInvitedMembers: Boolean) = roomStore.setShouldEncryptForInvitedMembers(roomId, shouldEncryptForInvitedMembers)
    override fun setShouldShareHistory(roomId: String, shouldShareHistory: Boolean) = roomStore.setShouldShareHistory(roomId, shouldShareHistory)
    override fun blockUnverifiedDevicesInRoom(roomId: String, block: Boolean) = roomStore.blockUnverifiedDevicesInRoom(roomId, block)
    override fun getBlockUnverifiedDevices(roomId: String): Boolean = roomStore.getBlockUnverifiedDevices(roomId)
    override fun getRoomsListBlacklistUnverifiedDevices(): List<String> = roomStore.getRoomsListBlacklistUnverifiedDevices()

    override fun getBlockUnverifiedDevicesFlow(roomId: String): Flow<Boolean> =
            database.cryptoRoomQueries.roomSelectById(roomId).asFlow().mapToOneOrNull(dispatcher).map { it?.blacklist_unverified_devices == 1L }.flowOn(dispatcher)

    // ==================== Cross signing ====================

    override fun getMyCrossSigningInfo(): MXCrossSigningInfo? = crossSigningStore.getCrossSigningInfo(userId)
    override fun setMyCrossSigningInfo(info: MXCrossSigningInfo?) = crossSigningStore.setCrossSigningInfo(userId, info)
    override fun getCrossSigningInfo(userId: String): MXCrossSigningInfo? = crossSigningStore.getCrossSigningInfo(userId)
    override fun getCrossSigningInfoUserIds(): List<String> = crossSigningStore.getAllUserIds()
    override fun setCrossSigningInfo(userId: String, info: MXCrossSigningInfo?) = crossSigningStore.setCrossSigningInfo(userId, info)
    override fun markMyMasterKeyAsLocallyTrusted(trusted: Boolean) = crossSigningStore.markMasterKeyAsLocallyTrusted(userId, trusted)
    override fun setUserKeysAsTrusted(userId: String, trusted: Boolean) = crossSigningStore.setUserKeysAsTrusted(userId, trusted)
    override fun setDeviceTrust(userId: String, deviceId: String, crossSignedVerified: Boolean, locallyVerified: Boolean?) = deviceStore.setDeviceTrust(userId, deviceId, crossSignedVerified, locallyVerified)
    override fun clearOtherUserTrust() = crossSigningStore.clearOtherUserTrust(userId)
    override fun updateUsersTrust(check: (String) -> Boolean) = crossSigningStore.updateUsersTrust(userId, check)

    override fun getCrossSigningInfoFlow(userId: String): Flow<Optional<MXCrossSigningInfo>> =
            database.cryptoCrossSigningQueries.keyInfoSelectByUser(userId).asFlow().mapToList(dispatcher).map { crossSigningStore.getCrossSigningInfo(userId).toOptional() }.flowOn(dispatcher)

    override fun storeUserIdentity(userId: String, userIdentity: UserIdentity) = doStoreUserIdentity(userId, userIdentity)

    private fun doStoreUserIdentity(userId: String, userIdentity: UserIdentity) {
        val masterKey = userIdentity.masterKey
        val selfSigningKey = userIdentity.selfSigningKey
        if (masterKey == null || selfSigningKey == null) {
            crossSigningStore.setCrossSigningInfo(userId, null)
            return
        }
        val existing = crossSigningStore.getCrossSigningInfo(userId)
        val masterChanged = existing?.masterKey()?.unpaddedBase64PublicKey != masterKey.unpaddedBase64PublicKey
        val selfChanged = existing?.selfSigningKey()?.unpaddedBase64PublicKey != selfSigningKey.unpaddedBase64PublicKey
        val userSigningKey = userIdentity.userSigningKey
        val userChanged = userSigningKey != null && existing?.userKey()?.unpaddedBase64PublicKey != userSigningKey.unpaddedBase64PublicKey

        database.transaction {
            var shouldResetMyDevicesLocalTrust = false
            if (userId == this@SqlCryptoStore.userId) {
                if (masterChanged) { metadataStore.storeMSKPrivateKey(null); shouldResetMyDevicesLocalTrust = true }
                if (selfChanged) { metadataStore.storeSSKPrivateKey(null); shouldResetMyDevicesLocalTrust = true }
                if (userChanged) { metadataStore.storeUSKPrivateKey(null); shouldResetMyDevicesLocalTrust = true }
            }
            crossSigningStore.storeUserIdentityKeys(userId, listOfNotNull(masterKey, selfSigningKey, userIdentity.userSigningKey))
            if (shouldResetMyDevicesLocalTrust) {
                deviceStore.resetMyDevicesLocalTrust(this@SqlCryptoStore.userId, deviceId)
            }
        }
    }

    // ==================== Withheld / shared ====================

    override fun addWithHeldMegolmSession(withHeldContent: RoomKeyWithHeldContent) {
        val roomId = withHeldContent.roomId ?: return
        val sessionId = withHeldContent.sessionId ?: return
        if (withHeldContent.algorithm != org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM) return
        roomStore.addWithHeld(roomId, sessionId, withHeldContent.senderKey, withHeldContent.code?.value, withHeldContent.reason)
    }

    override fun getWithHeldMegolmSession(roomId: String, sessionId: String): RoomKeyWithHeldContent? =
            roomStore.getWithHeld(roomId, sessionId)?.let {
                RoomKeyWithHeldContent(roomId = roomId, sessionId = sessionId, algorithm = it.algorithm, codeString = it.code_string, reason = it.reason, senderKey = it.sender_key)
            }

    override fun markedSessionAsShared(roomId: String?, sessionId: String, userId: String, deviceId: String, deviceIdentityKey: String, chainIndex: Int) =
            roomStore.markedSessionAsShared(roomId, sessionId, userId, deviceId, deviceIdentityKey, chainIndex)

    override fun getSharedSessionInfo(roomId: String?, sessionId: String, deviceInfo: CryptoDeviceInfo): IMXCryptoStore.SharedSessionResult {
        val row = roomStore.getSharedSession(roomId, sessionId, deviceInfo.userId, deviceInfo.deviceId, deviceInfo.identityKey())
        return if (row != null) IMXCryptoStore.SharedSessionResult(true, row.chain_index?.toInt()) else IMXCryptoStore.SharedSessionResult(false, null)
    }

    override fun getSharedWithInfo(roomId: String?, sessionId: String): MXUsersDevicesMap<Int> {
        val result = MXUsersDevicesMap<Int>()
        roomStore.getSharedSessions(roomId, sessionId).forEach { row ->
            val rowUserId = row.user_id ?: return@forEach
            val rowDeviceId = row.device_id ?: return@forEach
            result.setObject(rowUserId, rowDeviceId, row.chain_index?.toInt())
        }
        return result
    }

    // ==================== Outgoing key requests + audit ====================

    override fun getOutgoingRoomKeyRequest(requestBody: RoomKeyRequestBody): OutgoingKeyRequest? = keyRequestStore.getOutgoingRoomKeyRequest(requestBody)
    override fun getOutgoingRoomKeyRequest(requestId: String): OutgoingKeyRequest? = keyRequestStore.getOutgoingRoomKeyRequest(requestId)
    override fun getOutgoingRoomKeyRequest(roomId: String, sessionId: String, algorithm: String, senderKey: String): List<OutgoingKeyRequest> = keyRequestStore.getOutgoingRoomKeyRequest(roomId, sessionId, algorithm, senderKey)
    override fun getOrAddOutgoingRoomKeyRequest(requestBody: RoomKeyRequestBody, recipients: Map<String, List<String>>, fromIndex: Int): OutgoingKeyRequest = keyRequestStore.getOrAddOutgoingRoomKeyRequest(requestBody, recipients, fromIndex)
    override fun updateOutgoingRoomKeyRequestState(requestId: String, newState: OutgoingRoomKeyRequestState) = keyRequestStore.updateOutgoingRoomKeyRequestState(requestId, newState)
    override fun updateOutgoingRoomKeyRequiredIndex(requestId: String, newIndex: Int) = keyRequestStore.updateOutgoingRoomKeyRequiredIndex(requestId, newIndex)
    override fun updateOutgoingRoomKeyReply(roomId: String, sessionId: String, algorithm: String, senderKey: String, fromDevice: String?, event: Event) = keyRequestStore.updateOutgoingRoomKeyReply(roomId, sessionId, algorithm, senderKey, fromDevice, event)
    override fun deleteOutgoingRoomKeyRequest(requestId: String) = keyRequestStore.deleteOutgoingRoomKeyRequest(requestId)
    override fun deleteOutgoingRoomKeyRequestInState(state: OutgoingRoomKeyRequestState) = keyRequestStore.deleteOutgoingRoomKeyRequestInState(state)
    override fun getOutgoingRoomKeyRequests(): List<OutgoingKeyRequest> = keyRequestStore.getOutgoingRoomKeyRequests()
    override fun getOutgoingRoomKeyRequests(inStates: Set<OutgoingRoomKeyRequestState>): List<OutgoingKeyRequest> = keyRequestStore.getOutgoingRoomKeyRequests(inStates)

    override fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingKeyRequest>> =
            livePaged(database.cryptoKeyRequestQueries.okrSelectAll()) { keyRequestStore.getOutgoingRoomKeyRequests() }

    override fun saveIncomingKeyRequestAuditTrail(requestId: String, roomId: String, sessionId: String, senderKey: String, algorithm: String, fromUser: String, fromDevice: String) =
            keyRequestStore.saveIncomingKeyRequestAuditTrail(requestId, roomId, sessionId, senderKey, algorithm, fromUser, fromDevice)

    override fun saveWithheldAuditTrail(roomId: String, sessionId: String, senderKey: String, algorithm: String, code: WithHeldCode, userId: String, deviceId: String) =
            keyRequestStore.saveWithheldAuditTrail(roomId, sessionId, senderKey, algorithm, code, userId, deviceId)

    override fun saveForwardKeyAuditTrail(roomId: String, sessionId: String, senderKey: String, algorithm: String, userId: String, deviceId: String, chainIndex: Long?) =
            keyRequestStore.saveForwardKeyAuditTrail(roomId, sessionId, senderKey, algorithm, userId, deviceId, chainIndex, incoming = false)

    override fun saveIncomingForwardKeyAuditTrail(roomId: String, sessionId: String, senderKey: String, algorithm: String, userId: String, deviceId: String, chainIndex: Long?) =
            keyRequestStore.saveForwardKeyAuditTrail(roomId, sessionId, senderKey, algorithm, userId, deviceId, chainIndex, incoming = true)

    override fun getGossipingEventsTrail(): LiveData<PagedList<AuditTrail>> =
            livePaged(database.cryptoBackupAuditQueries.auditSelectAllOrdered()) { keyRequestStore.getOrderedAuditTrails() }

    override fun <T> getGossipingEventsTrail(type: TrailType, mapper: (AuditTrail) -> T): LiveData<PagedList<T>> =
            livePaged(database.cryptoBackupAuditQueries.auditSelectByTypeOrdered(type.name)) { keyRequestStore.getOrderedAuditTrailsByType(type).map(mapper) }

    override fun getGossipingEvents(): List<AuditTrail> = keyRequestStore.getGossipingEvents()

    // ==================== Session listeners ====================

    override fun addNewSessionListener(listener: NewSessionListener) {
        if (!newSessionListeners.contains(listener)) newSessionListeners.add(listener)
    }

    override fun removeSessionListener(listener: NewSessionListener) {
        newSessionListeners.remove(listener)
    }

    // ==================== Bulk store ====================

    override fun storeData(userDataToStore: UserDataToStore) {
        database.transaction {
            userDataToStore.userDevices.forEach { deviceStore.storeUserDevices(it.key, it.value, clock.epochMillis()) }
            userDataToStore.userIdentities.forEach { doStoreUserIdentity(it.key, it.value) }
        }
    }

    override fun storeData(cryptoStoreAggregator: CryptoStoreAggregator) {
        if (cryptoStoreAggregator.isEmpty()) return
        database.transaction {
            cryptoStoreAggregator.setShouldShareHistoryData.forEach { roomStore.setShouldShareHistory(it.key, it.value) }
            cryptoStoreAggregator.setShouldEncryptForInvitedMembersData.forEach { roomStore.setShouldEncryptForInvitedMembers(it.key, it.value) }
        }
    }

    // ==================== Helpers ====================

    private fun Olm_inbound_group_session.toModelWrapper(): MXInboundMegolmSessionWrapper? = OlmInboundGroupSessionEntity(
            primaryKey = primary_key,
            sessionId = session_id,
            senderKey = sender_key,
            roomId = room_id,
            inboundGroupSessionDataJson = inbound_group_session_data_json,
            serializedOlmInboundGroupSession = serialized_olm_inbound_group_session,
            sharedHistory = shared_history == 1L,
            backedUp = backed_up == 1L,
    ).toModel()

}
