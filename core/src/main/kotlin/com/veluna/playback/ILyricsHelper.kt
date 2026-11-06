package com.veluna.playback

import com.veluna.models.MediaMetadata

data class LyricsWithProvider(val lyrics: String?, val providerName: String)

interface ILyricsHelper {
  suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider
}
