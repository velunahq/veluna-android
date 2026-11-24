package com.example.data.network

import com.example.data.local.LyricsCacheEntity
import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.model.LyricLine
import com.example.model.LyricWord
import com.example.model.LyricsData
import com.example.model.SongItem
import com.example.util.SecurityValidators
import com.example.util.readUtf8AtMost
import com.example.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LyricsRepository(
    private val dao: VelunaDao,
    private val innerTube: InnerTubeClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = innerTube.httpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun getLyrics(
        song: SongItem,
        providerOrderRaw: String,
        forceProviderId: String? = null,
        trackDurationMs: Long = song.durationSeconds * 1000L
    ): LyricsData? = withContext(Dispatchers.IO) {
        val effectiveDurationMs = trackDurationMs.takeIf { it > 0L } ?: (song.durationSeconds * 1000L)

        if (forceProviderId == null) {
            val cached = dao.getLyricsCache(song.videoId)
            if (cached != null) {
                val parsed = runCatching { json.decodeFromString<LyricsData>(cached.lyricsJson) }.getOrNull()
                if (parsed != null) {
                    val validated = LyricsTimingValidator.validateAndNormalize(
                        parsed.copy(offsetMs = cached.offsetMs, pinned = cached.pinned),
                        effectiveDurationMs
                    )
                    if (validated.synced != parsed.synced) {
                        dao.upsertLyricsCache(
                            cached.copy(
                                lyricsJson = json.encodeToString(validated),
                                offsetMs = validated.offsetMs
                            )
                        )
                    }
                    return@withContext enrichRomanization(validated)
                }
            }
        }

        val providers = if (forceProviderId != null) {
            listOf(forceProviderId)
        } else {
            UserPrefsRepository.parseProviderOrder(providerOrderRaw)
                .filter { it.enabled }
                .map { it.id }
        }

        var plainFallback: LyricsData? = null
        for (pid in providers) {
            val rawResult = suspendRunCatching { fetchFromProvider(pid, song) }.getOrNull() ?: continue
            val validated = LyricsTimingValidator.validateAndNormalize(rawResult, effectiveDurationMs)
            if (validated.synced && validated.lines.isNotEmpty()) {
                val enriched = enrichRomanization(validated)
                dao.upsertLyricsCache(
                    LyricsCacheEntity(
                        videoId = song.videoId,
                        lyricsJson = json.encodeToString(enriched),
                        pinned = forceProviderId != null,
                        offsetMs = 0L
                    )
                )
                return@withContext enriched
            } else if (plainFallback == null && (validated.lines.isNotEmpty() || validated.instrumental)) {
                plainFallback = validated
            }
        }

        if (plainFallback != null) {
            val enriched = enrichRomanization(plainFallback)
            dao.upsertLyricsCache(
                LyricsCacheEntity(
                    videoId = song.videoId,
                    lyricsJson = json.encodeToString(enriched),
                    pinned = forceProviderId != null,
                    offsetMs = 0L
                )
            )
            return@withContext enriched
        }
        null
    }

    suspend fun revalidateAgainstDuration(
        videoId: String,
        current: LyricsData,
        actualDurationMs: Long
    ): LyricsData = withContext(Dispatchers.IO) {
        if (!current.synced || actualDurationMs <= 0L) return@withContext current
        val validated = LyricsTimingValidator.validateAndNormalize(current, actualDurationMs)
        if (validated.synced != current.synced) {
            dao.getLyricsCache(videoId)?.let { cached ->
                dao.upsertLyricsCache(
                    cached.copy(
                        lyricsJson = json.encodeToString(validated),
                        offsetMs = 0L
                    )
                )
            }
        }
        validated
    }

    suspend fun updateOffset(videoId: String, offsetMs: Long): LyricsData? = withContext(Dispatchers.IO) {
        val cached = dao.getLyricsCache(videoId) ?: return@withContext null
        val parsed = runCatching { json.decodeFromString<LyricsData>(cached.lyricsJson) }.getOrNull() ?: return@withContext null
        if (!parsed.synced) return@withContext parsed
        val updated = parsed.copy(offsetMs = offsetMs, pinned = true)
        dao.upsertLyricsCache(
            cached.copy(
                lyricsJson = json.encodeToString(updated),
                pinned = true,
                offsetMs = offsetMs
            )
        )
        updated
    }

    suspend fun clearPinnedSource(
        song: SongItem,
        providerOrderRaw: String,
        trackDurationMs: Long = song.durationSeconds * 1000L
    ): LyricsData? = withContext(Dispatchers.IO) {
        dao.deleteLyricsCache(song.videoId)
        getLyrics(song, providerOrderRaw, forceProviderId = null, trackDurationMs = trackDurationMs)
    }

    private suspend fun fetchFromProvider(providerId: String, song: SongItem): LyricsData? {
        return when (providerId) {
            "lrclib" -> fetchLrcLib(song)
            "boidu" -> fetchBoidu(song)
            "lyricsplus" -> fetchLyricsPlus(song)
            "youtube" -> fetchYouTube(song)
            "simpmusic" -> fetchSimpMusic(song)
            else -> null
        }
    }

    private fun fetchLrcLib(song: SongItem): LyricsData? {
        val builder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", song.title)
            .addQueryParameter("artist_name", song.artists.split(",").firstOrNull()?.trim().orEmpty())
        song.album?.let { builder.addQueryParameter("album_name", it) }
        if (song.durationSeconds > 0) builder.addQueryParameter("duration", song.durationSeconds.toString())

        val direct = executeJson(builder.build().toString())
        parseLrcLibObject(direct)?.let { return it }

        val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${song.title} ${song.artists.split(",").firstOrNull().orEmpty()}")
            .build().toString()
        val arr = executeJson(searchUrl) as? JsonArray ?: return null
        val best = LyricsTimingValidator.pickBestLrcLibCandidate(arr, song) ?: return null
        return parseLrcLibObject(best)
    }

    private fun parseLrcLibObject(el: kotlinx.serialization.json.JsonElement?): LyricsData? {
        val obj = el as? JsonObject ?: return null
        val instrumental = obj["instrumental"]?.jsonPrimitive?.contentOrNull == "true"
        if (instrumental) {
            return LyricsData(source = "LRCLIB", providerId = "lrclib", synced = false, instrumental = true)
        }
        val syncedRaw = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull
        if (!syncedRaw.isNullOrBlank()) {
            val lines = parseLrcText(syncedRaw)
            if (lines.isNotEmpty()) {
                return LyricsData(source = "LRCLIB", providerId = "lrclib", synced = true, lines = lines)
            }
        }
        val plainRaw = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull
        if (!plainRaw.isNullOrBlank()) {
            val lines = plainRaw.lines().filter { it.isNotBlank() }.map { LyricLine(text = it.trim()) }
            return LyricsData(source = "LRCLIB", providerId = "lrclib", synced = false, lines = lines)
        }
        return null
    }

    private fun fetchBoidu(song: SongItem): LyricsData? {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", song.title)
            .addQueryParameter("a", song.artists.split(",").firstOrNull()?.trim().orEmpty())
            .apply {
                song.album?.let { addQueryParameter("al", it) }
                if (song.durationSeconds > 0) addQueryParameter("d", song.durationSeconds.toString())
            }
            .build().toString()
        val obj = executeJson(url) as? JsonObject ?: return null
        val ttml = obj["ttml"]?.jsonPrimitive?.contentOrNull ?: return null
        val lines = parseSimpleTtml(ttml)
        if (lines.isEmpty()) return null
        return LyricsData(
            source = "Boidu",
            providerId = "boidu",
            synced = lines.any { it.timeMs != null },
            lines = lines
        )
    }

    private fun fetchLyricsPlus(song: SongItem): LyricsData? {
        val url = "https://lyricsplus.binimum.org/v2/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("title", song.title)
            .addQueryParameter("artist", song.artists.split(",").firstOrNull()?.trim().orEmpty())
            .apply {
                song.album?.let { addQueryParameter("album", it) }
                if (song.durationSeconds > 0) addQueryParameter("duration", song.durationSeconds.toString())
            }
            .build().toString()
        val obj = executeJson(url) as? JsonObject ?: return null
        val arr = obj["lyrics"] as? JsonArray ?: return null
        val lines = arr.mapNotNull { el ->
            val lineObj = el as? JsonObject ?: return@mapNotNull null
            val time = lineObj["time"]?.jsonPrimitive?.longOrNull
            val duration = lineObj["duration"]?.jsonPrimitive?.longOrNull
            val text = lineObj["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val syllabus = (lineObj["syllabus"] as? JsonArray)?.mapNotNull { w ->
                val wo = w as? JsonObject ?: return@mapNotNull null
                val wt = wo["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ws = wo["time"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val wd = wo["duration"]?.jsonPrimitive?.longOrNull ?: 250L
                LyricWord(text = wt, startMs = ws, endMs = ws + wd)
            }.orEmpty()
            LyricLine(
                timeMs = time,
                endTimeMs = if (time != null && duration != null) time + duration else null,
                text = text,
                words = syllabus
            )
        }
        if (lines.isEmpty()) return null
        return LyricsData(
            source = "LyricsPlus",
            providerId = "lyricsplus",
            synced = lines.any { it.timeMs != null },
            lines = lines
        )
    }

    private suspend fun fetchYouTube(song: SongItem): LyricsData? {
        if (song.isLocal) return null
        val (timed, plain) = innerTube.getYouTubeLyrics(song.videoId).getOrNull() ?: return null
        if (timed.isNotEmpty()) {
            return LyricsData(source = "YouTube Music", providerId = "youtube", synced = true, lines = timed)
        }
        if (!plain.isNullOrBlank()) {
            val lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(text = it.trim()) }
            return LyricsData(source = "YouTube Music", providerId = "youtube", synced = false, lines = lines)
        }
        return null
    }

    private fun fetchSimpMusic(song: SongItem): LyricsData? {
        if (song.isLocal || !SecurityValidators.isValidYouTubeId(song.videoId)) return null
        val url = "https://api-lyrics.simpmusic.org/v1".toHttpUrl().newBuilder()
            .addPathSegment(song.videoId)
            .build()
            .toString()
        val obj = executeJson(url) as? JsonObject ?: return null
        val data = obj["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val synced = data["syncedLyrics"]?.jsonPrimitive?.contentOrNull
        if (!synced.isNullOrBlank()) {
            val lines = parseLrcText(synced)
            if (lines.isNotEmpty()) {
                return LyricsData(source = "SimpMusic", providerId = "simpmusic", synced = true, lines = lines)
            }
        }
        val plain = data["plainLyrics"]?.jsonPrimitive?.contentOrNull
        if (!plain.isNullOrBlank()) {
            val lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(text = it.trim()) }
            return LyricsData(source = "SimpMusic", providerId = "simpmusic", synced = false, lines = lines)
        }
        return null
    }

    private fun executeJson(url: String): kotlinx.serialization.json.JsonElement? {
        if (!SecurityValidators.isSafeHttpsUrl(url)) return null
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Veluna-Android/1.0")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.readUtf8AtMost(MAX_LYRICS_RESPONSE_BYTES)
                    ?.takeIf { it.isNotBlank() } ?: return@use null
                json.parseToJsonElement(body)
            }
        }.getOrNull()
    }

    private fun parseLrcText(lrc: String): List<LyricLine> {
        val tagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val matches = tagRegex.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = raw.replace(tagRegex, "").trim()
            if (text.isBlank()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = m.groupValues[3]
                val msPart = when (fracStr.length) {
                    0 -> 0L
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    else -> fracStr.take(3).toLongOrNull() ?: 0L
                }
                out.add(LyricLine(timeMs = min * 60_000L + sec * 1_000L + msPart, text = text))
            }
        }
        return out.sortedBy { it.timeMs ?: Long.MAX_VALUE }
    }

    private fun parseSimpleTtml(ttml: String): List<LyricLine> {
        val pRegex = Regex("""<p[^>]*begin="([^"]+)"[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val spanRegex = Regex("""<span[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</span>""")
        val out = mutableListOf<LyricLine>()
        for (pm in pRegex.findAll(ttml)) {
            val startMs = parseTtmlTime(pm.groupValues[1]) ?: continue
            val inner = pm.groupValues[2]
            val words = spanRegex.findAll(inner).mapNotNull { sm ->
                val ws = parseTtmlTime(sm.groupValues[1]) ?: return@mapNotNull null
                val we = parseTtmlTime(sm.groupValues[2]) ?: (ws + 200L)
                val wt = sm.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
                if (wt.isEmpty()) null else LyricWord(wt, ws, we)
            }.toList()
            val plain = inner.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (plain.isNotEmpty()) {
                out.add(LyricLine(timeMs = startMs, text = plain, words = words))
            }
        }
        return out.sortedBy { it.timeMs ?: Long.MAX_VALUE }
    }

    private fun parseTtmlTime(raw: String): Long? {
        val clean = raw.trim().removeSuffix("s")
        if (":" !in clean) {
            return clean.toDoubleOrNull()?.times(1000.0)?.toLong()
        }
        val parts = clean.split(":")
        return when (parts.size) {
            2 -> ((parts[0].toDoubleOrNull() ?: 0.0) * 60.0 + (parts[1].toDoubleOrNull() ?: 0.0)).times(1000.0).toLong()
            3 -> ((parts[0].toDoubleOrNull() ?: 0.0) * 3600.0 + (parts[1].toDoubleOrNull() ?: 0.0) * 60.0 + (parts[2].toDoubleOrNull() ?: 0.0)).times(1000.0).toLong()
            else -> null
        }
    }

    private fun enrichRomanization(data: LyricsData): LyricsData {
        val updated = data.lines.map { line ->
            if (line.romanized != null) line
            else {
                val rom = romanizeIfCjkOrKana(line.text)
                if (rom != null) line.copy(romanized = rom) else line
            }
        }
        return data.copy(lines = updated)
    }

    private fun romanizeIfCjkOrKana(text: String): String? {
        val hasKana = text.any { it in '\u3040'..'\u30FF' }
        val hasHangul = text.any { it in '\uAC00'..'\uD7AF' }
        return when {
            hasKana -> romanizeKana(text)
            hasHangul -> romanizeHangul(text)
            else -> null
        }
    }

    private fun romanizeKana(input: String): String {
        val table = mapOf(
            'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
            'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
            'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
            'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
            'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
            'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
            'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
            'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
            'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
            'わ' to "wa", 'を' to "wo", 'ん' to "n",
            'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
            'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
            'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
            'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
            'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po"
        )
        val sb = StringBuilder()
        for (ch in input) {
            val hira = if (ch in '\u30A1'..'\u30F6') (ch.code - 0x60).toChar() else ch
            sb.append(table[hira] ?: ch)
        }
        return sb.toString()
    }

    private fun romanizeHangul(input: String): String {
        val initials = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        val medials = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
        val finals = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "l", "l", "l", "l", "l", "l", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
        val sb = StringBuilder()
        for (ch in input) {
            if (ch in '\uAC00'..'\uD7A3') {
                val base = ch.code - 0xAC00
                val i = base / (21 * 28)
                val m = (base % (21 * 28)) / 28
                val f = base % 28
                sb.append(initials[i]).append(medials[m]).append(finals[f])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}

private const val MAX_LYRICS_RESPONSE_BYTES = 2L * 1024L * 1024L
