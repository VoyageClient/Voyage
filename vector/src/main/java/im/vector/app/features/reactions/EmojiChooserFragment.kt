/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.EmojiChooserFragmentBinding

@AndroidEntryPoint
class EmojiChooserFragment : VectorBaseFragment<EmojiChooserFragmentBinding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): EmojiChooserFragmentBinding {
        return EmojiChooserFragmentBinding.inflate(inflater, container, false)
    }

    private lateinit var viewModel: EmojiChooserViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = activityViewModelProvider.get(EmojiChooserViewModel::class.java)
        views.root.onEmojiClick = EmojiPickerView.OnEmojiClickListener { item ->
            viewModel.onReactionSelected(
                    when (item) {
                        is EmojiPickerItem.Unicode -> item.glyph
                        is EmojiPickerItem.Emote -> item.key
                    }
            )
        }
        viewModel.sections.observe(viewLifecycleOwner) { views.root.setSections(it) }
    }
}
