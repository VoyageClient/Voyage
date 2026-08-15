/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.utils.MediaPlayerCompat
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.voice.VoiceFailure
import im.vector.app.features.voice.VoiceRecorder
import im.vector.app.features.voice.VoiceRecorderProvider
import im.vector.lib.core.utils.timer.CountUpTimer
import im.vector.lib.multipicker.entity.MultiPickerAudioType
import im.vector.lib.multipicker.utils.toMultiPickerAudioType
import io.element.android.opusencoder.OggOpusDecoder
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlin.math.sqrt

private const val AMPLITUDE_INTERVAL_MS = 50

/**
 * Helper class to record audio for voice messages.
 */
class AudioMessageHelper @Inject constructor(
        private val context: Context,
        private val playbackTracker: AudioMessagePlaybackTracker,
        private val buildMeta: BuildMeta,
        private val vectorPreferences: VectorPreferences,
        voiceRecorderProvider: VoiceRecorderProvider
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingId: String? = null
    private val voiceRecorder: VoiceRecorder by lazy { voiceRecorderProvider.provideVoiceRecorder() }
    private val opusDecoder: OggOpusDecoder by lazy { OggOpusDecoder.create() }

    private val amplitudeList = mutableListOf<Int>()

    private var amplitudeTicker: CountUpTimer? = null
    private var playbackTicker: CountUpTimer? = null

    fun initializeRecorder(roomId: String, attachmentData: ContentAttachmentData) {
        voiceRecorder.initializeRecord(roomId, attachmentData)
        amplitudeList.clear()
        attachmentData.waveform?.let {
            amplitudeList.addAll(it)
            playbackTracker.updateCurrentRecording(AudioMessagePlaybackTracker.RECORDING_ID, amplitudeList)
        }
    }

    fun startRecording(roomId: String) {
        stopPlayback()
        playbackTracker.pauseAllPlaybacks()
        amplitudeList.clear()

        try {
            voiceRecorder.startRecord(roomId)
        } catch (failure: Throwable) {
            Timber.e(failure, "Unable to start recording")
            throw VoiceFailure.UnableToRecord(failure)
        }
        startRecordingAmplitudes()
    }

    fun stopRecording(): MultiPickerAudioType? {
        val voiceMessageFile = tryOrNull("Cannot stop media recorder!") {
            voiceRecorder.stopRecord()
            voiceRecorder.getVoiceMessageFile()
        }

        tryOrNull("Cannot stop media recording amplitude") {
            stopRecordingAmplitudes()
        }

        return try {
            voiceMessageFile?.let {
                val outputFileUri = FileProvider.getUriForFile(context, buildMeta.applicationId + ".fileProvider", it, "Voice message.${it.extension}")
                outputFileUri
                        .toMultiPickerAudioType(context)
                        // Opus duration is unreadable from metadata below API 24, so fall back to the
                        // elapsed time implied by the amplitude samples (one every AMPLITUDE_INTERVAL_MS).
                        ?.let { audioType ->
                            val duration = audioType.duration.takeIf { d -> d > 0 }
                                    ?: (amplitudeList.size * AMPLITUDE_INTERVAL_MS).toLong()
                            audioType.copy(duration = duration)
                        }
                        ?.apply {
                            waveform = if (amplitudeList.size < 50) {
                                amplitudeList
                            } else {
                                amplitudeList.chunked(amplitudeList.size / 50) { items -> items.maxOrNull() ?: 0 }
                            }.normalizeWaveform()
                        }
            }
        } catch (e: FileNotFoundException) {
            Timber.e(e, "Cannot stop voice recording")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "Error while retrieving metadata")
            null
        }
    }

    /**
     * When entering in playback mode actually.
     */
    fun pauseRecording() {
        // TODO should we pause instead of stop?
        voiceRecorder.stopRecord()
        stopRecordingAmplitudes()
    }

    fun deleteRecording() {
        tryOrNull("Cannot stop media recording amplitude") {
            stopRecordingAmplitudes()
        }
        tryOrNull("Cannot stop media recorder!") {
            voiceRecorder.cancelRecord()
        }
    }

    fun startOrPauseRecordingPlayback() {
        voiceRecorder.getVoiceMessageFile()?.let {
            startOrPausePlayback(AudioMessagePlaybackTracker.RECORDING_ID, it)
        }
    }

    fun startOrPausePlayback(id: String, file: File) {
        val playbackState = playbackTracker.getPlaybackState(id)
        mediaPlayer?.stop()
        stopPlaybackTicker()
        stopRecordingAmplitudes()
        currentPlayingId = null
        if (playbackState is AudioMessagePlaybackTracker.Listener.State.Playing) {
            playbackTracker.pausePlayback(id)
        } else {
            startPlayback(id, file)
            playbackTracker.startPlayback(id)
        }
    }

    private fun startPlayback(id: String, file: File) {
        val currentPlaybackTime = playbackTracker.getPlaybackTime(id) ?: 0
        val playableFile = resolvePlayableFile(file)

        try {
            FileInputStream(playableFile).use { fis ->
                mediaPlayer = MediaPlayer().apply {
                    MediaPlayerCompat.setMediaAudioAttributes(this)
                    setDataSource(fis.fd)
                    prepare()
                    // Sought before it is started, and precisely: starting first plays the opening
                    // of the file, and the plain seek lands on the previous sync frame — which on
                    // a sparsely framed one is the beginning, so resuming restarts the whole clip.
                    seekToPrecise(currentPlaybackTime)
                    start()
                    // Recorded before the first tick can answer with where the player was: the
                    // seek it was just given may not have landed yet.
                    val duration = tryOrNull { duration } ?: 0
                    if (duration > 0) {
                        playbackTracker.updatePlayingAtPlaybackTime(
                                id, currentPlaybackTime, currentPlaybackTime.toFloat() / duration
                        )
                    }
                }
            }
            currentPlayingId = id
        } catch (failure: Throwable) {
            Timber.e(failure, "Unable to start playback")
            throw VoiceFailure.UnableToPlay(failure)
        }
        startPlaybackTicker(id)
    }

    /**
     * MediaPlayer only supports Opus-in-Ogg from API 24, so below that we transcode the file to a
     * (cached) PCM WAV that MediaPlayer can always play, keeping the rest of the playback machinery
     * unchanged. Non-Opus audio and API 24+ are passed through untouched.
     */
    private fun resolvePlayableFile(file: File): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || !isOggOpus(file)) return file

        val wav = File(context.cacheDir, "opus-decoded-${file.name}.wav")
        if (wav.exists() && wav.length() > 0 && wav.lastModified() >= file.lastModified()) return wav

        val decoded = tryOrNull { opusDecoder.decodeToWav(file.absolutePath, wav.absolutePath) }
        return if (decoded == 0 && wav.length() > 0) {
            wav
        } else {
            tryOrNull { wav.delete() }
            file
        }
    }

    /** SEEK_CLOSEST is exact; the int overload snaps to the previous sync frame, seconds earlier. */
    private fun MediaPlayer.seekToPrecise(positionMs: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            seekTo(positionMs)
        }
    }

    private fun isOggOpus(file: File): Boolean {
        // The Opus identification header ("OpusHead") sits at the start of the first Ogg page payload.
        return tryOrNull {
            FileInputStream(file).use { fis ->
                val head = ByteArray(64)
                val read = fis.read(head)
                read > 0 && String(head, 0, read, Charsets.ISO_8859_1).contains("OpusHead")
            }
        }.orFalse()
    }

    fun stopPlayback() {
        playbackTracker.pausePlayback(AudioMessagePlaybackTracker.RECORDING_ID)
        mediaPlayer?.stop()
        stopPlaybackTicker()
        currentPlayingId = null
    }

    fun movePlaybackTo(id: String, percentage: Float, totalDuration: Int) {
        val toMillisecond = (totalDuration * percentage).toInt()
        playbackTracker.pauseAllPlaybacks()

        if (currentPlayingId == id) {
            mediaPlayer?.seekToPrecise(toMillisecond)
            playbackTracker.updatePlayingAtPlaybackTime(id, toMillisecond, percentage)
        } else {
            mediaPlayer?.pause()
            playbackTracker.updatePausedAtPlaybackTime(id, toMillisecond, percentage)
            stopPlaybackTicker()
        }
    }

    private fun startRecordingAmplitudes() {
        amplitudeTicker?.stop()
        amplitudeTicker = CountUpTimer(intervalInMs = AMPLITUDE_INTERVAL_MS.toLong()).apply {
            tickListener = CountUpTimer.TickListener { onAmplitudeTick() }
            start()
        }
    }

    private fun onAmplitudeTick() {
        try {
            val maxAmplitude = voiceRecorder.getMaxAmplitude()
            amplitudeList.add(maxAmplitude)
            playbackTracker.updateCurrentRecording(AudioMessagePlaybackTracker.RECORDING_ID, amplitudeList)
        } catch (e: IllegalStateException) {
            Timber.e(e, "Cannot get max amplitude. Amplitude recording timer will be stopped.")
            stopRecordingAmplitudes()
        } catch (e: RuntimeException) {
            Timber.e(e, "Cannot get max amplitude (native error). Amplitude recording timer will be stopped.")
            stopRecordingAmplitudes()
        }
    }

    private fun stopRecordingAmplitudes() {
        amplitudeTicker?.stop()
        amplitudeTicker = null
    }

    private fun startPlaybackTicker(id: String) {
        playbackTicker?.stop()
        // Ten a second, as the media viewer's own scrubber ticks: a bar told where playback is once
        // a second can only jump there, however it is animated in between.
        playbackTicker = CountUpTimer(intervalInMs = PLAYBACK_TICK_INTERVAL_MS).apply {
            tickListener = CountUpTimer.TickListener { onPlaybackTick(id) }
            start()
        }
        onPlaybackTick(id)
    }

    private fun onPlaybackTick(id: String) {
        val player = mediaPlayer
        if (player == null) {
            stopPlaybackTicker()
            return
        }
        val playing = tryOrNull { player.isPlaying }.orFalse()
        val duration = tryOrNull { player.duration } ?: 0
        val position = tryOrNull { player.currentPosition } ?: 0
        if (!playing) {
            // A player that has run out is finished and starts again from the top; one that is
            // merely between states — mid-seek, or a sink spinning up — keeps where it had got to,
            // and going idle there is what makes the next play restart the whole file.
            val finished = duration > 0 && position >= duration - PLAYBACK_END_WINDOW_MS
            if (finished && vectorPreferences.loopVideos()) {
                // The same setting media loops under, applied to sound: play it again from the top.
                runCatching {
                    player.seekToPrecise(0)
                    player.start()
                }
                playbackTracker.updatePlayingAtPlaybackTime(id, 0, 0f)
                return
            }
            if (finished) playbackTracker.stopPlaybackOrRecorder(id) else playbackTracker.pausePlayback(id)
            stopPlaybackTicker()
            return
        }
        if (duration <= 0) return
        // A player asked the moment after a seek answers with where it was, which would drag the
        // recorded position back to the start of the file.
        val recorded = playbackTracker.getPlaybackTime(id) ?: 0
        if (recorded - position in 1..STALE_REPORT_MS) return
        playbackTracker.updatePlayingAtPlaybackTime(id, position, position.toFloat() / duration)
    }

    private fun stopPlaybackTicker() {
        playbackTicker?.stop()
        playbackTicker = null
    }

    fun resetPlaybackStates() {
        playbackTracker.resetAllPlaybackStates()
    }

    fun getCurrentVoiceFile(): File? = voiceRecorder.getVoiceMessageFile()

    // Lets the in-flight local-echo of an audio/voice message be played before its mxc:// URL
    // arrives — by streaming the picker's content:// (or local file://) URI into a cache file.
    fun resolveLocalFile(url: String?): File? {
        val safeUrl = url?.takeIf { it.startsWith("content://") || it.startsWith("file://") } ?: return null
        return tryOrNull {
            val uri = Uri.parse(safeUrl)
            context.contentResolver.openInputStream(uri)?.use { input ->
                File.createTempFile("local_audio_", null, context.cacheDir).also { tmp ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    fun stopAllVoiceActions(deleteRecord: Boolean = true): MultiPickerAudioType? {
        val audioType = stopRecording()
        stopPlayback()
        if (deleteRecord) {
            deleteRecording()
        }
        return audioType
    }
}

// Peak-normalize with sqrt companding so bar heights stay legible regardless of recording volume
private fun List<Int>.normalizeWaveform(): List<Int> {
    val peak = maxOrNull()?.takeIf { it > 0 } ?: return this
    return map { (sqrt(it.coerceAtLeast(0) / peak.toDouble()) * 1024).toInt() }
}

/** How often playback reports where it is. */
private const val PLAYBACK_TICK_INTERVAL_MS = 100L

/** Past this a player has run out rather than been interrupted. */
private const val PLAYBACK_END_WINDOW_MS = 250

/** How far behind the recorded position a report can be and still be a stale one. */
private const val STALE_REPORT_MS = 1_500
