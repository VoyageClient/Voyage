/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.R
import im.vector.app.databinding.ItemAudioPlayerTrackBinding
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings

class AudioPlaybackPlaylistAdapter(
        private val controller: AudioPlaybackController,
        private val context: Context,
        private val dismiss: () -> Unit,
) : RecyclerView.Adapter<AudioPlaybackPlaylistAdapter.Holder>() {
    private var tracks = emptyList<AudioPlaybackController.Track>()
    private var currentId: String? = null
    private var playing = false

    class Holder(val views: ItemAudioPlayerTrackBinding) : RecyclerView.ViewHolder(views.root)

    fun render(state: AudioPlaybackController.State) {
        if (tracks == state.playlist && currentId == state.track.id && playing == state.playing) return
        tracks = state.playlist
        currentId = state.track.id
        playing = state.playing
        notifyDataSetChanged()
    }

    override fun getItemCount() = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemAudioPlayerTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val track = tracks[position]
        val metadata = track.content.audioInfo?.metadata
        val title = if (track.content.voiceMessageIndicator != null) context.getString(CommonStrings.audio_player_voice_message)
                    else metadata?.title?.takeIf { it.isNotBlank() } ?: track.content.body
        holder.views.title.text = title.neutralizeDirectionOverrides()
        holder.views.credits.text = AudioDetails.credits(title, metadata?.artist?.takeIf { it.isNotBlank() } ?: track.sender, metadata?.album)
                .orEmpty().neutralizeDirectionOverrides()
        holder.views.duration.text = DateUtils.formatElapsedTime((track.content.audioInfo?.duration ?: 0).toLong() / 1000)
        holder.views.status.setImageDrawable(AppCompatResources.getDrawable(context, if (controller.isCurrentTrack(track)) {
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        } else R.drawable.ic_music_note))
        holder.views.root.setOnClickListener { controller.playTrack(track) }
        holder.views.more.setOnClickListener { controller.trackActions.show(context, holder.views.more, track, dismiss) }
    }
}
