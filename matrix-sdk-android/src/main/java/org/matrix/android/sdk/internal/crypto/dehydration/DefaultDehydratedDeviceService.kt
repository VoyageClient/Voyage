/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

import dagger.Lazy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.accountdata.SessionAccountDataService
import org.matrix.android.sdk.api.session.crypto.dehydration.DehydratedDeviceService
import org.matrix.android.sdk.api.session.crypto.dehydration.DehydratedDeviceService.RehydrationResult
import org.matrix.android.sdk.api.session.securestorage.KeyRef
import org.matrix.android.sdk.api.session.securestorage.SharedSecretStorageService
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toBase64NoPadding
import org.matrix.android.sdk.internal.crypto.DefaultCryptoService
import org.matrix.android.sdk.internal.crypto.dehydration.model.DehydratedDeviceResponse
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.olm.OlmAccount
import timber.log.Timber
import java.security.SecureRandom
import javax.inject.Inject

private val loggerTag = LoggerTag("DehydratedDeviceService", LoggerTag.CRYPTO)

@SessionScope
internal class DefaultDehydratedDeviceService @Inject constructor(
        private val dehydratedDeviceApi: DehydratedDeviceApi,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val sharedSecretStorageService: Lazy<SharedSecretStorageService>,
        private val accountDataService: Lazy<SessionAccountDataService>,
        private val cryptoService: Lazy<DefaultCryptoService>,
        private val creator: DehydratedDeviceCreator,
        private val rehydrator: DehydratedDeviceRehydrator,
) : DehydratedDeviceService {

    private val mutex = Mutex()

    override suspend fun rehydrateDevice(): RehydrationResult = mutex.withLock {
        val stored = when (val fetched = getStoredDevice()) {
            is StoredDevice.Unsupported -> return RehydrationResult.Unsupported
            is StoredDevice.None -> return RehydrationResult.NothingToRehydrate
            is StoredDevice.Found -> fetched.response
        }
        val deviceId = stored.deviceId
        val data = stored.deviceData
        if (deviceId.isNullOrEmpty() || data?.algorithm !in DehydratedDeviceConstants.SUPPORTED_ALGORITHMS) {
            Timber.tag(loggerTag.value).w("Ignoring a dehydrated device with algorithm ${data?.algorithm}")
            return RehydrationResult.NothingToRehydrate
        }
        val pickle = data?.devicePickle
        val nonce = data?.nonce
        if (pickle.isNullOrEmpty() || nonce.isNullOrEmpty()) {
            return RehydrationResult.NothingToRehydrate
        }
        val key = readDehydrationKey() ?: return RehydrationResult.KeyUnavailable

        val account = try {
            withContext(coroutineDispatchers.crypto) {
                OlmAccount.rehydrate(key, nonce.toByteArray(Charsets.US_ASCII), pickle.toByteArray(Charsets.US_ASCII))
            }
        } catch (failure: Throwable) {
            Timber.tag(loggerTag.value).e(failure, "Failed to rehydrate the device")
            return RehydrationResult.NothingToRehydrate
        }

        return try {
            val count = rehydrator.rehydrate(deviceId, account) { event ->
                cryptoService.get().onToDeviceEvent(event)
            }
            Timber.tag(loggerTag.value).i("Rehydrated $deviceId, picked up $count event(s)")
            RehydrationResult.Rehydrated(deviceId, count)
        } finally {
            account.releaseAccount()
        }
    }

    override suspend fun createDehydratedDevice(displayName: String?): String = mutex.withLock {
        val key = readDehydrationKey() ?: storeNewDehydrationKey()
        val body = withContext(coroutineDispatchers.crypto) {
            creator.create(key, displayName ?: DehydratedDeviceConstants.DEFAULT_DISPLAY_NAME)
        }
        executeRequest(globalErrorReceiver) { dehydratedDeviceApi.putDehydratedDevice(body) }
        Timber.tag(loggerTag.value).i("Stored a new dehydrated device ${body.deviceId}")
        return body.deviceId
    }

    override suspend fun startDehydration(displayName: String?): RehydrationResult {
        val result = rehydrateDevice()
        val shouldReplace = when (result) {
            RehydrationResult.Unsupported,
            // Replacing the device now would strand the events the stored one is holding.
            RehydrationResult.KeyUnavailable -> false
            RehydrationResult.NothingToRehydrate -> true
            // A device that handed us messages has spent the one-time keys they were sent to, so it
            // is replaced. One that had nothing waiting is still perfectly good, and a new one would
            // cost an upload of fifty signed keys for nothing.
            is RehydrationResult.Rehydrated -> result.eventCount > 0
        }
        if (!shouldReplace) return result
        if (sharedSecretStorageService.get().getCachedKeySpec() == null) return RehydrationResult.KeyUnavailable
        createDehydratedDevice(displayName)
        return result
    }

    private sealed interface StoredDevice {
        object Unsupported : StoredDevice
        object None : StoredDevice
        data class Found(val response: DehydratedDeviceResponse) : StoredDevice
    }

    private suspend fun getStoredDevice(): StoredDevice = try {
        StoredDevice.Found(executeRequest(globalErrorReceiver) { dehydratedDeviceApi.getDehydratedDevice() })
    } catch (failure: Throwable) {
        when {
            failure.isUnknownEndpoint() -> StoredDevice.Unsupported
            failure.isNotFound() -> StoredDevice.None
            else -> throw failure
        }
    }

    private suspend fun readDehydrationKey(): ByteArray? {
        val (keyId, keySpec) = sharedSecretStorageService.get().getCachedKeySpec() ?: return null
        val secretName = DehydratedDeviceConstants.SECRET_NAMES
                .firstOrNull { accountDataService.get().getUserAccountDataEvent(it) != null }
                ?: return null
        return try {
            sharedSecretStorageService.get().getSecret(secretName, keyId, keySpec).fromBase64()
        } catch (failure: Throwable) {
            Timber.tag(loggerTag.value).w(failure, "Failed to read the dehydration key")
            null
        }
    }

    private suspend fun storeNewDehydrationKey(): ByteArray {
        val (keyId, keySpec) = sharedSecretStorageService.get().getCachedKeySpec()
                ?: throw IllegalStateException("Secret storage is locked, cannot store a dehydration key")
        val key = ByteArray(DehydratedDeviceConstants.KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        val encoded = key.toBase64NoPadding()
        DehydratedDeviceConstants.SECRET_NAMES.forEach {
            sharedSecretStorageService.get().storeSecret(it, encoded, listOf(KeyRef(keyId, keySpec)))
        }
        return key
    }
}

private fun Throwable.isUnknownEndpoint() =
        this is Failure.ServerError && error.code == MatrixError.M_UNRECOGNIZED

private fun Throwable.isNotFound() =
        this is Failure.ServerError && (error.code == MatrixError.M_NOT_FOUND || httpCode == 404)
