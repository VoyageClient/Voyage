/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.pgp

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import org.openintents.openpgp.util.OpenPgpUtils
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Talks to OpenKeychain through the OpenPGP API (AIDL, works down to API 14). The binding is
 * kept alive for the whole process and lazily (re)established. All crypto calls are blocking
 * and dispatched on IO; callers should treat them as suspend functions.
 *
 * Nothing here touches Matrix E2EE — this operates on plain message bodies only.
 */
@Singleton
class PgpServiceManager @Inject constructor(
        private val context: Context,
) {
    companion object {
        const val OPENKEYCHAIN_PACKAGE = "org.sufficientlysecure.keychain"
    }

    @Volatile private var connection: OpenPgpServiceConnection? = null
    @Volatile private var api: OpenPgpApi? = null
    private val bindMutex = Mutex()

    // OpenKeychain serializes requests on its own binder thread anyway; cap our own fan-out so that
    // opening a room full of PGP messages doesn't launch dozens of IO coroutines all blocked in a
    // synchronous binder transaction (which starves the shared Dispatchers.IO pool used by sync/DB
    // and was causing main-thread input timeouts).
    private val executeSemaphore = Semaphore(2)

    // App-wide cache of armored block -> decrypted plaintext, shared by every surface (timeline,
    // room list, replies, action sheet, …) so a message is only decrypted once.
    private val decryptionCache = ConcurrentHashMap<String, String>()

    fun isOpenKeychainInstalled(): Boolean = OpenPgpUtils.isAvailable(context)

    fun keyIdToHex(keyId: Long): String = OpenPgpUtils.convertKeyIdToHex(keyId)

    /** Synchronous cache lookup; null if this block hasn't been decrypted yet. */
    fun peekDecrypted(armored: String): String? = decryptionCache[armored]

    suspend fun decrypt(armored: String): PgpResult {
        decryptionCache[armored]?.let { return PgpResult.Success(it) }
        // Repair non-RFC-compliant armor (missing header/data blank line) before OpenKeychain sees
        // it; cache by the original so callers' body.replace(armored, plain) still matches the wire.
        val result = executeRaw(Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY), PgpUtils.repairArmor(armored).toByteArray(Charsets.UTF_8)).toPgpResult()
        if (result is PgpResult.Success) decryptionCache[armored] = result.data
        return result
    }

    suspend fun encrypt(plain: String, recipientUserIds: List<String>, recipientKeyIds: LongArray, signKeyId: Long): PgpResult {
        val intent = Intent(OpenPgpApi.ACTION_SIGN_AND_ENCRYPT)
        if (recipientUserIds.isNotEmpty()) {
            intent.putExtra(OpenPgpApi.EXTRA_USER_IDS, recipientUserIds.toTypedArray())
        }
        if (recipientKeyIds.isNotEmpty()) {
            intent.putExtra(OpenPgpApi.EXTRA_KEY_IDS, recipientKeyIds)
        }
        intent.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, signKeyId)
        intent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        return executeRaw(intent, plain.toByteArray(Charsets.UTF_8)).toPgpResult()
    }

    /**
     * Resolves recipient addresses to OpenKeychain key ids, **one address at a time**, so that an
     * address without a (uniquely) resolvable key is silently skipped instead of forcing the user
     * through OpenKeychain's recipient picker. We never launch the picker here — a non-success
     * result just means "no usable key for this address", and that recipient is dropped.
     */
    suspend fun resolveRecipientKeyIds(addresses: List<String>): LongArray {
        val resolved = LinkedHashSet<Long>()
        for (address in addresses) {
            val intent = Intent(OpenPgpApi.ACTION_GET_KEY_IDS).apply {
                putExtra(OpenPgpApi.EXTRA_USER_IDS, arrayOf(address))
            }
            val raw = executeRaw(intent, null)
            if (raw.code == OpenPgpApi.RESULT_CODE_SUCCESS) {
                val ids = raw.resultIntent?.getLongArrayExtra(OpenPgpApi.RESULT_KEY_IDS)?.toList().orEmpty()
                resolved.addAll(ids)
            }
        }
        return resolved.toLongArray()
    }

    /**
     * Resolves the user's own signing key. The first call returns [PgpKeyResult.NeedsInteraction]
     * with a key-picker PendingIntent; re-call with the returned data Intent to read the chosen
     * key id.
     */
    suspend fun requestSignKeyId(resultData: Intent?): PgpKeyResult {
        val intent = (resultData ?: Intent()).apply { action = OpenPgpApi.ACTION_GET_SIGN_KEY_ID }
        val raw = executeRaw(intent, null)
        return when (raw.code) {
            OpenPgpApi.RESULT_CODE_SUCCESS ->
                PgpKeyResult.Success(raw.resultIntent?.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0L) ?: 0L)
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
                raw.pendingIntent?.let { PgpKeyResult.NeedsInteraction(it) } ?: PgpKeyResult.Error("User interaction required")
            else -> PgpKeyResult.Error(raw.errorMessage ?: "Could not get key from OpenKeychain")
        }
    }

    private data class RawPgp(
            val code: Int,
            val resultIntent: Intent?,
            val output: ByteArray,
            val pendingIntent: PendingIntent?,
            val errorMessage: String?,
    )

    @Suppress("DEPRECATION")
    private suspend fun executeRaw(intent: Intent, input: ByteArray?): RawPgp = withContext(Dispatchers.IO) {
        executeSemaphore.withPermit { executeRawLocked(intent, input) }
    }

    @Suppress("DEPRECATION")
    private suspend fun executeRawLocked(intent: Intent, input: ByteArray?): RawPgp {
        val openPgpApi = ensureApi() ?: return RawPgp(
                OpenPgpApi.RESULT_CODE_ERROR, null, ByteArray(0), null,
                if (isOpenKeychainInstalled()) "Could not connect to OpenKeychain" else "OpenKeychain is not installed"
        ).also { Timber.w("PGP executeRaw: no API (action=${intent.action})") }
        intent.putExtra(OpenPgpApi.EXTRA_API_VERSION, OpenPgpApi.API_VERSION)
        val out = ByteArrayOutputStream()
        val result = try {
            openPgpApi.executeApi(intent, ByteArrayInputStream(input ?: ByteArray(0)), out)
        } catch (t: Throwable) {
            Timber.w(t, "PGP executeApi failed (action=${intent.action})")
            return RawPgp(OpenPgpApi.RESULT_CODE_ERROR, null, ByteArray(0), null, t.message ?: "PGP operation failed")
        }
        val code = result?.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) ?: OpenPgpApi.RESULT_CODE_ERROR
        val pendingIntent = result?.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
        val error = result?.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
        return RawPgp(code, result, out.toByteArray(), pendingIntent, error?.message)
    }

    private fun RawPgp.toPgpResult(): PgpResult = when (code) {
        OpenPgpApi.RESULT_CODE_SUCCESS -> PgpResult.Success(String(output, Charsets.UTF_8))
        OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED ->
            pendingIntent?.let { PgpResult.NeedsInteraction(it) } ?: PgpResult.Error("User interaction required")
        else -> PgpResult.Error(errorMessage ?: "PGP operation failed")
    }

    private suspend fun ensureApi(): OpenPgpApi? {
        connection?.takeIf { it.isBound }?.let { bound ->
            return api ?: OpenPgpApi(context, bound.service).also { api = it }
        }
        return bindMutex.withLock {
            connection?.takeIf { it.isBound }?.let { bound ->
                return@withLock api ?: OpenPgpApi(context, bound.service).also { api = it }
            }
            if (!isOpenKeychainInstalled()) {
                Timber.w("PGP ensureApi: OpenKeychain not installed")
                return@withLock null
            }
            val service = bindBlocking() ?: return@withLock null
            OpenPgpApi(context, service).also { api = it }
        }
    }

    private suspend fun bindBlocking(): IOpenPgpService2? = suspendCancellableCoroutine { cont ->
        val conn = OpenPgpServiceConnection(
                context.applicationContext,
                OPENKEYCHAIN_PACKAGE,
                object : OpenPgpServiceConnection.OnBound {
                    override fun onBound(service: IOpenPgpService2) {
                        if (cont.isActive) cont.resume(service)
                    }

                    override fun onError(e: Exception) {
                        Timber.w(e, "Failed to bind to OpenKeychain")
                        if (cont.isActive) cont.resume(null)
                    }
                }
        )
        connection = conn
        try {
            conn.bindToService()
        } catch (t: Throwable) {
            Timber.w(t, "bindToService threw")
            if (cont.isActive) cont.resume(null)
        }
    }
}
