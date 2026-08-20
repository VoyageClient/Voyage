/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import im.vector.app.features.settings.useragent.data.UaDataRepository
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class UserAgentSpoofBuilderTest {

    private val settings = mockk<UserAgentSettings>()
    private val repository = mockk<UaDataRepository>(relaxed = true)

    // Fixed value per field, so the builder assembles from known inputs without touching the cache.
    private fun fixed(field: UaField): String = when (field) {
        UaField.APP_VERSION -> "1.2.3"
        UaField.DEVICE_MANUFACTURER -> "Google"
        UaField.DEVICE_MODEL -> "Pixel 9"
        UaField.ANDROID_VERSION -> "15"
        UaField.BUILD_ID -> "AP4A.250205.002"
        UaField.SDK_VERSION -> "1.6.62"
        UaField.FLAVOUR -> "FDroid"
        UaField.OS -> "windows"
        UaField.OS_VERSION -> "10.0"
        UaField.BROWSER_VERSION -> "131.0"
        UaField.CURL_VERSION -> "8.11.0"
        UaField.IOS_DEVICE -> "iPhone 15 Pro"
        UaField.IOS_VERSION -> "18.5"
        UaField.SCALE -> "3.00"
        UaField.ELECTRON_VERSION -> "33.2.1"
        UaField.MTXCLIENT_VERSION -> "0.9.2"
        UaField.GOMUKS_VERSION -> "26.08"
        UaField.MAUTRIX_VERSION -> "0.30.0"
        UaField.GO_VERSION -> "1.23.4"
        UaField.DART_VERSION -> "3.5"
        UaField.SUFFIX -> "none"
        UaField.CUSTOM_UA -> "MyCustomUA/1.0"
    }

    private fun builderWith(client: UaSpoofClient): UserAgentSpoofBuilder {
        every { settings.editScope() } returns "s"
        every { settings.sessionScope() } returns "s"
        every { settings.selectedClient } returns client
        every { settings.selectedClientFor(any()) } returns client
        every { settings.storedValue(any(), any(), any()) } answers { fixed(secondArg<UaField>()) }
        every { settings.surfaces(any(), any()) } answers { firstArg<UaSpoofClient>().defaultSurfaces }
        every { settings.sdkShaFor(any()) } returns "b629ba1e6"
        every { settings.autoUpgradeFor(any(), any()) } returns false
        return UserAgentSpoofBuilder(settings, repository)
    }

    @Test
    fun `none produces no user agent`() {
        builderWith(UaSpoofClient.NONE).build(UaSpoofClient.NONE).shouldBeNull()
        builderWith(UaSpoofClient.NONE).buildFor(UaSurface.API_MEDIA).shouldBeNull()
    }

    @Test
    fun `browsers assemble from the given values`() {
        builderWith(UaSpoofClient.CHROME).build(UaSpoofClient.CHROME) shouldBeEqualTo
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
        builderWith(UaSpoofClient.FIREFOX).build(UaSpoofClient.FIREFOX) shouldBeEqualTo
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
    }

    @Test
    fun `element desktop assembles the electron string`() {
        builderWith(UaSpoofClient.ELEMENT_DESKTOP).build(UaSpoofClient.ELEMENT_DESKTOP) shouldBeEqualTo
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Element/1.2.3 Chrome/131.0 Electron/33.2.1 Safari/537.36"
    }

    @Test
    fun `android family formats`() {
        builderWith(UaSpoofClient.ELEMENT_X_ANDROID).build(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo
                "Element X/1.2.3 (Google Pixel 9; Android 15; AP4A.250205.002; Sdk b629ba1e6)"
        builderWith(UaSpoofClient.SCHILDICHAT_NEXT).build(UaSpoofClient.SCHILDICHAT_NEXT) shouldBeEqualTo
                "SchildiChat Next/1.2.3 (Google Pixel 9; Android 15; AP4A.250205.002; Sdk b629ba1e6)"
        builderWith(UaSpoofClient.ELEMENT_ANDROID_LEGACY).build(UaSpoofClient.ELEMENT_ANDROID_LEGACY) shouldBeEqualTo
                "Element/1.2.3 (Google Pixel 9; Android 15; AP4A.250205.002; Flavour FDroid; MatrixAndroidSdk2 1.6.62)"
    }

    @Test
    fun `ios family formats`() {
        builderWith(UaSpoofClient.ELEMENT_X_IOS).build(UaSpoofClient.ELEMENT_X_IOS) shouldBeEqualTo
                "Element X/1.2.3 (iPhone 15 Pro; iOS 18.5.0; Scale/3.00)"
        builderWith(UaSpoofClient.ELEMENT_IOS_CLASSIC).build(UaSpoofClient.ELEMENT_IOS_CLASSIC) shouldBeEqualTo
                "Element Classic/1.2.3 (iPhone 15 Pro; iOS 18.5; Scale/3.00)"
    }

    @Test
    fun `sdk-default and static clients`() {
        builderWith(UaSpoofClient.NHEKO).build(UaSpoofClient.NHEKO) shouldBeEqualTo "mtxclient v0.9.2"
        builderWith(UaSpoofClient.GOMUKS).build(UaSpoofClient.GOMUKS) shouldBeEqualTo "gomuks/v26.08 mautrix-go/v0.30.0 go/1.23.4"
        builderWith(UaSpoofClient.COMMET).build(UaSpoofClient.COMMET) shouldBeEqualTo "Dart/3.5 (dart:io)"
        builderWith(UaSpoofClient.CURL).build(UaSpoofClient.CURL) shouldBeEqualTo "curl/8.11.0"
        builderWith(UaSpoofClient.FRACTAL).build(UaSpoofClient.FRACTAL) shouldBeEqualTo "matrix-rust-sdk"
        builderWith(UaSpoofClient.NEOCHAT).build(UaSpoofClient.NEOCHAT) shouldBeEqualTo "Mozilla/5.0"
        builderWith(UaSpoofClient.SCHILDI_REVENGE).build(UaSpoofClient.SCHILDI_REVENGE) shouldBeEqualTo "SchildiChat Revenge"
        builderWith(UaSpoofClient.CUSTOM).build(UaSpoofClient.CUSTOM) shouldBeEqualTo "MyCustomUA/1.0"
    }

    @Test
    fun `buildFor honours the covered surfaces`() {
        builderWith(UaSpoofClient.ELEMENT_X_ANDROID).buildFor(UaSurface.SSO_WEBVIEW).shouldBeNull()
        builderWith(UaSpoofClient.ELEMENT_X_ANDROID).buildFor(UaSurface.API_MEDIA) shouldBeEqualTo
                "Element X/1.2.3 (Google Pixel 9; Android 15; AP4A.250205.002; Sdk b629ba1e6)"
    }

    @Test
    fun `scraped fields with no stored value and empty cache resolve to blank, no seed`() {
        every { settings.editScope() } returns "s"
        every { settings.sessionScope() } returns "s"
        every { settings.selectedClient } returns UaSpoofClient.CURL
        every { settings.selectedClientFor(any()) } returns UaSpoofClient.CURL
        every { settings.storedValue(any(), any(), any()) } returns null
        every { settings.autoUpgradeFor(any(), any()) } returns false
        every { settings.surfaces(any(), any()) } answers { firstArg<UaSpoofClient>().defaultSurfaces }
        every { repository.cached(any()) } returns emptyList()
        // No hardcoded seed: curl version is blank until the cache is populated.
        UserAgentSpoofBuilder(settings, repository).build(UaSpoofClient.CURL) shouldBeEqualTo "curl/"
    }

    @Test
    fun `isModified reflects whether any field is stored`() {
        every { settings.editScope() } returns "s"
        every { settings.storedValue(any(), any(), any()) } returns null
        UserAgentSpoofBuilder(settings, repository).isModified(UaSpoofClient.CHROME) shouldBeEqualTo false
        every { settings.storedValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION, any()) } returns "120.0"
        UserAgentSpoofBuilder(settings, repository).isModified(UaSpoofClient.CHROME) shouldBeEqualTo true
    }
}
