/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import im.vector.app.features.settings.useragent.data.UaDataRepository
import im.vector.app.features.settings.useragent.data.UaProviderIds
import im.vector.app.features.settings.useragent.data.mostPopularValue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the spoofed User-Agent for the selected client from its field values. There are no
 * hardcoded version seeds: a field the user hasn't set resolves to the most popular value from the
 * live-fetched cache (or a fixed choice for enum fields like OS/scale). Returns null when spoofing is
 * off, which the interceptor reads as "leave the UA alone".
 */
@Singleton
class UserAgentSpoofBuilder @Inject constructor(
        private val settings: UserAgentSettings,
        private val repository: UaDataRepository,
) {

    /** The UA to send for [surface], or null if nothing should be overridden. Always reads the active account. */
    fun buildFor(surface: UaSurface): String? {
        val scope = settings.sessionScope()
        val client = settings.selectedClientFor(scope)
        if (client == UaSpoofClient.NONE) return null
        if (surface !in settings.surfaces(client, scope)) return null
        return build(client, scope)
    }

    /** The assembled UA for a client regardless of surface — used for the settings preview. */
    fun build(client: UaSpoofClient, scope: String = settings.editScope()): String? {
        if (client == UaSpoofClient.NONE) return null
        fun v(field: UaField) = resolvedValue(client, field, scope)
        return when (client) {
            UaSpoofClient.NONE -> null
            UaSpoofClient.CHROME ->
                "Mozilla/5.0 (${osToken(v(UaField.OS), v(UaField.OS_VERSION))}) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/${v(UaField.BROWSER_VERSION)} Safari/537.36"
            UaSpoofClient.FIREFOX -> {
                val rv = v(UaField.BROWSER_VERSION).substringBefore(".").let { "$it.0" }
                "Mozilla/5.0 (${osToken(v(UaField.OS), v(UaField.OS_VERSION))}; rv:$rv) " +
                        "Gecko/20100101 Firefox/${v(UaField.BROWSER_VERSION)}"
            }
            UaSpoofClient.ELEMENT_DESKTOP -> {
                val appToken = if (v(UaField.SUFFIX) == "nightly") "ElementNightly" else "Element"
                "Mozilla/5.0 (${osToken(v(UaField.OS), v(UaField.OS_VERSION))}) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) $appToken/${v(UaField.APP_VERSION)} Chrome/${v(UaField.BROWSER_VERSION)} " +
                        "Electron/${v(UaField.ELECTRON_VERSION)} Safari/537.36"
            }
            UaSpoofClient.ELEMENT_X_ANDROID ->
                "Element X/${v(UaField.APP_VERSION)} (${deviceString(client, scope)}; " +
                        "Android ${v(UaField.ANDROID_VERSION)}; ${v(UaField.BUILD_ID)}; Sdk ${sdkSha(client, scope)})"
            UaSpoofClient.SCHILDICHAT_NEXT ->
                "${schildiAppName(v(UaField.SUFFIX))}/${v(UaField.APP_VERSION)} (${deviceString(client, scope)}; " +
                        "Android ${v(UaField.ANDROID_VERSION)}; ${v(UaField.BUILD_ID)}; Sdk ${sdkSha(client, scope)})"
            UaSpoofClient.ELEMENT_ANDROID_LEGACY -> {
                val name = if (v(UaField.SUFFIX) == "dbg") "Element dbg" else "Element"
                "$name/${v(UaField.APP_VERSION)} (${deviceString(client, scope)}; Android ${v(UaField.ANDROID_VERSION)}; " +
                        "${v(UaField.BUILD_ID)}; Flavour ${v(UaField.FLAVOUR)}; MatrixAndroidSdk2 ${v(UaField.SDK_VERSION)})"
            }
            UaSpoofClient.SCHILDI_REVENGE -> "SchildiChat Revenge"
            UaSpoofClient.ELEMENT_X_IOS ->
                "Element X/${v(UaField.APP_VERSION)} (${v(UaField.IOS_DEVICE)}; " +
                        "iOS ${iosThreeComponent(v(UaField.IOS_VERSION))}; Scale/${v(UaField.SCALE)})"
            UaSpoofClient.ELEMENT_IOS_CLASSIC -> {
                val name = if (v(UaField.SUFFIX) == "alpha") "Element Alpha" else "Element Classic"
                "$name/${v(UaField.APP_VERSION)} (${v(UaField.IOS_DEVICE)}; " +
                        "iOS ${v(UaField.IOS_VERSION)}; Scale/${v(UaField.SCALE)})"
            }
            UaSpoofClient.NHEKO -> "mtxclient v${v(UaField.MTXCLIENT_VERSION)}"
            UaSpoofClient.GOMUKS ->
                "gomuks/v${v(UaField.GOMUKS_VERSION)} mautrix-go/v${v(UaField.MAUTRIX_VERSION)} go/${v(UaField.GO_VERSION)}"
            UaSpoofClient.COMMET -> "Dart/${v(UaField.DART_VERSION)} (dart:io)"
            UaSpoofClient.FRACTAL -> "matrix-rust-sdk"
            UaSpoofClient.IAMB -> "iamb"
            UaSpoofClient.TAMMY -> "Trixnity"
            UaSpoofClient.NEOCHAT -> "Mozilla/5.0"
            UaSpoofClient.CURL -> "curl/${v(UaField.CURL_VERSION)}"
            UaSpoofClient.CUSTOM -> v(UaField.CUSTOM_UA).takeIf { it.isNotBlank() }
        }
    }

    /**
     * With auto-upgrade on, software version fields always resolve to the newest available. Otherwise:
     * the user's stored value if set, else the live default (most popular, from cache).
     */
    fun resolvedValue(client: UaSpoofClient, field: UaField, scope: String = settings.editScope()): String =
            if (field in SOFTWARE_FIELDS && settings.autoUpgradeFor(client, scope)) newestValueFor(client, field, scope)
            else settings.storedValue(client, field, scope) ?: liveDefault(client, field, scope)

    /** The newest available value for a software field (highest version), used by upgrade + auto-upgrade. */
    fun newestValueFor(client: UaSpoofClient, field: UaField, scope: String = settings.editScope()): String {
        fun firstOf(providerId: String?) = providerId?.let { repository.cached(it).firstOrNull()?.value }.orEmpty()
        return when (field) {
            UaField.ELECTRON_VERSION -> repository.cachedElectron().firstOrNull()?.version.orEmpty()
            UaField.BROWSER_VERSION -> if (client == UaSpoofClient.ELEMENT_DESKTOP) desktopChrome(client, scope)
            else firstOf(client.providerIdFor(field, ""))
            UaField.IOS_VERSION -> iosVersionOptions(client, scope).firstOrNull()?.value.orEmpty()
            UaField.BUILD_ID -> {
                val major = resolvedValue(client, UaField.ANDROID_VERSION, scope).substringBefore(".").toIntOrNull() ?: -1
                repository.cachedBuildIds(deviceString(client, scope), major).firstOrNull()?.value.orEmpty()
            }
            UaField.OS_VERSION -> firstOf(client.providerIdFor(field, settings.storedValue(client, UaField.OS, scope) ?: UaOs.WINDOWS.value))
            else -> firstOf(client.providerIdFor(field, ""))
        }
    }

    /** Whether the user has explicitly set any field for this client (drives the reset button). */
    fun isModified(client: UaSpoofClient, scope: String = settings.editScope()): Boolean =
            client.fields.any { settings.storedValue(client, it, scope) != null }

    private fun liveDefault(client: UaSpoofClient, field: UaField, scope: String): String = when (field) {
        UaField.OS -> UaOs.WINDOWS.value
        UaField.SCALE -> "3.00"
        UaField.FLAVOUR -> "FDroid"
        UaField.SUFFIX -> client.suffixOptions.firstOrNull()?.value.orEmpty()
        UaField.CUSTOM_UA -> ""
        UaField.ELECTRON_VERSION -> repository.cachedElectron().firstOrNull()?.version.orEmpty()
        UaField.BROWSER_VERSION -> if (client == UaSpoofClient.ELEMENT_DESKTOP) desktopChrome(client, scope) else fromCache(client, field, scope)
        // Manufacturer = the top OEM in the device cache; model = that manufacturer's first model.
        UaField.DEVICE_MANUFACTURER -> deviceOptions().firstOrNull()?.let(::manufacturerOf).orEmpty()
        UaField.DEVICE_MODEL -> {
            val manufacturer = resolvedValue(client, UaField.DEVICE_MANUFACTURER, scope)
            deviceOptions().firstOrNull { manufacturerOf(it) == manufacturer }?.let(::modelOf).orEmpty()
        }
        UaField.BUILD_ID -> {
            val major = majorOf(resolvedValue(client, UaField.ANDROID_VERSION, scope))
            mostPopularValue(repository.cachedBuildIds(deviceString(client, scope), major)).orEmpty()
        }
        // An iPhone can't run an iOS older than the one it launched with.
        UaField.IOS_VERSION -> {
            val min = minIosMajor(resolvedValue(client, UaField.IOS_DEVICE, scope))
            mostPopularValue(repository.cached(UaProviderIds.IOS_VERSION).filter { majorOf(it.value) >= min }).orEmpty()
        }
        else -> fromCache(client, field, scope)
    }

    fun iosVersionOptions(client: UaSpoofClient, scope: String = settings.editScope()): List<im.vector.app.features.settings.useragent.data.UaOption> {
        val min = minIosMajor(resolvedValue(client, UaField.IOS_DEVICE, scope))
        return repository.cached(UaProviderIds.IOS_VERSION).filter { majorOf(it.value) >= min }
    }

    private fun majorOf(value: String) = value.substringBefore(".").toIntOrNull() ?: -1

    /** iPhone N shipped with iOS N+2 (iPhone 12→14, 15→17, 16→18). Non-iPhones aren't restricted. */
    private fun minIosMajor(device: String): Int =
            Regex("iPhone (\\d+)").find(device)?.groupValues?.get(1)?.toIntOrNull()?.let { it + 2 } ?: 0

    /** The rust-sdk sha for the client's app version, resolved+cached by the fragment. Blank until then. */
    fun sdkSha(client: UaSpoofClient, scope: String = settings.editScope()): String =
            settings.sdkShaFor(resolvedValue(client, UaField.APP_VERSION, scope)).orEmpty()

    /** The full "<manufacturer> <model>" device string used in the UA and for build-id matching. */
    fun deviceString(client: UaSpoofClient, scope: String = settings.editScope()): String =
            "${resolvedValue(client, UaField.DEVICE_MANUFACTURER, scope)} ${resolvedValue(client, UaField.DEVICE_MODEL, scope)}".trim()

    private fun deviceOptions() = repository.cached(UaProviderIds.DEVICE_MODEL)
    private fun manufacturerOf(option: im.vector.app.features.settings.useragent.data.UaOption) = option.value.substringBefore(" ")
    private fun modelOf(option: im.vector.app.features.settings.useragent.data.UaOption) = option.value.substringAfter(" ")

    private fun fromCache(client: UaSpoofClient, field: UaField, scope: String): String {
        val osValue = settings.storedValue(client, UaField.OS, scope) ?: UaOs.WINDOWS.value
        val providerId = client.providerIdFor(field, osValue) ?: return ""
        return mostPopularValue(repository.cached(providerId)).orEmpty()
    }

    private fun desktopChrome(client: UaSpoofClient, scope: String): String {
        val electron = resolvedValue(client, UaField.ELECTRON_VERSION, scope)
        return repository.cachedElectron().firstOrNull { it.version == electron }?.chrome.orEmpty()
    }

    private fun osToken(os: String, osVersion: String): String = when (UaOs.fromValue(os)) {
        // Windows always reports NT 10.0 in the UA, even on 11 — osVersion is cosmetic here.
        UaOs.WINDOWS -> "Windows NT 10.0; Win64; x64"
        UaOs.MACOS -> "Macintosh; Intel Mac OS X ${osVersion.ifBlank { "10_15_7" }}"
        UaOs.LINUX -> "X11; Linux x86_64"
    }

    private fun schildiAppName(suffix: String): String = when (suffix) {
        "beta" -> "SchildiChat Next (Beta)"
        "internal" -> "SchildiChat Next (Internal)"
        "dbg" -> "SchildiChat Next dbg"
        "nightly" -> "SchildiChat Next nightly"
        else -> "SchildiChat Next"
    }

    // Element X iOS reports a 3-component iOS version; the source supplies major.minor.
    private fun iosThreeComponent(version: String): String =
            if (version.matches(Regex("^\\d+\\.\\d+$"))) "$version.0" else version
}
