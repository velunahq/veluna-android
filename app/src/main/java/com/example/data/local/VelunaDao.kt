package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.cloud.SyncMutationStamp
import kotlinx.coroutines.flow.Flow

data class PlaylistTrackCountRow(
    val playlistId: Long,
    val count: Int,
    val firstSongJson: String?
)

data class TopSongAggRow(
    val videoId: String,
    val plays: Int,
    val secs: Long
)

data class ListenTotalsRow(
    val totalPlays: Int,
    val totalSecs: Long
)

data class LibraryRestoreSnapshot(
    val likedSongs: List<LikedSongEntity>,
    val blockedArtists: List<BlockedArtistEntity>,
    val savedAlbums: List<SavedAlbumEntity>,
    val followedArtists: List<FollowedArtistEntity>,
    val shortcuts: List<ShortcutEntity>,
    val searchHistory: List<SearchHistoryEntity>,
    val listenSongs: List<ListenSongEntity>,
    val listens: List<ListenEntity>,
    val playlists: List<LocalPlaylistEntity>,
    val playlistTracks: List<LocalPlaylistTrackEntity>,
    val playlistTrackOps: List<PlaylistTrackOpEntity> = emptyList(),
    val playlistFieldOps: List<PlaylistFieldOpEntity> = emptyList()
)

@Dao
interface VelunaDao {
    // --- Listens & Stats ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListen(listen: ListenEntity): Long

    @Query("UPDATE listens SET secs = secs + :deltaSecs WHERE id = :listenId")
    suspend fun addListenSeconds(listenId: Long, deltaSecs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertListenSong(song: ListenSongEntity)

    @Query("SELECT * FROM listens WHERE playedAt >= :sinceMs ORDER BY playedAt DESC")
    fun observeListensSince(sinceMs: Long): Flow<List<ListenEntity>>

    @Query("SELECT * FROM listens ORDER BY playedAt DESC LIMIT 500")
    fun observeAllListens(): Flow<List<ListenEntity>>

    @Query("SELECT * FROM listens WHERE playedAt >= :sinceMs ORDER BY playedAt ASC")
    suspend fun getListensSince(sinceMs: Long): List<ListenEntity>

    @Query("SELECT playedAt FROM listens ORDER BY playedAt ASC")
    suspend fun getAllListenTimestampsAsc(): List<Long>

    @Query("SELECT * FROM listens ORDER BY playedAt ASC")
    suspend fun getAllListensAsc(): List<ListenEntity>

    @Query("SELECT COUNT(*) AS totalPlays, COALESCE(SUM(secs), 0) AS totalSecs FROM listens WHERE playedAt >= :sinceMs")
    suspend fun getListenTotalsSince(sinceMs: Long): ListenTotalsRow

    @Query("SELECT COALESCE(SUM(secs), 0) FROM listens WHERE playedAt >= :startMs AND playedAt < :endMs")
    suspend fun sumListenSecsInRange(startMs: Long, endMs: Long): Long

    @Query(
        """
        SELECT videoId, COUNT(*) AS plays, COALESCE(SUM(secs), 0) AS secs
        FROM listens
        WHERE playedAt >= :sinceMs
        GROUP BY videoId
        ORDER BY plays DESC, secs DESC
        LIMIT :limit
        """
    )
    suspend fun topSongsSince(sinceMs: Long, limit: Int): List<TopSongAggRow>

    @Query("SELECT COUNT(DISTINCT videoId) FROM listens WHERE playedAt >= :sinceMs")
    suspend fun countUniqueSongsSince(sinceMs: Long): Int

    @Query("SELECT * FROM listen_songs WHERE videoId IN (:videoIds)")
    suspend fun getListenSongsByIds(videoIds: List<String>): List<ListenSongEntity>

    @Query("SELECT * FROM listen_songs")
    fun observeListenSongs(): Flow<List<ListenSongEntity>>

    @Query("SELECT * FROM listen_songs")
    suspend fun getAllListenSongs(): List<ListenSongEntity>

    @Query("DELETE FROM listens")
    suspend fun clearListens()

    @Query("DELETE FROM listen_songs")
    suspend fun clearListenSongs()

    // --- Local Playlists ---
    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    suspend fun getPlaylistsOnce(): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): LocalPlaylistEntity?

