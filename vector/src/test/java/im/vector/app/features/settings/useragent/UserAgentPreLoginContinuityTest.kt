/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.settings.useragent.data.UaDataRepository
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNullOrBlank
import org.junit.Test
import javax.inject.Provider

/**
 * Validates the pre-login → account hand-off for the User-Agent spoof: a spoof configured before
 * signing in must apply DURING sign-in (no session yet) and remain applied AFTER, with byte-identical
 * output across the migration (no window where the real UA leaks), and without touching a current
 * account during "add account".
 */
class UserAgentPreLoginContinuityTest {

    private val prefs = FakeSharedPreferences()
    private var activeUserId: String? = null
    private val settings = UserAgentSettings(prefs, Provider { activeSessionHolder() })
    private val repository = mockk<UaDataRepository>(relaxed = true)
    private val builder = UserAgentSpoofBuilder(settings, repository)

    private fun activeSessionHolder(): ActiveSessionHolder = mockk {
        every { getSafeActiveSession() } answers {
            activeUserId?.let { id -> mockk(relaxed = true) { every { myUserId } returns id } }
        }
    }

    /** Configure a deterministic Chrome spoof (no cache needed) into whatever scope [block] targets. */
    private fun configureChrome() {
        settings.selectedClient = UaSpoofClient.CHROME
        settings.setValue(UaSpoofClient.CHROME, UaField.OS, "windows")
        settings.setValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION, "131.0")
    }

    private val chromeUa =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

    @Test
    fun `pre-login spoof applies during sign-in when there is no session yet`() {
        // Pre-login screen: edits target PENDING; no account is active.
        settings.editScopeOverride = UserAgentSettings.PENDING
        configureChrome()
        activeUserId = null

        // The interceptor path reads the session scope, which with no session is PENDING.
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo chromeUa
    }

    @Test
    fun `spoof is byte-identical before and after sign-in with no gap`() {
        settings.editScopeOverride = UserAgentSettings.PENDING
        configureChrome()
        activeUserId = null
        val duringSignIn = builder.buildFor(UaSurface.API_MEDIA)

        // Sign-in completes: migrate PENDING into the new account, then the account becomes active.
        settings.migratePendingInto("@alice:hs")
        settings.editScopeOverride = null
        activeUserId = "@alice:hs"
        val afterSignIn = builder.buildFor(UaSurface.API_MEDIA)

        duringSignIn.shouldNotBeNullOrBlank()
        afterSignIn shouldBeEqualTo duringSignIn
        afterSignIn shouldBeEqualTo chromeUa
        // PENDING is emptied so it can't bleed into the next sign-in.
        settings.selectedClientFor(UserAgentSettings.PENDING) shouldBeEqualTo UaSpoofClient.NONE
    }

    @Test
    fun `every facet of the config migrates into the account`() {
        settings.editScopeOverride = UserAgentSettings.PENDING
        settings.selectedClient = UaSpoofClient.ELEMENT_X_ANDROID
        settings.setValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.APP_VERSION, "26.08.1")
        settings.setValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.ANDROID_VERSION, "15")
        settings.setAutoUpgradeFor(UaSpoofClient.ELEMENT_X_ANDROID, true)
        settings.setSurfaces(UaSpoofClient.ELEMENT_X_ANDROID, setOf(UaSurface.API_MEDIA))

        settings.migratePendingInto("@alice:hs")
        settings.editScopeOverride = null
        activeUserId = "@alice:hs"

        settings.selectedClient shouldBeEqualTo UaSpoofClient.ELEMENT_X_ANDROID
        settings.storedValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.APP_VERSION) shouldBeEqualTo "26.08.1"
        settings.storedValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.ANDROID_VERSION) shouldBeEqualTo "15"
        settings.autoUpgradeFor(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo true
        settings.surfaces(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo setOf(UaSurface.API_MEDIA)
    }

    @Test
    fun `add-account never touches the current account`() {
        // Current account @bob has its own spoof (Firefox).
        activeUserId = "@bob:hs"
        settings.selectedClient = UaSpoofClient.FIREFOX
        settings.setValue(UaSpoofClient.FIREFOX, UaField.OS, "windows")
        settings.setValue(UaSpoofClient.FIREFOX, UaField.BROWSER_VERSION, "131.0")
        val bobUaBefore = builder.buildFor(UaSurface.API_MEDIA)

        // Open pre-login from the switcher (session still active) and configure Chrome.
        settings.editScopeOverride = UserAgentSettings.PENDING
        configureChrome()

        // The interceptor still reads @bob's scope (never the override), so @bob is unaffected.
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo bobUaBefore
        settings.selectedClientFor("@bob:hs") shouldBeEqualTo UaSpoofClient.FIREFOX

        // Migrating targets the NEW account, still leaving @bob alone.
        settings.migratePendingInto("@carol:hs")
        settings.selectedClientFor("@carol:hs") shouldBeEqualTo UaSpoofClient.CHROME
        settings.selectedClientFor("@bob:hs") shouldBeEqualTo UaSpoofClient.FIREFOX
    }

    @Test
    fun `interceptor ignores the edit override while a session is active`() {
        activeUserId = "@bob:hs"
        // Nothing configured for @bob → no spoof; the override must not make the interceptor read PENDING.
        settings.editScopeOverride = UserAgentSettings.PENDING
        configureChrome() // writes to PENDING
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo null
    }

    @Test
    fun `abandonPending discards the pre-login choice`() {
        settings.editScopeOverride = UserAgentSettings.PENDING
        configureChrome()
        settings.selectedClientFor(UserAgentSettings.PENDING) shouldNotBeEqualTo UaSpoofClient.NONE

        settings.abandonPending()

        settings.editScopeOverride shouldBeEqualTo null
        settings.selectedClientFor(UserAgentSettings.PENDING) shouldBeEqualTo UaSpoofClient.NONE
        settings.storedValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION, UserAgentSettings.PENDING) shouldBeEqualTo null
    }
}
