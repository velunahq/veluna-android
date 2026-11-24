package com.example.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class Rating {
    LIKE,
    DISLIKE,
    INDIFFERENT
}

@Serializable
enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

@Serializable
enum class AudioQuality(val label: String, val targetBitrateKbps: Int) {
    HIGH("High (256-320 kbps)", 320),
    MEDIUM("Medium (~128 kbps)", 128),
    LOW("Low (~64 kbps)", 64)
}

@Serializable
enum class VelunaThemePreset(val id: String, val label: String) {
    DEFAULT("default", "Veluna Warm"),
    CATPPUCCIN("catppuccin", "Catppuccin"),
    TOKYO_NIGHT("tokyonight", "Tokyo Night"),
    CAFFEINE("caffeine", "Caffeine"),
    NEON("neon", "Neon"),
    BREEZE("breeze", "Breeze"),
    AMOLED("amoled", "AMOLED Black")
}

@Serializable
enum class ThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

@Immutable
@Serializable
data class ArtistRun(
    val text: String,
    val id: String? = null
)

@Immutable
@Serializable
data class SongItem(
    val videoId: String,
    val title: String,
    val artists: String,
    val artistId: String? = null,
    val artistRuns: List<ArtistRun> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val duration: String? = null,
    val playCount: String? = null,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val isVideo: Boolean = false,
    val isLocal: Boolean = false,
    val localUri: String? = null,
    val rating: Rating = Rating.INDIFFERENT,
    val addedAt: Long? = null,
    val queued: Boolean = false,
    val queuedEnd: Boolean = false,
    val queuedFrom: String? = null,
    val autoplay: Boolean = false
) {
    val durationSeconds: Long
        get() {
            val parts = duration?.split(":")?.mapNotNull { it.trim().toLongOrNull() } ?: return 0L
            return when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0L
            }
        }
}

@Serializable
enum class BrowseKind {
    SONG,
    PLAYLIST,
    ALBUM,
    ARTIST
}

@Immutable
@Serializable
data class BrowseItem(
    val kind: BrowseKind,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val albumId: String? = null,
    val artistRuns: List<ArtistRun> = emptyList(),
    val playCount: String? = null,
    val explicit: Boolean = false,
    val isVideo: Boolean = false
) {
    fun toSongItem(): SongItem = SongItem(
        videoId = id,
        title = title,
        artists = subtitle.orEmpty(),
        artistId = artistRuns.firstOrNull { it.id != null }?.id,
        artistRuns = artistRuns,
        albumId = albumId,
        duration = duration,
        playCount = playCount,
        thumbnail = thumbnail,
        explicit = explicit,
        isVideo = isVideo,
        isLocal = id.startsWith(LOCAL_SONG_PREFIX),
        localUri = if (id.startsWith(LOCAL_SONG_PREFIX)) id.removePrefix(LOCAL_SONG_PREFIX) else null
    )
}

const val ON_REPEAT_ID = "VELUNA_ON_REPEAT"
const val LIKED_MUSIC_ID = "VELUNA_LIKED_MUSIC"
const val LOCAL_SONG_PREFIX = "LOCAL:"
const val LOCAL_ALBUM_PREFIX = "LOCALALBUM:"
const val LOCAL_ARTIST_PREFIX = "LOCALARTIST:"
const val LOCAL_PLAYLIST_PREFIX = "LOCALPLAYLIST:"

fun isLocalPlaylistId(id: String?): Boolean = id != null && id.startsWith(LOCAL_PLAYLIST_PREFIX)
fun isLocalId(id: String?): Boolean = id != null && (
    id.startsWith(LOCAL_SONG_PREFIX) ||
        id.startsWith(LOCAL_ALBUM_PREFIX) ||
        id.startsWith(LOCAL_ARTIST_PREFIX) ||
        id.startsWith(LOCAL_PLAYLIST_PREFIX)
    )

@Immutable
data class HomeChip(
    val title: String,
    val params: String
)

@Immutable
data class HomeSection(
    val title: String,
    val kind: String? = null,
    val items: List<BrowseItem>,
    val moreBrowseId: String? = null,
    val moreParams: String? = null
)

@Immutable
data class HomePage(
    val chips: List<HomeChip> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val continuation: String? = null
)

@Immutable
data class MoodTile(
    val title: String,
    val params: String,
    val colorHex: String
)

@Immutable
data class MoodSection(
    val title: String,
    val items: List<MoodTile>
)

@Immutable
data class SearchResults(
    val top: List<BrowseItem> = emptyList(),
    val songs: List<BrowseItem> = emptyList(),
    val videos: List<BrowseItem> = emptyList(),
    val albums: List<BrowseItem> = emptyList(),
    val artists: List<BrowseItem> = emptyList(),
    val playlists: List<BrowseItem> = emptyList()
)

@Immutable
data class SearchSuggestions(
    val queries: List<String> = emptyList(),
    val items: List<BrowseItem> = emptyList()
)

@Immutable
data class ArtistCarousel(
    val title: String,
    val items: List<BrowseItem>,
    val moreBrowseId: String? = null,
    val moreParams: String? = null
)

