package com.example.data.network

import com.example.model.AlbumPage
import com.example.model.ArtistPage
import com.example.model.AudioQuality
import com.example.model.CommentsPage
import com.example.model.HomePage
import com.example.model.MoodSection
import com.example.model.PlaylistPage
import com.example.model.ResolvedStream
import com.example.model.SearchResults
import com.example.model.SearchSuggestions
import com.example.model.SongItem
import com.example.util.SecurityValidators
import com.example.util.readUtf8AtMost
import com.example.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ClientIdentity(
    val key: String,
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null
)

private data class CachedStreams(
    val streams: List<ResolvedStream>,
    val cachedAtMs: Long
)

internal suspend fun <T> resolveMirrorFallbackIfNeeded(
    primary: List<T>,
    allowMirrors: Boolean,
    resolveMirrors: suspend () -> List<T>
): Result<List<T>> {
    if (primary.isNotEmpty()) return Result.success(primary)
    if (!allowMirrors) return Result.failure(IllegalStateException("Unable to resolve audio stream"))
    return try {
        resolveMirrors().takeIf { it.isNotEmpty() }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Unable to resolve audio stream"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

class InnerTubeClient(
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val fastHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var cachedVisitorData: String? = null
    private val visitorMutex = Mutex()

    private val streamCache = object : LinkedHashMap<String, CachedStreams>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStreams>?): Boolean = size > 48
    }
    private val cacheLock = Any()

    private val webRemix = ClientIdentity(
        key = "WEB_REMIX",
        clientName = "WEB_REMIX",
        clientVersion = "1.20260213.01.00",
        clientId = "67",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    )

    private val iosMusic = ClientIdentity(
        key = "IOS_MUSIC",
        clientName = "IOS_MUSIC",
        clientVersion = "7.01.05",
        clientId = "26",
        userAgent = "com.google.ios.youtubemusic/7.01.05 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
        osName = "iOS",
        osVersion = "18.2.22C152",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2"
    )

    private val androidVr = ClientIdentity(
        key = "ANDROID_VR",
        clientName = "ANDROID_VR",
        clientVersion = "1.65.10",
        clientId = "28",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        osName = "Android",
        osVersion = "12L",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = "32"
    )

    private val visionOs = ClientIdentity(
        key = "VISIONOS",
        clientName = "VISIONOS",
        clientVersion = "0.1",
        clientId = "101",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
        osName = "visionOS",
        osVersion = "1.3.21O771",
        deviceMake = "Apple",
        deviceModel = "RealityDevice14,1"
    )

    private val androidCreator = ClientIdentity(
        key = "ANDROID_CREATOR",
        clientName = "ANDROID_CREATOR",
        clientVersion = "24.40.100",
        clientId = "14",
        userAgent = "com.google.android.apps.youtube.creator/24.40.100 (Linux; U; Android 14) gzip",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = "34"
    )

    fun invalidateStreamCache(videoId: String) {
        synchronized(cacheLock) {
            streamCache.keys.removeAll { it.startsWith("$videoId|") }
        }
    }

    private suspend fun ensureVisitorData(fastWaitOnly: Boolean = false): String? = withContext(Dispatchers.IO) {
        cachedVisitorData?.let { return@withContext it }
        val timeoutMs = if (fastWaitOnly) 900L else 2500L
        withTimeoutOrNull(timeoutMs) {
            visitorMutex.withLock {
                cachedVisitorData?.let { return@withLock it }
                suspendRunCatching {
                    val req = Request.Builder()
                        .url("https://music.youtube.com/sw.js_data")
                        .header("User-Agent", webRemix.userAgent)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                    fastHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val raw = resp.body?.readUtf8AtMost(MAX_VISITOR_RESPONSE_BYTES)?.removePrefix(")]}'") ?: return@use null
                        val match = Regex("""Cgt[A-Za-z0-9_\-]{10,}""").find(raw)?.value
                        if (match != null) cachedVisitorData = match
                        match
                    }
                }.getOrNull()
            }
        }
    }

    private fun buildContext(client: ClientIdentity, visitorData: String?): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("hl", "en")
            put("gl", "US")
            if (visitorData != null) put("visitorData", visitorData)
            client.osName?.let { put("osName", it) }
            client.osVersion?.let { put("osVersion", it) }
            client.deviceMake?.let { put("deviceMake", it) }
            client.deviceModel?.let { put("deviceModel", it) }
            client.androidSdkVersion?.let { put("androidSdkVersion", it) }
        }
    }

    private suspend fun post(
        endpoint: String,
        client: ClientIdentity,
        extraFields: Map<String, JsonElement>,
        useFastClient: Boolean = false
    ): JsonElement = withContext(Dispatchers.IO) {
        val visitor = cachedVisitorData ?: ensureVisitorData(fastWaitOnly = useFastClient)
        val bodyObj = buildJsonObject {
            put("context", buildContext(client, visitor))
            extraFields.forEach { (k, v) -> put(k, v) }
        }
        val bodyStr = json.encodeToString(JsonObject.serializer(), bodyObj)
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(bodyStr.toRequestBody(jsonMediaType))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply {
                if (visitor != null) header("X-Goog-Visitor-Id", visitor)
            }
            .build()

        val activeClient = if (useFastClient) fastHttpClient else httpClient
        activeClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.readUtf8AtMost(MAX_INNERTUBE_RESPONSE_BYTES).orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("InnerTube $endpoint HTTP ${resp.code}")
            }
            json.parseToJsonElement(raw)
        }
    }

    suspend fun getHome(params: String? = null): Result<HomePage> = suspendRunCatching {
        val fields = buildMap<String, JsonElement> {
            put("browseId", JsonPrimitive("FEmusic_home"))
            if (!params.isNullOrBlank()) put("params", JsonPrimitive(params))
        }
        val root = post("browse", webRemix, fields)
        InnerTubeParsers.parseHome(root)
    }

    suspend fun getMoodsAndGenres(): Result<List<MoodSection>> = suspendRunCatching {
        val root = post("browse", webRemix, mapOf("browseId" to JsonPrimitive("FEmusic_moods_and_genres")))
        InnerTubeParsers.parseMoods(root)
    }

    suspend fun getMoodCategory(params: String): Result<HomePage> = suspendRunCatching {
        val root = post(
            "browse",
            webRemix,
            mapOf(
                "browseId" to JsonPrimitive("FEmusic_moods_and_genres_category"),
                "params" to JsonPrimitive(params)
            )
        )
        InnerTubeParsers.parseHome(root)
    }

    suspend fun searchAll(query: String): Result<SearchResults> = suspendRunCatching {
        val root = post("search", webRemix, mapOf("query" to JsonPrimitive(query)))
        InnerTubeParsers.parseSearchAll(root)
    }

    suspend fun searchSongs(query: String): Result<List<SongItem>> = suspendRunCatching {
        val root = post(
            "search",
            webRemix,
            mapOf(
                "query" to JsonPrimitive(query),
                "params" to JsonPrimitive("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            )
        )
        InnerTubeParsers.parseSearchSongs(root)
    }

    suspend fun searchVideos(query: String): Result<List<SongItem>> = suspendRunCatching {
        val root = post(
            "search",
            webRemix,
            mapOf(
                "query" to JsonPrimitive(query),
                "params" to JsonPrimitive("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            )
        )
        InnerTubeParsers.parseSearchSongs(root)
    }

    suspend fun searchSuggestions(input: String): Result<SearchSuggestions> = suspendRunCatching {
        val root = post(
            "music/get_search_suggestions",
            webRemix,
            mapOf("input" to JsonPrimitive(input))
        )
        InnerTubeParsers.parseSearchSuggestions(root)
    }

    suspend fun getAlbum(browseId: String): Result<AlbumPage> = suspendRunCatching {
        require(SecurityValidators.isValidPlaylistId(browseId)) { "Invalid album identifier" }
        val root = post("browse", webRemix, mapOf("browseId" to JsonPrimitive(browseId)))
        InnerTubeParsers.parseAlbum(browseId, root)
    }

    suspend fun getArtist(channelId: String): Result<ArtistPage> = suspendRunCatching {
        require(SecurityValidators.isValidPlaylistId(channelId)) { "Invalid artist identifier" }
        val root = post("browse", webRemix, mapOf("browseId" to JsonPrimitive(channelId)))
        InnerTubeParsers.parseArtist(channelId, root)
    }

    suspend fun getPlaylist(browseId: String): Result<PlaylistPage> = suspendRunCatching {
        require(SecurityValidators.isValidPlaylistId(browseId)) { "Invalid playlist identifier" }
        val id = if (browseId.startsWith("PL") || browseId.startsWith("RD") || browseId.startsWith("OLAK")) {
            "VL$browseId"
        } else {
            browseId
        }
        val root = post("browse", webRemix, mapOf("browseId" to JsonPrimitive(id)))
        InnerTubeParsers.parsePlaylist(browseId, root)
    }

    suspend fun getNext(
        videoId: String?,
        playlistId: String? = null,
        params: String? = null
    ): Result<List<SongItem>> = suspendRunCatching {
        val safeVideoId = videoId?.takeIf { SecurityValidators.isValidYouTubeId(it) }
        val safePlaylistId = playlistId?.takeIf { SecurityValidators.isValidPlaylistId(it) }
        val effectivePlaylistId = safePlaylistId ?: safeVideoId?.let { "RDAMVM$it" }
        val effectiveParams = params ?: if (effectivePlaylistId?.startsWith("RD") == true) "wAEB" else null
        val fields = buildMap<String, JsonElement> {
            safeVideoId?.let { put("videoId", JsonPrimitive(it)) }
            effectivePlaylistId?.let { put("playlistId", JsonPrimitive(it)) }
            effectiveParams?.let { put("params", JsonPrimitive(it)) }
            put("isAudioOnly", JsonPrimitive(true))
            put("enablePersistentPlaylistPanel", JsonPrimitive(true))
            put("tunerSettingValue", JsonPrimitive("AUTOMIX_SETTING_NORMAL"))
        }
        val root = post("next", webRemix, fields, useFastClient = true)
        val parsed = InnerTubeParsers.parseNext(root).first
        if (parsed.size > 1 || safeVideoId == null) {
            return@suspendRunCatching parsed
        }
        // Fallback try generic RD mix playlist when RDAMVM is thin
        if (effectivePlaylistId != "RD$safeVideoId") {
            val altFields = mapOf(
                "videoId" to JsonPrimitive(safeVideoId),
                "playlistId" to JsonPrimitive("RD$safeVideoId"),
                "isAudioOnly" to JsonPrimitive(true),
                "enablePersistentPlaylistPanel" to JsonPrimitive(true)
            )
            val altRoot = suspendRunCatching { post("next", webRemix, altFields, useFastClient = true) }.getOrNull()
            val altSongs = altRoot?.let { InnerTubeParsers.parseNext(it).first }.orEmpty()
            if (altSongs.size > parsed.size) return@suspendRunCatching altSongs
        }
        parsed
    }

    suspend fun getYouTubeLyrics(videoId: String): Result<Pair<List<com.example.model.LyricLine>, String?>> = suspendRunCatching {
        require(SecurityValidators.isValidYouTubeId(videoId)) { "Invalid video identifier" }
        val nextRoot = post(
            "next",
            webRemix,
            mapOf("videoId" to JsonPrimitive(videoId), "isAudioOnly" to JsonPrimitive(true)),
            useFastClient = true
        )
        val (_, lyricsBrowseId) = InnerTubeParsers.parseNext(nextRoot)
        if (lyricsBrowseId == null) return@suspendRunCatching emptyList<com.example.model.LyricLine>() to null

        val timedRoot = post("browse", iosMusic, mapOf("browseId" to JsonPrimitive(lyricsBrowseId)), useFastClient = true)
        val timed = InnerTubeParsers.parseTimedLyrics(timedRoot)
        if (timed.isNotEmpty()) return@suspendRunCatching timed to null

        val plainRoot = post("browse", webRemix, mapOf("browseId" to JsonPrimitive(lyricsBrowseId)), useFastClient = true)
        val plain = InnerTubeParsers.parsePlainLyrics(plainRoot)
        emptyList<com.example.model.LyricLine>() to plain
    }

    suspend fun getComments(videoId: String): Result<CommentsPage> = suspendRunCatching {
        require(SecurityValidators.isValidYouTubeId(videoId)) { "Invalid video identifier" }
        val tabsRoot = post(
            "next",
            webRemix,
            mapOf("videoId" to JsonPrimitive(videoId), "isAudioOnly" to JsonPrimitive(true))
        )
        val token = InnerTubeParsers.parseCommentsToken(tabsRoot)
            ?: return@suspendRunCatching CommentsPage(disabled = true)
        val pageRoot = post("next", webRemix, mapOf("continuation" to JsonPrimitive(token)))
        InnerTubeParsers.parseCommentsPage(pageRoot)
    }

    suspend fun resolveStreamUrl(
        videoId: String,
        quality: AudioQuality,
        allowMirrorFallbacks: Boolean = false
    ): Result<ResolvedStream> = suspendRunCatching {
        require(SecurityValidators.isValidYouTubeId(videoId)) { "Invalid video identifier" }
        val candidates = resolveStreamCandidates(videoId, quality, allowMirrorFallbacks).getOrElse { emptyList() }
        val chosen = candidates.firstOrNull { !it.isHls } ?: candidates.firstOrNull()
        chosen ?: if (allowMirrorFallbacks) {
            resolveMirrorStreamCandidates(videoId, quality).firstOrNull()
                ?: throw IllegalStateException("Unable to resolve audio stream")
        } else {
            throw IllegalStateException("Unable to resolve audio stream")
        }
    }

    suspend fun resolveStreamCandidates(
        videoId: String,
        quality: AudioQuality,
        allowMirrorFallbacks: Boolean = false
    ): Result<List<ResolvedStream>> =
        withContext(Dispatchers.IO) {
            if (!SecurityValidators.isValidYouTubeId(videoId)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid video identifier"))
            }
            val cacheKey = streamCacheKey(videoId, quality, allowMirrorFallbacks)
            val now = System.currentTimeMillis()
            synchronized(cacheLock) {
                streamCache[cacheKey]?.takeIf { now - it.cachedAtMs < STREAM_CACHE_TTL_MS }?.let { cached ->
                    return@withContext Result.success(cached.streams)
                }
            }

            suspendRunCatching {
                val clients = listOf(androidVr, iosMusic, androidCreator, visionOs)
                val out = mutableListOf<ResolvedStream>()
                val seenUrls = hashSetOf<String>()
                var lastError: Throwable? = null

                coroutineScope {
                    val channel = Channel<Pair<Boolean, Result<List<ResolvedStream>>>>(Channel.UNLIMITED)
                    val jobs = clients.map { client ->
                        async {
                            val res = suspendRunCatching { fetchClientStreams(videoId, quality, client) }
                            channel.trySend(false to res)
                        }
                    }
                    var remaining = jobs.size
                    var gotFirstSuccess = false
                    while (remaining > 0) {
                        val next = if (!gotFirstSuccess) {
                            channel.receiveCatching().getOrNull()
                        } else {
                            // Briefly allow sibling in-flight responses for backup candidates, max 160ms
                            withTimeoutOrNull(160L) { channel.receiveCatching().getOrNull() }
                        } ?: break

                        remaining--
                        val (_, res) = next
                        res.onSuccess { list ->
                            list.forEach { item ->
                                if (seenUrls.add(item.url)) out.add(item)
                            }
                            if (list.isNotEmpty()) gotFirstSuccess = true
                        }.onFailure { err ->
                            lastError = err
                        }
                        if (gotFirstSuccess && out.size >= 4) break
                    }
                    jobs.forEach { it.cancel() }
                    channel.close()
                }

                // Mirrors are a last resort. Only query them when every primary client returned nothing.
                resolveMirrorFallbackIfNeeded(out.toList(), allowMirrorFallbacks) {
                    resolveMirrorStreamCandidates(videoId, quality)
                }.onSuccess { list ->
                    list.forEach { if (seenUrls.add(it.url)) out.add(it) }
                }.onFailure { error -> lastError = error }

                if (out.isEmpty()) {
                    throw (lastError ?: IllegalStateException("Unable to resolve audio stream"))
                }

                val sorted = out.sortedBy { if (it.isHls) 1 else 0 }
                synchronized(cacheLock) {
                    streamCache[cacheKey] = CachedStreams(sorted, System.currentTimeMillis())
                }
                sorted
            }
        }

    private suspend fun fetchClientStreams(
        videoId: String,
        quality: AudioQuality,
        client: ClientIdentity
    ): List<ResolvedStream> {
        val root = post(
            "player",
            client,
            mapOf(
                "videoId" to JsonPrimitive(videoId),
                "contentCheckOk" to JsonPrimitive(true),
                "racyCheckOk" to JsonPrimitive(true)
            ),
            useFastClient = true
        )
        val obj = root.jsonObject
        val status = obj["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        if (status != "OK") return emptyList()
        val streamingData = obj["streamingData"]?.jsonObject ?: return emptyList()
        val adaptive = streamingData["adaptiveFormats"]?.jsonArray.orEmpty()
        val formats = streamingData["formats"]?.jsonArray.orEmpty()

        val loudnessDb = obj["playerConfig"]?.jsonObject
            ?.get("audioConfig")?.jsonObject
            ?.get("loudnessDb")?.jsonPrimitive?.doubleOrNull

        val musicVideoType = obj["videoDetails"]?.jsonObject
            ?.get("musicVideoType")?.jsonPrimitive?.contentOrNull
        val isVideo = musicVideoType != null &&
            musicVideoType != "MUSIC_VIDEO_TYPE_ATV" &&
            musicVideoType != "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        val audioCandidates = (adaptive + formats).mapNotNull { el ->
            val f = el.jsonObject
            val url = f["url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { SecurityValidators.isSafeHttpsUrl(it, MAX_STREAM_URL_LENGTH) }
                ?: return@mapNotNull null
            val mime = f["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!mime.startsWith("audio/")) return@mapNotNull null
            val bitrate = f["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: f["bitrate"]?.jsonPrimitive?.intOrNull ?: 128000
            Triple(url, mime, bitrate)
        }

        val out = mutableListOf<ResolvedStream>()
        val chosen = selectFormatForQuality(audioCandidates, quality)
        if (chosen != null) {
            out.add(
                ResolvedStream(
                    videoId = videoId,
                    url = chosen.first,
                    clientName = client.key,
                    userAgent = client.userAgent,
                    mimeType = chosen.second,
                    bitrateKbps = chosen.third / 1000,
                    loudnessDb = loudnessDb,
                    isVideo = isVideo,
                    isHls = false
                )
            )
        }

        val backupProgressive = audioCandidates
            .sortedByDescending { it.third }
            .firstOrNull { it.first != chosen?.first }
        if (backupProgressive != null) {
            out.add(
                ResolvedStream(
                    videoId = videoId,
                    url = backupProgressive.first,
                    clientName = "${client.key} (Alt)",
                    userAgent = client.userAgent,
                    mimeType = backupProgressive.second,
                    bitrateKbps = backupProgressive.third / 1000,
                    loudnessDb = loudnessDb,
                    isVideo = isVideo,
                    isHls = false
                )
            )
        }

        val hlsUrl = streamingData["hlsManifestUrl"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { SecurityValidators.isSafeHttpsUrl(it, MAX_STREAM_URL_LENGTH) }
        if (!hlsUrl.isNullOrBlank()) {
            out.add(
                ResolvedStream(
                    videoId = videoId,
                    url = hlsUrl,
                    clientName = "${client.key} HLS",
                    userAgent = client.userAgent,
                    mimeType = "application/x-mpegURL",
                    bitrateKbps = quality.targetBitrateKbps,
                    loudnessDb = loudnessDb,
                    isVideo = isVideo,
                    isHls = true
                )
            )
        }
        return out
    }

    suspend fun resolveMirrorStreamCandidates(videoId: String, quality: AudioQuality): List<ResolvedStream> =
        withContext(Dispatchers.IO) {
            if (!SecurityValidators.isValidYouTubeId(videoId)) return@withContext emptyList()
            val mirrorUa = "Veluna-Android/1.0"
            val pipedHosts = listOf(
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.adminforge.de",
                "https://api.piped.private.coffee"
            )
            val invidiousHosts = listOf(
                "https://inv.nadeko.net",
                "https://yewtu.be"
            )

            val collected = mutableListOf<ResolvedStream>()
            val seen = hashSetOf<String>()

            coroutineScope {
                val channel = Channel<List<ResolvedStream>>(Channel.UNLIMITED)
                val pipedJobs = pipedHosts.map { host ->
                    async {
                        val list = fetchPipedMirror(host, videoId, quality, mirrorUa)
                        channel.trySend(list)
                    }
                }
                val invJobs = invidiousHosts.map { host ->
                    async {
                        val list = fetchInvidiousMirror(host, videoId, quality, mirrorUa)
                        channel.trySend(list)
                    }
                }
                val allJobs = pipedJobs + invJobs
                var remaining = allJobs.size
                var gotFirst = false

                while (remaining > 0) {
                    val item = if (!gotFirst) {
                        withTimeoutOrNull(5500L) { channel.receiveCatching().getOrNull() }
                    } else {
                        withTimeoutOrNull(180L) { channel.receiveCatching().getOrNull() }
                    } ?: break
                    remaining--
                    if (item.isNotEmpty()) {
                        gotFirst = true
                        item.forEach { s ->
                            if (seen.add(s.url)) collected.add(s)
                        }
                    }
                    if (gotFirst && collected.size >= 2) break
                }

                allJobs.forEach { it.cancel() }
                channel.close()
            }

            if (collected.isNotEmpty()) {
                val cacheKey = streamCacheKey(videoId, quality, allowMirrorFallbacks = true)
                synchronized(cacheLock) {
                    streamCache[cacheKey] = CachedStreams(collected, System.currentTimeMillis())
                }
            }
            collected
        }

    private fun isAllowedMirrorStreamUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return raw.length <= MAX_STREAM_URL_LENGTH &&
            url.isHttps && SecurityValidators.isKnownPlaybackStreamHost(url.host)
    }

    private fun fetchPipedMirror(
        host: String,
        videoId: String,
        quality: AudioQuality,
        mirrorUa: String
    ): List<ResolvedStream> {
        if (!SecurityValidators.isValidYouTubeId(videoId)) return emptyList()
        val requestUrl = host.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("streams")
            ?.addPathSegment(videoId)
            ?.build()
            ?: return emptyList()
        val mirrors = mutableListOf<ResolvedStream>()
        runCatching {
            val req = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", mirrorUa)
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val root = json.parseToJsonElement(
                    resp.body?.readUtf8AtMost(MAX_MIRROR_RESPONSE_BYTES).orEmpty()
                ).jsonObject
                val streams = root["audioStreams"]?.jsonArray.orEmpty()
                val parsed = streams.mapNotNull { el ->
                    val o = el.jsonObject
                    val u = o["url"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(::isAllowedMirrorStreamUrl)
                        ?: return@mapNotNull null
                    val mime = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: "audio/mp4"
                    val bps = o["bitrate"]?.jsonPrimitive?.intOrNull ?: 128000
                    Triple(u, mime, bps)
                }
                val best = selectFormatForQuality(parsed, quality)
                if (best != null) {
                    mirrors.add(
                        ResolvedStream(
                            videoId = videoId,
                            url = best.first,
                            clientName = "Piped Mirror",
                            userAgent = mirrorUa,
                            mimeType = best.second,
                            bitrateKbps = best.third / 1000,
                            isHls = false
                        )
                    )
                }
                val hls = root["hls"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(::isAllowedMirrorStreamUrl)
                if (!hls.isNullOrBlank()) {
                    mirrors.add(
                        ResolvedStream(
                            videoId = videoId,
                            url = hls,
                            clientName = "Piped HLS",
                            userAgent = mirrorUa,
                            mimeType = "application/x-mpegURL",
                            bitrateKbps = quality.targetBitrateKbps,
                            isHls = true
                        )
                    )
                }
            }
        }
        return mirrors
    }

    private fun fetchInvidiousMirror(
        host: String,
        videoId: String,
        quality: AudioQuality,
        mirrorUa: String
    ): List<ResolvedStream> {
        if (!SecurityValidators.isValidYouTubeId(videoId)) return emptyList()
        val requestUrl = host.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/v1/videos")
            ?.addPathSegment(videoId)
            ?.build()
            ?: return emptyList()
        val mirrors = mutableListOf<ResolvedStream>()
        runCatching {
            val req = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", mirrorUa)
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val root = json.parseToJsonElement(
                    resp.body?.readUtf8AtMost(MAX_MIRROR_RESPONSE_BYTES).orEmpty()
                ).jsonObject
                val adaptive = root["adaptiveFormats"]?.jsonArray.orEmpty()
                val parsed = adaptive.mapNotNull { el ->
                    val o = el.jsonObject
                    val u = o["url"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(::isAllowedMirrorStreamUrl)
                        ?: return@mapNotNull null
                    val type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (!type.startsWith("audio/")) return@mapNotNull null
                    val bps = o["bitrate"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        ?: o["bitrate"]?.jsonPrimitive?.intOrNull ?: 128000
                    Triple(u, type.substringBefore(';'), bps)
                }
                val best = selectFormatForQuality(parsed, quality)
                if (best != null) {
                    mirrors.add(
                        ResolvedStream(
                            videoId = videoId,
                            url = best.first,
                            clientName = "Invidious Mirror",
                            userAgent = mirrorUa,
                            mimeType = best.second,
                            bitrateKbps = best.third / 1000,
                            isHls = false
                        )
                    )
                }
            }
        }
        return mirrors
    }

    private fun selectFormatForQuality(
        candidates: List<Triple<String, String, Int>>,
        quality: AudioQuality
    ): Triple<String, String, Int>? {
        if (candidates.isEmpty()) return null
        val targetBps = quality.targetBitrateKbps * 1000
        return when (quality) {
            AudioQuality.HIGH -> candidates.maxByOrNull { it.third }
            AudioQuality.LOW -> candidates.minByOrNull { it.third }
            AudioQuality.MEDIUM -> candidates.minByOrNull { abs(it.third - targetBps) }
        }
    }

    private fun streamCacheKey(videoId: String, quality: AudioQuality, allowMirrorFallbacks: Boolean) =
        "$videoId|${quality.name}|${if (allowMirrorFallbacks) "mirrors" else "primary"}"

    companion object {
        private const val MAX_VISITOR_RESPONSE_BYTES = 1024L * 1024L
        private const val MAX_INNERTUBE_RESPONSE_BYTES = 24L * 1024L * 1024L
        private const val MAX_MIRROR_RESPONSE_BYTES = 8L * 1024L * 1024L
        private const val MAX_STREAM_URL_LENGTH = 8192

        private const val STREAM_CACHE_TTL_MS = 20L * 60L * 1000L

        fun fallbackDiscoveryHome(): HomePage = HomePage(
            chips = listOf(
                com.example.model.HomeChip("Energize", ""),
                com.example.model.HomeChip("Focus", ""),
                com.example.model.HomeChip("Relax", ""),
                com.example.model.HomeChip("Workout", ""),
                com.example.model.HomeChip("Feel good", ""),
                com.example.model.HomeChip("Commute", "")
            ),
            sections = emptyList()
        )
    }
}
