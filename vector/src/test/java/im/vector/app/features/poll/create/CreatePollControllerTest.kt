/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.poll.create

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.airbnb.epoxy.EpoxyController
import com.google.android.material.textfield.TextInputEditText
import im.vector.app.R
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.GenericButtonItem
import im.vector.app.features.form.FormEditTextWithDeleteItem
import im.vector.app.features.form.FormEditTextWithDeleteItem_
import im.vector.app.features.poll.PollMode
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CreatePollControllerTest {

    @Test
    fun `the retained add button focuses each newly added option on repeated presses`() {
        val previousBuildHandler = EpoxyController.defaultModelBuildingHandler
        val previousDiffHandler = EpoxyController.defaultDiffingHandler
        EpoxyController.defaultModelBuildingHandler = Handler(Looper.getMainLooper())
        EpoxyController.defaultDiffingHandler = Handler(Looper.getMainLooper())
        val activityController = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = activityController.get().apply { setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light) }
            val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            activity.setContentView(container)
            val strings = mockk<StringProvider> {
                every { getString(any()) } returns "Poll"
                every { getString(any(), *anyVararg()) } returns "Option"
            }
            val controller = CreatePollController(strings, mockk(relaxed = true))
            var state = CreatePollViewState(roomId = "!room:example.org", editedEventId = null, mode = PollMode.CREATE)
            controller.callback = mockk(relaxed = true) {
                every { onAddOption() } answers {
                    state = state.copy(options = state.options + "")
                    controller.setData(state)
                }
            }
            controller.setData(state)
            ShadowLooper.idleMainLooper()
            val button = controller.adapter.copyOfModels.filterIsInstance<GenericButtonItem>().single()

            repeat(4) {
                button.buttonClickAction!!.invoke(View(activity))
                ShadowLooper.idleMainLooper()
                button shouldBeEqualTo controller.adapter.copyOfModels.filterIsInstance<GenericButtonItem>().single()
                val model = controller.adapter.copyOfModels.filterIsInstance<FormEditTextWithDeleteItem_>().last()
                val field = TextInputEditText(activity).apply { id = R.id.formTextInputTextInputEditText }
                val row = LinearLayout(activity).apply { addView(field) }
                container.addView(row)
                val holder = FormEditTextWithDeleteItem.Holder().apply { view = row }
                model.handlePostBind(holder, controller.adapter.getModelPosition(model))
                ShadowLooper.idleMainLooper()

                assertTrue("Add option press ${it + 1} should focus its new field", field.isFocused)
            }
        } finally {
            activityController.pause().stop().destroy()
            EpoxyController.defaultModelBuildingHandler = previousBuildHandler
            EpoxyController.defaultDiffingHandler = previousDiffHandler
        }
    }
}
