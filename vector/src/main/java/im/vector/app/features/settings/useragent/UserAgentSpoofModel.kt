/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import androidx.annotation.StringRes
import im.vector.app.features.settings.useragent.data.UaProviderIds
import im.vector.lib.strings.CommonStrings

/** Which outgoing requests a chosen spoof applies to. */
enum class UaSurface(@StringRes val labelRes: Int) {
    /** Core client-server API + media/content, i.e. everything on the SDK OkHttp clients. */
    API_MEDIA(CommonStrings.settings_ua_surface_api),

    /** The SSO login WebView (its UA is otherwise a fixed string). */
    SSO_WEBVIEW(CommonStrings.settings_ua_surface_sso),
}

/**
 * A configurable component of a spoofed User-Agent. [prefKey] is the key of the single reusable
 * preference that edits this field; the stored value is namespaced per client by [UserAgentSettings].
 */
enum class UaField(val prefKey: String) {
    APP_VERSION("SETTINGS_UA_FIELD_APP_VERSION"),
    DEVICE_MANUFACTURER("SETTINGS_UA_FIELD_DEVICE_MANUFACTURER"),
    DEVICE_MODEL("SETTINGS_UA_FIELD_DEVICE_MODEL"),
    ANDROID_VERSION("SETTINGS_UA_FIELD_ANDROID_VERSION"),
    BUILD_ID("SETTINGS_UA_FIELD_BUILD_ID"),
    SDK_VERSION("SETTINGS_UA_FIELD_SDK_VERSION"),
    FLAVOUR("SETTINGS_UA_FIELD_FLAVOUR"),
    OS("SETTINGS_UA_FIELD_OS"),
    OS_VERSION("SETTINGS_UA_FIELD_OS_VERSION"),
    BROWSER_VERSION("SETTINGS_UA_FIELD_BROWSER_VERSION"),
    CURL_VERSION("SETTINGS_UA_FIELD_CURL_VERSION"),
    IOS_DEVICE("SETTINGS_UA_FIELD_IOS_DEVICE"),
    IOS_VERSION("SETTINGS_UA_FIELD_IOS_VERSION"),
    SCALE("SETTINGS_UA_FIELD_SCALE"),
    ELECTRON_VERSION("SETTINGS_UA_FIELD_ELECTRON_VERSION"),
    MTXCLIENT_VERSION("SETTINGS_UA_FIELD_MTXCLIENT_VERSION"),
    GOMUKS_VERSION("SETTINGS_UA_FIELD_GOMUKS_VERSION"),
    MAUTRIX_VERSION("SETTINGS_UA_FIELD_MAUTRIX_VERSION"),
    GO_VERSION("SETTINGS_UA_FIELD_GO_VERSION"),
    DART_VERSION("SETTINGS_UA_FIELD_DART_VERSION"),
    SUFFIX("SETTINGS_UA_FIELD_SUFFIX"),
    CUSTOM_UA("SETTINGS_UA_FIELD_CUSTOM_UA"),
}

/** Version fields that move forward with upgrades (software, not hardware) — targeted by the upgrade action. */
val SOFTWARE_FIELDS = setOf(
        UaField.APP_VERSION, UaField.ANDROID_VERSION, UaField.OS_VERSION, UaField.BROWSER_VERSION,
        UaField.CURL_VERSION, UaField.IOS_VERSION, UaField.ELECTRON_VERSION, UaField.MTXCLIENT_VERSION,
        UaField.GOMUKS_VERSION, UaField.MAUTRIX_VERSION, UaField.GO_VERSION, UaField.DART_VERSION,
        UaField.SDK_VERSION, UaField.BUILD_ID,
)

/** A build-variant suffix a client can carry (e.g. dbg/nightly/Beta). [value] is what gets stored. */
data class UaSuffix(val value: String, @StringRes val labelRes: Int)

/** Desktop OS families for browser/desktop profiles; [value] is stored, resolved to a UA token by the builder. */
enum class UaOs(val value: String, @StringRes val labelRes: Int) {
    WINDOWS("windows", CommonStrings.settings_ua_os_windows),
    MACOS("macos", CommonStrings.settings_ua_os_macos),
    LINUX("linux", CommonStrings.settings_ua_os_linux);

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: WINDOWS
    }
}

private val SUFFIX_NONE = UaSuffix("none", CommonStrings.settings_ua_suffix_none)
private val ALL_SURFACES = setOf(UaSurface.API_MEDIA, UaSurface.SSO_WEBVIEW)
private val API_ONLY = setOf(UaSurface.API_MEDIA)