    @Query("SELECT * FROM local_playlists WHERE cloudKey = :cloudKey LIMIT 1")
    suspend fun getPlaylistByCloudKey(cloudKey: String): LocalPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: LocalPlaylistEntity): Long

    @Query("UPDATE local_playlists SET title = :title, description = :description, coverUri = :coverUri, updatedAt = :updatedAt, syncRevision = syncRevision + 1 WHERE id = :id")
    suspend fun updatePlaylist(id: Long, title: String, description: String, coverUri: String?, updatedAt: Long)

    @Query(
        "UPDATE local_playlists SET title = :title, description = :description, coverUri = :coverUri, " +
            "remoteCoverUri = :remoteCoverUri, coverRemoved = :coverRemoved, updatedAt = :updatedAt, " +
            "syncRevision = syncRevision + 1 WHERE id = :id"
    )
    suspend fun updatePlaylistWithCoverMeta(
        id: Long,
        title: String,
        description: String,
        coverUri: String?,
        remoteCoverUri: String?,
        coverRemoved: Boolean,
        updatedAt: Long
    )

    @Query(
        "UPDATE local_playlists SET title = :title, description = :description, coverUri = :coverUri, " +
            "remoteCoverUri = :remoteCoverUri, coverRemoved = :coverRemoved, updatedAt = :updatedAt, " +
            "syncRevision = :syncRevision WHERE id = :id"
    )
    suspend fun updatePlaylistFromCloud(
        id: Long,
        title: String,
        description: String,
        coverUri: String?,
        remoteCoverUri: String?,
        coverRemoved: Boolean,
        updatedAt: Long,
        syncRevision: Long
    )

    @Query("UPDATE local_playlists SET updatedAt = :updatedAt, syncRevision = syncRevision + 1 WHERE id = :id")
    suspend fun touchPlaylistRevision(id: Long, updatedAt: Long)

    @Query("UPDATE local_playlists SET syncRevision = :syncRevision WHERE id = :id")
    suspend fun setPlaylistSyncRevision(id: Long, syncRevision: Long)

    @Query("UPDATE local_playlists SET cloudKey = :cloudKey WHERE id = :id")
    suspend fun updatePlaylistCloudKey(id: Long, cloudKey: String)

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM local_playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM local_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: Long)

    @Query("DELETE FROM local_playlist_tracks")
    suspend fun clearAllPlaylistTracks()

    @Query("SELECT * FROM local_playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    fun observePlaylistTracks(playlistId: Long): Flow<List<LocalPlaylistTrackEntity>>

    @Query("SELECT * FROM local_playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC, addedAt ASC")
    suspend fun getPlaylistTracks(playlistId: Long): List<LocalPlaylistTrackEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM local_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPlaylistPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(track: LocalPlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(tracks: List<LocalPlaylistTrackEntity>)

    @Query("DELETE FROM local_playlist_tracks WHERE id = :trackRowId")
    suspend fun deletePlaylistTrackByRowId(trackRowId: Long)

    @Query("DELETE FROM local_playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun deletePlaylistTrack(playlistId: Long, videoId: String)

    @Query("UPDATE local_playlist_tracks SET position = :position WHERE id = :rowId")
    suspend fun updatePlaylistTrackPosition(rowId: Long, position: Int)

    // --- Local Device Clock & Causal Playlist Ops ---
    @Query("SELECT * FROM local_device_clock WHERE id = 1")
    suspend fun getLocalDeviceClock(): LocalDeviceClockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalDeviceClock(clock: LocalDeviceClockEntity)

    @Transaction
    suspend fun allocateNextOperationStamp(
        timestampMs: Long = System.currentTimeMillis(),
        preferredDeviceId: String? = null,
        opId: String = ""
    ): SyncMutationStamp {
        val existing = getLocalDeviceClock()
        val deviceId = preferredDeviceId?.trim()?.takeIf { it.isNotBlank() }?.take(128)
            ?: existing?.deviceId?.takeIf { it.isNotBlank() }
            ?: ("dev_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16))
        val nextSeq = (existing?.lastSeq ?: 0L) + 1L
        upsertLocalDeviceClock(LocalDeviceClockEntity(id = 1, deviceId = deviceId, lastSeq = nextSeq))
        return SyncMutationStamp(
            timestampMs = timestampMs.coerceAtLeast(0L),
            seq = nextSeq,
            deviceId = deviceId,
            opId = opId.ifBlank { java.util.UUID.randomUUID().toString().replace("-", "").take(16) }
        )
    }

    @Query("SELECT * FROM playlist_track_ops WHERE playlistId = :playlistId")
    suspend fun getPlaylistTrackOps(playlistId: Long): List<PlaylistTrackOpEntity>

    @Query("SELECT * FROM playlist_track_ops")
    suspend fun getAllPlaylistTrackOpsOnce(): List<PlaylistTrackOpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistTrackOp(op: PlaylistTrackOpEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrackOps(ops: List<PlaylistTrackOpEntity>)

    @Query("DELETE FROM playlist_track_ops WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTrackOps(playlistId: Long)

    @Query("DELETE FROM playlist_track_ops")
    suspend fun clearAllPlaylistTrackOps()

    @Query("SELECT * FROM playlist_field_ops WHERE playlistId = :playlistId")
    suspend fun getPlaylistFieldOps(playlistId: Long): List<PlaylistFieldOpEntity>

    @Query("SELECT * FROM playlist_field_ops")
    suspend fun getAllPlaylistFieldOpsOnce(): List<PlaylistFieldOpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistFieldOp(op: PlaylistFieldOpEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistFieldOps(ops: List<PlaylistFieldOpEntity>)

    @Query("DELETE FROM playlist_field_ops WHERE playlistId = :playlistId")
    suspend fun clearPlaylistFieldOps(playlistId: Long)

    @Query("DELETE FROM playlist_field_ops")
    suspend fun clearAllPlaylistFieldOps()

    @Transaction
    suspend fun updatePlaylistMetadataAtomic(
        id: Long,
        title: String,
        description: String,
        coverUri: String?,
        remoteCoverUri: String?,
        coverRemoved: Boolean,
        updatedAt: Long,
        preferredDeviceId: String? = null
    ) {
        val existing = getPlaylistById(id) ?: return
        updatePlaylistWithCoverMeta(
            id = id,
            title = title,
            description = description,
            coverUri = coverUri,
            remoteCoverUri = remoteCoverUri,
            coverRemoved = coverRemoved,
            updatedAt = updatedAt
        )
        val stamp = allocateNextOperationStamp(updatedAt, preferredDeviceId)
        val existingFields = getPlaylistFieldOps(id).associateBy { it.fieldName }
        if (existing.title != title || PlaylistFieldNames.TITLE !in existingFields) {
            upsertPlaylistFieldOp(
                PlaylistFieldOpEntity(id, PlaylistFieldNames.TITLE, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
            )
        }
        if (existing.description != description || PlaylistFieldNames.DESCRIPTION !in existingFields) {
            upsertPlaylistFieldOp(
                PlaylistFieldOpEntity(id, PlaylistFieldNames.DESCRIPTION, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
            )
        }
        if (existing.coverUri != coverUri ||
            existing.remoteCoverUri != remoteCoverUri ||
            existing.coverRemoved != coverRemoved ||
            PlaylistFieldNames.COVER !in existingFields
        ) {
            upsertPlaylistFieldOp(
                PlaylistFieldOpEntity(id, PlaylistFieldNames.COVER, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
            )
        }
    }

    @Transaction
    suspend fun createPlaylistWithTracks(
        playlist: LocalPlaylistEntity,
        tracks: List<LocalPlaylistTrackEntity>,
        trackOps: List<PlaylistTrackOpEntity>? = null,
        fieldOps: List<PlaylistFieldOpEntity>? = null,
        preferredDeviceId: String? = null
    ): Long {
        require(playlist.cloudKey.isNotBlank()) { "Playlist cloudKey must not be blank" }
        val newId = insertPlaylist(playlist)
        if (tracks.isNotEmpty()) {
            val normalized = tracks.mapIndexed { index, track ->
                track.copy(id = 0, playlistId = newId, position = index)
            }
            insertPlaylistTracks(normalized)
        }
        if (fieldOps != null) {
            if (fieldOps.isNotEmpty()) {
                insertPlaylistFieldOps(fieldOps.map { it.copy(playlistId = newId) })
            }
        } else {
            val stamp = allocateNextOperationStamp(playlist.updatedAt, preferredDeviceId)
            insertPlaylistFieldOps(
                listOf(
                    PlaylistFieldOpEntity(newId, PlaylistFieldNames.TITLE, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId),
                    PlaylistFieldOpEntity(newId, PlaylistFieldNames.DESCRIPTION, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId),
                    PlaylistFieldOpEntity(newId, PlaylistFieldNames.COVER, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId),
                    PlaylistFieldOpEntity(newId, PlaylistFieldNames.ORDER, stamp.timestampMs, stamp.seq, stamp.deviceId, stamp.opId)
                )
            )
        }
        if (trackOps != null) {
            if (trackOps.isNotEmpty()) {
                insertPlaylistTrackOps(trackOps.map { it.copy(playlistId = newId) })
            }
        } else if (tracks.isNotEmpty()) {
            val resolvedTrackOps = tracks.distinctBy { it.videoId }.map { trk ->
                val trkStamp = allocateNextOperationStamp(trk.addedAt, preferredDeviceId)
                PlaylistTrackOpEntity(
                    playlistId = newId,
                    videoId = trk.videoId,
                    deleted = false,
                    timestampMs = trkStamp.timestampMs,
                    opSeq = trkStamp.seq,
                    deviceId = trkStamp.deviceId,
                    opId = trkStamp.opId
                )
            }
            insertPlaylistTrackOps(resolvedTrackOps)
        }
        return newId
    }

    @Transaction
    suspend fun addTrackToPlaylistAtomic(
        playlistId: Long,
        track: LocalPlaylistTrackEntity,
        skipDuplicates: Boolean,
        updatedAt: Long,
        preferredDeviceId: String? = null
    ): Boolean {
        if (getPlaylistById(playlistId) == null) return false
        val existing = getPlaylistTracks(playlistId)
        if (skipDuplicates && existing.any { it.videoId == track.videoId }) return false
        val pos = (existing.maxOfOrNull { it.position } ?: -1) + 1
        insertPlaylistTrack(track.copy(id = 0, playlistId = playlistId, position = pos))
        touchPlaylistRevision(playlistId, updatedAt)
        val stamp = allocateNextOperationStamp(updatedAt, preferredDeviceId)
        upsertPlaylistTrackOp(
            PlaylistTrackOpEntity(
                playlistId = playlistId,
                videoId = track.videoId,
                deleted = false,
                timestampMs = stamp.timestampMs,
                opSeq = stamp.seq,
                deviceId = stamp.deviceId,
                opId = stamp.opId
            )
        )
        upsertPlaylistFieldOp(
            PlaylistFieldOpEntity(
                playlistId = playlistId,
                fieldName = PlaylistFieldNames.ORDER,
                timestampMs = stamp.timestampMs,
                opSeq = stamp.seq,
                deviceId = stamp.deviceId,
                opId = stamp.opId
            )
        )
        return true
    }

    @Transaction
    suspend fun removeTrackFromPlaylistAtomic(
        playlistId: Long,
        videoId: String,
        updatedAt: Long,
        preferredDeviceId: String? = null
    ): Boolean {
        val existing = getPlaylistTracks(playlistId)
        val remaining = existing.filterNot { it.videoId == videoId }
        if (remaining.size == existing.size) return false
        deletePlaylistTrack(playlistId, videoId)
        remaining.forEachIndexed { idx, row ->
            if (row.position != idx) {
                updatePlaylistTrackPosition(row.id, idx)
            }
        }
        touchPlaylistRevision(playlistId, updatedAt)
        val stamp = allocateNextOperationStamp(updatedAt, preferredDeviceId)
        upsertPlaylistTrackOp(
            PlaylistTrackOpEntity(
                playlistId = playlistId,
                videoId = videoId,
                deleted = true,
                timestampMs = stamp.timestampMs,
                opSeq = stamp.seq,
                deviceId = stamp.deviceId,
                opId = stamp.opId
            )
        )
        upsertPlaylistFieldOp(
            PlaylistFieldOpEntity(
                playlistId = playlistId,
                fieldName = PlaylistFieldNames.ORDER,
                timestampMs = stamp.timestampMs,
                opSeq = stamp.seq,
                deviceId = stamp.deviceId,
                opId = stamp.opId
            )
        )
        return true
    }

    @Transaction
    suspend fun removeDuplicateTracksFromPlaylistAtomic(
        playlistId: Long,
        updatedAt: Long,
        preferredDeviceId: String? = null
    ): Int {
        val tracks = getPlaylistTracks(playlistId)
        val seen = HashSet<String>(tracks.size)
        val kept = mutableListOf<LocalPlaylistTrackEntity>()
        var removed = 0
        for (row in tracks) {
            if (!seen.add(row.videoId)) {
                deletePlaylistTrackByRowId(row.id)
                removed++
            } else {
                kept.add(row)
            }
        }
        if (removed > 0) {
            kept.forEachIndexed { idx, row ->
                if (row.position != idx) {
                    updatePlaylistTrackPosition(row.id, idx)
                }
            }
            touchPlaylistRevision(playlistId, updatedAt)
            val stamp = allocateNextOperationStamp(updatedAt, preferredDeviceId)
            upsertPlaylistFieldOp(
                PlaylistFieldOpEntity(
                    playlistId = playlistId,
                    fieldName = PlaylistFieldNames.ORDER,
                    timestampMs = stamp.timestampMs,
                    opSeq = stamp.seq,
                    deviceId = stamp.deviceId,
                    opId = stamp.opId
                )
            )
        }
        return removed
    }

    @Transaction
    suspend fun reorderPlaylistTracksAtomic(
        playlistId: Long,
        orderedRowIds: List<Long>,
        updatedAt: Long,
        preferredDeviceId: String? = null
    ): Boolean {
        val existing = getPlaylistTracks(playlistId)
        if (existing.size <= 1) return false
        val byId = existing.associateBy { it.id }
        val reordered = mutableListOf<LocalPlaylistTrackEntity>()
        val seen = HashSet<Long>(existing.size)
        for (rowId in orderedRowIds) {
            val row = byId[rowId]
            if (row != null && seen.add(rowId)) {
                reordered.add(row)
            }
        }
        for (row in existing) {
            if (seen.add(row.id)) {
                reordered.add(row)
            }
        }
        if (reordered.map { it.id } == existing.map { it.id }) return false
        reordered.forEachIndexed { idx, row ->
            if (row.position != idx) {
                updatePlaylistTrackPosition(row.id, idx)
            }
        }
        touchPlaylistRevision(playlistId, updatedAt)
        val stamp = allocateNextOperationStamp(updatedAt, preferredDeviceId)
        upsertPlaylistFieldOp(
            PlaylistFieldOpEntity(
                playlistId = playlistId,
                fieldName = PlaylistFieldNames.ORDER,
                timestampMs = stamp.timestampMs,
                opSeq = stamp.seq,
                deviceId = stamp.deviceId,
                opId = stamp.opId
            )
        )
        return true
    }

    @Transaction
    suspend fun replacePlaylistFromCloudAtomic(
        playlistId: Long,
        title: String,
        description: String,
        coverUri: String?,
        remoteCoverUri: String? = coverUri?.takeIf { it.startsWith("https://") },
        coverRemoved: Boolean = false,
        updatedAt: Long,
        syncRevision: Long,
        tracks: List<LocalPlaylistTrackEntity>,
        trackOps: List<PlaylistTrackOpEntity>? = null,
        fieldOps: List<PlaylistFieldOpEntity>? = null
    ) {
        updatePlaylistFromCloud(
            id = playlistId,
            title = title,
            description = description,
            coverUri = coverUri,
            remoteCoverUri = remoteCoverUri,
            coverRemoved = coverRemoved,
            updatedAt = updatedAt,
            syncRevision = syncRevision
        )
        clearPlaylistTracks(playlistId)
        if (tracks.isNotEmpty()) {
            val normalized = tracks.mapIndexed { idx, row ->
                row.copy(id = 0, playlistId = playlistId, position = idx)
            }
            insertPlaylistTracks(normalized)
        }
        if (trackOps != null) {
            clearPlaylistTrackOps(playlistId)
            if (trackOps.isNotEmpty()) {
                insertPlaylistTrackOps(trackOps.map { it.copy(playlistId = playlistId) })
            }
        }
        if (fieldOps != null) {
            clearPlaylistFieldOps(playlistId)
            if (fieldOps.isNotEmpty()) {
                insertPlaylistFieldOps(fieldOps.map { it.copy(playlistId = playlistId) })
            }
        }
    }

    @Transaction
    suspend fun deletePlaylistAtomic(playlistId: Long, shortcutId: String) {
        clearPlaylistTracks(playlistId)
        clearPlaylistTrackOps(playlistId)
        clearPlaylistFieldOps(playlistId)
        deletePlaylist(playlistId)
        deleteShortcut(shortcutId)
    }

    @Query(
        """
        SELECT playlistId, COUNT(*) AS count,
        (SELECT songJson FROM local_playlist_tracks t2 WHERE t2.playlistId = t1.playlistId ORDER BY position ASC, addedAt ASC LIMIT 1) AS firstSongJson
        FROM local_playlist_tracks t1
        GROUP BY playlistId
        """
    )
    fun observePlaylistTrackCounts(): Flow<List<PlaylistTrackCountRow>>

    // --- Liked Songs ---
    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun observeLikedSongs(): Flow<List<LikedSongEntity>>

    @Query("SELECT videoId FROM liked_songs")
    fun observeLikedVideoIds(): Flow<List<String>>

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getLikedSongsOnce(): List<LikedSongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :videoId)")
    suspend fun isSongLiked(videoId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSong(entity: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE videoId = :videoId")
    suspend fun deleteLikedSong(videoId: String)

    @Query("SELECT videoId FROM liked_songs WHERE videoId LIKE 'LOCAL:%'")
    suspend fun getLocalLikedVideoIds(): List<String>

    @Query("DELETE FROM liked_songs WHERE videoId LIKE 'LOCAL:%'")
    suspend fun deleteLocalLikedSongs(): Int

    @Query("DELETE FROM liked_songs")
    suspend fun clearLikedSongs()

    // --- Saved Albums & Followed Artists ---
    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    fun observeSavedAlbums(): Flow<List<SavedAlbumEntity>>

    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    suspend fun getSavedAlbumsOnce(): List<SavedAlbumEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE albumId = :albumId)")
    fun observeIsAlbumSaved(albumId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE albumId = :albumId)")
    suspend fun isAlbumSaved(albumId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedAlbum(album: SavedAlbumEntity)

    @Query("DELETE FROM saved_albums WHERE albumId = :albumId")
    suspend fun deleteSavedAlbum(albumId: String)

    @Query("DELETE FROM saved_albums")
    suspend fun clearSavedAlbums()

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun observeFollowedArtists(): Flow<List<FollowedArtistEntity>>

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    suspend fun getFollowedArtistsOnce(): List<FollowedArtistEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE channelId = :channelId)")
    fun observeIsArtistFollowed(channelId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE channelId = :channelId)")
    suspend fun isArtistFollowed(channelId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFollowedArtist(artist: FollowedArtistEntity)

    @Query("DELETE FROM followed_artists WHERE channelId = :channelId")
    suspend fun deleteFollowedArtist(channelId: String)

    @Query("DELETE FROM followed_artists")
    suspend fun clearFollowedArtists()

    // --- Shortcuts ---
    @Query("SELECT * FROM shortcuts ORDER BY position ASC")
    fun observeShortcuts(): Flow<List<ShortcutEntity>>

    @Query("SELECT * FROM shortcuts ORDER BY position ASC")
    suspend fun getShortcutsOnce(): List<ShortcutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShortcut(shortcut: ShortcutEntity)

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun deleteShortcut(id: String)

    @Query("DELETE FROM shortcuts")
    suspend fun clearShortcuts()

    // --- Local Audio Files ---
    @Query("SELECT * FROM local_tracks ORDER BY title ASC")
    fun observeLocalTracks(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks ORDER BY title ASC")
    suspend fun getLocalTracksOnce(): List<LocalTrackEntity>

    @Query("SELECT * FROM local_tracks WHERE albumKey = :albumKey ORDER BY trackNo ASC, title ASC")
    suspend fun getLocalTracksByAlbum(albumKey: String): List<LocalTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalTracks(tracks: List<LocalTrackEntity>)

    @Query("DELETE FROM local_tracks WHERE uri = :uri")
    suspend fun deleteLocalTrack(uri: String)

    // --- Downloads ---
    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun observeDownloads(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks WHERE videoId = :videoId")
    suspend fun getDownload(videoId: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(download: DownloadedTrackEntity)

    @Query("DELETE FROM downloaded_tracks WHERE videoId = :videoId")
    suspend fun deleteDownload(videoId: String)

    // --- Search History ---
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 20")
    fun observeSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun getSearchHistoryOnce(limit: Int = 100): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchHistory(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearchHistory(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    // --- Blocked Artists ---
    @Query("SELECT * FROM blocked_artists ORDER BY blockedAt DESC")
    fun observeBlockedArtists(): Flow<List<BlockedArtistEntity>>

    @Query("SELECT * FROM blocked_artists ORDER BY blockedAt DESC")
    suspend fun getBlockedArtistsOnce(): List<BlockedArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedArtist(artist: BlockedArtistEntity)

    @Query("DELETE FROM blocked_artists WHERE name = :name")
    suspend fun deleteBlockedArtist(name: String)

    @Query("DELETE FROM blocked_artists")
    suspend fun clearBlockedArtists()

    // --- Lyrics Cache ---
    @Query("SELECT * FROM lyrics_cache WHERE videoId = :videoId")
    suspend fun getLyricsCache(videoId: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyricsCache(entity: LyricsCacheEntity)

    @Query("DELETE FROM lyrics_cache WHERE pinned = 0")
    suspend fun clearUnpinnedLyricsCache()

    @Query("DELETE FROM lyrics_cache WHERE videoId = :videoId")
    suspend fun deleteLyricsCache(videoId: String)

    // --- Sync Tombstones ---
    @Query("SELECT * FROM sync_tombstones WHERE entityType = :entityType")
    suspend fun getTombstonesByType(entityType: String): List<SyncTombstoneEntity>

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAllTombstones(): List<SyncTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Query("DELETE FROM sync_tombstones WHERE entityType = :entityType AND entityKey = :entityKey")
    suspend fun deleteTombstone(entityType: String, entityKey: String)

    @Query("DELETE FROM sync_tombstones WHERE deletedAt < :cutoffMs")
    suspend fun purgeOldTombstones(cutoffMs: Long): Int

    @Query("DELETE FROM sync_tombstones")
    suspend fun clearTombstones()

    // --- Durable Sync Baselines ---
    @Query("SELECT * FROM sync_baselines WHERE userId = :userId AND entityType = :entityType")
    suspend fun getSyncBaselinesByType(userId: String, entityType: String): List<SyncBaselineEntity>

    @Query("SELECT * FROM sync_baselines WHERE userId = :userId")
    suspend fun getAllSyncBaselinesForUser(userId: String): List<SyncBaselineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncBaseline(baseline: SyncBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncBaselines(baselines: List<SyncBaselineEntity>)

    @Query("DELETE FROM sync_baselines WHERE userId = :userId AND entityType = :entityType AND entityKey = :entityKey")
    suspend fun deleteSyncBaseline(userId: String, entityType: String, entityKey: String)

    @Query("DELETE FROM sync_baselines WHERE userId = :userId AND entityType = :entityType")
    suspend fun clearSyncBaselinesForType(userId: String, entityType: String)

    @Query("DELETE FROM sync_baselines WHERE userId = :userId")
    suspend fun clearSyncBaselinesForUser(userId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_baselines WHERE userId != :uid)")
    suspend fun hasSyncBaselinesForOtherUsers(uid: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_baselines)")
    suspend fun hasAnySyncBaselines(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<SyncTombstoneEntity>)

    @Query("DELETE FROM sync_baselines")
    suspend fun clearAllSyncBaselines()

    // --- Pending Scrobbles Retry Queue ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingScrobble(entity: PendingScrobbleEntity): Long

    @Query("SELECT * FROM pending_scrobbles WHERE nextAttemptAt <= :nowMs ORDER BY id ASC")
    suspend fun getDuePendingScrobbles(nowMs: Long): List<PendingScrobbleEntity>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    fun observePendingScrobbleCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_scrobbles")
    suspend fun countPendingScrobbles(): Int

    @Query("UPDATE pending_scrobbles SET attempts = :attempts, nextAttemptAt = :nextAttemptAt, lastError = :lastError WHERE id = :id")
    suspend fun updatePendingScrobbleRetry(id: Long, attempts: Int, nextAttemptAt: Long, lastError: String?)

    @Query("DELETE FROM pending_scrobbles WHERE id = :id")
    suspend fun deletePendingScrobble(id: Long)

    @Query("DELETE FROM pending_scrobbles")
    suspend fun clearPendingScrobbles()

    @Query("SELECT * FROM local_playlist_tracks ORDER BY playlistId ASC, position ASC, addedAt ASC")
    suspend fun getAllPlaylistTracksOnce(): List<LocalPlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<LocalPlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSongs(songs: List<LikedSongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedArtists(artists: List<BlockedArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedAlbums(albums: List<SavedAlbumEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFollowedArtists(artists: List<FollowedArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShortcuts(shortcuts: List<ShortcutEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistoryList(entries: List<SearchHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListenSongs(songs: List<ListenSongEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListens(listens: List<ListenEntity>)

    @Transaction
    suspend fun captureLibrarySnapshot(): LibraryRestoreSnapshot {
        return LibraryRestoreSnapshot(
            likedSongs = getLikedSongsOnce(),
            blockedArtists = getBlockedArtistsOnce(),
            savedAlbums = getSavedAlbumsOnce(),
            followedArtists = getFollowedArtistsOnce(),
            shortcuts = getShortcutsOnce(),
            searchHistory = getSearchHistoryOnce(10_000),
            listenSongs = getAllListenSongs(),
            listens = getAllListensAsc(),
            playlists = getPlaylistsOnce(),
            playlistTracks = getAllPlaylistTracksOnce(),
            playlistTrackOps = getAllPlaylistTrackOpsOnce(),
            playlistFieldOps = getAllPlaylistFieldOpsOnce()
        )
    }

    @Transaction
    suspend fun restoreLibrarySnapshotAtomic(snapshot: LibraryRestoreSnapshot) {
        clearLikedSongs()
        clearBlockedArtists()
        clearSavedAlbums()
        clearFollowedArtists()
        clearShortcuts()
        clearSearchHistory()
        clearListens()
        clearListenSongs()
        clearAllPlaylistTracks()
        clearAllPlaylistTrackOps()
        clearAllPlaylistFieldOps()
        clearPlaylists()

        if (snapshot.likedSongs.isNotEmpty()) insertLikedSongs(snapshot.likedSongs)
        if (snapshot.blockedArtists.isNotEmpty()) insertBlockedArtists(snapshot.blockedArtists)
        if (snapshot.savedAlbums.isNotEmpty()) insertSavedAlbums(snapshot.savedAlbums)
        if (snapshot.followedArtists.isNotEmpty()) insertFollowedArtists(snapshot.followedArtists)
        if (snapshot.shortcuts.isNotEmpty()) insertShortcuts(snapshot.shortcuts)
        if (snapshot.searchHistory.isNotEmpty()) insertSearchHistoryList(snapshot.searchHistory)
        if (snapshot.listenSongs.isNotEmpty()) insertListenSongs(snapshot.listenSongs)
        if (snapshot.listens.isNotEmpty()) insertListens(snapshot.listens)
        if (snapshot.playlists.isNotEmpty()) insertPlaylists(snapshot.playlists)
        if (snapshot.playlistTracks.isNotEmpty()) insertPlaylistTracks(snapshot.playlistTracks)
        if (snapshot.playlistTrackOps.isNotEmpty()) insertPlaylistTrackOps(snapshot.playlistTrackOps)
        if (snapshot.playlistFieldOps.isNotEmpty()) insertPlaylistFieldOps(snapshot.playlistFieldOps)
    }

    /**
     * Transactionally clears ONLY cloud-synced account tables on sign-out.
     * Preserves all device-only datasets: listens, listen_songs, search_history, saved_albums,
     * followed_artists, shortcuts, local_tracks, downloaded_tracks, lyrics_cache, and pending_scrobbles.
     */
    @Transaction
    suspend fun clearSyncedAccountData() {
        clearLikedSongs()
        clearAllPlaylistTracks()
        clearAllPlaylistTrackOps()
        clearAllPlaylistFieldOps()
        clearPlaylists()
        clearBlockedArtists()
        clearTombstones()
        clearAllSyncBaselines()
    }

    /**
     * Transactionally clears all account-owned tables on permanent account deletion.
     * Deliberately preserves local_tracks, downloaded_tracks, and lyrics_cache.
     */
    @Transaction
    suspend fun clearAccountData() {
        clearSyncedAccountData()
        clearSavedAlbums()
        clearFollowedArtists()
        clearShortcuts()
        clearListens()
        clearListenSongs()
        clearSearchHistory()
        clearPendingScrobbles()
    }

    /**
     * Atomically restores quarantined unowned library data (liked songs, blocked artists,
     * sync tombstones, and all playlists with tracks + causal ops) inside a single Room transaction.
     * Deduplicates playlists by `cloudKey` so interrupted retries are 100% idempotent and cannot
     * violate the unique `cloudKey` index.
     */
    @Transaction
    suspend fun restoreQuarantinedAccountDataAtomic(
        likedSongs: List<LikedSongEntity>,
        blockedArtists: List<BlockedArtistEntity>,
        tombstones: List<SyncTombstoneEntity>,
        playlists: List<QuarantinePlaylistBundle>
    ) {
        if (likedSongs.isNotEmpty()) insertLikedSongs(likedSongs)
        if (blockedArtists.isNotEmpty()) insertBlockedArtists(blockedArtists)
        if (tombstones.isNotEmpty()) insertTombstones(tombstones)
        for (bundle in playlists) {
            val pl = bundle.playlist
            require(pl.cloudKey.isNotBlank()) { "Playlist cloudKey must not be blank" }
            val existing = getPlaylistByCloudKey(pl.cloudKey)
            if (existing == null) {
                createPlaylistWithTracks(
                    playlist = pl,
                    tracks = bundle.tracks,
                    trackOps = bundle.trackOps.takeIf { it.isNotEmpty() },
                    fieldOps = bundle.fieldOps.takeIf { it.isNotEmpty() }
                )
            } else {
                updatePlaylistFromCloud(
                    id = existing.id,
                    title = pl.title,
                    description = pl.description,
                    coverUri = pl.coverUri,
                    remoteCoverUri = pl.remoteCoverUri,
                    coverRemoved = pl.coverRemoved,
                    updatedAt = pl.updatedAt,
                    syncRevision = pl.syncRevision
                )
                clearPlaylistTracks(existing.id)
                if (bundle.tracks.isNotEmpty()) {
                    insertPlaylistTracks(
                        bundle.tracks.mapIndexed { idx, track ->
                            track.copy(id = 0, playlistId = existing.id, position = idx)
                        }
                    )
                }
                if (bundle.trackOps.isNotEmpty()) {
                    clearPlaylistTrackOps(existing.id)
                    insertPlaylistTrackOps(bundle.trackOps.map { it.copy(playlistId = existing.id) })
                }
                if (bundle.fieldOps.isNotEmpty()) {
                    clearPlaylistFieldOps(existing.id)
                    insertPlaylistFieldOps(bundle.fieldOps.map { it.copy(playlistId = existing.id) })
                }
            }
        }
    }
}

data class QuarantinePlaylistBundle(
    val playlist: LocalPlaylistEntity,
    val tracks: List<LocalPlaylistTrackEntity>,
    val trackOps: List<PlaylistTrackOpEntity>,
    val fieldOps: List<PlaylistFieldOpEntity>
)
