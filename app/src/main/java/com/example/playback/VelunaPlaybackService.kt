package com.example.playback

import android.app.PendingIntent
import android.net.Uri
import android.content.Intent
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.util.SecurityValidators
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.File
import android.system.Os
import kotlin.math.roundToInt

class VelunaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        val streamHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val orig = chain.request()
                val reqUrl = orig.url.toString()
                val host = orig.url.host
                val mappedUa = urlUserAgents[reqUrl]
                    ?: hostUserAgents[host]
                    ?: activeUserAgent
                val builder = orig.newBuilder()
                    .header("User-Agent", mappedUa)
                if (SecurityValidators.isYouTubeStreamHost(host) && mappedUa.startsWith("Mozilla/")) {
                    builder.header("Origin", "https://music.youtube.com")
                    builder.header("Referer", "https://music.youtube.com/")
                }
                chain.proceed(builder.build())
            }
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(streamHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachAudioEffects(audioSessionId)
            }
        })

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                if (canSkipNextProvider()) {
                    builder.add(COMMAND_SEEK_TO_NEXT)
                    builder.add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (canSkipPreviousProvider()) {
                    builder.add(COMMAND_SEEK_TO_PREVIOUS)
                    builder.add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                return builder.build()
            }

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                    canSkipNextProvider() || super.isCommandAvailable(command)
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                    canSkipPreviousProvider() || super.isCommandAvailable(command)
                else -> super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean =
                canSkipNextProvider() || super.hasNextMediaItem()

            override fun hasPreviousMediaItem(): Boolean =
                canSkipPreviousProvider() || super.hasPreviousMediaItem()

            override fun seekToNext() {
                onSkipNextRequested?.invoke() ?: super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                onSkipNextRequested?.invoke() ?: super.seekToNextMediaItem()
            }

            override fun seekToPrevious() {
                onSkipPreviousRequested?.invoke() ?: super.seekToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                onSkipPreviousRequested?.invoke() ?: super.seekToPreviousMediaItem()
            }
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(VelunaMediaSessionCallback())
            .build()

        attachAudioEffects(player.audioSessionId)
    }

    @OptIn(UnstableApi::class)
    internal inner class VelunaMediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (!isControllerAuthorized(session, controller)) {
                return MediaSession.ConnectionResult.reject()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            return if (isControllerAuthorized(session, controller)) {
                SessionResult.RESULT_SUCCESS
            } else {
                SessionResult.RESULT_ERROR_PERMISSION_DENIED
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            validateMediaItems(mediaSession, controller, mediaItems)?.let {
                return Futures.immediateFailedFuture(it)
            }
            return Futures.immediateFuture(mediaItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            validateMediaItems(mediaSession, controller, mediaItems)?.let {
                return Futures.immediateFailedFuture(it)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }
    }

    private fun validateMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): Exception? {
        if (!isControllerAuthorized(mediaSession, controller)) {
            return SecurityException("Unauthorized media controller")
        }
        if (mediaItems.any { item ->
                val uri = item.localConfiguration?.uri ?: return@any true
                !isAllowedMediaItemUri(uri)
            }) {
            return IllegalArgumentException("Media item URI is not allowed")
        }
        return null
    }

    private fun isAllowedMediaItemUri(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "https" -> uri.host?.let(SecurityValidators::isKnownPlaybackStreamHost) == true
            "file" -> uri.path?.let(::isSafeDownloadPath) == true
            "content" -> {
                if (uri.authority !in setOf(packageName, "$packageName.fileprovider")) {
                    false
                } else {
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            isSafeDownloadPath(Os.readlink("/proc/self/fd/${descriptor.fd}"))
                        } ?: false
                    }.getOrDefault(false)
                }
            }
            else -> false
        }
    }

    private fun isSafeDownloadPath(path: String): Boolean {
        val downloadRoot = runCatching { File(filesDir, "downloads").canonicalPath }.getOrNull() ?: return false
        val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        return canonical.startsWith("$downloadRoot${File.separator}") && File(canonical).isFile
    }

    @OptIn(UnstableApi::class)
    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        runCatching {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId)
        }
        runCatching {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId)
        }
        applyEqualizerState(lastEqEnabled, lastEqBands)
        applyLoudnessNormalization(lastLoudnessDb)
    }

    fun applyEqualizerState(enabled: Boolean, bandsDb10: List<Float>) {
        lastEqEnabled = enabled
        lastEqBands = bandsDb10
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = enabled
            if (!enabled) return
            val numBands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val minMb = range[0].toFloat()
            val maxMb = range[1].toFloat()
            for (bandIdx in 0 until numBands) {
                val centerHz = eq.getCenterFreq(bandIdx.toShort()) / 1000
                val uiIdx = when {
                    centerHz <= 45 -> 0
                    centerHz <= 90 -> 1
                    centerHz <= 180 -> 2
                    centerHz <= 350 -> 3
                    centerHz <= 750 -> 4
                    centerHz <= 1500 -> 5
                    centerHz <= 3000 -> 6
                    centerHz <= 6000 -> 7
                    centerHz <= 12000 -> 8
                    else -> 9
                }
                val db = bandsDb10.getOrElse(uiIdx) { 0f }
                val targetMb = (db * 100f).coerceIn(minMb, maxMb).roundToInt().toShort()
                eq.setBandLevel(bandIdx.toShort(), targetMb)
            }
        }
    }

    fun applyLoudnessNormalization(loudnessDb: Double?) {
        lastLoudnessDb = loudnessDb
        val enhancer = loudnessEnhancer ?: return
        runCatching {
            if (loudnessDb == null || !loudnessNormEnabled) {
                enhancer.enabled = false
            } else {
                val gainDb = (-14.0 - loudnessDb).coerceIn(-6.0, 8.0)
                if (gainDb > 0.5) {
                    enhancer.setTargetGain((gainDb * 100).roundToInt())
                    enhancer.enabled = true
                } else {
                    enhancer.enabled = false
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        runCatching { equalizer?.release() }
        equalizer = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: VelunaPlaybackService? = null
        @Volatile
        var lastEqEnabled: Boolean = false
        @Volatile
        var lastEqBands: List<Float> = List(10) { 0f }
        @Volatile
        var lastLoudnessDb: Double? = null
        @Volatile
        var loudnessNormEnabled: Boolean = true
        @Volatile
        var onSkipNextRequested: (() -> Unit)? = null
        @Volatile
        var onSkipPreviousRequested: (() -> Unit)? = null
        @Volatile
        var canSkipNextProvider: () -> Boolean = { false }
        @Volatile
        var canSkipPreviousProvider: () -> Boolean = { true }

        @Volatile
        var activeUserAgent: String =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

        private val urlUserAgents = ConcurrentHashMap<String, String>()
        private val hostUserAgents = ConcurrentHashMap<String, String>()

        @OptIn(UnstableApi::class)
        internal fun isControllerAuthorized(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): Boolean {
            return controller.uid == Process.myUid() ||
                controller.isTrusted ||
                session.isMediaNotificationController(controller) ||
                session.isAutoCompanionController(controller) ||
                session.isAutomotiveController(controller)
        }

        fun registerStreamHeaders(url: String, userAgent: String) {
            if (!SecurityValidators.isSafeHttpsUrl(url, maxLength = 8192)) return
            activeUserAgent = userAgent
            if (urlUserAgents.size >= 64) {
                urlUserAgents.clear()
            }
            urlUserAgents[url] = userAgent
            url.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }?.let { host ->
                if (hostUserAgents.size >= 64) {
                    hostUserAgents.clear()
                }
                hostUserAgents[host] = userAgent
            }
        }
    }
}
