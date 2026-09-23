/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FragmentManagerTest {

    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    @Test
    fun `no dialog showing`() {
        activity.supportFragmentManager.commitTransactionNow { add(Fragment(), "plain") }

        activity.supportFragmentManager.hasShowingDialogFragment().shouldBeFalse()
    }

    @Test
    fun `a dialog of our own is showing`() {
        TestDialogFragment().show(activity.supportFragmentManager, "dialog")
        activity.supportFragmentManager.executePendingTransactions()

        activity.supportFragmentManager.hasShowingDialogFragment().shouldBeTrue()
    }

    @Test
    fun `a dialog shown by a child fragment manager is showing`() {
        val host = HostFragment()
        activity.supportFragmentManager.commitTransactionNow { add(host, "host") }
        TestDialogFragment().show(host.childFragmentManager, "dialog")
        host.childFragmentManager.executePendingTransactions()

        activity.supportFragmentManager.hasShowingDialogFragment().shouldBeTrue()
    }

    @Test
    fun `a dismissed dialog is not showing`() {
        val dialog = TestDialogFragment()
        dialog.show(activity.supportFragmentManager, "dialog")
        activity.supportFragmentManager.executePendingTransactions()
        dialog.dismissNow()

        activity.supportFragmentManager.hasShowingDialogFragment().shouldBeFalse()
    }

    class HostFragment : Fragment()

    class TestDialogFragment : DialogFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setStyle(STYLE_NO_FRAME, 0)
        }
    }
}
