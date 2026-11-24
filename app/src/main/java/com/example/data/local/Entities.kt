package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "listens",
    indices = [Index(value = ["playedAt"])]
)
data class ListenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val playedAt: Long,
    val secs: Long
)

@Entity(tableName = "listen_songs")
data class ListenSongEntity(
    @PrimaryKey val videoId: String,
    val songJson: String
)

fun newCloudPlaylistKey(): String = "pl_${java.util.UUID.randomUUID()}"

@Entity(
    tableName = "local_playlists",
    indices = [Index(value = ["cloudKey"], unique = true)]
)
data class LocalPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cloudKey: String = newCloudPlaylistKey(),
    val title: String,
    val description: String = "",
    val coverUri: String? = null,
    val remoteCoverUri: String? = null,
    val coverRemoved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncRevision: Long = 0L
) {
    init {
        require(cloudKey.isNotBlank()) { "LocalPlaylistEntity.cloudKey must not be blank" }
    }
}

@Entity(
    tableName = "local_playlist_tracks",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["videoId"])
    ]
)
data class LocalPlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val videoId: String,
    val songJson: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val videoId: String,
    val songJson: String,
    val likedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_albums")
data class SavedAlbumEntity(
    @PrimaryKey val albumId: String,
    val title: String,
    val subtitle: String?,
    val thumbnail: String?,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "followed_artists")
data class FollowedArtistEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val thumbnail: String?,
    val subscribers: String?,
    val followedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "shortcuts")
data class ShortcutEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val subtitle: String?,
    val thumbnail: String?,
    val position: Int
)

@Entity(
    tableName = "local_tracks",
    indices = [Index(value = ["albumKey"])]
)
data class LocalTrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumKey: String,
    val trackNo: Int = 0,
    val durationSecs: Long = 0L,
    val coverUri: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloaded_tracks")
data class DownloadedTrackEntity(
    @PrimaryKey val videoId: String,
    val filePath: String,
    val format: String,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    val songJson: String,
    val downloadedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "blocked_artists")
data class BlockedArtistEntity(
    @PrimaryKey val name: String,
    val channelId: String? = null,
    val blockedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val videoId: String,
    val lyricsJson: String,
    val pinned: Boolean = false,
    val offsetMs: Long = 0L,
    val fetchedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["entityType", "entityKey"]
)
data class SyncTombstoneEntity(
    val entityType: String,
    val entityKey: String,
    val clientUpdatedAt: Long,
    val deletedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sync_baselines",
    primaryKeys = ["userId", "entityType", "entityKey"]
)
data class SyncBaselineEntity(
    val userId: String,
    val entityType: String,
    val entityKey: String,
    val revision: Long = 0L,
    val signature: String = "",
    val clientUpdatedAt: Long = 0L,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val snapshotJson: String = ""
)

@Entity(
    tableName = "pending_scrobbles",
    indices = [Index(value = ["nextAttemptAt"])]
)
data class PendingScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val service: String,
    val payloadJson: String,
    val startedAtSecs: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null
)

@Entity(tableName = "local_device_clock")
data class LocalDeviceClockEntity(
    @PrimaryKey val id: Int = 1,
    val deviceId: String,
    val lastSeq: Long = 0L
)

@Entity(
    tableName = "playlist_track_ops",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistTrackOpEntity(
    val playlistId: Long,
    val videoId: String,
    val deleted: Boolean,
    val timestampMs: Long,
    val opSeq: Long,
    val deviceId: String,
    val opId: String
)

@Entity(
    tableName = "playlist_field_ops",
    primaryKeys = ["playlistId", "fieldName"],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistFieldOpEntity(
    val playlistId: Long,
    val fieldName: String,
    val timestampMs: Long,
    val opSeq: Long,
    val deviceId: String,
    val opId: String
)

object PlaylistFieldNames {
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val COVER = "cover"
    const val ORDER = "order"
}

object SyncEntityTypes {
    const val LIKED = "LIKED"
    const val PLAYLIST = "PLAYLIST"
    const val HIDDEN_ARTIST = "HIDDEN_ARTIST"
    const val SEED = "SEED"
    const val PROFILE_PREFS = "PROFILE_PREFS"
}

object ScrobbleServices {
    const val LASTFM = "LASTFM"
    const val LISTENBRAINZ = "LISTENBRAINZ"
}
