package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.data.local.PendingScrobbleEntity
import com.example.data.local.ScrobbleServices
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.local.VelunaUserPrefs
import com.example.model.SongItem
import com.example.util.SecurityValidators
import com.example.util.readUtf8AtMost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

sealed interface ScrobbleSubmitResult {
    data object Success : ScrobbleSubmitResult
    data class Failure(
        val retryable: Boolean,
        val code: Int? = null,
        val reason: String
    ) : ScrobbleSubmitResult
}

@Serializable
data class QueuedScrobbleTrack(
    val title: String,
    val artists: String,
    val album: String? = null,
    val startedAtSecs: Long
)

class ScrobbleClient(
    private val httpClient: OkHttpClient,
    private val dao: VelunaDao? = null,
    private val prefsRepo: UserPrefsRepository? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val flushMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observePendingCount(): Flow<Int> = dao?.observePendingScrobbleCount() ?: flowOf(0)

    fun registerConnectivityFlush(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    backgroundScope.launch { flushPendingScrobbles() }
                }
            })
        }
    }

    suspend fun submitNowPlaying(song: SongItem, prefs: VelunaUserPrefs) {
        if (!prefs.scrobblingEnabled || song.isLocal) return
        if (prefs.listenBrainzToken.isNotBlank()) {
            submitListenBrainz(song, prefs.listenBrainzToken, "playing_now", null)
        }
        if (prefs.lastFmSessionKey.isNotBlank() && prefs.lastFmApiKey.isNotBlank() && prefs.lastFmApiSecret.isNotBlank()) {
            submitLastFm(
                method = "track.updateNowPlaying",
                song = song,
                apiKey = prefs.lastFmApiKey,
                secret = prefs.lastFmApiSecret,
                sessionKey = prefs.lastFmSessionKey,
                timestampSecs = null
            )
        }
    }

    suspend fun enqueueScrobble(song: SongItem, startedAtSecs: Long, prefs: VelunaUserPrefs) {
        if (!prefs.scrobblingEnabled || song.isLocal) return
        val localDao = dao ?: run {
            submitScrobbleDirect(song, startedAtSecs, prefs)
            return
        }

        val payloadJson = json.encodeToString(
            QueuedScrobbleTrack(
                title = song.title,
                artists = song.artists,
                album = song.album,
                startedAtSecs = startedAtSecs
            )
        )
        if (prefs.listenBrainzToken.isNotBlank()) {
            localDao.insertPendingScrobble(
                PendingScrobbleEntity(
                    service = ScrobbleServices.LISTENBRAINZ,
                    payloadJson = payloadJson,
                    startedAtSecs = startedAtSecs
                )
            )
        }
        if (prefs.lastFmSessionKey.isNotBlank() && prefs.lastFmApiKey.isNotBlank() && prefs.lastFmApiSecret.isNotBlank()) {
            localDao.insertPendingScrobble(
                PendingScrobbleEntity(
                    service = ScrobbleServices.LASTFM,
                    payloadJson = payloadJson,
                    startedAtSecs = startedAtSecs
                )
            )
        }
        flushPendingScrobbles()
    }

    suspend fun flushPendingScrobbles(nowMs: Long = System.currentTimeMillis()) {
        val localDao = dao ?: return
        val repo = prefsRepo ?: return

        flushMutex.withLock {
            val prefs = repo.prefsFlow.first()
            if (!prefs.scrobblingEnabled) return@withLock

            val due = localDao.getDuePendingScrobbles(nowMs)
            if (due.isEmpty()) return@withLock

            val nowSecs = nowMs / 1000L
            var batchError: String? = null
            var anySucceeded = false
            for (row in due) {
                val trackPayload = runCatching {
                    json.decodeFromString<QueuedScrobbleTrack>(row.payloadJson)
                }.getOrNull()
                if (trackPayload == null) {
                    localDao.deletePendingScrobble(row.id)
                    continue
                }

                // Last.fm rejects scrobbles older than 14 days
                if (row.service == ScrobbleServices.LASTFM &&
                    (nowSecs - row.startedAtSecs) > MAX_LASTFM_SCROBBLE_AGE_SECS
                ) {
                    localDao.deletePendingScrobble(row.id)
                    continue
                }

                val song = SongItem(
                    videoId = "scrobble",
                    title = trackPayload.title,
                    artists = trackPayload.artists,
                    album = trackPayload.album
                )

                val result = when (row.service) {
                    ScrobbleServices.LISTENBRAINZ -> {
                        if (prefs.listenBrainzToken.isBlank()) {
                            ScrobbleSubmitResult.Failure(false, reason = "ListenBrainz token cleared")
                        } else {
                            submitListenBrainz(song, prefs.listenBrainzToken, "single", trackPayload.startedAtSecs)
                        }
                    }
                    ScrobbleServices.LASTFM -> {
                        if (prefs.lastFmSessionKey.isBlank() || prefs.lastFmApiKey.isBlank() || prefs.lastFmApiSecret.isBlank()) {
                            ScrobbleSubmitResult.Failure(false, reason = "Last.fm credentials missing")
                        } else {
                            submitLastFm(
                                method = "track.scrobble",
                                song = song,
                                apiKey = prefs.lastFmApiKey,
                                secret = prefs.lastFmApiSecret,
                                sessionKey = prefs.lastFmSessionKey,
                                timestampSecs = trackPayload.startedAtSecs
                            )
                        }
                    }
                    else -> ScrobbleSubmitResult.Failure(false, reason = "Unknown scrobble service")
                }

                when (result) {
                    is ScrobbleSubmitResult.Success -> {
                        localDao.deletePendingScrobble(row.id)
                        anySucceeded = true
                    }
                    is ScrobbleSubmitResult.Failure -> {
                        batchError = result.reason
                        val nextAttempt = row.attempts + 1
                        if (!result.retryable || nextAttempt >= MAX_ATTEMPTS) {
                            localDao.deletePendingScrobble(row.id)
                        } else {
                            val delayMs = computeBackoffMs(nextAttempt)
                            localDao.updatePendingScrobbleRetry(
                                id = row.id,
                                attempts = nextAttempt,
                                nextAttemptAt = nowMs + delayMs,
                                lastError = result.reason
                            )
                        }
                    }
                }
            }
            if (batchError != null) {
                repo.setLastScrobbleError(batchError)
            } else if (anySucceeded) {
                repo.setLastScrobbleError(null)
            }
        }
    }

    private suspend fun submitScrobbleDirect(song: SongItem, startedAtSecs: Long, prefs: VelunaUserPrefs) {
        if (prefs.listenBrainzToken.isNotBlank()) {
            submitListenBrainz(song, prefs.listenBrainzToken, "single", startedAtSecs)
        }
        if (prefs.lastFmSessionKey.isNotBlank() && prefs.lastFmApiKey.isNotBlank() && prefs.lastFmApiSecret.isNotBlank()) {
            submitLastFm(
                method = "track.scrobble",
                song = song,
                apiKey = prefs.lastFmApiKey,
                secret = prefs.lastFmApiSecret,
                sessionKey = prefs.lastFmSessionKey,
                timestampSecs = startedAtSecs
            )
        }
    }

    suspend fun submitListenBrainz(
        song: SongItem,
        token: String,
        listenType: String,
        listenedAt: Long? = null
    ): ScrobbleSubmitResult = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("listen_type", listenType)
                put("payload", buildJsonArray {
                    add(buildJsonObject {
                        listenedAt?.let { put("listened_at", it) }
                        putJsonObject("track_metadata") {
                            put("artist_name", song.artists)
                            put("track_name", song.title)
                            song.album?.let { put("release_name", it) }
                        }
                    })
                })
            }
            val req = Request.Builder()
                .url("https://api.listenbrainz.org/1/submit-listens")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Authorization", "Token $token")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                parseListenBrainzResponse(
                    resp.code,
                    resp.body?.readUtf8AtMost(MAX_SCROBBLE_RESPONSE_BYTES).orEmpty()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScrobbleSubmitResult.Failure(
                retryable = true,
                reason = SecurityValidators.safeUserErrorMessage(e, "Network error")
            )
        } catch (_: Exception) {
            ScrobbleSubmitResult.Failure(retryable = false, reason = "ListenBrainz request failed")
        }
    }

    suspend fun submitLastFm(
        method: String,
        song: SongItem,
        apiKey: String,
        secret: String,
        sessionKey: String,
        timestampSecs: Long?
    ): ScrobbleSubmitResult = withContext(Dispatchers.IO) {
        try {
            val params = sortedMapOf(
                "method" to method,
                "api_key" to apiKey,
                "sk" to sessionKey,
                "artist" to song.artists,
                "track" to song.title
            )
            song.album?.let { params["album"] = it }
            timestampSecs?.let { params["timestamp"] = it.toString() }

            val sigBase = params.entries.joinToString("") { "${it.key}${it.value}" } + secret
            val sig = md5Hex(sigBase)

            val form = FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
                add("api_sig", sig)
                add("format", "json")
            }.build()

            val req = Request.Builder()
                .url("https://ws.audioscrobbler.com/2.0/")
                .post(form)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                parseLastFmResponse(
                    resp.code,
                    resp.body?.readUtf8AtMost(MAX_SCROBBLE_RESPONSE_BYTES).orEmpty()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ScrobbleSubmitResult.Failure(
                retryable = true,
                reason = SecurityValidators.safeUserErrorMessage(e, "Network error")
            )
        } catch (_: Exception) {
            ScrobbleSubmitResult.Failure(retryable = false, reason = "Last.fm request failed")
        }
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_ATTEMPTS = 8
        const val MAX_LASTFM_SCROBBLE_AGE_SECS = 14L * 24L * 3600L // 1,209,600s (Last.fm 14-day window)

        private val parserJson = Json { ignoreUnknownKeys = true }

        /**
         * Last.fm track scrobble eligibility rule:
         * Track duration must be strictly > 30s, and heard seconds must reach
         * at least half the duration or 240 seconds (4 minutes), whichever comes first.
         */
        fun shouldScrobbleTrack(durationSecs: Long, heardSecs: Long): Boolean {
            if (durationSecs <= 30L) return false
            return (heardSecs * 2L >= durationSecs) || heardSecs >= 240L
        }

        fun computeBackoffMs(attempt: Int): Long {
            val safe = attempt.coerceIn(1, 8)
            val secs = when (safe) {
                1 -> 15L
                2 -> 60L
                3 -> 180L
                4 -> 600L
                5 -> 1800L
                6 -> 3600L
                7 -> 7200L
                else -> 14400L
            }
            return secs * 1000L
        }

        fun parseLastFmResponse(httpCode: Int, body: String): ScrobbleSubmitResult {
            val rootObj = runCatching { parserJson.parseToJsonElement(body).jsonObject }.getOrNull()
            val errorCode = rootObj?.get("error")?.jsonPrimitive?.intOrNull
            val message = rootObj?.get("message")?.jsonPrimitive?.content ?: "HTTP $httpCode"

            if (errorCode != null) {
                return when (errorCode) {
                    8, 11, 16, 29 -> ScrobbleSubmitResult.Failure(
                        retryable = true,
                        code = errorCode,
                        reason = "Last.fm temporary error ($errorCode): $message"
                    )
                    9, 14, 26 -> ScrobbleSubmitResult.Failure(
                        retryable = false,
                        code = errorCode,
                        reason = "Last.fm auth error ($errorCode): $message"
                    )
                    else -> ScrobbleSubmitResult.Failure(
                        retryable = false,
                        code = errorCode,
                        reason = "Last.fm error ($errorCode): $message"
                    )
                }
            }

            if (httpCode in 200..299) {
                return ScrobbleSubmitResult.Success
            }

            val retryable = httpCode >= 500 || httpCode == 429
            return ScrobbleSubmitResult.Failure(
                retryable = retryable,
                code = httpCode,
                reason = "Last.fm $message"
            )
        }

        fun parseListenBrainzResponse(httpCode: Int, body: String): ScrobbleSubmitResult {
            if (httpCode in 200..299) return ScrobbleSubmitResult.Success
            val msg = runCatching {
                parserJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: "HTTP $httpCode"
            val retryable = httpCode >= 500 || httpCode == 429
            return ScrobbleSubmitResult.Failure(
                retryable = retryable,
                code = httpCode,
                reason = "ListenBrainz error ($httpCode): $msg"
            )
        }
    }
}

private const val MAX_SCROBBLE_RESPONSE_BYTES = 1024L * 1024L
