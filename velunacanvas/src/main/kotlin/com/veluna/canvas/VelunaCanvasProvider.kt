package com.veluna.canvas

import com.veluna.canvas.CanvasArtwork
import kotlinx.serialization.Serializable

@Serializable
data class VelunaCanvasManifest(val items: List<VelunaCanvasItem> = emptyList())

@Serializable
data class VelunaCanvasItem(val song: String, val artist: String, val url: String)

object VelunaCanvasProvider {
  suspend fun getBySongArtist(
    song: String,
    artist: String,
  ): CanvasArtwork? = null
}
