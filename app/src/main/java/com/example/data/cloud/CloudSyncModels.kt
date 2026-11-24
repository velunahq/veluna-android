package com.example.data.cloud

import com.example.model.LOCAL_SONG_PREFIX
import com.example.model.SongItem
import com.example.util.SecurityValidators
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.serialization.Serializable

@Serializable
data class SyncMutationStamp(
    val timestampMs: Long = 0L,
    val seq: Long = 0L,
    val deviceId: String = "",
    val opId: String = ""
) : Comparable<SyncMutationStamp> {
    val isPresent: Boolean
        get() = timestampMs > 0L || seq > 0L || deviceId.isNotEmpty() || opId.isNotEmpty()

    override fun compareTo(other: SyncMutationStamp): Int {
        if (timestampMs != other.timestampMs) return timestampMs.compareTo(other.timestampMs)
        if (seq != other.seq) return seq.compareTo(other.seq)
        val devCmp = deviceId.compareTo(other.deviceId)
        if (devCmp != 0) return devCmp
        return opId.compareTo(other.opId)
    }

    companion object {
        val EMPTY = SyncMutationStamp()
    }
}

@IgnoreExtraProperties
data class CloudMutationStamp(
    val timestampMs: Long = 0L,
    val seq: Long = 0L,
    val deviceId: String = "",
    val opId: String = ""
) {
    fun toSyncStamp(): SyncMutationStamp = SyncMutationStamp(
        timestampMs = timestampMs,
        seq = seq,
        deviceId = deviceId,
        opId = opId
    )
}

fun SyncMutationStamp.toFirestoreMap(): Map<String, Any> = mapOf(
    "timestampMs" to timestampMs.coerceAtLeast(0L),
    "seq" to seq.coerceAtLeast(0L),
    "deviceId" to deviceId.take(128),
    "opId" to opId.take(128)
)

