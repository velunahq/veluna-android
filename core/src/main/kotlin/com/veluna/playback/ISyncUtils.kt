package com.veluna.playback

import com.veluna.db.entities.SongEntity

interface ISyncUtils {
  fun likeSong(song: SongEntity)
}
