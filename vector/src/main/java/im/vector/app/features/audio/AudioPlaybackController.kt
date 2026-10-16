/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.attachments.editor.video.PlaybackSpeed
import im.vector.app.features.attachments.preview.AudioDetails
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.home.room.detail.timeline.helper.timelineStableId
import im.vector.app.features.media.galleryPageId
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.attachmentviewer.AttachmentViewerActivity
import im.vector.lib.mediatranscode.audio.PlaybackPcmFile
import im.vector.lib.strings.CommonStrings
import io.element.android.opusencoder.OggOpusDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.getRootThreadEventId
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.model.message.getFileUrl
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AudioPlaybackController @Inject constructor(
        context: Context,
        private val tracker: AudioMessagePlaybackTracker,
        private val sessionHolder: ActiveSessionHolder,
        private val preferences: VectorPreferences,
        val trackActions: AudioPlaybackActions,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<State?>(null)
    val state = mutableState.asStateFlow()
    private var player: MessageAudioPlayer? = null
    private var load: Job? = null
    private var command: Job? = null
    private var navigation: Job? = null
    private var playerGeneration = 0
    private var pendingSeek: Pair<Int, Long>? = null
    private var timeline: Timeline? = null
    private var session: Session? = null
    private var source: File? = null
    private var processed: File? = null
    private var timeScale = 1f
    private var queue = emptyList<Track>()
    private var playlistLoad: Job? = null
    private val queueEvents = linkedMapOf<String, TimelineEvent>()
    private var activeRoom: String? = null
    private var speed = 1f
    private var pitch = 1f
    private var loopMode: LoopMode? = null
    private var changePitch = false
    private var pendingAutoplay = false
    private var autoplayBlocked = false
    private val localFiles = mutableMapOf<String, File>()
    private var localFileGeneration = 0
    private val legacyDecodeLock = Any()
    var requestAudioFocus: (() -> Boolean)? = null
    val hasPendingSelection: Boolean get() = command?.isActive == true || navigation?.isActive == true

    enum class LoopMode { OFF, ALL, ONE }

    data class Track(val id: String, val eventId: String, val roomId: String, val content: MessageAudioContent, val sender: String, val galleryIndex: Int? = null)
    data class State(
            val track: Track,
            val title: String,
            val artist: String? = null,
            val album: String? = null,
            val art: Bitmap? = null,
            val playlist: List<Track> = emptyList(),
            val canLoadMore: Boolean = false,
            val loadingTracks: Boolean = false,
            val playing: Boolean = false,
            val loading: Boolean = true,
            val position: Int = 0,
            val duration: Int = 0,
            val speed: Float = 1f,
            val pitch: Float = 1f,
            val adjustable: Boolean = true,
            val hasPrevious: Boolean = false,
            val hasNext: Boolean = false,
            val error: Boolean = false,
            val loopMode: LoopMode = LoopMode.OFF,
            val changePitch: Boolean = false,
    )

    fun playAudio(data: AudioGalleryData) {
        val id = galleryPageId(data.stableId, data.galleryIndex)
        val current = state.value
        if (!isCurrentTrack(id, data.eventId, data.galleryIndex) || current?.track?.roomId != data.roomId) {
            playOrPause(data.roomId, id, data.content, data.eventId, data.galleryIndex)
        } else if (!current.playing && !current.loading) resume()
    }

    fun playOrPause(roomId: String, id: String, content: MessageAudioContent, eventId: String = id, galleryIndex: Int? = null) {
        navigation?.cancel()
        command?.cancel()
        command = scope.launch {
            if (isCurrentTrack(id, eventId, galleryIndex) && activeRoom == roomId) {
                if (state.value?.loading == true) stop() else toggle()
                return@launch
            }
            session = sessionHolder.getOrInitializeSession() ?: return@launch
            val event = session?.getRoom(roomId)?.timelineService()?.getTimelineEvent(eventId)
            val track = Track(id, event?.eventId ?: eventId, roomId, content, event?.senderInfo?.displayName.orEmpty(), galleryIndex)
            if (activeRoom != roomId || timeline == null || queue.none { sameTrack(it, track) }) openQueue(track)
            select(track)
        }
    }

    private fun openQueue(track: Track) {
        playlistLoad?.cancel()
        timeline?.dispose()
        queueEvents.clear()
        queue = emptyList()
        activeRoom = track.roomId
        timeline = session?.getRoom(track.roomId)?.timelineService()?.createTimeline(track.eventId.takeIf { it.startsWith("$") },
                        TimelineSettings(100, false, session?.getRoom(track.roomId)?.timelineService()?.getTimelineEvent(track.eventId)?.root?.getRootThreadEventId()))?.also { next ->
            next.addListener(object : Timeline.Listener {
                override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
                    scope.launch {
                        if (timeline !== next) return@launch
                        snapshot.forEach { queueEvents[it.timelineStableId()] = it }
                        rebuildQueue()
                        val current = state.value?.track ?: return@launch
                        if (queueEvents.values.any { it.eventId == current.eventId && it.root.isRedacted() }) stop()
                    }
                }
                override fun onStateUpdated(direction: Timeline.Direction, state: Timeline.PaginationState) {
                    scope.launch { if (timeline === next) updateQueueControls() }
                }
                override fun onTimelineFailure(throwable: Throwable) { Timber.w(throwable, "Audio queue failed") }
            })
            next.start()
        }
    }

    private fun rebuildQueue() {
        queue = queueEvents.values.sortedWith(compareBy<TimelineEvent> { it.root.originServerTs ?: 0 }.thenBy { it.seq })
                .filter { !it.root.isRedacted() }
                .flatMap { event ->
                    val content = event.getLastMessageContent()
                    when (content) {
                        is MessageAudioContent -> listOf(Track(event.timelineStableId(), event.eventId, event.roomId, content, event.senderInfo.displayName.orEmpty()))
                        is MessageGalleryContent -> content.galleryItems().mapIndexedNotNull { index, item ->
                            (item as? MessageAudioContent)?.let { Track(galleryPageId(event.timelineStableId(), index), event.eventId,
                                    event.roomId, it, event.senderInfo.displayName.orEmpty(), index) }
                        }
                        else -> emptyList()
                    }
                }
        updateQueueControls()
    }

    private fun canLoad(direction: Timeline.Direction): Boolean = timeline?.let {
        it.hasMoreToLoad(direction) && !it.getPaginationState(direction).inError
    } == true

    private fun updateQueueControls() {
        val current = state.value ?: return
        val queued = queue.firstOrNull { sameTrack(it, current.track) }
        mutableState.value = current.copy(
                track = current.track.copy(eventId = queued?.eventId ?: current.track.eventId),
                playlist = queue.filter { (it.content.voiceMessageIndicator != null) == (current.track.content.voiceMessageIndicator != null) },
                canLoadMore = canLoad(Timeline.Direction.BACKWARDS) || canLoad(Timeline.Direction.FORWARDS),
                hasPrevious = current.loopMode == LoopMode.ALL || neighbor(current.track.id, false) != null || canLoad(Timeline.Direction.BACKWARDS),
                hasNext = current.loopMode == LoopMode.ALL || neighbor(current.track.id, true) != null || canLoad(Timeline.Direction.FORWARDS),
        )
    }

    fun isCurrentTrack(id: String, eventId: String = id, galleryIndex: Int? = null): Boolean = state.value?.track?.let {
        id == it.id || id == galleryPageId(it.eventId, it.galleryIndex) || (eventId == it.eventId && galleryIndex == it.galleryIndex)
    } == true

    fun isCurrentTrack(track: Track): Boolean = sameTrack(track, state.value?.track)

    private fun sameTrack(first: Track?, second: Track?): Boolean = first != null && second != null &&
            first.roomId == second.roomId && first.galleryIndex == second.galleryIndex && (first.id == second.id || first.eventId == second.eventId)

    private fun playbackIds(track: Track): Set<String> = setOf(track.id, galleryPageId(track.eventId, track.galleryIndex))

    fun playTrack(track: Track) {
        val selected = queue.firstOrNull { it.id == track.id && it.roomId == track.roomId } ?: return
        command?.cancel()
        navigation?.cancel()
        if (sameTrack(state.value?.track, selected)) {
            if (state.value?.loading == false && state.value?.playing == false) resume()
        } else {
            command = scope.launch { select(selected, restart = true) }
        }
    }

    fun loadMoreTracks() {
        if (playlistLoad?.isActive == true) return
        val activeTimeline = timeline ?: return
        playlistLoad = scope.launch {
            mutableState.value = state.value?.copy(loadingTracks = true)
            try {
                for (direction in listOf(Timeline.Direction.BACKWARDS, Timeline.Direction.FORWARDS)) {
                    if (!canLoad(direction)) continue
                    val snapshot = activeTimeline.awaitPaginate(direction, 100)
                    if (timeline !== activeTimeline) return@launch
                    snapshot.forEach { queueEvents[it.timelineStableId()] = it }
                    rebuildQueue()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.w(failure, "Cannot load audio playlist")
            } finally {
                if (timeline === activeTimeline) mutableState.value = state.value?.copy(loadingTracks = false)
            }
        }
    }

    fun skip(next: Boolean) {
        command?.cancel()
        playlistLoad?.cancel()
        navigation?.cancel()
        navigation = scope.launch {
            val current = state.value ?: return@launch
            pause()
            try {
                val direction = if (next) Timeline.Direction.FORWARDS else Timeline.Direction.BACKWARDS
                var target = neighbor(current.track.id, next)
                while (target == null && canLoad(direction)) {
                    val activeTimeline = timeline ?: break
                    val knownEvents = queueEvents.size
                    val snapshot = activeTimeline.awaitPaginate(direction, 100)
                    if (timeline !== activeTimeline || state.value?.track?.id != current.track.id) return@launch
                    snapshot.forEach { queueEvents[it.timelineStableId()] = it }
                    rebuildQueue()
                    target = neighbor(current.track.id, next)
                    if (activeTimeline.getPaginationState(direction).inError || queueEvents.size == knownEvents) break
                }
                if (target == null && state.value?.loopMode == LoopMode.ALL) {
                    target = if (next) state.value?.playlist?.firstOrNull() else state.value?.playlist?.lastOrNull()
                }
                target?.let {
                    if (sameTrack(it, current.track)) {
                        seek(0)
                        resume()
                    } else {
                        select(it, restart = true)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                ensureActive()
                Timber.w(failure, "Cannot advance audio queue")
                mutableState.value = state.value?.copy(error = true)
            }
        }
    }

    private fun neighbor(id: String, next: Boolean): Track? {
        val index = queue.indexOfFirst { it.id == id || (isCurrentTrack(id) && sameTrack(it, state.value?.track)) }
        if (index < 0) return null
        val voice = queue[index].content.voiceMessageIndicator != null
        val candidates = if (next) queue.drop(index + 1) else queue.take(index).asReversed()
        return candidates.firstOrNull { (it.content.voiceMessageIndicator != null) == voice }
    }

    private suspend fun select(track: Track, restart: Boolean = false) {
        load?.cancel()
        autoplayBlocked = false
        if (requestAudioFocus == null) {
            try {
                // Start while the app is visible; downloading metadata may outlast the foreground-service start window.
                ContextCompat.startForegroundService(context, Intent(context, AudioPlaybackService::class.java))
            } catch (failure: Exception) {
                Timber.w(failure, "Cannot start audio service before preparing playback")
            }
        }
        var preparedFile: File? = null
        val details = if (track.content.voiceMessageIndicator == null) {
            try {
                withContext(Dispatchers.IO) {
                    val activeSession = session ?: return@withContext null
                    val file = localFile(track.content.getFileUrl()) ?: activeSession.fileService().downloadFile(track.content)
                    preparedFile = file
                    val uri = Uri.fromFile(file)
                    AudioDetails.cached(uri) ?: AudioDetails.cached(uri, forTimeline = true) ?: AudioDetails.load(context, uri)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Timber.w(failure, "Cannot prepare audio metadata")
                null
            }
        } else null
        val art = try {
            loadArtwork(track, preparedFile, details)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Timber.w(failure, "Cannot prepare audio artwork")
            null
        }
        currentCoroutineContext().ensureActive()
        if (session?.getRoom(track.roomId)?.timelineService()?.getTimelineEvent(track.eventId)?.root?.isRedacted() == true) return
        releasePlayer()
        tracker.activePlayerStopper?.takeIf { it !== stopper }?.invoke(AudioMessagePlaybackTracker.RECORDING_ID)
        tracker.pausePlayback(AudioMessagePlaybackTracker.RECORDING_ID)
        source = null
        val metadata = track.content.audioInfo?.metadata
        val isVoice = track.content.voiceMessageIndicator != null
        val title = if (isVoice) context.getString(CommonStrings.audio_player_voice_message)
                    else metadata?.title?.takeIf { it.isNotBlank() } ?: details?.title?.takeIf { it.isNotBlank() } ?: track.content.body
        val artist = if (isVoice) track.sender else metadata?.artist?.takeIf { it.isNotBlank() } ?: details?.artist?.takeIf { it.isNotBlank() } ?: track.sender
        val album = if (isVoice) null else metadata?.album?.takeIf { it.isNotBlank() } ?: details?.album?.takeIf { it.isNotBlank() }
        mutableState.value = State(track, title, artist = artist, album = album, art = art,
                duration = (track.content.audioInfo?.duration ?: 0).coerceAtLeast(0),
                speed = speed, pitch = pitch, loopMode = loopMode ?: if (preferences.loopVideos()) LoopMode.ONE else LoopMode.OFF, changePitch = changePitch)
        updateQueueControls()
        tracker.activePlayerStopper = stopper
        if (restart) tracker.stopPlaybackOrRecorder(track.id)
        try {
            if (requestAudioFocus == null) ContextCompat.startForegroundService(context, Intent(context, AudioPlaybackService::class.java))
        } catch (failure: Exception) {
            failed(failure)
            return
        }
        load = scope.launch {
            try {
                val activeSession = session ?: return@launch
                val file = preparedFile ?: withContext(Dispatchers.IO) { localFile(track.content.getFileUrl()) ?: activeSession.fileService().downloadFile(track.content) }
                source = withContext(Dispatchers.IO) { legacySource(file) }
                mutableState.value = state.value?.copy(
                        adjustable = Build.VERSION.SDK_INT >= 16 || withContext(Dispatchers.IO) { PlaybackPcmFile.supports(source!!) },
                )
                val adjustable = state.value?.adjustable == true
                if (!adjustable) mutableState.value = state.value?.copy(speed = 1f, pitch = 1f)
                prepare(tracker.getPlaybackTime(track.id) ?: 0, true)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                ensureActive()
                failed(failure)
            }
        }
    }

    private suspend fun loadArtwork(track: Track, preparedFile: File?, details: AudioDetails.Details?): Bitmap? {
        if (track.content.voiceMessageIndicator != null) return null
        val activeSession = session ?: return null
        val metadata = track.content.audioInfo?.metadata
        val url = metadata?.coverArtUrl
        url?.let { AudioDetails.cachedCoverArt(it) }?.let { return it }
        val local = preparedFile ?: track.content.getFileUrl()?.takeIf { it.startsWith("file://") }?.let { Uri.parse(it).path?.let(::File) }
                ?: activeSession.fileService().getLocalFileFor(
                track.content.getFileUrl(), track.content.getFileName(), track.content.mimeType, track.content.encryptedFileInfo != null)
        if (url == null && local != null) {
            val uri = Uri.fromFile(local)
            val cached = AudioDetails.cached(uri) ?: AudioDetails.cached(uri, forTimeline = true)
            if (cached != null) return cached.art
        }
        return withContext(Dispatchers.IO) {
            val cover = if (url != null) {
                try {
                    val file = activeSession.fileService().downloadFile("cover_art", metadata.coverArtInfo?.mimeType, url, metadata.coverArtElementToDecrypt)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                            ?.also { AudioDetails.keepCoverArt(url, it) }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    Timber.w(failure, "Cannot load audio cover art")
                    null
                }
            } else null
            cover ?: details?.art ?: run {
                val file = local ?: localFile(track.content.getFileUrl()) ?: activeSession.fileService().downloadFile(track.content)
                val uri = Uri.fromFile(file)
                val fileDetails = AudioDetails.cached(uri) ?: AudioDetails.cached(uri, forTimeline = true) ?: AudioDetails.load(context, uri)
                fileDetails.art
            }
        }
    }

    private fun legacySource(file: File): File {
        if (Build.VERSION.SDK_INT >= 24) return file
        val opus = file.inputStream().use { input ->
            val head = ByteArray(64)
            val count = input.read(head)
            count > 0 && String(head, 0, count, Charsets.ISO_8859_1).contains("OpusHead")
        }
        if (!opus) return file
        val key = MessageDigest.getInstance("SHA-256").digest("${file.absolutePath}:${file.lastModified()}:${file.length()}".toByteArray()).joinToString("") { "%02x".format(it) }
        val wav = File(context.cacheDir, "player-opus-$key.wav")
        // Native decoding cannot be cancelled; serialize writers so a replacement load sees a complete WAV.
        synchronized(legacyDecodeLock) {
            if (!wav.exists() || wav.length() == 0L) {
                try {
                    check(OggOpusDecoder.create().decodeToWav(file.path, wav.path) == 0 && wav.length() > 0) { "Cannot decode Opus" }
                } catch (failure: Throwable) {
                    wav.delete()
                    throw failure
                }
            }
        }
        return wav
    }

    private suspend fun prepare(position: Int, play: Boolean) {
        val original = source ?: return
        val current = state.value ?: return
        releasePlayer()
        timeScale = if (Build.VERSION.SDK_INT < 16 && current.adjustable) speed else 1f
        var file = original
        if (Build.VERSION.SDK_INT < 16 && current.adjustable && (speed != 1f || pitch != 1f)) {
            val adjusted = File.createTempFile("playback-pcm-", ".wav", context.cacheDir)
            processed = adjusted
            try {
                withContext(Dispatchers.IO) { PlaybackPcmFile.transform(original, adjusted, speed, pitch) { isActive } }
            } catch (failure: Throwable) {
                adjusted.delete()
                throw failure
            }
            file = adjusted
        }
        val generation = playerGeneration
        val listener = object : MessageAudioPlayer.Listener {
            override fun ready() {
                if (playerGeneration != generation) return
                player?.parameters(speed, pitch)
                player?.seek((position / timeScale).toInt())
                pendingSeek = position to SystemClock.uptimeMillis() + 500
                val duration = ((player?.duration ?: 0) * timeScale).toInt()
                mutableState.value = state.value?.copy(loading = false, duration = duration, position = position, error = false)
                if (play && !autoplayBlocked) resume() else publish()
            }
            override fun ended() {
                if (playerGeneration != generation) return
                player?.pause()
                player?.seek(0)
                mutableState.value = state.value?.copy(playing = false, position = 0)
                playbackIds(current.track).forEach(tracker::stopPlaybackOrRecorder)
                if (state.value?.loopMode == LoopMode.ONE) {
                    seek(0); resume()
                } else if (state.value?.hasNext == true) {
                    skip(true)
                } else {
                    publish()
                }
            }
            override fun failed(error: Throwable) {
                if (playerGeneration == generation) this@AudioPlaybackController.failed(error)
            }
        }
        player = MessageAudioPlayer.open(context, file, listener)
        tracker.activePlayerStopper = stopper
    }

    fun toggle() { if (state.value?.playing == true) pause() else resume() }

    fun resume() {
        val current = state.value ?: return
        autoplayBlocked = false
        if (current.loading) return
        if (current.error || player == null) {
            command?.cancel()
            command = scope.launch { select(current.track) }
            return
        }
        val requestFocus = requestAudioFocus
        if (requestFocus == null) {
            pendingAutoplay = true
            return
        }
        pendingAutoplay = false
        if (!requestFocus()) return
        AttachmentViewerActivity.pausePictureInPicture()
        runCatching {
            player?.play()
            mutableState.value = current.copy(playing = true)
            tracker.activePlayerStopper = stopper
            handler.removeCallbacks(tick)
            tick.run()
        }.onFailure(::failed)
    }

    fun onAudioServiceReady() {
        if (pendingAutoplay) resume()
    }

    fun pause() {
        pendingAutoplay = false
        // A track still downloading or preparing would otherwise start once it is ready.
        autoplayBlocked = true
        player?.pause()
        handler.removeCallbacks(tick)
        mutableState.value = state.value?.copy(playing = false)
        publish()
    }

    fun seek(position: Int) {
        val current = state.value ?: return
        val bounded = position.coerceIn(0, current.duration)
        player?.seek((bounded / timeScale).toInt())
        pendingSeek = bounded to SystemClock.uptimeMillis() + 500
        mutableState.value = current.copy(position = bounded)
        publish()
    }

    fun cycleLoopMode() {
        val current = state.value ?: return
        val next = when (current.loopMode) {
            LoopMode.OFF -> LoopMode.ALL
            LoopMode.ALL -> LoopMode.ONE
            LoopMode.ONE -> LoopMode.OFF
        }
        loopMode = next
        mutableState.value = current.copy(loopMode = next)
        updateQueueControls()
    }

    fun setPlaybackSpeed(value: PlaybackSpeed) {
        if (state.value?.adjustable != true || !value.speed.isFinite()) return
        changePitch = value.changePitch
        setParameters(value.speed, if (value.changePitch) value.speed else 1f)
        mutableState.value = state.value?.copy(changePitch = changePitch)
    }

    private fun setParameters(newSpeed: Float, newPitch: Float) {
        val current = state.value ?: return
        if (!current.adjustable || !newSpeed.isFinite() || !newPitch.isFinite()) return
        speed = newSpeed.coerceIn(0.1f, 3f)
        pitch = newPitch.coerceIn(0.1f, 3f)
        mutableState.value = current.copy(speed = speed, pitch = pitch)
        if (current.loading) return
        if (Build.VERSION.SDK_INT >= 16) {
            player?.parameters(speed, pitch)
        } else {
            pause()
            autoplayBlocked = false
            mutableState.value = state.value?.copy(loading = true)
            load?.cancel()
            load = scope.launch {
                try {
                    prepare(current.position, current.playing)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    ensureActive()
                    failed(failure)
                }
            }
        }
    }

    fun stop(clearTracker: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) { scope.launch { stop(clearTracker) }; return }
        command?.cancel()
        command = null
        playlistLoad?.cancel()
        playlistLoad = null
        navigation?.cancel()
        navigation = null
        load?.cancel()
        load = null
        handler.removeCallbacks(tick)
        val ids = state.value?.track?.let(::playbackIds).orEmpty()
        releasePlayer()
        ids.forEach(tracker::stopPlaybackOrRecorder)
        if (tracker.activePlayerStopper === stopper) tracker.activePlayerStopper = null
        mutableState.value = null
        if (clearTracker) tracker.clearAllStates()
        loopMode = null
        pendingAutoplay = false
        val copies = synchronized(localFiles) {
            localFileGeneration++
            localFiles.values.toList().also { localFiles.clear() }
        }
        copies.forEach(File::delete)
        timeline?.dispose()
        timeline = null
        queueEvents.clear()
        queue = emptyList()
        activeRoom = null
        session = null
        source = null
    }

    private val stopper: (String) -> Unit = { id -> scope.launch { if (isCurrentTrack(id)) stop() } }

    private fun releasePlayer() {
        playerGeneration++
        pendingSeek = null
        state.value?.track?.let(::playbackIds)?.forEach(tracker::pausePlayback)
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        processed?.delete()
        processed = null
    }

    private fun failed(failure: Throwable) {
        Timber.w(failure, "Message audio playback failed")
        releasePlayer()
        mutableState.value = state.value?.copy(playing = false, loading = false, error = true)
        state.value?.track?.let(::playbackIds)?.forEach { tracker.onError(it, failure) }
    }

    private fun publish() {
        val current = state.value ?: return
        if (current.loading || current.error) return
        val fraction = if (current.duration > 0) current.position.toFloat() / current.duration else 0f
        playbackIds(current.track).forEach { id ->
            if (current.playing) tracker.updatePlayingAtPlaybackTime(id, current.position, fraction)
            else tracker.updatePausedAtPlaybackTime(id, current.position, fraction)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val current = state.value ?: return
            if (!current.playing) return
            val position = ((player?.position ?: 0) * timeScale).toInt()
            val seek = pendingSeek
            // Legacy MediaPlayer can report the old position until its asynchronous seek lands.
            if (seek != null && SystemClock.uptimeMillis() < seek.second && abs(position - seek.first) > 250) {
                handler.postDelayed(this, 100)
                return
            }
            pendingSeek = null
            mutableState.value = current.copy(position = position)
            publish()
            handler.postDelayed(this, 100)
        }
    }

    private suspend fun localFile(url: String?): File? {
        val uri = url?.let(Uri::parse) ?: return null
        if (uri.scheme == "file") return uri.path?.let(::File)
        if (uri.scheme != "content") return null
        val generation = synchronized(localFiles) {
            localFiles[url]?.takeIf { it.exists() }?.let { return it }
            localFileGeneration
        }
        val file = File.createTempFile("player-local-", null, context.cacheDir)
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            currentCoroutineContext().ensureActive()
            synchronized(localFiles) {
                if (generation != localFileGeneration) throw CancellationException("Playback stopped")
                localFiles[url]?.let { file.delete(); return it }
                localFiles[url] = file
            }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }
}