@Immutable
data class AlbumPage(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val artistRuns: List<ArtistRun> = emptyList(),
    val artistThumbnail: String? = null,
    val subtitle: String? = null,
    val secondSubtitle: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val items: List<SongItem> = emptyList(),
    val explicit: Boolean = false,
    val playlistId: String? = null,
    val sections: List<ArtistCarousel> = emptyList()
)

@Immutable
data class ArtistPage(
    val channelId: String,
    val name: String,
    val thumbnail: String? = null,
    val avatar: String? = null,
    val description: String? = null,
    val subscribers: String? = null,
    val monthlyListeners: String? = null,
    val radioPlaylistId: String? = null,
    val topSongs: List<SongItem> = emptyList(),
    val topSongsId: String? = null,
    val sections: List<ArtistCarousel> = emptyList()
)

@Immutable
data class PlaylistPage(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val coverUri: String? = null,
    val items: List<SongItem> = emptyList(),
    val owned: Boolean = false,
    val isLocal: Boolean = false
)

@Immutable
data class CommentItem(
    val id: String,
    val author: String,
    val authorAvatar: String? = null,
    val publishedTime: String,
    val text: String,
    val likeCount: String? = null,
    val replyCount: String? = null,
    val isPinned: Boolean = false,
    val isHearted: Boolean = false
)

@Immutable
data class CommentsPage(
    val countText: String? = null,
    val comments: List<CommentItem> = emptyList(),
    val disabled: Boolean = false
)

@Immutable
@Serializable
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

@Immutable
@Serializable
data class LyricLine(
    val timeMs: Long? = null,
    val endTimeMs: Long? = null,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val romanized: String? = null
)

@Immutable
@Serializable
data class LyricsData(
    val source: String,
    val providerId: String,
    val synced: Boolean,
    val instrumental: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    val pinned: Boolean = false,
    val offsetMs: Long = 0L,
    val validatedDurationMs: Long = 0L
)

@Immutable
data class LyricsProviderInfo(
    val id: String,
    val name: String,
    val enabled: Boolean
)

@Immutable
@Serializable
data class TasteSeed(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val updatedAtMs: Long = 0L
)

@Serializable
data class ArtistSignal(
    val id: String? = null,
    val name: String,
    val score: Float = 0f,
    val plays: Int = 0,
    val skips: Int = 0,
    val lastUpdatedMs: Long = 0L
)

@Serializable
data class TrackSignal(
    val videoId: String,
    val title: String,
    val artist: String,
    val score: Float = 0f,
    val completions: Int = 0,
    val skips: Int = 0,
    val lastUpdatedMs: Long = 0L
)

@Immutable
@Serializable
data class TasteProfile(
    val onboardingCompleted: Boolean = false,
    val selectedGenres: List<String> = emptyList(),
    val seedArtists: List<TasteSeed> = emptyList(),
    val seedSongs: List<TasteSeed> = emptyList(),
    val artistSignals: Map<String, ArtistSignal> = emptyMap(),
    val genreWeights: Map<String, Float> = emptyMap(),
    val trackSignals: Map<String, TrackSignal> = emptyMap()
)

enum class StatsRange(val label: String, val days: Int?) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    YEAR("12 months", 365),
    ALL("All time", null)
}

@Immutable
data class DailyListening(
    val dateKey: String,
    val label: String,
    val secs: Long
)

@Immutable
data class TopSongStat(
    val song: SongItem,
    val plays: Int,
    val secs: Long
)

@Immutable
data class TopArtistStat(
    val name: String,
    val id: String?,
    val thumbnail: String?,
    val plays: Int,
    val secs: Long
)

@Immutable
data class TopAlbumStat(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val plays: Int,
    val secs: Long
)

@Immutable
data class StatsSummary(
    val range: StatsRange,
    val totalSecs: Long = 0L,
    val prevSecs: Long? = null,
    val totalPlays: Int = 0,
    val uniqueSongs: Int = 0,
    val uniqueArtists: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val dailySeries: List<DailyListening> = emptyList(),
    val hourlyHeatmap: List<Long> = List(7 * 24) { 0L },
    val topSongs: List<TopSongStat> = emptyList(),
    val topArtists: List<TopArtistStat> = emptyList(),
    val topAlbums: List<TopAlbumStat> = emptyList()
)

@Immutable
data class HistoryGroup(
    val title: String,
    val items: List<Pair<Long, SongItem>>
)

@Immutable
data class DownloadProgress(
    val videoId: String,
    val title: String,
    val artists: String,
    val thumbnail: String?,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: DownloadStatus,
    val error: String? = null
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class ResolvedStream(
    val videoId: String,
    val url: String,
    val clientName: String,
    val userAgent: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val loudnessDb: Double? = null,
    val isVideo: Boolean = false,
    val isHls: Boolean = false
)

object EqualizerPresets {
    val bandsHz = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val bandLabels = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

    val presets: Map<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 1f, 2f, 2f),
        "Rock" to listOf(4f, 3f, 2f, -1f, -2f, 0f, 2f, 4f, 5f, 5f),
        "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, 1f, 3f, 4f),
        "Electronic" to listOf(5f, 4f, 2f, 0f, -2f, 1f, 2f, 4f, 5f, 6f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 4f, 4f),
        "Vocal" to listOf(-2f, -2f, 0f, 2f, 4f, 5f, 4f, 2f, 0f, -1f)
    )
}