/**
 * The catalog of clients whose User-Agent can be impersonated. Field metadata lives here; the string
 * assembly and per-field defaults live in [UserAgentSpoofBuilder]. Enum order is the picker order.
 */
enum class UaSpoofClient(
        val id: String,
        @StringRes val labelRes: Int,
        val fields: List<UaField>,
        val suffixOptions: List<UaSuffix> = emptyList(),
        val defaultSurfaces: Set<UaSurface>,
) {
    NONE("none", CommonStrings.settings_ua_client_none, emptyList(), defaultSurfaces = emptySet()),

    CHROME(
            "chrome", CommonStrings.settings_ua_client_chrome,
            listOf(UaField.BROWSER_VERSION, UaField.OS, UaField.OS_VERSION),
            defaultSurfaces = ALL_SURFACES,
    ),
    FIREFOX(
            "firefox", CommonStrings.settings_ua_client_firefox,
            listOf(UaField.BROWSER_VERSION, UaField.OS, UaField.OS_VERSION),
            defaultSurfaces = ALL_SURFACES,
    ),
    ELEMENT_DESKTOP(
            "element_desktop", CommonStrings.settings_ua_client_element_desktop,
            // Chrome is derived from the chosen Electron version, so it isn't a separate field.
            listOf(UaField.APP_VERSION, UaField.ELECTRON_VERSION, UaField.OS, UaField.OS_VERSION, UaField.SUFFIX),
            suffixOptions = listOf(SUFFIX_NONE, UaSuffix("nightly", CommonStrings.settings_ua_suffix_nightly)),
            defaultSurfaces = ALL_SURFACES,
    ),
    ELEMENT_X_ANDROID(
            "element_x_android", CommonStrings.settings_ua_client_element_x_android,
            listOf(UaField.APP_VERSION, UaField.DEVICE_MANUFACTURER, UaField.DEVICE_MODEL, UaField.ANDROID_VERSION, UaField.BUILD_ID),
            defaultSurfaces = API_ONLY,
    ),
    ELEMENT_X_IOS(
            "element_x_ios", CommonStrings.settings_ua_client_element_x_ios,
            listOf(UaField.APP_VERSION, UaField.IOS_DEVICE, UaField.IOS_VERSION, UaField.SCALE),
            defaultSurfaces = API_ONLY,
    ),
    ELEMENT_ANDROID_LEGACY(
            "element_android_legacy", CommonStrings.settings_ua_client_element_android_legacy,
            listOf(UaField.APP_VERSION, UaField.DEVICE_MANUFACTURER, UaField.DEVICE_MODEL, UaField.ANDROID_VERSION, UaField.BUILD_ID, UaField.FLAVOUR, UaField.SDK_VERSION, UaField.SUFFIX),
            suffixOptions = listOf(SUFFIX_NONE, UaSuffix("dbg", CommonStrings.settings_ua_suffix_dbg)),
            defaultSurfaces = API_ONLY,
    ),
    ELEMENT_IOS_CLASSIC(
            "element_ios_classic", CommonStrings.settings_ua_client_element_ios_classic,
            listOf(UaField.APP_VERSION, UaField.IOS_DEVICE, UaField.IOS_VERSION, UaField.SCALE, UaField.SUFFIX),
            suffixOptions = listOf(SUFFIX_NONE, UaSuffix("alpha", CommonStrings.settings_ua_suffix_alpha)),
            defaultSurfaces = API_ONLY,
    ),
    SCHILDICHAT_NEXT(
            "schildichat_next", CommonStrings.settings_ua_client_schildichat_next,
            listOf(UaField.APP_VERSION, UaField.DEVICE_MANUFACTURER, UaField.DEVICE_MODEL, UaField.ANDROID_VERSION, UaField.BUILD_ID, UaField.SUFFIX),
            suffixOptions = listOf(
                    SUFFIX_NONE,
                    UaSuffix("beta", CommonStrings.settings_ua_suffix_beta),
                    UaSuffix("internal", CommonStrings.settings_ua_suffix_internal),
                    UaSuffix("dbg", CommonStrings.settings_ua_suffix_dbg),
                    UaSuffix("nightly", CommonStrings.settings_ua_suffix_nightly),
            ),
            defaultSurfaces = API_ONLY,
    ),
    SCHILDI_REVENGE("schildi_revenge", CommonStrings.settings_ua_client_schildichat_revenge, emptyList(), defaultSurfaces = API_ONLY),

    NHEKO("nheko", CommonStrings.settings_ua_client_nheko, listOf(UaField.MTXCLIENT_VERSION), defaultSurfaces = API_ONLY),
    GOMUKS(
            "gomuks", CommonStrings.settings_ua_client_gomuks,
            listOf(UaField.GOMUKS_VERSION, UaField.MAUTRIX_VERSION, UaField.GO_VERSION),
            defaultSurfaces = API_ONLY,
    ),
    COMMET("commet", CommonStrings.settings_ua_client_commet, listOf(UaField.DART_VERSION), defaultSurfaces = API_ONLY),
    FRACTAL("fractal", CommonStrings.settings_ua_client_fractal, emptyList(), defaultSurfaces = API_ONLY),
    IAMB("iamb", CommonStrings.settings_ua_client_iamb, emptyList(), defaultSurfaces = API_ONLY),
    TAMMY("tammy", CommonStrings.settings_ua_client_tammy, emptyList(), defaultSurfaces = API_ONLY),
    NEOCHAT("neochat", CommonStrings.settings_ua_client_neochat, emptyList(), defaultSurfaces = API_ONLY),

    CURL("curl", CommonStrings.settings_ua_client_curl, listOf(UaField.CURL_VERSION), defaultSurfaces = ALL_SURFACES),
    CUSTOM("custom", CommonStrings.settings_ua_client_custom, listOf(UaField.CUSTOM_UA), defaultSurfaces = ALL_SURFACES);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * The data provider that supplies a field's option list, or null if the field is free-text or a static
 * list only. [osValue] is the sibling OS selection, needed because the browser OS-version source depends
 * on it. BUILD_ID is intentionally absent: its candidates depend on device + Android version and are
 * resolved separately.
 */
fun UaSpoofClient.providerIdFor(field: UaField, osValue: String): String? = when (field) {
    UaField.BROWSER_VERSION -> when (this) {
        UaSpoofClient.FIREFOX -> UaProviderIds.FIREFOX_VERSION
        UaSpoofClient.CHROME -> UaProviderIds.CHROME_VERSION
        else -> null
    }
    UaField.ANDROID_VERSION -> UaProviderIds.ANDROID_VERSION
    UaField.OS_VERSION -> UaProviderIds.MACOS_VERSION.takeIf { UaOs.fromValue(osValue) == UaOs.MACOS }
    UaField.CURL_VERSION -> UaProviderIds.CURL_VERSION
    // DEVICE_MANUFACTURER + DEVICE_MODEL both derive from the device_model cache (fetched by the gate).
    UaField.APP_VERSION -> when (this) {
        UaSpoofClient.ELEMENT_X_ANDROID -> UaProviderIds.EXA_APP_VERSION
        UaSpoofClient.SCHILDICHAT_NEXT -> UaProviderIds.SCN_APP_VERSION
        UaSpoofClient.ELEMENT_ANDROID_LEGACY -> UaProviderIds.LEGACY_APP_VERSION
        UaSpoofClient.ELEMENT_DESKTOP -> UaProviderIds.DESKTOP_APP_VERSION
        UaSpoofClient.ELEMENT_X_IOS -> UaProviderIds.EXA_IOS_APP_VERSION
        UaSpoofClient.ELEMENT_IOS_CLASSIC -> UaProviderIds.IOS_CLASSIC_APP_VERSION
        else -> null
    }
    // The legacy SDK version tracks element-android's own release version.
    UaField.SDK_VERSION -> UaProviderIds.LEGACY_APP_VERSION
    UaField.IOS_VERSION -> UaProviderIds.IOS_VERSION
    UaField.IOS_DEVICE -> UaProviderIds.IOS_DEVICE
    // ELECTRON_VERSION is resolved separately (paired with the Chrome version it bundles).
    UaField.MTXCLIENT_VERSION -> UaProviderIds.MTXCLIENT_VERSION
    UaField.GOMUKS_VERSION -> UaProviderIds.GOMUKS_VERSION
    UaField.MAUTRIX_VERSION -> UaProviderIds.MAUTRIX_VERSION
    UaField.GO_VERSION -> UaProviderIds.GO_VERSION
    UaField.DART_VERSION -> UaProviderIds.DART_VERSION
    else -> null
}
