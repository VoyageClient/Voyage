/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.dispatchers.CoroutineDispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.profile.ColorPreference
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only projection of one signed-in account suitable for the account switcher. Holds no
 * homeserver-specific URLs or access tokens — anything beyond this struct must come from the
 * matching session itself, and we only build that session when the user explicitly switches
 * or signs out.
 */
data class AccountSnapshot(
        val sessionId: String,
        val userId: String,
        val displayName: String?,
        /** Non-null only when more than one stored account shares this exact MXID. */
        val homeServerHost: String?,
        /** The MSC4522 profile color this account had chosen while it was last active. */
        val colorPreference: ColorPreference?,
)

/**
 * Per-account profile cache — display name in SharedPreferences, avatar binary on disk —
 * populated only while a given account is the active session. The switcher reads from this
 * for non-active rows, so it never asks one homeserver to resolve or proxy another account's
 * media (which would leak the other account's existence to the active HS) and never
 * instantiates a non-active SessionComponent purely to read profile info.
 *
 * Lives under filesDir, not cacheDir, so it survives the cacheDir wipe in
 * MainActivity.doLocalCleanup at sign-out — those records belong to other accounts and must
 * persist. Entries are explicitly removed when the matching account is signed out.
 */
@Singleton
class AccountInfoCache @Inject constructor(
        @ApplicationContext private val context: Context,
        private val coroutineDispatchers: CoroutineDispatchers,
        private val authenticationService: AuthenticationService,
) {

    /**
     * All signed-in accounts on this device, with their locally-cached display name. Never
     * instantiates a non-active SessionComponent: pure Realm + SharedPreferences read.
     */
    suspend fun listAccounts(): List<AccountSnapshot> = withContext(coroutineDispatchers.io) {
        val all = authenticationService.getAllSessionParams()
        val userIdCounts = all.groupingBy { it.userId }.eachCount()
        all.map { params ->
            val sessionId = params.credentials.sessionId()
            val ambiguous = (userIdCounts[params.userId] ?: 0) > 1
            AccountSnapshot(
                    sessionId = sessionId,
                    userId = params.userId,
                    displayName = displayNameFor(sessionId),
                    colorPreference = colorPreferenceFor(sessionId),
                    homeServerHost = if (ambiguous) {
                        Uri.parse(params.homeServerConnectionConfig.homeServerUriBase).host
                                ?: Uri.parse(params.homeServerConnectionConfig.homeServerUri).host
                    } else null,
            )
        }
    }

    private val baseDir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun avatarFileFor(sessionId: String): File = File(baseDir, "$sessionId.bin")

    private fun tmpFileFor(sessionId: String): File = File(baseDir, "$sessionId.tmp")

    fun displayNameFor(sessionId: String): String? = prefs.getString(keyDisplayName(sessionId), null)

    fun colorPreferenceFor(sessionId: String): ColorPreference? = ColorPreference(
            onLight = prefs.getString(keyColorLight(sessionId), null),
            onDark = prefs.getString(keyColorDark(sessionId), null),
    ).takeIf { !it.isEmpty() }

    suspend fun storeForActive(session: Session) = withContext(coroutineDispatchers.io) {
        val user = runCatching { session.userService().getUser(session.myUserId) }.getOrNull()
        val name = user?.displayName?.takeIf { it.isNotBlank() }
        val mxc = user?.avatarUrl

        // The profile-field cache is in-memory: at session start it holds nothing yet, and a null
        // color then means "unknown", not "cleared" — only persist once the profile has been seen.
        val profileService = session.profileService()
        val color = profileService.getCachedColorPreference(session.myUserId)
        val colorKnown = color != null || profileService.getCachedProfile(session.myUserId) != null

        // Avatar — only re-download when the MXC URL actually changed (or got removed).
        // Avoids burning bandwidth on every display-name edit.
        val target = avatarFileFor(session.sessionId)
        val tmp = tmpFileFor(session.sessionId)
        val previousMxc = prefs.getString(keyAvatarMxc(session.sessionId), null)
        if (mxc.isNullOrBlank()) {
            target.delete()
        } else if (mxc != previousMxc || !target.exists()) {
            val resolved = session.contentUrlResolver().resolveFullSize(mxc)
            if (resolved != null) {
                val client = if (session.contentUrlResolver().requiresAuthentication(resolved)) {
                    session.getAuthenticatedOkHttpClient()
                } else {
                    session.getOkHttpClient()
                }
                runCatching {
                    client.newCall(Request.Builder().url(resolved).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching
                        tmp.outputStream().use { out -> resp.body()?.byteStream()?.copyTo(out) }
                        if (!tmp.renameTo(target)) tmp.delete()
                    }
                }.onFailure {
                    Timber.w(it, "AccountInfoCache: failed to cache avatar for ${session.sessionId}")
                    tmp.delete()
                }
            }
        }

        // Wrap the prefs write — a disk failure here would otherwise bubble up to the observer's
        // retryWhen and turn into a tight retry loop on permanently broken storage.
        runCatching {
            prefs.edit(commit = true) {
                if (name == null) remove(keyDisplayName(session.sessionId)) else putString(keyDisplayName(session.sessionId), name)
                if (mxc.isNullOrBlank()) remove(keyAvatarMxc(session.sessionId)) else putString(keyAvatarMxc(session.sessionId), mxc)
                if (colorKnown) {
                    if (color?.onLight == null) remove(keyColorLight(session.sessionId)) else putString(keyColorLight(session.sessionId), color.onLight)
                    if (color?.onDark == null) remove(keyColorDark(session.sessionId)) else putString(keyColorDark(session.sessionId), color.onDark)
                }
            }
        }.onFailure { Timber.w(it, "AccountInfoCache: failed to persist prefs for ${session.sessionId}") }
    }

    suspend fun delete(sessionId: String) = withContext(coroutineDispatchers.io) {
        avatarFileFor(sessionId).delete()
        tmpFileFor(sessionId).delete()
        runCatching {
            prefs.edit(commit = true) {
                remove(keyDisplayName(sessionId))
                remove(keyAvatarMxc(sessionId))
                remove(keyColorLight(sessionId))
                remove(keyColorDark(sessionId))
            }
        }.onFailure { Timber.w(it, "AccountInfoCache: failed to clear prefs for $sessionId") }
    }

    private fun keyDisplayName(sessionId: String) = "displayName_$sessionId"
    private fun keyAvatarMxc(sessionId: String) = "avatarMxc_$sessionId"
    private fun keyColorLight(sessionId: String) = "colorLight_$sessionId"
    private fun keyColorDark(sessionId: String) = "colorDark_$sessionId"

    companion object {
        private const val DIR_NAME = "account_info"
        private const val PREFS_NAME = "account_info_prefs"
    }
}
