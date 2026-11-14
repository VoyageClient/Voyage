/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceManager
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import im.vector.lib.ui.styles.R as StylesR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28])
class SwipeActionPreferenceTest {
    private val context = RuntimeEnvironment.getApplication().apply { setTheme(StylesR.style.Theme_Vector_Light) }
    private val screen = PreferenceManager(context).inflateFromResource(context, R.xml.vector_settings_swipe_actions, null)
    private val preference = (screen.getPreference(0) as PreferenceCategory).getPreference(1) as SwipeActionPreference
    private val adapter = PreferenceGroupAdapter(screen)
    private val position = (0 until adapter.itemCount).first { adapter.getItem(it) === preference }
    private val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(position))

    @Test
    fun `every binding inflates its icon and label on the settings screen`() {
        MessageSwipeAction.values().forEach { action ->
            preference.action = action
            preference.onBindViewHolder(holder)
            val label = holder.findViewById(R.id.swipe_action_value) as TextView
            TextViewCompat.getCompoundDrawablesRelative(label)[0].shouldNotBeNull()
            label.text.toString() shouldBeEqualTo context.getString(action.titleRes)
        }
    }

    @Test
    fun `a disabled farther binding dims its label and becomes available again without losing its action`() {
        preference.action = MessageSwipeAction.REDACT
        preference.isEnabled = false
        preference.onBindViewHolder(holder)
        val label = holder.findViewById(R.id.swipe_action_value) as TextView
        label.isEnabled shouldBeEqualTo false
        label.currentTextColor shouldBeEqualTo ThemeUtils.getColor(context, StylesR.attr.vctr_content_quaternary)

        preference.isEnabled = true
        preference.onBindViewHolder(holder)
        label.isEnabled shouldBeEqualTo true
        label.currentTextColor shouldBeEqualTo ThemeUtils.getColor(context, com.google.android.material.R.attr.colorError)
        label.text.toString() shouldBeEqualTo context.getString(MessageSwipeAction.REDACT.titleRes)
    }

    @Test
    fun `swipe chooser uses the same dialog text colors and row layout as Sync room states`() {
        listOf(StylesR.style.Theme_Vector_Light, StylesR.style.Theme_Vector_Dark).forEach { theme ->
            val controller = Robolectric.buildActivity(FragmentActivity::class.java)
            controller.get().setTheme(theme)
            val activity = controller.setup().get()
            val fragment = SwipePreferencesFragment()
            activity.supportFragmentManager.beginTransaction().add(fragment, "preferences").commitNow()
            val swipe = fragment.findPreference<ListPreference>(MessageSwipeSlot.LEFT.preferenceKey)!!
            val swipeAppearance = chooserAppearance(fragment, swipe)
            fragment.setPreferencesFromResource(R.xml.vector_settings_rooms_spaces, null)
            val sync = fragment.findPreference<ListPreference>("SETTINGS_ROOM_SYNC_SCOPE_KEY")!!
            chooserAppearance(fragment, sync) shouldBeEqualTo swipeAppearance
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `room preference choices save overrides and reset restores inheritance and farther availability`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        controller.get().setTheme(StylesR.style.Theme_Vector_Dark)
        val activity = controller.setup().get()
        val fragment = SwipePreferencesFragment().apply { roomId = "!room:example.org" }
        activity.supportFragmentManager.beginTransaction().add(fragment, "preferences").commitNow()
        val near = fragment.findPreference<SwipeActionPreference>(MessageSwipeSlot.LEFT.preferenceKey)!!
        val far = fragment.findPreference<SwipeActionPreference>(MessageSwipeSlot.LEFT_FAR.preferenceKey)!!
        near.summary shouldBeEqualTo fragment.getString(CommonStrings.settings_use_account_setting)
        near.callChangeListener(MessageSwipeAction.NONE.name) shouldBeEqualTo true
        far.isEnabled shouldBeEqualTo false
        near.summary shouldBeEqualTo null
        fragment.preferences.getRoomMessageSwipeActionOverride(fragment.roomId!!, MessageSwipeSlot.LEFT) shouldBeEqualTo MessageSwipeAction.NONE
        fragment.preferences.messageSwipeBindings()[MessageSwipeSlot.LEFT] shouldBeEqualTo MessageSwipeAction.REPLY
        fragment.preferences.resetRoomMessageSwipeActions(fragment.roomId!!)
        fragment.binder.refresh()
        near.value shouldBeEqualTo "inherit"
        near.action shouldBeEqualTo MessageSwipeAction.REPLY
        far.isEnabled shouldBeEqualTo true
        far.value shouldBeEqualTo "inherit"
        near.summary shouldBeEqualTo fragment.getString(CommonStrings.settings_use_account_setting)
        controller.pause().stop().destroy()
    }

    private fun chooserAppearance(fragment: PreferenceFragmentCompat, preference: ListPreference): Pair<Int, String> {
        fragment.onDisplayPreferenceDialog(preference)
        fragment.parentFragmentManager.executePendingTransactions()
        val dialogFragment = fragment.parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") as
                androidx.fragment.app.DialogFragment
        val dialog = dialogFragment.dialog as AlertDialog
        val row = dialog.listView.adapter.getView(0, null, dialog.listView)
        val text = row.findViewById<TextView>(android.R.id.text1)
        val result = text.currentTextColor to row.javaClass.name
        dialogFragment.dismissNow()
        return result
    }

    class SwipePreferencesFragment : PreferenceFragmentCompat() {
        var roomId: String? = null
        lateinit var preferences: VectorPreferences
        lateinit var binder: MessageSwipePreferenceBinder

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.vector_settings_swipe_actions, null)
            preferences = VectorPreferences(
                    requireContext(), mockk(relaxed = true), mockk(relaxed = true),
                    PreferenceManager.getDefaultSharedPreferences(requireContext()),
            )
            binder = MessageSwipePreferenceBinder(this, preferences, roomId)
            binder.bind()
        }
    }
}