@IgnoreExtraProperties
data class CloudUserProfile(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val displayName: String = "Listener",
    @Deprecated("not stored")
    val email: String? = null,
    val photoUrl: String? = null,
    val themePreset: String = "default",
    val themeMode: String = "DARK",
    val customAccentHex: String? = null,
    val adaptToArtwork: Boolean = true,
    val cornerRadiusDp: Long = 12L,
    val audioQuality: String = "HIGH",
    val autoplay: Boolean = true,
    val loudnessNormalization: Boolean = true,
    val hideMusicVideos: Boolean = false,
    val selectedGenres: List<String> = emptyList(),
    val onboardingCompleted: Boolean = false,
    val prefsUpdatedAt: Long = 0L,
    val tombstoneCompactedBeforeMs: Long = 0L,
    val syncGeneration: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudTasteSeed(
    @DocumentId val seedId: String = "",
    val kind: String = "ARTIST",
    val name: String = "",
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val deleted: Boolean = false,
    val clientUpdatedAt: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudLikedSong(
    @DocumentId val videoId: String = "",
    val title: String = "",
    val artists: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val duration: String? = null,
    val durationSecs: Long = 0L,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val likedAt: Long = 0L,
    val deleted: Boolean = false,
    val clientUpdatedAt: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toSongItem(): SongItem = SongItem(
        videoId = videoId,
        title = title,
        artists = artists,
        album = album,
        albumId = albumId,
        artistId = artistId,
        duration = duration,
        thumbnail = SecurityValidators.sanitizeHttpsUrl(thumbnail, 2048),
        explicit = explicit,
        rating = com.example.model.Rating.LIKE
    )
}

@IgnoreExtraProperties
data class CloudPlaylistTrack(
    @DocumentId val docId: String = "",
    val videoId: String = "",
    val title: String = "",
    val artists: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val duration: String? = null,
    val durationSecs: Long = 0L,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
    val position: Int = 0,
    val addedAt: Long = 0L,
    val opTs: Long = 0L,
    val opSeq: Long = 0L,
    val deviceId: String = "",
    val opId: String = "",
    val deleted: Boolean = false,
    val clientUpdatedAt: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toSongItem(): SongItem? {
        val vid = videoId.ifBlank { docId }.trim()
            .takeIf { it.isNotBlank() && !it.startsWith(LOCAL_SONG_PREFIX) }
            ?: return null
        if (title.isBlank()) return null
        return SongItem(
            videoId = vid,
            title = title,
            artists = artists,
            album = album,
            albumId = albumId,
            artistId = artistId,
            duration = duration,
            thumbnail = SecurityValidators.sanitizeHttpsUrl(thumbnail, 2048),
            explicit = explicit,
            addedAt = addedAt.takeIf { it > 0L }
        )
    }

    fun effectiveOpStamp(fallbackTs: Long, fallbackDeviceId: String = "cloud_legacy"): SyncMutationStamp {
        val resolvedTs = opTs.takeIf { it > 0L }
            ?: addedAt.takeIf { it > 0L }
            ?: clientUpdatedAt.takeIf { it > 0L }
            ?: fallbackTs
        return SyncMutationStamp(
            timestampMs = resolvedTs.coerceAtLeast(0L),
            seq = opSeq.coerceAtLeast(0L),
            deviceId = deviceId.ifBlank { fallbackDeviceId },
            opId = opId
        )
    }
}

@IgnoreExtraProperties
data class CloudPlaylistSnapshotMeta(
    @DocumentId val snapshotId: String = "",
    val state: String = "STAGING",
    val syncRevision: Long = 0L,
    val trackCount: Long = 0L,
    val clientUpdatedAt: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudPlaylist(
    @DocumentId val playlistKey: String = "",
    val title: String = "",
    val description: String = "",
    val coverUri: String? = null,
    val coverRemoved: Boolean = false,
    // Legacy array kept readable for one-time migration to subcollection tracks
    val tracks: List<Map<String, Any?>> = emptyList(),
    val deleted: Boolean = false,
    val syncRevision: Long = 0L,
    val activeSnapshotId: String? = null,
    val trackCount: Long = 0L,
    val lastDeviceId: String = "",
    val lastOpSeq: Long = 0L,
    val lastOpId: String = "",
    val fieldStamps: Map<String, CloudMutationStamp> = emptyMap(),
    val trackTombstones: Map<String, CloudMutationStamp> = emptyMap(),
    val clientUpdatedAt: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun playlistMutationStamp(): SyncMutationStamp = SyncMutationStamp(
        timestampMs = clientUpdatedAt.coerceAtLeast(0L),
        seq = lastOpSeq.coerceAtLeast(0L),
        deviceId = lastDeviceId,
        opId = lastOpId
    )
}

@IgnoreExtraProperties
data class CloudHiddenArtist(
    @DocumentId val artistKey: String = "",
    val name: String = "",
    val channelId: String? = null,
    val blockedAt: Long = 0L,
    val deleted: Boolean = false,
    val clientUpdatedAt: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class CloudPlaylistWithTracks(
    val playlist: CloudPlaylist,
    val subcollectionTracks: List<CloudPlaylistTrack> = emptyList(),
    val hasLegacyArrayTracks: Boolean = false
)

data class CloudAccountSnapshot(
    val profile: CloudUserProfile? = null,
    val tasteSeeds: List<CloudTasteSeed> = emptyList(),
    val likedSongs: List<CloudLikedSong> = emptyList(),
    val playlists: List<CloudPlaylistWithTracks> = emptyList(),
    val hiddenArtists: List<CloudHiddenArtist> = emptyList(),
    val maxLikedUpdatedAtMs: Long = 0L,
    val maxSeedsUpdatedAtMs: Long = 0L,
    val maxPlaylistsUpdatedAtMs: Long = 0L,
    val maxHiddenUpdatedAtMs: Long = 0L
)
