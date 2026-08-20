/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import im.vector.app.features.settings.useragent.data.UaDataRepository
import im.vector.app.features.settings.useragent.data.UaOption
import im.vector.app.features.settings.useragent.data.UaProviderIds
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/** Covers the live (no-seed) default resolution that reads the cache: device combining, restrictions, sha. */
class UserAgentSpoofBuilderLiveTest {

    private val settings = mockk<UserAgentSettings>()
    private val repository = mockk<UaDataRepository>(relaxed = true)

    private fun builder(): UserAgentSpoofBuilder {
        // Nothing stored: everything resolves from the cache.
        every { settings.editScope() } returns "s"
        every { settings.storedValue(any(), any(), any()) } returns null
        every { settings.sdkShaFor(any()) } returns null
        every { settings.autoUpgradeFor(any(), any()) } returns false
        return UserAgentSpoofBuilder(settings, repository)
    }

    @Test
    fun `auto-upgrade resolves software fields to the newest, ignoring stored`() {
        every { settings.editScope() } returns "s"
        every { settings.storedValue(any(), any(), any()) } returns "131.0.0.0"
        every { settings.autoUpgradeFor(any(), any()) } returns true
        // Providers return newest-first, so the newest is the first option.
        every { repository.cached(UaProviderIds.CHROME_VERSION) } returns listOf(
                UaOption("140.0.0.0", "140", 5.0), UaOption("131.0.0.0", "131", 40.0),
        )
        UserAgentSpoofBuilder(settings, repository).resolvedValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION) shouldBeEqualTo "140.0.0.0"
    }

    @Test
    fun `unset field defaults to the most popular cached value`() {
        every { repository.cached(UaProviderIds.CHROME_VERSION) } returns listOf(
                UaOption("130.0.0.0", "130", 20.0),
                UaOption("131.0.0.0", "131", 45.0),
                UaOption("129.0.0.0", "129", 10.0),
        )
        builder().resolvedValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION) shouldBeEqualTo "131.0.0.0"
    }

    @Test
    fun `device string combines the top manufacturer and its first model`() {
        every { repository.cached(UaProviderIds.DEVICE_MODEL) } returns listOf(
                UaOption("Samsung SM-S921B", "Samsung SM-S921B", null),
                UaOption("Samsung SM-A546B", "Samsung SM-A546B", null),
                UaOption("Google Pixel 9", "Google Pixel 9", null),
        )
        builder().deviceString(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo "Samsung SM-S921B"
    }

    @Test
    fun `ios versions are limited by the iphone the user picked`() {
        every { repository.cached(UaProviderIds.IOS_DEVICE) } returns listOf(UaOption("iPhone 15 Pro", "iPhone 15 Pro", null))
        every { repository.cached(UaProviderIds.IOS_VERSION) } returns listOf(
                UaOption("18.5", "iOS 18.5", 40.0), UaOption("17.2", "iOS 17.2", 10.0), UaOption("15.1", "iOS 15.1", 3.0),
        )
        // iPhone 15 shipped with iOS 17, so iOS 15 must be excluded.
        builder().iosVersionOptions(UaSpoofClient.ELEMENT_X_IOS).map { it.value } shouldBeEqualTo listOf("18.5", "17.2")
    }

    @Test
    fun `sdk sha comes from the resolved-for-app-version cache`() {
        every { settings.editScope() } returns "s"
        every { settings.storedValue(any(), any(), any()) } returns null
        every { settings.autoUpgradeFor(any(), any()) } returns false
        every { repository.cached(UaProviderIds.EXA_APP_VERSION) } returns listOf(UaOption("26.08.1", "26.08.1", null))
        every { settings.sdkShaFor("26.08.1") } returns "b629ba1e6"
        UserAgentSpoofBuilder(settings, repository).sdkSha(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo "b629ba1e6"
    }
}
