package com.veluna.core.repository

import com.veluna.core.mapping.toDomain
import com.veluna.db.MusicDatabase
import com.veluna.db.entities.PlaylistSongMap
import com.veluna.domain.models.Playlist
import com.veluna.domain.models.Song
import com.veluna.domain.repositories.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PlaylistRepositoryImpl @Inject constructor(
  private val database: MusicDatabase
) : PlaylistRepository {

  override fun getAllPlaylists(): Flow<List<Playlist>> {
    return database.playlistsByNameAsc().map { list -> list.map { it.toDomain() } }
  }

  override fun getPlaylistSongs(playlistId: String): Flow<List<Song>> {
    return database.playlistSongs(playlistId).map { list -> list.map { it.song.toDomain() } }
  }

  override suspend fun addSongToPlaylist(playlistId: String, songId: String) {
    database.query {
      val position = (playlistSongMaps(songId).filter { it.playlistId == playlistId }.maxOfOrNull { it.position } ?: -1) + 1
      insert(
        PlaylistSongMap(
          songId = songId,
          playlistId = playlistId,
          position = position
        )
      )
    }
  }

  override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
    database.query {
      playlistSongMaps(songId)
        .filter { it.playlistId == playlistId }
        .forEach { delete(it) }
    }
  }
}
