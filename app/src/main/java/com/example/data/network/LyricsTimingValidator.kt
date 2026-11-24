package com.example.data.network

import com.example.model.LyricLine
import com.example.model.LyricsData
import com.example.model.SongItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

object LyricsTimingValidator {

    fun normalizeTitleOrArtist(raw: String): String =
        raw.lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun pickBestLrcLibCandidate(arr: JsonArray, song: SongItem): JsonElement? {
        val targetTitle = normalizeTitleOrArtist(song.title)
        val primaryArtist = normalizeTitleOrArtist(song.artists.split(",").firstOrNull().orEmpty())
        val targetDurSec = song.durationSeconds

        var bestEl: JsonElement? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val trackName = normalizeTitleOrArtist(obj["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val artistName = normalizeTitleOrArtist(obj["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val candDur = obj["duration"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
            val hasSynced = !obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            val hasPlain = !obj["plainLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            val isInst = obj["instrumental"]?.jsonPrimitive?.contentOrNull == "true"
            if (!hasSynced && !hasPlain && !isInst) continue

            if (targetDurSec > 20L && candDur > 20L && abs(candDur - targetDurSec) > 22L) {
                // Duration too far off; likely a cover, remix, or live cut.
                continue
            }

            var score = 0.0
            if (trackName == targetTitle) score += 50.0
            else if (targetTitle.isNotBlank() && (trackName.contains(targetTitle) || targetTitle.contains(trackName))) score += 28.0

            if (primaryArtist.isNotBlank()) {
                if (artistName == primaryArtist) score += 35.0
                else if (artistName.contains(primaryArtist) || primaryArtist.contains(artistName)) score += 20.0
            }

            if (targetDurSec > 0L && candDur > 0L) {
                val diff = abs(candDur - targetDurSec)
                score += (20.0 - diff * 1.5).coerceAtLeast(-25.0)
            }
            if (hasSynced) score += 12.0

            if (score > bestScore) {
                bestScore = score
                bestEl = obj
            }
        }
        return bestEl
    }

    fun validateAndNormalize(data: LyricsData, trackDurationMs: Long): LyricsData {
        if (!data.synced || data.instrumental || data.lines.isEmpty()) {
            return data.copy(validatedDurationMs = trackDurationMs)
        }

        val timedLines = data.lines.mapNotNull { line -> line.timeMs?.let { it to line } }
        val minRequiredLines = if (trackDurationMs in 1L..45_000L) 2 else 3
        if (timedLines.size < minRequiredLines) {
            return downgradeToPlain(data, trackDurationMs)
        }

        val coverageRatio = timedLines.size.toDouble() / data.lines.size.toDouble()
        if (coverageRatio < 0.60) {
            return downgradeToPlain(data, trackDurationMs)
        }

        val times = timedLines.map { it.first }
        if (times.any { it < 0L }) {
            return downgradeToPlain(data, trackDurationMs)
        }

        val distinctCount = times.toHashSet().size
        if (distinctCount < (times.size * 0.50).toInt().coerceAtLeast(2)) {
            return downgradeToPlain(data, trackDurationMs)
        }

        // Monotonicity & duplicate spacing checks
        var regressions = 0
        var duplicateGaps = 0
        for (i in 1 until times.size) {
            val dt = times[i] - times[i - 1]
            if (dt < -500L) return downgradeToPlain(data, trackDurationMs)
            if (dt < 0L) regressions++
            if (dt == 0L) duplicateGaps++
        }
        if (regressions > 1 || duplicateGaps > (times.size * 0.45)) {
            return downgradeToPlain(data, trackDurationMs)
        }

        // Duration coherence checks when song length is known
        if (trackDurationMs >= 30_000L) {
            val firstMs = times.first()
            val lastMs = times.last()
            if (lastMs > trackDurationMs + 18_000L) {
                return downgradeToPlain(data, trackDurationMs)
            }
            val outOfBoundsCount = times.count { it > trackDurationMs + 5_000L }
            if (outOfBoundsCount > (times.size * 0.20)) {
                return downgradeToPlain(data, trackDurationMs)
            }
            if (trackDurationMs >= 75_000L && times.size >= 8) {
                if (lastMs < (trackDurationMs * 0.35).toLong()) {
                    return downgradeToPlain(data, trackDurationMs)
                }
                if (firstMs > (trackDurationMs * 0.60).toLong()) {
                    return downgradeToPlain(data, trackDurationMs)
                }
            }
        }

        // Clean per-line word timestamps while preserving validated line sync
        val cleanedLines = data.lines.mapIndexed { idx, line ->
            val nextTime = data.lines.getOrNull(idx + 1)?.timeMs
            sanitizeLineWords(line, nextTime)
        }
        return data.copy(
            synced = true,
            lines = cleanedLines,
            validatedDurationMs = trackDurationMs
        )
    }

    fun downgradeToPlain(data: LyricsData, trackDurationMs: Long): LyricsData =
        data.copy(
            synced = false,
            offsetMs = 0L,
            validatedDurationMs = trackDurationMs,
            lines = data.lines.map { line ->
                line.copy(timeMs = null, endTimeMs = null, words = emptyList())
            }
        )

    private fun sanitizeLineWords(line: LyricLine, nextLineTimeMs: Long?): LyricLine {
        val lineStart = line.timeMs ?: return line.copy(words = emptyList())
        if (line.words.isEmpty()) return line
        val lineEndLimit = line.endTimeMs ?: nextLineTimeMs ?: (lineStart + 25_000L)
        var prevStart = -1L
        val valid = line.words.all { w ->
            val ok = w.startMs >= (lineStart - 1500L) &&
                w.endMs >= w.startMs &&
                w.startMs >= prevStart &&
                w.endMs <= (lineEndLimit + 3500L)
            prevStart = w.startMs
            ok
        }
        return if (valid) line else line.copy(words = emptyList())
    }
}
