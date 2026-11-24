package com.example.data.repository

import com.example.data.local.UserPrefsRepository
import com.example.data.local.VelunaDao
import com.example.data.network.InnerTubeClient
import com.example.model.ArtistSignal
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.HomePage
import com.example.model.HomeSection
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.model.TrackSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.exp

data class GenreOption(
    val id: String,
    val label: String,
    val queryHint: String
)

class TasteEngine(
    private val dao: VelunaDao,
    private val prefsRepo: UserPrefsRepository,
    private val innerTube: InnerTubeClient
) {
    companion object {
        val AVAILABLE_GENRES = listOf(
            GenreOption("pop", "Pop", "pop hits music"),
            GenreOption("hiphop", "Hip-Hop & Rap", "hip hop rap hits"),
            GenreOption("rnb", "R&B & Soul", "rnb soul music"),
            GenreOption("indie", "Indie & Alt", "indie alternative music"),
            GenreOption("electronic", "Electronic & EDM", "electronic dance music"),
            GenreOption("rock", "Rock", "modern rock music"),
            GenreOption("lofi", "Lo-Fi & Chill", "lofi chill beats"),
            GenreOption("jazz", "Jazz", "modern jazz music"),
            GenreOption("kpop", "K-Pop", "kpop hits"),
            GenreOption("latin", "Latin", "latin urban reggaeton"),
            GenreOption("afrobeats", "Afrobeats", "afrobeats hits"),
            GenreOption("desi", "Desi & Bollywood", "bollywood hindi hits"),
            GenreOption("metal", "Metal", "metal rock music"),
            GenreOption("classical", "Classical & OST", "cinematic soundtrack instrumental"),
            GenreOption("country", "Country & Folk", "country acoustic folk")
        )

        private const val HALF_LIFE_MS = 14.0 * 24.0 * 3600.0 * 1000.0
        private const val MAX_TRACKED_ARTISTS = 120
        private const val MAX_TRACKED_SONGS = 200
    }

    suspend fun applyOnboardingSeeds(
        genres: List<String>,
        artists: List<TasteSeed>,
        songs: List<TasteSeed>
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prefsRepo.updateTasteProfile(touchProfileTimestamp = true) { current ->
            val genreWeights = current.genreWeights.toMutableMap()
            genres.forEach { g ->
                genreWeights[g] = ((genreWeights[g] ?: 0f) + 5.0f).coerceAtMost(25f)
            }

            val artistSignals = current.artistSignals.toMutableMap()
            artists.forEach { a ->
                val key = normalizeKey(a.name)
                if (key.isNotBlank()) {
                    val prev = artistSignals[key]
                    artistSignals[key] = ArtistSignal(
                        id = a.id.takeIf { it.startsWith("UC") } ?: prev?.id,
                        name = a.name,
                        score = ((prev?.score ?: 0f) + 7.5f).coerceAtMost(35f),
                        plays = (prev?.plays ?: 0) + 1,
                        skips = prev?.skips ?: 0,
                        lastUpdatedMs = now
                    )
                }
            }

            val trackSignals = current.trackSignals.toMutableMap()
            songs.forEach { s ->
                val artistName = s.subtitle?.substringBefore("•")?.trim().orEmpty()
                if (artistName.isNotBlank()) {
                    val aKey = normalizeKey(artistName.substringBefore(","))
                    if (aKey.isNotBlank()) {
                        val prev = artistSignals[aKey]
                        artistSignals[aKey] = ArtistSignal(
                            id = prev?.id,
                            name = artistName.substringBefore(",").trim(),
                            score = ((prev?.score ?: 0f) + 5.0f).coerceAtMost(35f),
                            plays = (prev?.plays ?: 0) + 1,
                            skips = prev?.skips ?: 0,
                            lastUpdatedMs = now
                        )
                    }
                }
                val prevT = trackSignals[s.id]
                trackSignals[s.id] = TrackSignal(
                    videoId = s.id,
                    title = s.name,
                    artist = artistName,
                    score = ((prevT?.score ?: 0f) + 6.0f).coerceAtMost(30f),
                    completions = (prevT?.completions ?: 0) + 1,
                    skips = prevT?.skips ?: 0,
                    lastUpdatedMs = now
                )
            }

            val existingArtistsById = current.seedArtists.associateBy { it.id }
            val existingSongsById = current.seedSongs.associateBy { it.id }
            val stampedArtists = artists.distinctBy { it.id }.map { seed ->
                val ts = seed.updatedAtMs.takeIf { it > 0L }
                    ?: existingArtistsById[seed.id]?.updatedAtMs?.takeIf { it > 0L }
                    ?: now
                seed.copy(updatedAtMs = ts)
            }
            val stampedSongs = songs.distinctBy { it.id }.map { seed ->
                val ts = seed.updatedAtMs.takeIf { it > 0L }
                    ?: existingSongsById[seed.id]?.updatedAtMs?.takeIf { it > 0L }
                    ?: now
                seed.copy(updatedAtMs = ts)
            }

            current.copy(
                onboardingCompleted = true,
                selectedGenres = genres.distinct(),
                seedArtists = stampedArtists,
                seedSongs = stampedSongs,
                genreWeights = genreWeights,
                artistSignals = trimArtists(artistSignals),
                trackSignals = trimTracks(trackSignals)
            )
        }
    }

    suspend fun skipOnboarding() {
        prefsRepo.setOnboardingCompleted(true)
    }

    suspend fun recordPlaybackOutcome(
        song: SongItem,
        heardSecs: Long,
        durationSecs: Long
    ) = withContext(Dispatchers.IO) {
        if (heardSecs < 2L) return@withContext
        val effDur = durationSecs.coerceAtLeast(30L)
        val ratio = heardSecs.toFloat() / effDur.toFloat()

        val artistDelta: Float
        val trackDelta: Float
        val isCompletion: Boolean
        val isSkip: Boolean

        when {
            ratio >= 0.72f || heardSecs >= 110L -> {
                artistDelta = 2.8f
                trackDelta = 2.4f
                isCompletion = true
                isSkip = false
            }
            ratio >= 0.35f || heardSecs >= 45L -> {
                artistDelta = 1.1f
                trackDelta = 0.9f
                isCompletion = false
                isSkip = false
            }
            heardSecs in 2L..24L && ratio < 0.22f -> {
                artistDelta = -1.3f
                trackDelta = -3.2f
                isCompletion = false
                isSkip = true
            }
            else -> return@withContext
        }

        val now = System.currentTimeMillis()
        val primaryArtist = song.artists.split(",").firstOrNull()?.trim().orEmpty()
        prefsRepo.updateTasteProfile { current ->
            val artists = current.artistSignals.toMutableMap()
            if (primaryArtist.isNotBlank() && !primaryArtist.equals("Unknown Artist", ignoreCase = true)) {
                val key = normalizeKey(primaryArtist)
                val prev = artists[key]
                val decayed = decayedScore(prev?.score ?: 0f, prev?.lastUpdatedMs ?: now, now)
                artists[key] = ArtistSignal(
                    id = song.artistId ?: prev?.id,
                    name = primaryArtist,
                    score = (decayed + artistDelta).coerceIn(-15f, 40f),
                    plays = (prev?.plays ?: 0) + if (isCompletion) 1 else 0,
                    skips = (prev?.skips ?: 0) + if (isSkip) 1 else 0,
                    lastUpdatedMs = now
                )
            }

            val tracks = current.trackSignals.toMutableMap()
            val prevT = tracks[song.videoId]
            val decayedT = decayedScore(prevT?.score ?: 0f, prevT?.lastUpdatedMs ?: now, now)
            tracks[song.videoId] = TrackSignal(
                videoId = song.videoId,
                title = song.title,
                artist = song.artists,
                score = (decayedT + trackDelta).coerceIn(-20f, 40f),
                completions = (prevT?.completions ?: 0) + if (isCompletion) 1 else 0,
                skips = (prevT?.skips ?: 0) + if (isSkip) 1 else 0,
                lastUpdatedMs = now
            )

            current.copy(
                artistSignals = trimArtists(artists),
                trackSignals = trimTracks(tracks)
            )
        }
    }

    suspend fun recordLikeChange(song: SongItem, liked: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val primaryArtist = song.artists.split(",").firstOrNull()?.trim().orEmpty()
        val artistDelta = if (liked) 3.5f else -2.0f
        val trackDelta = if (liked) 5.5f else -3.0f

        prefsRepo.updateTasteProfile { current ->
            val artists = current.artistSignals.toMutableMap()
            if (primaryArtist.isNotBlank()) {
                val key = normalizeKey(primaryArtist)
                val prev = artists[key]
                val decayed = decayedScore(prev?.score ?: 0f, prev?.lastUpdatedMs ?: now, now)
                artists[key] = ArtistSignal(
                    id = song.artistId ?: prev?.id,
                    name = primaryArtist,
                    score = (decayed + artistDelta).coerceIn(-15f, 40f),
                    plays = prev?.plays ?: 0,
                    skips = prev?.skips ?: 0,
                    lastUpdatedMs = now
                )
            }
            val tracks = current.trackSignals.toMutableMap()
            val prevT = tracks[song.videoId]
            val decayedT = decayedScore(prevT?.score ?: 0f, prevT?.lastUpdatedMs ?: now, now)
            tracks[song.videoId] = TrackSignal(
                videoId = song.videoId,
                title = song.title,
                artist = song.artists,
                score = (decayedT + trackDelta).coerceIn(-20f, 40f),
                completions = prevT?.completions ?: 0,
                skips = prevT?.skips ?: 0,
                lastUpdatedMs = now
            )
            current.copy(
                artistSignals = trimArtists(artists),
                trackSignals = trimTracks(tracks)
            )
        }
    }

    suspend fun recordArtistFollow(name: String, channelId: String, followed: Boolean) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val key = normalizeKey(name)
        val delta = if (followed) 6.0f else -3.0f
        prefsRepo.updateTasteProfile { current ->
            val artists = current.artistSignals.toMutableMap()
            val prev = artists[key]
            val decayed = decayedScore(prev?.score ?: 0f, prev?.lastUpdatedMs ?: now, now)
            artists[key] = ArtistSignal(
                id = channelId,
                name = name,
                score = (decayed + delta).coerceIn(-15f, 40f),
                plays = prev?.plays ?: 0,
                skips = prev?.skips ?: 0,
                lastUpdatedMs = now
            )
            current.copy(artistSignals = trimArtists(artists))
        }
    }

    suspend fun personalizeHomePage(
        remotePage: HomePage,
        isFilteredChipOrMood: Boolean
    ): HomePage = withContext(Dispatchers.IO) {
        val profile = prefsRepo.tasteProfileFlow.first()
        val blocked = dao.getBlockedArtistsOnce().map { normalizeKey(it.name) }.toHashSet()
        val recentHistoryIds = dao.getListensSince(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
            .map { it.videoId }
            .toHashSet()

        // Always strip blocked artists & personalize Quick Picks ordering
        val cleanedRemoteSections = remotePage.sections.mapNotNull { sec ->
            val filtered = sec.items.filterNot { isItemBlocked(it, blocked) }
            if (filtered.isEmpty()) return@mapNotNull null
            val reordered = if (sec.title.contains("quick picks", ignoreCase = true) ||
                filtered.all { it.kind == BrowseKind.SONG }
            ) {
                diversifyItems(
                    items = filtered.sortedByDescending {
                        scoreBrowseItem(it, profile, recentHistoryIds, blocked)
                    }
                )
            } else {
                filtered
            }
            sec.copy(items = reordered)
        }

        if (isFilteredChipOrMood || !hasTasteSignals(profile)) {
            return@withContext remotePage.copy(sections = cleanedRemoteSections)
        }

        val personalizedSections = buildPersonalizedHomeShelves(profile, recentHistoryIds, blocked)
        val combinedSections = (personalizedSections + cleanedRemoteSections)
            .distinctBy { it.title.lowercase() }

        remotePage.copy(sections = combinedSections)
    }

    private suspend fun buildPersonalizedHomeShelves(
        profile: TasteProfile,
        recentHistoryIds: Set<String>,
        blocked: Set<String>
    ): List<HomeSection> = coroutineScope {
        val now = System.currentTimeMillis()
        val topArtists = profile.artistSignals.values
            .map { it to decayedScore(it.score, it.lastUpdatedMs, now) }
            .filter { it.second > 0.5f && normalizeKey(it.first.name) !in blocked }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)

        val topGenreIds = (profile.selectedGenres + profile.genreWeights.entries.sortedByDescending { it.value }.map { it.key })
            .distinct()
            .take(2)
        val seedSong = profile.seedSongs.firstOrNull()

        // Parallel personalized fetches (fast non-blocking fan-out)
        val seedRadioJob = async {
            if (seedSong != null) {
                innerTube.getNext(seedSong.id, playlistId = "RDAMVM${seedSong.id}").getOrNull().orEmpty()
            } else emptyList()
        }
        val artistTracksJobs = topArtists.take(2).map { artist ->
            async {
                val fromPage = artist.id?.takeIf { it.startsWith("UC") }?.let { cid ->
                    innerTube.getArtist(cid).getOrNull()?.topSongs.orEmpty()
                }.orEmpty()
                val songs = if (fromPage.isNotEmpty()) {
                    fromPage
                } else {
                    innerTube.searchSongs(artist.name).getOrNull().orEmpty()
                }
                artist to songs
            }
        }
        val genreJobs = topGenreIds.map { gid ->
            async {
                val opt = AVAILABLE_GENRES.find { it.id == gid }
                val label = opt?.label ?: gid
                val query = opt?.queryHint ?: "$gid music"
                label to innerTube.searchSongs(query).getOrNull().orEmpty()
            }
        }

        val seedRadioTracks = seedRadioJob.await()
        val artistBatches = artistTracksJobs.awaitAll()
        val genreBatches = genreJobs.awaitAll()

        val allArtistSongs = artistBatches.flatMap { it.second }
        val allGenreSongs = genreBatches.flatMap { it.second }
        val pool = (seedRadioTracks + allArtistSongs + allGenreSongs)
            .distinctBy { it.videoId }
            .filterNot { isSongBlocked(it, blocked) }

        val sections = mutableListOf<HomeSection>()

        // 1. "Picked For Your Taste" (Quick-play personalized list)
        val picked = diversifySongs(
            pool.sortedByDescending { scoreSong(it, profile, recentHistoryIds, blocked, preferNovelty = false) }
        ).take(8)
        if (picked.isNotEmpty()) {
            sections.add(
                HomeSection(
                    title = "Picked For Your Taste",
                    kind = "PERSONALIZED_SONGS",
                    items = picked.map { it.toBrowseItem() }
                )
            )
        }

        // 2. "Discover New Music" (Fresh tracks user hasn't heard/seeded yet)
        val seedSongIds = profile.seedSongs.map { it.id }.toHashSet()
        val discoveries = diversifySongs(
            pool.filter { it.videoId !in recentHistoryIds && it.videoId !in seedSongIds }
                .sortedByDescending { scoreSong(it, profile, recentHistoryIds, blocked, preferNovelty = true) }
        ).take(12)
        if (discoveries.isNotEmpty()) {
            sections.add(
                HomeSection(
                    title = "New Discoveries For You",
                    kind = "DISCOVERY_CAROUSEL",
                    items = discoveries.map { it.toBrowseItem() }
                )
            )
        }

        // 3. Top Artist Showcase / Genre Mix Shelf
        val leadArtist = artistBatches.firstOrNull { it.second.isNotEmpty() }
        if (leadArtist != null) {
            sections.add(
                HomeSection(
                    title = "Because You Like ${leadArtist.first.name}",
                    items = leadArtist.second.filterNot { isSongBlocked(it, blocked) }
                        .take(12)
                        .map { it.toBrowseItem() }
                )
            )
        } else {
            val leadGenre = genreBatches.firstOrNull { it.second.isNotEmpty() }
            if (leadGenre != null) {
                sections.add(
                    HomeSection(
                        title = "${leadGenre.first} Mix",
                        items = leadGenre.second.filterNot { isSongBlocked(it, blocked) }
                            .take(12)
                            .map { it.toBrowseItem() }
                    )
                )
            }
        }
        sections
    }

    suspend fun generateSmartAutoplayTracks(
        seed: SongItem,
        existingQueueIds: Set<String>,
        limit: Int = 15
    ): List<SongItem> = withContext(Dispatchers.IO) {
        val profile = prefsRepo.tasteProfileFlow.first()
        val blocked = dao.getBlockedArtistsOnce().map { normalizeKey(it.name) }.toHashSet()
        val recentHistoryIds = dao.getListensSince(System.currentTimeMillis() - 4L * 3600 * 1000)
            .map { it.videoId }
            .toHashSet()

        coroutineScope {
            val radioDeferred = async {
                innerTube.getNext(seed.videoId, playlistId = "RDAMVM${seed.videoId}").getOrNull().orEmpty()
            }
            val tasteQueryDeferred = async {
                val now = System.currentTimeMillis()
                val topArtist = profile.artistSignals.values
                    .filter { normalizeKey(it.name) !in blocked }
                    .maxByOrNull { decayedScore(it.score, it.lastUpdatedMs, now) }
                    ?.takeIf { decayedScore(it.score, it.lastUpdatedMs, now) > 1.5f }
                    ?.name
                val topGenre = profile.selectedGenres.firstOrNull()
                    ?.let { gid -> AVAILABLE_GENRES.find { it.id == gid }?.queryHint ?: gid }

                val query = when {
                    topArtist != null && !seed.artists.contains(topArtist, ignoreCase = true) -> "$topArtist similar songs"
                    topGenre != null -> topGenre
                    else -> "${seed.artists.substringBefore(',').trim()} mix"
                }
                innerTube.searchSongs(query).getOrNull().orEmpty()
            }

            val radioList = radioDeferred.await()
            val tasteList = tasteQueryDeferred.await()

            val combined = (radioList + tasteList)
                .distinctBy { it.videoId }
                .filter { it.videoId != seed.videoId && it.videoId !in existingQueueIds }
                .filterNot { isSongBlocked(it, blocked) }

            val scored = combined.sortedByDescending { song ->
                val base = scoreSong(song, profile, recentHistoryIds, blocked, preferNovelty = true)
                val radioBonus = if (radioList.any { it.videoId == song.videoId }) 3.0f else 0f
                base + radioBonus
            }

            diversifySongs(scored, initialLastArtist = seed.artists.substringBefore(",").trim())
                .take(limit)
                .map { it.copy(autoplay = true) }
        }
    }

    private fun scoreSong(
        song: SongItem,
        profile: TasteProfile,
        recentHistoryIds: Set<String>,
        blocked: Set<String>,
        preferNovelty: Boolean
    ): Float {
        if (isSongBlocked(song, blocked)) return Float.NEGATIVE_INFINITY
        val now = System.currentTimeMillis()
        var score = 0f

        // Artist affinity
        val artistTokens = song.artists.split(",").map { normalizeKey(it) }.filter { it.isNotBlank() }
        for (aKey in artistTokens) {
            profile.artistSignals[aKey]?.let { sig ->
                val d = decayedScore(sig.score, sig.lastUpdatedMs, now)
                score += d * 1.4f
                if (sig.skips > sig.plays + 1) score -= 3.5f
            }
            if (profile.seedArtists.any { normalizeKey(it.name) == aKey }) {
                score += 4.5f
            }
        }

        // Track history & skip signal
        profile.trackSignals[song.videoId]?.let { sig ->
            val d = decayedScore(sig.score, sig.lastUpdatedMs, now)
            score += if (preferNovelty && sig.completions > 0) d * 0.25f else d * 1.1f
            if (sig.skips > sig.completions) score -= 7.0f
        } ?: run {
            if (preferNovelty) score += 2.6f // Discovery boost for unheard track
        }

        // Genre keyword resonance
        val hay = "${song.title} ${song.artists} ${song.album.orEmpty()}".lowercase()
        profile.selectedGenres.forEach { gid ->
            val opt = AVAILABLE_GENRES.find { it.id == gid }
            val tokens = (opt?.queryHint ?: gid).lowercase().split(" ")
            if (tokens.any { tok -> tok.length >= 3 && tok in hay }) {
                score += 2.0f
            }
        }

        // Avoid immediate replay spam
        if (song.videoId in recentHistoryIds) {
            score -= 6.5f
        }
        return score
    }

    private fun scoreBrowseItem(
        item: BrowseItem,
        profile: TasteProfile,
        recentHistoryIds: Set<String>,
        blocked: Set<String>
    ): Float = scoreSong(item.toSongItem(), profile, recentHistoryIds, blocked, preferNovelty = false)

    private fun diversifySongs(
        sorted: List<SongItem>,
        initialLastArtist: String? = null
    ): List<SongItem> {
        if (sorted.size <= 2) return sorted
        val remaining = sorted.toMutableList()
        val result = mutableListOf<SongItem>()
        var prevArtist = initialLastArtist?.let { normalizeKey(it) }.orEmpty()

        while (remaining.isNotEmpty()) {
            val pickIdx = remaining.indexOfFirst {
                normalizeKey(it.artists.substringBefore(",")) != prevArtist
            }.takeIf { it >= 0 } ?: 0
            val chosen = remaining.removeAt(pickIdx)
            result.add(chosen)
            prevArtist = normalizeKey(chosen.artists.substringBefore(","))
        }
        return result
    }

    private fun diversifyItems(items: List<BrowseItem>): List<BrowseItem> {
        if (items.size <= 2) return items
        val remaining = items.toMutableList()
        val result = mutableListOf<BrowseItem>()
        var prevArtist = ""
        while (remaining.isNotEmpty()) {
            val pickIdx = remaining.indexOfFirst {
                normalizeKey(it.subtitle.orEmpty().substringBefore(",")) != prevArtist
            }.takeIf { it >= 0 } ?: 0
            val chosen = remaining.removeAt(pickIdx)
            result.add(chosen)
            prevArtist = normalizeKey(chosen.subtitle.orEmpty().substringBefore(","))
        }
        return result
    }

    private fun hasTasteSignals(profile: TasteProfile): Boolean =
        profile.selectedGenres.isNotEmpty() ||
            profile.seedArtists.isNotEmpty() ||
            profile.seedSongs.isNotEmpty() ||
            profile.artistSignals.isNotEmpty()

    private fun isSongBlocked(song: SongItem, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        return song.artists.split(",").any { normalizeKey(it) in blocked }
    }

    private fun isItemBlocked(item: BrowseItem, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        if (item.kind == BrowseKind.ARTIST && normalizeKey(item.title) in blocked) return true
        return item.subtitle.orEmpty().split(",").any { normalizeKey(it) in blocked }
    }

    private fun decayedScore(score: Float, lastUpdatedMs: Long, nowMs: Long): Float {
        if (lastUpdatedMs <= 0L) return score
        val dt = (nowMs - lastUpdatedMs).coerceAtLeast(0L).toDouble()
        val decay = exp(-0.693147 * (dt / HALF_LIFE_MS)).toFloat()
        return score * decay
    }

    private fun normalizeKey(raw: String): String =
        raw.lowercase().trim().replace(Regex("""\s+"""), " ")

    private fun trimArtists(map: MutableMap<String, ArtistSignal>): Map<String, ArtistSignal> {
        if (map.size <= MAX_TRACKED_ARTISTS) return map
        return map.entries.sortedByDescending { kotlin.math.abs(it.value.score) }
            .take(MAX_TRACKED_ARTISTS)
            .associate { it.key to it.value }
    }

    private fun trimTracks(map: MutableMap<String, TrackSignal>): Map<String, TrackSignal> {
        if (map.size <= MAX_TRACKED_SONGS) return map
        return map.entries.sortedByDescending { it.value.lastUpdatedMs }
            .take(MAX_TRACKED_SONGS)
            .associate { it.key to it.value }
    }

    private fun SongItem.toBrowseItem(): BrowseItem = BrowseItem(
        kind = BrowseKind.SONG,
        id = videoId,
        title = title,
        subtitle = artists,
        thumbnail = thumbnail,
        duration = duration,
        albumId = albumId,
        artistRuns = artistRuns,
        playCount = playCount,
        explicit = explicit,
        isVideo = isVideo
    )
}
