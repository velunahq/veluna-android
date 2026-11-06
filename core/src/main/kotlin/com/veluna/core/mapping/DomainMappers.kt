package com.veluna.core.mapping

import com.veluna.db.entities.AlbumEntity
import com.veluna.db.entities.ArtistEntity
import com.veluna.db.entities.Playlist as DbPlaylist
import com.veluna.db.entities.PlaylistEntity
import com.veluna.db.entities.Song as DbSong
import com.veluna.db.entities.SongEntity
import com.veluna.domain.models.AlbumRef
import com.veluna.domain.models.ArtistRef
import com.veluna.domain.models.Playlist as DomainPlaylist
import com.veluna.domain.models.Song as DomainSong

fun SongEntity.toDomain(): DomainSong {
  return DomainSong(
    id = id,
    title = title,
    artists = emptyList(),
    album = if (albumId != null || albumName != null) AlbumRef(id = albumId ?: "", title = albumName ?: "") else null,
    durationSeconds = duration,
    thumbnailUrl = thumbnailUrl,
    isExplicit = explicit,
    isLocal = isLocal,
    liked = liked,
    totalPlayTimeMs = totalPlayTime
  )
}

fun DbSong.toDomain(): DomainSong {
  return DomainSong(
    id = song.id,
    title = song.title,
    artists = artists.map { ArtistRef(id = it.id, name = it.name) },
    album = album?.let { AlbumRef(id = it.id, title = it.title) },
    durationSeconds = song.duration,
    thumbnailUrl = song.thumbnailUrl,
    isExplicit = song.explicit,
    isLocal = song.isLocal,
    liked = song.liked,
    totalPlayTimeMs = song.totalPlayTime
  )
}

fun PlaylistEntity.toDomain(songCount: Int = 0): DomainPlaylist {
  return DomainPlaylist(
    id = id,
    name = name,
    songCount = songCount,
    thumbnailUrl = thumbnailUrl
  )
}

fun DbPlaylist.toDomain(): DomainPlaylist {
  return DomainPlaylist(
    id = playlist.id,
    name = playlist.name,
    songCount = songCount,
    thumbnailUrl = playlist.thumbnailUrl ?: songThumbnails.firstOrNull { it != null }
  )
}
