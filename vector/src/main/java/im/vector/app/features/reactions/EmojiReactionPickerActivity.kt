/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.core.extensions.observeEvent
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityEmojiReactionPickerBinding
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 *
 * TODO Loading indicator while getting emoji data source?
 * TODO Finish Refactor to vector base activity
 */
@AndroidEntryPoint
class EmojiReactionPickerActivity :
        VectorBaseActivity<ActivityEmojiReactionPickerBinding>(),
        EmojiCompatFontProvider.FontProviderListener {

    lateinit var viewModel: EmojiChooserViewModel

    override fun getBinding() = ActivityEmojiReactionPickerBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun getTitleRes() = CommonStrings.title_activity_emoji_reaction_picker

    @Inject lateinit var emojiCompatFontProvider: EmojiCompatFontProvider

    override fun initUiAndData() {
        setupToolbar(views.emojiPickerToolbar)
                .allowBack()
        // Finish directly on the toolbar up button. The default routes through the deprecated onBackPressed()
        // dispatch, which drops rapid taps under Android's predictive-back handling.
        views.emojiPickerToolbar.setNavigationOnClickListener { finish() }
        emojiCompatFontProvider.let {
            EmojiDrawView.configureTextPaint(this, it.typeface)
            it.addListener(this)
        }

        viewModel = viewModelProvider.get(EmojiChooserViewModel::class.java)

        viewModel.eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        viewModel.setRoomId(intent.getStringExtra(EXTRA_ROOM_ID))

        viewModel.navigateEvent.observeEvent(this) {
            if (it == EmojiChooserViewModel.NAVIGATE_FINISH) {
                // finish with result
                val dataResult = Intent()
                dataResult.putExtra(EXTRA_REACTION_RESULT, viewModel.selectedReaction)
                dataResult.putExtra(EXTRA_EVENT_ID, viewModel.eventId)
                setResult(Activity.RESULT_OK, dataResult)
                finish()
            }
        }
    }

    override fun compatibilityFontUpdate(typeface: Typeface?) {
        EmojiDrawView.configureTextPaint(this, typeface)
    }

    override fun onDestroy() {
        emojiCompatFontProvider.removeListener(this)
        super.onDestroy()
    }

    companion object {

        private const val EXTRA_EVENT_ID = "EXTRA_EVENT_ID"
        private const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        private const val EXTRA_REACTION_RESULT = "EXTRA_REACTION_RESULT"

        fun intent(context: Context, eventId: String, roomId: String? = null): Intent {
            val intent = Intent(context, EmojiReactionPickerActivity::class.java)
            intent.putExtra(EXTRA_EVENT_ID, eventId)
            intent.putExtra(EXTRA_ROOM_ID, roomId)
            return intent
        }

        fun getOutputEventId(data: Intent?): String? = data?.getStringExtra(EXTRA_EVENT_ID)

        fun getOutputReaction(data: Intent?): String? = data?.getStringExtra(EXTRA_REACTION_RESULT)
    }
}
