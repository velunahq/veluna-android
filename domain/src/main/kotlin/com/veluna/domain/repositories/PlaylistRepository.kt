package com.veluna.domain.repositories

import com.veluna.domain.models.Playlist
import com.veluna.domain.models.Song
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
  fun getAllPlaylists(): Flow<List<Playlist>>
  fun getPlaylistSongs(playlistId: String): Flow<List<Song>>
  suspend fun addSongToPlaylist(playlistId: String, songId: String)
  suspend fun removeSongFromPlaylist(playlistId: String, songId: String)
}
