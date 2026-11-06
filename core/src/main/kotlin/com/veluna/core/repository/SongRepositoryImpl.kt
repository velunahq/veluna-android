package com.veluna.core.repository

import com.veluna.core.mapping.toDomain
import com.veluna.db.MusicDatabase
import com.veluna.domain.models.Song
import com.veluna.domain.repositories.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject

class SongRepositoryImpl @Inject constructor(
  private val database: MusicDatabase
) : SongRepository {

  override fun getSong(id: String): Flow<Song?> {
    return database.song(id).map { it?.toDomain() }
  }

  override fun getLikedSongs(): Flow<List<Song>> {
    return database.likedSongsByRowIdAsc().map { list -> list.map { it.toDomain() } }
  }

  override fun getRecentlyPlayed(limit: Int): Flow<List<Song>> {
    return database.events().map { events ->
      events.take(limit).map { it.song.toDomain() }
    }
  }

  override suspend fun updateLiked(id: String, isLiked: Boolean) {
    val date = if (isLiked) LocalDateTime.now() else null
    database.query {
      updateLikedStatus(id, isLiked, date)
    }
  }

  override suspend fun incrementPlayTime(id: String, durationMs: Long) {
    database.query {
      incrementTotalPlayTime(id, durationMs)
    }
  }
}
