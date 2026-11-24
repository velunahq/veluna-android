package com.example.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.data.local.DownloadedTrackEntity
import com.example.data.local.ListenEntity
import com.example.data.local.ListenSongEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.local.VelunaUserPrefs
import com.example.data.network.InnerTubeClient
import com.example.data.network.ScrobbleClient
import com.example.data.repository.TasteEngine
import com.example.data.repository.VelunaRepository
import com.example.model.DownloadProgress
import com.example.model.DownloadStatus
import com.example.model.Rating
import com.example.model.RepeatMode
import com.example.model.ResolvedStream
import com.example.model.SongItem
import com.example.util.SecurityValidators
import com.example.util.suspendRunCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

@Immutable
data class PlaybackUiState(
    val queue: List<SongItem> = emptyList(),
    val currentIndex: Int = -1,
    val nowPlaying: SongItem? = null,
    val sourceName: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val speed: Float = 1.0f,
    val streamClient: String? = null,
    val artworkAccentColor: Color? = null,
    val sleepTimerRemainingMs: Long? = null,
    val errorMessage: String? = null,
    val nowPlayingOpen: Boolean = false
)

@Immutable
data class PlaybackPositionState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class PlayerController(
    private val context: Context,
    private val dao: VelunaDao,
    private val repository: VelunaRepository,
    private val prefsRepo: UserPrefsRepository,
    private val innerTube: InnerTubeClient,
    private val scrobbleClient: ScrobbleClient,
    private val tasteEngine: TasteEngine? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _positionState = MutableStateFlow(PlaybackPositionState())
    val positionState: StateFlow<PlaybackPositionState> = _positionState.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var currentPrefs = VelunaUserPrefs()
    private var mediaController: MediaController? = null
    @Volatile private var controllerReady = CompletableDeferred<MediaController>()
    @Volatile private var connectingService = false
    private var currentListenId: Long? = null
    private var recordedListenVideoId: String? = null
    private var currentTrackStartedAtSecs: Long = 0L
    private var scrobbledCurrentTrack = false
    private var heardSecondsThisTrack = 0L
    private var pendingFlushSecs = 0L

    private var activeCandidates: List<ResolvedStream> = emptyList()
    private var activeCandidateIndex: Int = -1
    private var triedMirrorsForCurrent: Boolean = false
    private var currentCandidateSong: SongItem? = null

    private var tickerJob: Job? = null
    @Volatile private var positionUiActive = false
    @Volatile private var pendingSeekTargetMs: Long? = null
    @Volatile private var pendingSeekUntilNanos: Long = 0L
    private var sleepJob: Job? = null
    private var resolveJob: Job? = null
    private var fallbackResolveJob: Job? = null
    private var prefetchJob: Job? = null
    private var autoplayJob: Job? = null
    private var radioJob: Job? = null
    private var artworkAccentJob: Job? = null

    init {
        scope.launch {
            prefsRepo.prefsFlow.collect { prefs ->
                val prev = currentPrefs
                currentPrefs = prefs
                VelunaPlaybackService.loudnessNormEnabled = prefs.loudnessNormalization
                if (prev.eqEnabled != prefs.eqEnabled || prev.eqBandsDb != prefs.eqBandsDb) {
                    VelunaPlaybackService.instance?.applyEqualizerState(prefs.eqEnabled, prefs.eqBandsDb)
                }
                if (prev.playbackSpeed != prefs.playbackSpeed) {
                    mediaController?.playbackParameters = PlaybackParameters(prefs.playbackSpeed)
                    _state.update { if (it.speed != prefs.playbackSpeed) it.copy(speed = prefs.playbackSpeed) else it }
                }
            }
        }
        scope.launch {
            repository.likedVideoIdsFlow.collect { likedIds ->
                _state.update { st ->
                    val updatedNow = st.nowPlaying?.let { np ->
                        val desired = if (np.videoId in likedIds) Rating.LIKE else Rating.INDIFFERENT
                        if (np.rating != desired) np.copy(rating = desired) else np
                    }
                    val updatedQueue = st.queue.map { item ->
                        val desired = if (item.videoId in likedIds) Rating.LIKE else Rating.INDIFFERENT
                        if (item.rating != desired) item.copy(rating = desired) else item
                    }
                    st.copy(nowPlaying = updatedNow, queue = updatedQueue)
                }
            }
        }
        VelunaPlaybackService.onSkipNextRequested = { scope.launch { skipNext() } }
        VelunaPlaybackService.onSkipPreviousRequested = { scope.launch { skipPrevious() } }
        VelunaPlaybackService.canSkipNextProvider = { canSkipNextNow() }
        VelunaPlaybackService.canSkipPreviousProvider = { _state.value.queue.isNotEmpty() }
        connectService()
    }

    private fun canSkipNextNow(): Boolean {
        val cur = _state.value
        if (cur.queue.isEmpty()) return false
        return (cur.shuffle && cur.queue.size > 1) ||
            (cur.currentIndex + 1 in cur.queue.indices) ||
            (cur.repeatMode == RepeatMode.ALL) ||
            (currentPrefs.autoplay && cur.nowPlaying != null && !cur.nowPlaying.isLocal)
    }

    private fun connectService() {
        val existing = mediaController
        if (existing != null && existing.isConnected) return
        if (connectingService) return
        connectingService = true
        if (controllerReady.isCompleted && mediaController == null) {
            controllerReady = CompletableDeferred()
        }
        val deferred = controllerReady
        val token = SessionToken(context, ComponentName(context, VelunaPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                connectingService = false
                val controllerResult = runCatching { future.get() }
                val controller = controllerResult.getOrNull()
                if (controller == null) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(
                            controllerResult.exceptionOrNull() ?: IllegalStateException("MediaController unavailable")
                        )
                    }
                    return@addListener
                }
                mediaController = controller
                if (!deferred.isCompleted) {
                    deferred.complete(controller)
                }
                controller.playbackParameters = PlaybackParameters(currentPrefs.playbackSpeed)
                controller.removeListener(playerListener)
                controller.addListener(playerListener)
                startTicker()
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) {
                flushListenSeconds()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            _state.update { it.copy(isBuffering = buffering) }
            if (playbackState == Player.STATE_READY) {
                val ctrl = mediaController
                if (ctrl != null) {
                    val dur = ctrl.duration.takeIf { it > 0 } ?: 0L
                    if (dur > 0L && _positionState.value.durationMs != dur) {
                        _positionState.update { it.copy(durationMs = dur) }
                    }
                }
            } else if (playbackState == Player.STATE_ENDED) {
                flushListenSeconds()
                onTrackEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val song = currentCandidateSong
            if (song != null && !song.isLocal) {
                innerTube.invalidateStreamCache(song.videoId)
                val nextIdx = activeCandidateIndex + 1
                if (nextIdx in activeCandidates.indices) {
                    fallbackResolveJob?.cancel()
                    fallbackResolveJob = scope.launch {
                        if (currentCandidateSong?.videoId == song.videoId) {
                            tryCandidateStream(song, nextIdx)
                        }
                    }
                    return
                }
                if (!triedMirrorsForCurrent && currentPrefs.allowMirrorFallbacks) {
                    triedMirrorsForCurrent = true
                    fallbackResolveJob?.cancel()
                    fallbackResolveJob = scope.launch {
                        val mirrors = innerTube.resolveMirrorStreamCandidates(song.videoId, currentPrefs.audioQuality)
                        if (currentCandidateSong?.videoId != song.videoId) return@launch
                        if (mirrors.isNotEmpty()) {
                            val start = activeCandidates.size
                            activeCandidates = activeCandidates + mirrors
                            tryCandidateStream(song, start)
                            return@launch
                        }
                        surfacePlaybackError(error)
                    }
                    return
                }
            }
            surfacePlaybackError(error)
        }
    }

    private fun surfacePlaybackError(error: PlaybackException) {
        val safeDetail = SecurityValidators.sanitizeErrorText(error.localizedMessage, "Source unavailable")
        _state.update {
            it.copy(
                isBuffering = false,
                isPlaying = false,
                errorMessage = "Playback failed (${error.errorCodeName}): $safeDetail"
            )
        }
    }

    fun setPositionUiActive(active: Boolean) { positionUiActive = active }

    @Suppress("OPT_IN_USAGE")
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var accumulatedMs = 0L
            kotlinx.coroutines.flow.combine(
                _state.map { it.isPlaying },
                _state.map { it.nowPlayingOpen },
                _state.map { it.isBuffering }
            ) { playing, open, buffering -> playing || open || buffering }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    while (true) {
                        val stepMs = if (positionUiActive) 250L else 500L
                        val ctrl = mediaController
                        if (ctrl != null) {
                            val rawPos = ctrl.currentPosition.coerceAtLeast(0L)
                            val pendingTarget = pendingSeekTargetMs
                            val pos = if (pendingTarget != null) {
                                if (System.nanoTime() < pendingSeekUntilNanos &&
                                    kotlin.math.abs(rawPos - pendingTarget) > 1500L
                                ) {
                                    pendingTarget
                                } else {
                                    pendingSeekTargetMs = null
                                    rawPos
                                }
                            } else {
                                rawPos
                            }
                            val dur = ctrl.duration.takeIf { it > 0 } ?: _positionState.value.durationMs
                            val curPosState = _positionState.value
                            if (curPosState.positionMs != pos || curPosState.durationMs != dur) {
                                _positionState.value = PlaybackPositionState(positionMs = pos, durationMs = dur)
                            }
                            if (ctrl.isPlaying) {
                                accumulatedMs += stepMs
                                while (accumulatedMs >= 1000L) {
                                    accumulatedMs -= 1000L
                                    recordOneSecondHeard()
                                }
                            }
                        }
                        delay(stepMs)
                    }
                }
        }
    }

    private fun recordOneSecondHeard() {
        val song = _state.value.nowPlaying ?: return
        heardSecondsThisTrack++
        pendingFlushSecs++
        if (pendingFlushSecs >= 15L) {
            flushListenSeconds()
        }
        val effectiveDurationSecs = (_positionState.value.durationMs / 1000L)
            .takeIf { it > 0L } ?: song.durationSeconds
        if (!scrobbledCurrentTrack && ScrobbleClient.shouldScrobbleTrack(effectiveDurationSecs, heardSecondsThisTrack)) {
            scrobbledCurrentTrack = true
            scope.launch(Dispatchers.IO) {
                scrobbleClient.enqueueScrobble(song, currentTrackStartedAtSecs, currentPrefs)
            }
        }
    }

    private fun flushListenSeconds() {
        val lid = currentListenId ?: return
        val delta = pendingFlushSecs
        if (delta <= 0L) return
        pendingFlushSecs = 0L
        scope.launch(Dispatchers.IO) {
            dao.addListenSeconds(lid, delta)
        }
    }

    private fun finalizeCurrentTrackOutcome() {
        flushListenSeconds()
        val prevSong = _state.value.nowPlaying ?: return
        val heard = heardSecondsThisTrack
        if (heard <= 0L) return
        val durSecs = (_positionState.value.durationMs / 1000L).takeIf { it > 0L } ?: prevSong.durationSeconds
        scope.launch {
            tasteEngine?.recordPlaybackOutcome(prevSong, heard, durSecs)
        }
    }

    fun openNowPlaying(open: Boolean) {
        _state.update { it.copy(nowPlayingOpen = open) }
    }

    fun playSong(song: SongItem, sourceName: String? = null, openPlayer: Boolean = true) {
        playQueue(listOf(song), 0, sourceName ?: song.title, openPlayer)
    }

    fun playQueue(
        items: List<SongItem>,
        startIndex: Int = 0,
        sourceName: String? = null,
        openPlayer: Boolean = true
    ) {
        playQueueInternal(
            items = items,
            startIndex = startIndex,
            sourceName = sourceName,
            openPlayer = openPlayer,
            triggerAutoplay = true,
            cancelRadio = true
        )
    }

    private fun playQueueInternal(
        items: List<SongItem>,
        startIndex: Int,
        sourceName: String?,
        openPlayer: Boolean,
        triggerAutoplay: Boolean,
        cancelRadio: Boolean
    ) {
        if (items.isEmpty()) return
        if (cancelRadio) radioJob?.cancel()
        autoplayJob?.cancel()
        val safeIdx = startIndex.coerceIn(0, items.lastIndex)
        _state.update {
            it.copy(
                queue = items,
                currentIndex = safeIdx,
                sourceName = sourceName,
                nowPlayingOpen = if (openPlayer) true else it.nowPlayingOpen,
                errorMessage = null
            )
        }
        playIndex(safeIdx)
        if (triggerAutoplay && currentPrefs.autoplay && items.size == 1 && !items[0].isLocal) {
            fetchAutoplayContinuation(items[0])
        }
    }

    fun playNext(song: SongItem) {
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            playSong(song)
            return
        }
        val insertAt = (cur.currentIndex + 1).coerceAtMost(cur.queue.size)
        val updated = cur.queue.toMutableList().apply {
            add(insertAt, song.copy(queued = true))
        }
        _state.update { it.copy(queue = updated) }
        _events.tryEmit("Playing next: ${song.title}")
    }

    fun addToQueue(song: SongItem) {
        val cur = _state.value
        if (cur.queue.isEmpty()) {
            playSong(song)
            return
        }
        val updated = cur.queue + song.copy(queuedEnd = true)
        _state.update { it.copy(queue = updated) }
        _events.tryEmit("Added to queue: ${song.title}")
    }

    fun removeFromQueue(index: Int) {
        val cur = _state.value
        if (index !in cur.queue.indices) return
        val updated = cur.queue.toMutableList().apply { removeAt(index) }
        val newIdx = when {
            updated.isEmpty() -> -1
            index < cur.currentIndex -> cur.currentIndex - 1
            index == cur.currentIndex -> cur.currentIndex.coerceAtMost(updated.lastIndex)
            else -> cur.currentIndex
        }
        _state.update { it.copy(queue = updated, currentIndex = newIdx) }
        if (index == cur.currentIndex && newIdx >= 0) {
            playIndex(newIdx)
        } else if (updated.isEmpty()) {
            finalizeCurrentTrackOutcome()
            resolveJob?.cancel()
            fallbackResolveJob?.cancel()
            prefetchJob?.cancel()
            autoplayJob?.cancel()
            radioJob?.cancel()
            currentListenId = null
            recordedListenVideoId = null
            currentCandidateSong = null
            pendingSeekTargetMs = null
            mediaController?.stop()
            _state.update { it.copy(nowPlaying = null, isPlaying = false, isBuffering = false) }
            _positionState.value = PlaybackPositionState()
        }
    }

    fun clearQueue() {
        val cur = _state.value
        val active = cur.nowPlaying
        if (active != null) {
            _state.update { it.copy(queue = listOf(active), currentIndex = 0) }
        } else {
            resolveJob?.cancel()
            fallbackResolveJob?.cancel()
            prefetchJob?.cancel()
            autoplayJob?.cancel()
            radioJob?.cancel()
            currentListenId = null
            recordedListenVideoId = null
            currentCandidateSong = null
            pendingSeekTargetMs = null
            mediaController?.stop()
            _state.update { it.copy(queue = emptyList(), currentIndex = -1, nowPlaying = null, isBuffering = false) }
            _positionState.value = PlaybackPositionState()
        }
    }

    fun togglePlayPause() {
        val ctrl = mediaController?.takeIf { it.isConnected }
        if (ctrl == null) {
            mediaController = null
            connectService()
            val cur = _state.value
            if (cur.queue.isNotEmpty() && cur.currentIndex in cur.queue.indices && !cur.isBuffering) {
                playIndex(cur.currentIndex)
            }
            return
        }
        if (ctrl.isPlaying) {
            flushListenSeconds()
            ctrl.pause()
        } else {
            ctrl.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        pendingSeekTargetMs = clamped
        pendingSeekUntilNanos = System.nanoTime() + 750_000_000L
        mediaController?.seekTo(clamped)
        _positionState.update { it.copy(positionMs = clamped) }
    }

    fun skipNext() {
        val cur = _state.value
        if (cur.queue.isEmpty()) return
        val nextIdx = if (cur.shuffle && cur.queue.size > 1) {
            val candidates = cur.queue.indices.filter { it != cur.currentIndex }
            candidates.random()
        } else {
            cur.currentIndex + 1
        }

        if (nextIdx in cur.queue.indices) {
            playIndex(nextIdx)
        } else if (cur.repeatMode == RepeatMode.ALL && cur.queue.isNotEmpty()) {
            playIndex(0)
        } else if (currentPrefs.autoplay && cur.nowPlaying != null && !cur.nowPlaying.isLocal) {
            fetchAutoplayContinuation(cur.nowPlaying, playImmediately = true)
        }
    }

    fun skipPrevious() {
        if (_positionState.value.positionMs > 4000L) {
            seekTo(0L)
            return
        }
        val cur = _state.value
        val prevIdx = cur.currentIndex - 1
        if (prevIdx in cur.queue.indices) {
            playIndex(prevIdx)
        } else {
            seekTo(0L)
        }
    }

    private fun onTrackEnded() {
        val cur = _state.value
        when {
            cur.repeatMode == RepeatMode.ONE -> {
                finalizeCurrentTrackOutcome()
                currentListenId = null
                recordedListenVideoId = null
                seekTo(0L)
                mediaController?.play()
                cur.nowPlaying?.let { repeatedSong ->
                    scope.launch { recordListenStart(repeatedSong) }
                }
            }
            (cur.shuffle && cur.queue.size > 1) || (cur.currentIndex + 1 in cur.queue.indices) -> {
                skipNext()
            }
            cur.repeatMode == RepeatMode.ALL && cur.queue.isNotEmpty() -> {
                playIndex(0)
            }
            currentPrefs.autoplay && cur.nowPlaying != null && !cur.nowPlaying.isLocal -> {
                fetchAutoplayContinuation(cur.nowPlaying, playImmediately = true)
            }
        }
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
    }

    fun cycleRepeat() {
        _state.update {
            val next = when (it.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            it.copy(repeatMode = next)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        scope.launch { prefsRepo.setPlaybackSpeed(speed) }
        mediaController?.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleLikeCurrent() {
        val song = _state.value.nowPlaying ?: return
        toggleLike(song)
    }

    fun toggleLike(song: SongItem) {
        if (song.isLocal || song.videoId.startsWith(com.example.model.LOCAL_SONG_PREFIX)) return
        scope.launch {
            val liked = repository.toggleLike(song)
            val newRating = if (liked) Rating.LIKE else Rating.INDIFFERENT
            _state.update { st ->
                val updatedNow = if (st.nowPlaying?.videoId == song.videoId) {
                    st.nowPlaying.copy(rating = newRating)
                } else {
                    st.nowPlaying
                }
                st.copy(
                    nowPlaying = updatedNow,
                    queue = st.queue.map { if (it.videoId == song.videoId) it.copy(rating = newRating) else it }
                )
            }
            tasteEngine?.recordLikeChange(song, liked)
        }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            return
        }
        var remaining = minutes * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = remaining) }
        sleepJob = scope.launch {
            while (remaining > 0L) {
                delay(1000L)
                remaining -= 1000L
                _state.update { it.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            mediaController?.pause()
            _state.update { it.copy(sleepTimerRemainingMs = null) }
        }
    }

    fun startRadio(song: SongItem) {
        if (song.isLocal) return
        autoplayJob?.cancel()
        radioJob?.cancel()
        val cur = _state.value
        val alreadyPlayingThisSong = cur.nowPlaying?.videoId == song.videoId && cur.currentIndex in cur.queue.indices

        if (!alreadyPlayingThisSong) {
            // Start playing the seed track immediately without blocking on the radio mix network call
            playQueueInternal(
                items = listOf(song),
                startIndex = 0,
                sourceName = "${song.title} Radio",
                openPlayer = true,
                triggerAutoplay = false,
                cancelRadio = false
            )
        } else {
            _state.update { it.copy(sourceName = "${song.title} Radio") }
        }

        radioJob = scope.launch {
            val radioTracks = if (tasteEngine != null) {
                tasteEngine.generateSmartAutoplayTracks(
                    seed = song,
                    existingQueueIds = setOf(song.videoId),
                    limit = 25
                )
            } else {
                innerTube.getNext(song.videoId, playlistId = "RDAMVM${song.videoId}").getOrNull().orEmpty()
                    .filter { it.videoId != song.videoId }
                    .distinctBy { it.videoId }
                    .take(25)
                    .map { it.copy(autoplay = true) }
            }

            val finalTracks = if (radioTracks.isNotEmpty()) {
                radioTracks
            } else {
                val artistLead = song.artists.substringBefore(",").trim()
                innerTube.searchSongs("$artistLead ${song.title}".trim()).getOrNull().orEmpty()
                    .filter { it.videoId != song.videoId }
                    .distinctBy { it.videoId }
                    .take(20)
                    .map { it.copy(autoplay = true) }
            }

            if (_state.value.nowPlaying?.videoId != song.videoId) return@launch

            if (finalTracks.isNotEmpty()) {
                _state.update { st ->
                    val currentIdx = st.currentIndex
                    val keepPrefix = if (currentIdx in st.queue.indices) {
                        st.queue.subList(0, currentIdx + 1).toList()
                    } else {
                        listOf(song)
                    }
                    val prefixIds = keepPrefix.map { it.videoId }.toHashSet()
                    val filteredRadio = finalTracks.filter { it.videoId !in prefixIds }
                    st.copy(
                        queue = keepPrefix + filteredRadio,
                        currentIndex = if (currentIdx >= 0) currentIdx else 0,
                        sourceName = "${song.title} Radio"
                    )
                }
                _events.tryEmit("Queued ${finalTracks.size} radio songs")
                prefetchNextInQueue()
            } else {
                _events.tryEmit("Could not find radio tracks right now")
            }
        }
    }

    fun playIndex(index: Int) {
        val cur = _state.value
        val song = cur.queue.getOrNull(index) ?: return
        if (cur.nowPlaying != null) {
            finalizeCurrentTrackOutcome()
            mediaController?.pause()
        }
        currentListenId = null
        recordedListenVideoId = null
        pendingSeekTargetMs = null
        resolveJob?.cancel()
        fallbackResolveJob?.cancel()
        prefetchJob?.cancel()

        resolveJob = scope.launch {
            val isLiked = withContext(Dispatchers.IO) { repository.isLiked(song.videoId) }
            val hydratedSong = song.copy(rating = if (isLiked) Rating.LIKE else Rating.INDIFFERENT)

            activeCandidates = emptyList()
            activeCandidateIndex = -1
            triedMirrorsForCurrent = false
            currentCandidateSong = hydratedSong

            _state.update {
                it.copy(
                    currentIndex = index,
                    nowPlaying = hydratedSong,
                    isPlaying = false,
                    isBuffering = true,
                    errorMessage = null
                )
            }
            _positionState.value = PlaybackPositionState(
                positionMs = 0L,
                durationMs = hydratedSong.durationSeconds * 1000L
            )

            extractArtworkAccent(hydratedSong.thumbnail)

            // 1) Local audio file
            if (hydratedSong.isLocal && hydratedSong.localUri != null) {
                playUri(hydratedSong.localUri.toUri(), hydratedSong, "Local File", null, null)
                prefetchNextInQueue()
                return@launch
            }

            // 2) Offline downloaded track (strictly confined to app downloads directory)
            val downloaded = withContext(Dispatchers.IO) { dao.getDownload(hydratedSong.videoId) }
            val offlineFile = downloaded?.let { resolveSafeDownloadFile(it.filePath) }
            if (offlineFile != null) {
                playUri(Uri.fromFile(offlineFile), hydratedSong, "Offline", null, null)
                prefetchNextInQueue()
                return@launch
            }

            // 3) Resolve prioritized stream candidates via Hedged InnerTube + Parallel Mirror Race
            val resolved = innerTube.resolveStreamCandidates(
                hydratedSong.videoId,
                currentPrefs.audioQuality,
                allowMirrorFallbacks = currentPrefs.allowMirrorFallbacks
            )
            resolved.onSuccess { list ->
                activeCandidates = list
                tryCandidateStream(hydratedSong, 0)
                prefetchNextInQueue()
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = SecurityValidators.safeUserErrorMessage(
                            err,
                            "Unable to resolve audio stream"
                        )
                    )
                }
            }

            if (currentPrefs.autoplay && index >= _state.value.queue.lastIndex - 1 && !hydratedSong.isLocal) {
                fetchAutoplayContinuation(hydratedSong)
            }
        }
    }

    private fun resolveSafeDownloadFile(rawPath: String): File? {
        val downloadsDir = File(context.filesDir, "downloads")
        val canonicalRoot = runCatching { downloadsDir.canonicalPath }.getOrNull() ?: return null
        val target = File(rawPath)
        val canonicalTarget = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (!canonicalTarget.startsWith("$canonicalRoot${File.separator}")) return null
        return target.takeIf { it.isFile && it.exists() }
    }

    private fun prefetchNextInQueue() {
        val nextSong = _state.value.queue.getOrNull(_state.value.currentIndex + 1) ?: return
        if (nextSong.isLocal) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            delay(900L)
            suspendRunCatching {
                innerTube.resolveStreamCandidates(
                    nextSong.videoId,
                    currentPrefs.audioQuality,
                    allowMirrorFallbacks = false, // prefetch uses primary sources only
                )
            }
        }
    }

    private suspend fun tryCandidateStream(song: SongItem, idx: Int) {
        if (currentCandidateSong?.videoId != song.videoId) return
        val candidate = activeCandidates.getOrNull(idx) ?: return
        if (!SecurityValidators.isSafeHttpsUrl(candidate.url, maxLength = 8192)) {
            val nextIdx = idx + 1
            if (nextIdx in activeCandidates.indices) {
                tryCandidateStream(song, nextIdx)
            } else {
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        errorMessage = "Unable to play audio stream from untrusted source"
                    )
                }
            }
            return
        }
        activeCandidateIndex = idx
        VelunaPlaybackService.registerStreamHeaders(candidate.url, candidate.userAgent)
        val mimeOverride = if (candidate.isHls) MimeTypes.APPLICATION_M3U8 else null
        playUri(
            uri = candidate.url.toUri(),
            song = song,
            clientName = candidate.clientName,
            loudnessDb = candidate.loudnessDb,
            mimeTypeOverride = mimeOverride
        )
    }

    private suspend fun playUri(
        uri: Uri,
        song: SongItem,
        clientName: String,
        loudnessDb: Double?,
        mimeTypeOverride: String?
    ) {
        val ctrl = mediaController ?: run {
            if (controllerReady.isCompleted) {
                connectService()
            }
            withTimeoutOrNull(5000L) {
                runCatching { controllerReady.await() }.getOrNull()
            }
        }
        if (ctrl == null) {
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = "Audio playback service unavailable. Please try again."
                )
            }
            return
        }
        recordListenStart(song)
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artists)
            .setAlbumTitle(song.album)
            .apply {
                SecurityValidators.sanitizeHttpsUrl(song.thumbnail, maxLength = 2048)?.let {
                    setArtworkUri(it.toUri())
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.videoId)
            .setUri(uri)
            .apply {
                if (mimeTypeOverride != null) setMimeType(mimeTypeOverride)
            }
            .setMediaMetadata(metadata)
            .build()

        VelunaPlaybackService.instance?.applyLoudnessNormalization(loudnessDb)
        ctrl.setMediaItem(mediaItem)
        ctrl.prepare()
        ctrl.play()

        _state.update { it.copy(streamClient = clientName, isBuffering = false, errorMessage = null) }
    }

    private suspend fun recordListenStart(song: SongItem) {
        if (recordedListenVideoId == song.videoId) return
        heardSecondsThisTrack = 0L
        pendingFlushSecs = 0L
        scrobbledCurrentTrack = false
        val nowMs = System.currentTimeMillis()
        currentTrackStartedAtSecs = nowMs / 1000L
        recordedListenVideoId = song.videoId
        currentListenId = try {
            withContext(Dispatchers.IO) {
                dao.upsertListenSong(ListenSongEntity(song.videoId, repository.encodeSong(song)))
                dao.insertListen(ListenEntity(videoId = song.videoId, playedAt = nowMs, secs = 0L))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        scope.launch(Dispatchers.IO) {
            scrobbleClient.submitNowPlaying(song, currentPrefs)
            scrobbleClient.flushPendingScrobbles(nowMs)
        }
    }

    private fun fetchAutoplayContinuation(seed: SongItem, playImmediately: Boolean = false) {
        if (autoplayJob?.isActive == true && !playImmediately) return
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            val existingIds = _state.value.queue.map { it.videoId }.toHashSet()
            val fresh = if (tasteEngine != null) {
                tasteEngine.generateSmartAutoplayTracks(seed, existingIds, limit = 16)
            } else {
                innerTube.getNext(seed.videoId, playlistId = "RDAMVM${seed.videoId}").getOrNull().orEmpty()
                    .filter { it.videoId !in existingIds }
                    .map { it.copy(autoplay = true) }
            }
            if (fresh.isNotEmpty()) {
                var addedStartIdx = -1
                _state.update { st ->
                    val currentIds = st.queue.asSequence().map { it.videoId }.toHashSet()
                    val deduped = fresh.filter { it.videoId !in currentIds }
                    if (deduped.isEmpty()) return@update st
                    addedStartIdx = st.queue.size
                    st.copy(queue = st.queue + deduped)
                }
                if (addedStartIdx >= 0) {
                    if (playImmediately) {
                        playIndex(addedStartIdx)
                    } else {
                        prefetchNextInQueue()
                    }
                }
            }
        }
    }

    private fun extractArtworkAccent(url: String?) {
        artworkAccentJob?.cancel()
        if (url.isNullOrBlank()) {
            _state.update { it.copy(artworkAccentColor = null) }
            return
        }
        artworkAccentJob = scope.launch(Dispatchers.IO) {
            val bmp = loadBitmap(url) ?: return@launch
            val palette = Palette.from(bmp).maximumColorCount(12).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
            if (swatch != null && _state.value.nowPlaying?.thumbnail == url) {
                _state.update { it.copy(artworkAccentColor = Color(swatch.rgb)) }
            }
        }
    }

    private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val safeUrl = SecurityValidators.sanitizeHttpsUrl(url, maxLength = 2048) ?: return@withContext null
        suspendRunCatching {
            val req = ImageRequest.Builder(context)
                .data(safeUrl)
                .allowHardware(false)
                .size(120)
                .build()
            val res = Coil.imageLoader(context).execute(req)
            ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    fun downloadTrack(song: SongItem) {
        if (song.isLocal) return
        if (_downloads.value[song.videoId]?.status == DownloadStatus.DOWNLOADING) return

        scope.launch(Dispatchers.IO) {
            _downloads.update {
                it + (song.videoId to DownloadProgress(
                    videoId = song.videoId,
                    title = song.title,
                    artists = song.artists,
                    thumbnail = song.thumbnail,
                    progress = 0f,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    status = DownloadStatus.DOWNLOADING
                ))
            }

            var partFile: File? = null
            try {
                val stream = innerTube.resolveStreamUrl(
                    song.videoId,
                    currentPrefs.audioQuality,
                    allowMirrorFallbacks = currentPrefs.allowMirrorFallbacks
                ).getOrThrow()
                if (stream.isHls || !SecurityValidators.isSafeHttpsUrl(stream.url, maxLength = 8192)) {
                    throw IllegalStateException("Downloadable HTTPS audio stream unavailable")
                }
                val ext = when {
                    "webm" in stream.mimeType || "opus" in stream.mimeType -> "opus"
                    else -> "m4a"
                }
                val dir = File(context.filesDir, "downloads").apply { mkdirs() }
                val fileId = SecurityValidators.sha256Hex(song.videoId)
                val outFile = File(dir, "$fileId.$ext")
                partFile = File(dir, "$fileId.$ext.part")

                val req = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", stream.userAgent)
                    .build()

                innerTube.httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Download server returned HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty download response")
                    val total = body.contentLength()
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw IllegalStateException("Audio file exceeds maximum allowed size")
                    }
                    var downloaded = 0L
                    var lastUiProgressAt = System.nanoTime()
                    var lastUiProgressBytes = 0L
                    val buf = ByteArray(16 * 1024)
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                downloaded += read
                                if (downloaded > MAX_DOWNLOAD_BYTES) {
                                    throw IllegalStateException("Audio file exceeds maximum allowed size")
                                }
                                output.write(buf, 0, read)
                                val now = System.nanoTime()
                                if (downloaded - lastUiProgressBytes >= DOWNLOAD_PROGRESS_BYTE_INTERVAL &&
                                    now - lastUiProgressAt >= DOWNLOAD_PROGRESS_TIME_INTERVAL_NANOS
                                ) {
                                    val pct = if (total > 0) {
                                        (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0.5f
                                    }
                                    _downloads.update { map ->
                                        map[song.videoId]?.let { progress ->
                                            map + (song.videoId to progress.copy(
                                                progress = pct,
                                                bytesDownloaded = downloaded,
                                                totalBytes = total
                                            ))
                                        } ?: map
                                    }
                                    lastUiProgressAt = now
                                    lastUiProgressBytes = downloaded
                                }
                            }
                        }
                    }

                    if (!partFile.renameTo(outFile)) {
                        partFile.copyTo(outFile, overwrite = true)
                        partFile.delete()
                    }
                    partFile = null

                    val previousDownload = dao.getDownload(song.videoId)?.let { resolveSafeDownloadFile(it.filePath) }
                    dao.upsertDownload(
                        DownloadedTrackEntity(
                            videoId = song.videoId,
                            filePath = outFile.absolutePath,
                            format = ext.uppercase(),
                            bitrateKbps = stream.bitrateKbps,
                            sizeBytes = outFile.length(),
                            songJson = repository.encodeSong(song)
                        )
                    )
                    if (previousDownload != null && previousDownload.canonicalPath != outFile.canonicalPath) {
                        runCatching { previousDownload.delete() }
                    }

                    _downloads.update { map ->
                        map[song.videoId]?.let { progress ->
                            map + (song.videoId to progress.copy(
                                progress = 1f,
                                status = DownloadStatus.COMPLETED
                            ))
                        } ?: map
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                _downloads.update { map ->
                    map[song.videoId]?.let { progress ->
                        map + (song.videoId to progress.copy(
                            status = DownloadStatus.FAILED,
                            error = SecurityValidators.safeUserErrorMessage(t, "Download failed")
                        ))
                    } ?: map
                }
            } finally {
                partFile?.let { tmp -> runCatching { if (tmp.exists()) tmp.delete() } }
            }
        }
    }

    fun dismissDownloadStatus(videoId: String) {
        _downloads.update { current ->
            val entry = current[videoId]
            if (entry != null && entry.status != DownloadStatus.DOWNLOADING) {
                current - videoId
            } else {
                current
            }
        }
    }

    fun deleteDownload(videoId: String) {
        scope.launch(Dispatchers.IO) {
            dao.getDownload(videoId)?.let { resolveSafeDownloadFile(it.filePath)?.delete() }
            dao.deleteDownload(videoId)
            _downloads.update { it - videoId }
        }
    }

    private companion object {
        const val DOWNLOAD_PROGRESS_BYTE_INTERVAL = 128L * 1024L
        const val DOWNLOAD_PROGRESS_TIME_INTERVAL_NANOS = 250_000_000L
        const val MAX_DOWNLOAD_BYTES = 250L * 1024L * 1024L
    }
}
