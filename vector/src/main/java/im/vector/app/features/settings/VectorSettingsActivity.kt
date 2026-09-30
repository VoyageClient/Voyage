/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.replaceFragment
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityVectorSettingsBinding
import im.vector.app.features.discovery.DiscoverySettingsFragment
import im.vector.app.features.matrixto.MatrixToBottomSheet
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.navigation.SettingsActivityPayload
import im.vector.app.features.settings.devices.VectorSettingsDevicesFragment
import im.vector.app.features.settings.notifications.VectorSettingsNotificationFragment
import im.vector.app.features.settings.threepids.ThreePidsSettingsFragment
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.failure.GlobalError
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import javax.inject.Inject

private const val KEY_ACTIVITY_PAYLOAD = "settings-activity-payload"

/**
 * Displays the client settings.
 */
@AndroidEntryPoint
class VectorSettingsActivity : VectorBaseActivity<ActivityVectorSettingsBinding>(),
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
        FragmentManager.OnBackStackChangedListener,
        MatrixToBottomSheet.InteractionListener,
        VectorSettingsFragmentInteractionListener {

    override fun getBinding() = ActivityVectorSettingsBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun getTitleRes() = CommonStrings.title_activity_settings

    private var keyToHighlight: String? = null

    var ignoreInvalidTokenError = false

    @Inject lateinit var session: Session

    override fun initUiAndData() {
        setupToolbar(views.settingsToolbar)
                .allowBack()

        if (isFirstCreation()) {
            showInitialFragment()
        }

        supportFragmentManager.addOnBackStackChangedListener(this)
    }

    private fun showInitialFragment() {
        when (val payload = readPayload<SettingsActivityPayload>(SettingsActivityPayload.Root)) {
            SettingsActivityPayload.General ->
                replaceFragment(views.vectorSettingsPage, VectorSettingsGeneralFragment::class.java, null, FRAGMENT_TAG)
            SettingsActivityPayload.AdvancedSettings ->
                replaceFragment(views.vectorSettingsPage, VectorSettingsAdvancedSettingsFragment::class.java, null, FRAGMENT_TAG)
            SettingsActivityPayload.SecurityPrivacy ->
                replaceFragment(views.vectorSettingsPage, VectorSettingsSecurityPrivacyFragment::class.java, null, FRAGMENT_TAG)
            SettingsActivityPayload.SecurityPrivacyManageSessions ->
                replaceFragment(views.vectorSettingsPage, VectorSettingsDevicesFragment::class.java, null, FRAGMENT_TAG)
            SettingsActivityPayload.Notifications -> {
                requestHighlightPreferenceKeyOnResume(VectorPreferences.SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY)
                replaceFragment(views.vectorSettingsPage, VectorSettingsNotificationFragment::class.java, null, FRAGMENT_TAG)
            }
            is SettingsActivityPayload.DiscoverySettings -> {
                replaceFragment(views.vectorSettingsPage, DiscoverySettingsFragment::class.java, payload, FRAGMENT_TAG)
            }
            else ->
                replaceFragment(views.vectorSettingsPage, VectorSettingsRootFragment::class.java, null, FRAGMENT_TAG)
        }
    }

    // Fragments pushed on top of the initial one, mirroring the back stack, so the stack can be rebuilt.
    // Empty after a recreate or process death, when it no longer matches the restored back stack.
    private val pushedFragments = mutableListOf<Pair<Class<out Fragment>, Bundle?>>()

    /**
     * Leave the top fragment and rebuild the ones below it from scratch, so they pick up a locale that was
     * switched in place (their preferences and texts were built with the old one). Recreates the activity
     * instead when the back stack can't be reconstructed.
     */
    fun popAndRebuildBackStack() {
        val fragmentManager = supportFragmentManager
        val count = fragmentManager.backStackEntryCount
        if (count == 0 || pushedFragments.size != count) {
            acknowledgeConfigurationChange()
            recreate()
            return
        }
        val remaining = pushedFragments.dropLast(1)
        // Swapping the fragments under the leaving one cuts its exit animation short, so replay it on a snapshot.
        val leavingSnapshot = fragmentManager.findFragmentById(views.vectorSettingsPage.id)?.view?.let { snapshotOf(it) }
        // Everything below runs before the next frame, so the intermediate screens are never drawn, and the
        // rebuilt top fades in like on a normal back.
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        showInitialFragment()
        remaining.forEachIndexed { index, (fragmentClass, arguments) ->
            val enterAnim = if (index == remaining.lastIndex) R.anim.fade_in else 0
            pushFragment(fragmentClass, arguments, enterAnim = enterAnim, exitAnim = 0)
        }
        fragmentManager.executePendingTransactions()
        leavingSnapshot?.let { playExitAnimation(it) }
    }

    private fun snapshotOf(view: View): Bitmap? {
        if (view.width == 0 || view.height == 0) return null
        return try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    private fun playExitAnimation(snapshot: Bitmap) {
        val page = views.vectorSettingsPage
        val overlay = ImageView(this).apply {
            setImageBitmap(snapshot)
            x = page.left.toFloat()
            y = (page.parent as View).top + page.top.toFloat()
        }
        views.coordinatorLayout.addView(overlay, CoordinatorLayout.LayoutParams(page.width, page.height))
        overlay.startAnimation(AnimationUtils.loadAnimation(this, R.anim.right_out).apply {
            // Keep the end state until the overlay is removed, or it flashes back for a frame.
            fillAfter = true
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) = Unit
                override fun onAnimationRepeat(animation: Animation) = Unit
                override fun onAnimationEnd(animation: Animation) {
                    overlay.post {
                        views.coordinatorLayout.removeView(overlay)
                        snapshot.recycle()
                    }
                }
            })
        })
    }

    override fun onDestroy() {
        supportFragmentManager.removeOnBackStackChangedListener(this)
        super.onDestroy()
    }

    override fun onBackStackChanged() {
        val count = supportFragmentManager.backStackEntryCount
        while (pushedFragments.size > count) pushedFragments.removeAt(pushedFragments.lastIndex)
        if (0 == count) {
            supportActionBar?.title = getString(getTitleRes())
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val oFragment = try {
            pref.fragment?.let {
                supportFragmentManager.fragmentFactory.instantiate(classLoader, it)
            }
        } catch (e: Throwable) {
            showSnackbar(getString(CommonStrings.not_implemented))
            Timber.e(e)
            null
        }

        if (oFragment != null) {
            // Deprecated, I comment it, I think it is useless
            // oFragment.setTargetFragment(caller, 0)
            // Replace the existing Fragment with the new Fragment
            supportFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.right_in, R.anim.fade_out, R.anim.fade_in, R.anim.right_out)
                    .replace(views.vectorSettingsPage.id, oFragment, pref.title.toString())
                    .addToBackStack(null)
                    .commit()
            pushedFragments.add(oFragment.javaClass to oFragment.arguments)
            return true
        }
        return false
    }

    override fun requestHighlightPreferenceKeyOnResume(key: String?) {
        keyToHighlight = key
    }

    override fun requestedKeyToHighlight(): String? {
        return keyToHighlight
    }

    override fun navigateToEmailAndPhoneNumbers() {
        navigateTo(ThreePidsSettingsFragment::class.java)
    }

    override fun handleInvalidToken(globalError: GlobalError.InvalidToken) {
        if (ignoreInvalidTokenError) {
            Timber.w("Ignoring invalid token global error")
        } else {
            super.handleInvalidToken(globalError)
        }
    }

    fun <T : Fragment> navigateTo(fragmentClass: Class<T>, arguments: Bundle? = null) {
        pushFragment(fragmentClass, arguments, enterAnim = R.anim.right_in, exitAnim = R.anim.fade_out)
    }

    private fun pushFragment(fragmentClass: Class<out Fragment>, arguments: Bundle?, enterAnim: Int, exitAnim: Int) {
        supportFragmentManager.beginTransaction()
                .setCustomAnimations(enterAnim, exitAnim, R.anim.fade_in, R.anim.right_out)
                .replace(views.vectorSettingsPage.id, fragmentClass, arguments)
                .addToBackStack(null)
                .commit()
        pushedFragments.add(fragmentClass to arguments)
    }

    override fun mxToBottomSheetNavigateToRoom(roomId: String) {
        navigator.openRoom(this, roomId)
    }

    override fun mxToBottomSheetSwitchToSpace(spaceId: String) {
        navigator.switchToSpace(this, spaceId, Navigator.PostSwitchSpaceAction.None)
    }

    companion object {
        fun getIntent(context: Context, directAccess: Int) = Companion.getIntent(
                context, when (directAccess) {
            EXTRA_DIRECT_ACCESS_ROOT -> SettingsActivityPayload.Root
            EXTRA_DIRECT_ACCESS_ADVANCED_SETTINGS -> SettingsActivityPayload.AdvancedSettings
            EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY -> SettingsActivityPayload.SecurityPrivacy
            EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY_MANAGE_SESSIONS -> SettingsActivityPayload.SecurityPrivacyManageSessions
            EXTRA_DIRECT_ACCESS_GENERAL -> SettingsActivityPayload.General
            EXTRA_DIRECT_ACCESS_NOTIFICATIONS -> SettingsActivityPayload.Notifications
            EXTRA_DIRECT_ACCESS_DISCOVERY_SETTINGS -> SettingsActivityPayload.DiscoverySettings()
            else -> {
                Timber.w("Unknown directAccess: $directAccess defaulting to Root")
                SettingsActivityPayload.Root
            }
        }
        )

        fun getIntent(context: Context, payload: SettingsActivityPayload) = Intent(context, VectorSettingsActivity::class.java)
                .applyPayload(payload)

        const val EXTRA_DIRECT_ACCESS_ROOT = 0
        const val EXTRA_DIRECT_ACCESS_ADVANCED_SETTINGS = 1
        const val EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY = 2
        const val EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY_MANAGE_SESSIONS = 3
        const val EXTRA_DIRECT_ACCESS_GENERAL = 4
        const val EXTRA_DIRECT_ACCESS_NOTIFICATIONS = 5
        const val EXTRA_DIRECT_ACCESS_DISCOVERY_SETTINGS = 6

        private const val FRAGMENT_TAG = "VectorSettingsPreferencesFragment"
    }
}

private inline fun <reified T : Parcelable> Activity.readPayload(default: T): T {
    return intent.getParcelableExtraCompat<T>(KEY_ACTIVITY_PAYLOAD) ?: default
}

private fun <T : Parcelable> Intent.applyPayload(payload: T): Intent {
    return putExtra(KEY_ACTIVITY_PAYLOAD, payload)
}
