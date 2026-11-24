package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SearchRoute

@Serializable
object LibraryRoute

@Serializable
object StatsRoute

@Serializable
object SettingsRoute

@Serializable
data class AlbumRoute(val albumId: String)

@Serializable
data class ArtistRoute(val channelId: String)

@Serializable
data class PlaylistRoute(val playlistId: String)

@Serializable
object HistoryRoute

enum class VelunaTopTab(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    STATS("Stats"),
    SETTINGS("Settings")
}
