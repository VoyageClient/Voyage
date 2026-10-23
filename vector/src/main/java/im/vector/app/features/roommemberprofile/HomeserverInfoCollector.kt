/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import im.vector.app.core.dialogs.InfoFieldsDialog
import im.vector.app.core.utils.TextUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.cache.CacheStrategy
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Developer-mode lookup of a user's homeserver, from its public unauthenticated endpoints only. */
class HomeserverInfoCollector @Inject constructor(
        private val context: Context,
        private val matrix: Matrix,
        private val session: Session,
) {

    enum class ClientUrlSource { WELL_KNOWN, SESSION, ASSUMED }

    data class ClientDiscovery(
            val url: String,
            val source: ClientUrlSource,
            val wellKnown: JSONObject?,
    )

    suspend fun discoverClientApi(serverName: String): ClientDiscovery {
        val wellKnown = fetchJson("https://${hostOf(serverName)}/.well-known/matrix/client")
        wellKnown?.optJSONObject("m.homeserver")?.optStringOrNull("base_url")?.let {
            return ClientDiscovery(it.trimEnd('/'), ClientUrlSource.WELL_KNOWN, wellKnown)
        }
        if (isOwnServer(serverName)) {
            return ClientDiscovery(session.sessionParams.homeServerUrlBase.trimEnd('/'), ClientUrlSource.SESSION, wellKnown)
        }
        return ClientDiscovery("https://$serverName", ClientUrlSource.ASSUMED, wellKnown)
    }

    /** Closes along with [lifecycleOwner], without calling [onDismiss]. */
    fun showDialog(context: Context, lifecycleOwner: LifecycleOwner, serverName: String, onDismiss: () -> Unit = {}) {
        var job: Job? = null
        var closeWithOwner: LifecycleEventObserver? = null
        val dialog = InfoFieldsDialog(context, CommonStrings.homeserver_info_title, asPopup = true, onDismiss = {
            job?.cancel()
            closeWithOwner?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            onDismiss()
        })
        closeWithOwner = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }.also { lifecycleOwner.lifecycle.addObserver(it) }
        dialog.showLoading()
        job = lifecycleOwner.lifecycleScope.launch {
            dialog.replaceAll(collect(serverName))
        }
    }

    private suspend fun collect(serverName: String): Map<String, String> = coroutineScope {
        val discovery = async { discoverClientApi(serverName) }
        // SRV records are skipped: Android has no DNS SRV API, and nearly every server delegates by well-known.
        val federationUrl = async { discoverFederationApi(serverName) }
        val software = async { softwareRows(federationUrl.await(), discovery.await().url) }
        val keys = async { keysRows(federationUrl.await()) }
        val versions = async { versionsRows(discovery.await().url) }
        val login = async { loginRows(discovery.await().url) }
        val auth = async { authRows(discovery.await().url, discovery.await().wellKnown) }
        val support = async { supportRows(serverName) }
        val own = if (isOwnServer(serverName)) ownServerRows() else emptyList()

        val rows = listOf(str(CommonStrings.homeserver_info_server_name) to serverName) +
                discoveryRows(discovery.await()) +
                software.await() +
                versions.await() +
                login.await() +
                auth.await() +
                support.await() +
                keys.await() +
                own
        rows.toMap(LinkedHashMap())
    }

    private fun discoveryRows(discovery: ClientDiscovery): List<Pair<String, String>> {
        val source = when (discovery.source) {
            ClientUrlSource.WELL_KNOWN -> CommonStrings.homeserver_info_discovery_well_known
            ClientUrlSource.SESSION -> CommonStrings.homeserver_info_discovery_session
            ClientUrlSource.ASSUMED -> CommonStrings.homeserver_info_discovery_assumed
        }
        return listOfNotNull(
                str(CommonStrings.homeserver_info_client_api) to discovery.url,
                str(CommonStrings.homeserver_info_discovery) to str(source),
                discovery.wellKnown?.optJSONObject("m.identity_server")?.optStringOrNull("base_url")
                        ?.let { str(CommonStrings.homeserver_info_identity_server) to it },
        )
    }

    private suspend fun softwareRows(federationUrl: String, clientUrl: String): List<Pair<String, String>> {
        // Federation is often reverse-proxied onto the client API host, so that is tried as well.
        val answered = listOf(federationUrl, clientUrl).distinct().firstNotNullOfOrNull { url ->
            fetchJson("$url/_matrix/federation/v1/version")?.optJSONObject("server")?.let { url to it }
        }
        val software = answered?.second
        return listOf(
                str(CommonStrings.homeserver_info_federation_api) to (answered?.first ?: federationUrl),
                str(CommonStrings.homeserver_info_software) to (software?.optStringOrNull("name") ?: unavailable()),
                str(CommonStrings.homeserver_info_version) to (software?.optStringOrNull("version") ?: unavailable()),
        )
    }

    private suspend fun discoverFederationApi(serverName: String): String {
        if (hasExplicitPort(serverName)) return "https://$serverName"
        val host = hostOf(serverName)
        if (!isIpLiteral(host)) {
            fetchJson("https://$host/.well-known/matrix/server")?.optStringOrNull("m.server")?.let { delegated ->
                return if (hasExplicitPort(delegated)) "https://$delegated" else "https://$delegated:$FEDERATION_DEFAULT_PORT"
            }
        }
        return "https://$serverName:$FEDERATION_DEFAULT_PORT"
    }

    private suspend fun versionsRows(clientUrl: String): List<Pair<String, String>> {
        val start = SystemClock.elapsedRealtime()
        val versions = fetchJson("$clientUrl/_matrix/client/versions")
        val elapsed = SystemClock.elapsedRealtime() - start
        versions ?: return listOf(str(CommonStrings.homeserver_info_spec_versions) to unavailable())
        val specVersions = versions.optJSONArray("versions").strings()
        val unstable = versions.optJSONObject("unstable_features")
                ?.let { features -> features.keys().asSequence().filter { features.optBoolean(it) }.sorted().toList() }
                .orEmpty()
        return listOfNotNull(
                str(CommonStrings.homeserver_info_spec_versions) to specVersions.joinToString(", ").ifEmpty { unavailable() },
                unstable.takeIf { it.isNotEmpty() }?.let {
                    context.getString(CommonStrings.homeserver_info_unstable_features, it.size) to it.joinToString("\n")
                },
                str(CommonStrings.homeserver_info_response_time) to context.getString(CommonStrings.homeserver_info_response_time_value, elapsed),
        )
    }

    private suspend fun loginRows(clientUrl: String): List<Pair<String, String>> {
        val login = fetchJson("$clientUrl/_matrix/client/v3/login") ?: fetchJson("$clientUrl/_matrix/client/r0/login")
        val flows = login?.optJSONArray("flows")
                ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optStringOrNull("type") } }
                .orEmpty()
        return listOf(str(CommonStrings.homeserver_info_login_methods) to flows.distinct().joinToString("\n").ifEmpty { unavailable() })
    }

    private suspend fun authRows(clientUrl: String, wellKnown: JSONObject?): List<Pair<String, String>> {
        val metadata = fetchJson("$clientUrl/_matrix/client/v1/auth_metadata")
                ?: fetchJson("$clientUrl/_matrix/client/unstable/org.matrix.msc2965/auth_metadata")
        val legacy = wellKnown?.optJSONObject("org.matrix.msc2965.authentication")
        val issuer = metadata?.optStringOrNull("issuer") ?: legacy?.optStringOrNull("issuer")
        val accountManagement = metadata?.optStringOrNull("account_management_uri") ?: legacy?.optStringOrNull("account")
        return listOfNotNull(
                issuer?.let { str(CommonStrings.homeserver_info_auth_issuer) to it },
                accountManagement?.let { str(CommonStrings.homeserver_info_account_management) to it },
        )
    }

    private suspend fun supportRows(serverName: String): List<Pair<String, String>> {
        val support = fetchJson("https://${hostOf(serverName)}/.well-known/matrix/support") ?: return emptyList()
        val contacts = support.optJSONArray("contacts")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val contact = array.optJSONObject(index) ?: return@mapNotNull null
                val handles = listOfNotNull(contact.optStringOrNull("matrix_id"), contact.optStringOrNull("email_address"))
                if (handles.isEmpty()) return@mapNotNull null
                val role = contact.optStringOrNull("role")?.removePrefix("m.role.")
                listOfNotNull(role, handles.joinToString(", ")).joinToString(": ")
            }
        }.orEmpty()
        return listOfNotNull(
                support.optStringOrNull("support_page")?.let { str(CommonStrings.homeserver_info_support) to it },
                contacts.takeIf { it.isNotEmpty() }?.let { str(CommonStrings.homeserver_info_contacts) to it.joinToString("\n") },
        )
    }

    private suspend fun keysRows(federationUrl: String): List<Pair<String, String>> {
        val keys = fetchJson("$federationUrl/_matrix/key/v2/server") ?: return emptyList()
        val verifyKeys = keys.optJSONObject("verify_keys")
                ?.let { obj -> obj.keys().asSequence().map { id -> "$id\n${obj.optJSONObject(id)?.optStringOrNull("key").orEmpty()}" }.toList() }
                .orEmpty()
        val validUntil = keys.optLong("valid_until_ts", 0L).takeIf { it > 0 }
        return listOfNotNull(
                verifyKeys.takeIf { it.isNotEmpty() }?.let { str(CommonStrings.homeserver_info_signing_keys) to it.joinToString("\n") },
                validUntil?.let {
                    str(CommonStrings.homeserver_info_keys_valid_until) to
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                },
        )
    }

    private fun ownServerRows(): List<Pair<String, String>> {
        val capabilities = session.homeServerCapabilitiesService().getHomeServerCapabilities()
        return listOfNotNull(
                capabilities.roomVersions?.defaultRoomVersion?.let { str(CommonStrings.homeserver_info_default_room_version) to it },
                capabilities.maxUploadFileSize.takeIf { it != HomeServerCapabilities.MAX_UPLOAD_FILE_SIZE_UNKNOWN }
                        ?.let { str(CommonStrings.homeserver_info_max_upload_size) to TextUtils.formatFileSize(context, it) },
        )
    }

    private suspend fun fetchJson(url: String): JSONObject? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        try {
            JSONObject(matrix.rawService().getUrl(url, CacheStrategy.NoCache))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The SDK's Failure types are Throwables, not Exceptions.
            null
        }
    }

    private fun isOwnServer(serverName: String) = session.myUserId.getServerName() == serverName

    private fun str(resId: Int) = context.getString(resId)

    private fun unavailable() = str(CommonStrings.homeserver_info_unavailable)

    private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONArray?.strings(): List<String> =
            this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()

    companion object {
        private const val FEDERATION_DEFAULT_PORT = 8448
        private const val REQUEST_TIMEOUT_MS = 15_000L

        /** The server name without its port, keeping an IPv6 literal's brackets. */
        fun hostOf(serverName: String): String =
                if (hasExplicitPort(serverName)) serverName.substringBeforeLast(':') else serverName

        fun hasExplicitPort(serverName: String): Boolean {
            val tail = serverName.substringAfterLast(':', missingDelimiterValue = "")
            // In an IPv6 literal only a colon after the closing bracket starts a port.
            return tail.isNotEmpty() && tail.all { it.isDigit() } &&
                    (!serverName.startsWith("[") || serverName.substringBeforeLast(':').endsWith("]"))
        }

        private fun isIpLiteral(host: String) = host.startsWith("[") || host.all { it.isDigit() || it == '.' }
    }
}
