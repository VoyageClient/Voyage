/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

import im.vector.app.ActiveSessionDataSource
import im.vector.app.features.settings.useragent.data.UaDataRepository
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNullOrBlank
import org.junit.Test
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOption

class UserAgentPreLoginContinuityTest {

    private val prefs = FakeSharedPreferences()
    private val activeSessionDataSource = ActiveSessionDataSource()
    private val settings = UserAgentSettings(prefs, activeSessionDataSource)
    private val repository = mockk<UaDataRepository>(relaxed = true)
    private val builder = UserAgentSpoofBuilder(settings, repository)

    private fun setActiveUser(userId: String?) {
        val session = userId?.let { id -> mockk<Session> { every { myUserId } returns id } }
        activeSessionDataSource.post(session?.toOption() ?: Optional.empty())
    }

    private fun configureChrome() {
        settings.selectedClient = UaSpoofClient.CHROME
        settings.setValue(UaSpoofClient.CHROME, UaField.OS, "windows")
        settings.setValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION, "131.0")
    }

    private fun configureFirefox() {
        settings.selectedClient = UaSpoofClient.FIREFOX
        settings.setValue(UaSpoofClient.FIREFOX, UaField.OS, "windows")
        settings.setValue(UaSpoofClient.FIREFOX, UaField.BROWSER_VERSION, "131.0")
    }

    private val chromeUa =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

    @Test
    fun `pre-login spoof applies during sign-in when there is no session yet`() {
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()
        setActiveUser(null)

        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo chromeUa
    }

    @Test
    fun `spoof is byte-identical before and after sign-in with no gap`() {
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()
        setActiveUser(null)
        val duringSignIn = builder.buildFor(UaSurface.API_MEDIA)

        settings.applyDefaultTo("@alice:example.org")
        settings.editScopeOverride = null
        setActiveUser("@alice:example.org")
        val afterSignIn = builder.buildFor(UaSurface.API_MEDIA)

        duringSignIn.shouldNotBeNullOrBlank()
        afterSignIn shouldBeEqualTo duringSignIn
        afterSignIn shouldBeEqualTo chromeUa
    }

    @Test
    fun `default persists across sign-ins and seeds every new account`() {
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()
        settings.editScopeOverride = null

        settings.applyDefaultTo("@alice:example.org")
        settings.applyDefaultTo("@carol:example.org")

        settings.selectedClientFor(UserAgentSettings.DEFAULT_SCOPE) shouldBeEqualTo UaSpoofClient.CHROME
        settings.selectedClientFor("@alice:example.org") shouldBeEqualTo UaSpoofClient.CHROME
        settings.selectedClientFor("@carol:example.org") shouldBeEqualTo UaSpoofClient.CHROME
    }

    @Test
    fun `editing an account afterwards leaves the default alone`() {
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()
        settings.applyDefaultTo("@alice:example.org")
        settings.editScopeOverride = null
        setActiveUser("@alice:example.org")

        settings.setValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION, "140.0")

        settings.storedValue(UaSpoofClient.CHROME, UaField.BROWSER_VERSION, UserAgentSettings.DEFAULT_SCOPE) shouldBeEqualTo "131.0"
    }

    @Test
    fun `seeding replaces config left over from an earlier sign-in`() {
        setActiveUser("@alice:example.org")
        configureFirefox()
        settings.setSurfaces(UaSpoofClient.CHROME, setOf(UaSurface.API_MEDIA))
        setActiveUser(null)

        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()
        settings.applyDefaultTo("@alice:example.org")

        settings.selectedClientFor("@alice:example.org") shouldBeEqualTo UaSpoofClient.CHROME
        settings.storedValue(UaSpoofClient.FIREFOX, UaField.OS, "@alice:example.org") shouldBeEqualTo null
        settings.surfaces(UaSpoofClient.CHROME, "@alice:example.org") shouldBeEqualTo UaSpoofClient.CHROME.defaultSurfaces
    }

    @Test
    fun `without a default the account keeps its own config`() {
        setActiveUser("@alice:example.org")
        configureFirefox()

        settings.applyDefaultTo("@alice:example.org")

        settings.selectedClientFor("@alice:example.org") shouldBeEqualTo UaSpoofClient.FIREFOX
    }

    @Test
    fun `every facet of the config is copied into the account`() {
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        settings.selectedClient = UaSpoofClient.ELEMENT_X_ANDROID
        settings.setValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.APP_VERSION, "26.08.1")
        settings.setValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.ANDROID_VERSION, "15")
        settings.setAutoUpgradeFor(UaSpoofClient.ELEMENT_X_ANDROID, true)
        settings.setSurfaces(UaSpoofClient.ELEMENT_X_ANDROID, setOf(UaSurface.API_MEDIA))

        settings.applyDefaultTo("@alice:example.org")
        settings.editScopeOverride = null
        setActiveUser("@alice:example.org")

        settings.selectedClient shouldBeEqualTo UaSpoofClient.ELEMENT_X_ANDROID
        settings.storedValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.APP_VERSION) shouldBeEqualTo "26.08.1"
        settings.storedValue(UaSpoofClient.ELEMENT_X_ANDROID, UaField.ANDROID_VERSION) shouldBeEqualTo "15"
        settings.autoUpgradeFor(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo true
        settings.surfaces(UaSpoofClient.ELEMENT_X_ANDROID) shouldBeEqualTo setOf(UaSurface.API_MEDIA)
    }

    @Test
    fun `add-account never touches the current account`() {
        setActiveUser("@bob:example.org")
        configureFirefox()
        val bobUaBefore = builder.buildFor(UaSurface.API_MEDIA)

        // Open pre-login from the switcher (session still active) and configure Chrome.
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()

        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo bobUaBefore
        settings.selectedClientFor("@bob:example.org") shouldBeEqualTo UaSpoofClient.FIREFOX

        settings.applyDefaultTo("@carol:example.org")
        settings.selectedClientFor("@carol:example.org") shouldBeEqualTo UaSpoofClient.CHROME
        settings.selectedClientFor("@bob:example.org") shouldBeEqualTo UaSpoofClient.FIREFOX
    }

    @Test
    fun `interceptor ignores the edit override while a session is active`() {
        setActiveUser("@bob:example.org")
        settings.editScopeOverride = UserAgentSettings.DEFAULT_SCOPE
        configureChrome()
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo null
    }

    @Test
    fun `interceptor follows published sessions across the account switch gap`() {
        setActiveUser("@alice:example.org")
        configureChrome()
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo chromeUa

        setActiveUser(null)
        settings.sessionScope() shouldBeEqualTo UserAgentSettings.DEFAULT_SCOPE
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo null

        setActiveUser("@bob:example.org")
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo null

        setActiveUser("@alice:example.org")
        builder.buildFor(UaSurface.API_MEDIA) shouldBeEqualTo chromeUa
    }
}
