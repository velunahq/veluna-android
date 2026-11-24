package com.example.data.network

import com.example.model.AlbumPage
import com.example.model.ArtistCarousel
import com.example.model.ArtistPage
import com.example.model.ArtistRun
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.CommentItem
import com.example.model.CommentsPage
import com.example.model.HomeChip
import com.example.model.HomePage
import com.example.model.HomeSection
import com.example.model.LyricLine
import com.example.model.MoodSection
import com.example.model.MoodTile
import com.example.model.PlaylistPage
import com.example.model.SearchResults
import com.example.model.SearchSuggestions
import com.example.model.SongItem
import com.example.util.SecurityValidators
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object InnerTubeParsers {

    fun findAll(root: JsonElement?, key: String): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        val pending = java.util.ArrayDeque<JsonElement>()
        root?.let(pending::addLast)
        while (pending.isNotEmpty()) {
            when (val node = pending.removeLast()) {
                is JsonObject -> {
                    node[key]?.let { out.add(it) }
                    val children = node.values.toList()
                    for (index in children.indices.reversed()) pending.addLast(children[index])
                }
                is JsonArray -> {
                    for (index in node.indices.reversed()) pending.addLast(node[index])
                }
                else -> {}
            }
        }
        return out
    }

    fun runsText(node: JsonElement?): String? {
        val obj = node as? JsonObject ?: return null
        val runs = obj["runs"] as? JsonArray
        if (runs != null) {
            val text = runs.mapNotNull { (it as? JsonObject)?.get("text")?.strOrNull() }.joinToString("")
            return text.takeIf { it.isNotEmpty() }
        }
        return obj["simpleText"]?.strOrNull()
    }

    private fun JsonElement?.strOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arrOrNull(): JsonArray? = this as? JsonArray

    fun upgradeThumbnailUrl(url: String?, targetPx: Int = 720): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.drop(7)
            else -> raw
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.host.isBlank()) return null
        val host = parsed.host
        val normalized = parsed.toString()

        if (SecurityValidators.isHostOrSubdomain(host, "googleusercontent.com") ||
            SecurityValidators.isHostOrSubdomain(host, "ggpht.com")
        ) {
            return when {
                Regex("""=w\d+-h\d+[^?#]*""").containsMatchIn(normalized) ->
                    normalized.replace(Regex("""=w\d+-h\d+[^?#]*"""), "=w$targetPx-h$targetPx-l95-rj")
                Regex("""=s\d+[^?#]*""").containsMatchIn(normalized) ->
                    normalized.replace(Regex("""=s\d+[^?#]*"""), "=s$targetPx-c-k-c0x00ffffff-no-rj")
                else -> normalized
            }
        }
        if (SecurityValidators.isHostOrSubdomain(host, "ytimg.com") && "/vi/" in parsed.encodedPath) {
            return normalized
                .replace("/default.jpg", "/hqdefault.jpg")
                .replace("/mqdefault.jpg", "/hqdefault.jpg")
        }
        return SecurityValidators.sanitizeHttpsUrl(normalized, 2048)
    }

    private fun lastThumbnail(node: JsonElement?): String? {
        val thumbnails = findAll(node, "thumbnails").firstOrNull()?.arrOrNull() ?: return null
        val raw = thumbnails.lastOrNull()?.objOrNull()?.get("url")?.strOrNull() ?: return null
        return upgradeThumbnailUrl(raw)
    }

    private fun isExplicit(node: JsonElement?): Boolean =
        findAll(node, "musicInlineBadgeRenderer").any { badge ->
            badge.objOrNull()?.get("icon")?.objOrNull()?.get("iconType")?.strOrNull() == "MUSIC_EXPLICIT_BADGE"
        }

    private fun endpointVideoType(endpoint: JsonElement?): String? {
        val obj = endpoint.objOrNull() ?: return null
        val watch = obj["watchEndpoint"].objOrNull() ?: obj["watchPlaylistEndpoint"].objOrNull() ?: return null
        return watch["watchEndpointMusicSupportedConfigs"]
            ?.objOrNull()?.get("watchEndpointMusicConfig")
            ?.objOrNull()?.get("musicVideoType")?.strOrNull()
    }

    private fun isVideoEndpoint(endpoint: JsonElement?): Boolean {
        val t = endpointVideoType(endpoint) ?: return false
        return t != "MUSIC_VIDEO_TYPE_ATV" && t != "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
    }

    private fun extractArtistRuns(runsNode: JsonElement?): List<ArtistRun> {
        val runs = runsNode.objOrNull()?.get("runs").arrOrNull() ?: return emptyList()
        return runs.mapNotNull { runEl ->
            val run = runEl.objOrNull() ?: return@mapNotNull null
            val text = run["text"].strOrNull() ?: return@mapNotNull null
            val browseId = run["navigationEndpoint"]
                ?.objOrNull()?.get("browseEndpoint")
                ?.objOrNull()?.get("browseId")?.strOrNull()
                ?.takeIf { it.startsWith("UC") || it.startsWith("FEmusic") }
            ArtistRun(text = text, id = browseId)
        }
    }

    private fun isDurationToken(s: String): Boolean {
        val parts = s.trim().split(":")
        return parts.size in 2..3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    private fun splitMetaRuns(runs: JsonArray?): Triple<List<ArtistRun>, String?, String?> {
        if (runs == null) return Triple(emptyList(), null, null)
        val artists = mutableListOf<ArtistRun>()
        var albumName: String? = null
        var albumId: String? = null
        var duration: String? = null
        for (el in runs) {
            val obj = el.objOrNull() ?: continue
            val text = obj["text"].strOrNull() ?: continue
            val browse = obj["navigationEndpoint"]?.objOrNull()?.get("browseEndpoint")?.objOrNull()
            val browseId = browse?.get("browseId")?.strOrNull()
            val pageType = browse?.get("browseEndpointContextSupportedConfigs")
                ?.objOrNull()?.get("browseEndpointContextMusicConfig")
                ?.objOrNull()?.get("pageType")?.strOrNull()

            when {
                isDurationToken(text) -> duration = text.trim()
                pageType == "MUSIC_PAGE_TYPE_ALBUM" || (browseId != null && browseId.startsWith("MPRE")) -> {
                    albumName = text
                    albumId = browseId
                }
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || (browseId != null && browseId.startsWith("UC")) -> {
                    artists.add(ArtistRun(text = text, id = browseId))
                }
                text.trim() in setOf("•", "·", ",", "&", "feat.", "Song", "Video", "Album", "EP", "Single") -> {}
                !text.trim().endsWith("plays") && !text.trim().endsWith("views") && albumName == null && artists.isEmpty() -> {
                    artists.add(ArtistRun(text = text, id = null))
                }
            }
        }
        return Triple(artists, albumName?.let { "$it|$albumId" }, duration)
    }

    fun parseResponsiveListItem(item: JsonObject): SongItem? {
        val overlayEndpoint = item["overlay"]
            ?.objOrNull()?.get("musicItemThumbnailOverlayRenderer")
            ?.objOrNull()?.get("content")
            ?.objOrNull()?.get("musicPlayButtonRenderer")
            ?.objOrNull()?.get("playNavigationEndpoint")

        val videoId = overlayEndpoint?.objOrNull()?.get("watchEndpoint")?.objOrNull()?.get("videoId")?.strOrNull()
            ?: item["playlistItemData"]?.objOrNull()?.get("videoId")?.strOrNull()
            ?: findAll(item, "watchEndpoint").firstOrNull()?.objOrNull()?.get("videoId")?.strOrNull()
            ?: return null

        val flexColumns = item["flexColumns"].arrOrNull().orEmpty()
        val title = flexColumns.getOrNull(0)
            ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.objOrNull()?.get("text")?.let(::runsText)
            ?: return null

        val col1Runs = flexColumns.getOrNull(1)
            ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.objOrNull()?.get("text")?.objOrNull()?.get("runs").arrOrNull()

        val (parsedArtists, albumPacked, metaDuration) = splitMetaRuns(col1Runs)
        val col1FullText = flexColumns.getOrNull(1)
            ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.objOrNull()?.get("text")?.let(::runsText).orEmpty()

        val artistsText = if (parsedArtists.isNotEmpty()) {
            parsedArtists.joinToString(", ") { it.text }
        } else {
            col1FullText.split("•").firstOrNull { !isDurationToken(it) }?.trim().orEmpty()
        }

        val col2Text = flexColumns.getOrNull(2)
            ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.objOrNull()?.get("text")?.let(::runsText)
        val col2Album = flexColumns.getOrNull(2)
            ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
            ?.objOrNull()?.get("text")?.objOrNull()?.get("runs")?.arrOrNull()
            ?.firstOrNull()?.objOrNull()
        val col2BrowseId = col2Album?.get("navigationEndpoint")?.objOrNull()?.get("browseEndpoint")?.objOrNull()?.get("browseId")?.strOrNull()

        val fixedDuration = item["fixedColumns"]?.arrOrNull()?.firstOrNull()
            ?.objOrNull()?.get("musicResponsiveListItemFixedColumnRenderer")
            ?.objOrNull()?.get("text")?.let(::runsText)
            ?.takeIf { isDurationToken(it) }

        val packedParts = albumPacked?.split("|", limit = 2)
        val albumTitle = packedParts?.getOrNull(0) ?: col2Text?.takeIf { !it.contains("plays") }
        val albumId = packedParts?.getOrNull(1)?.takeIf { it != "null" } ?: col2BrowseId

        val playCount = listOfNotNull(col2Text, col1FullText).firstOrNull {
            it.contains("play", ignoreCase = true) || it.contains("view", ignoreCase = true)
        }?.split("•")?.firstOrNull { it.contains("play", ignoreCase = true) || it.contains("view", ignoreCase = true) }?.trim()

        return SongItem(
            videoId = videoId,
            title = title,
            artists = artistsText.ifBlank { "Unknown Artist" },
            artistId = parsedArtists.firstOrNull { it.id != null }?.id,
            artistRuns = parsedArtists,
            album = albumTitle,
            albumId = albumId,
            duration = fixedDuration ?: metaDuration,
            playCount = playCount,
            thumbnail = lastThumbnail(item["thumbnail"]),
            explicit = isExplicit(item),
            isVideo = isVideoEndpoint(overlayEndpoint)
        )
    }

    fun parseTwoRowItem(item: JsonObject): BrowseItem? {
        val title = runsText(item["title"]) ?: return null
        val subtitle = runsText(item["subtitle"])
        val thumb = lastThumbnail(item["thumbnailRenderer"])
        val nav = item["navigationEndpoint"].objOrNull()
        val watch = nav?.get("watchEndpoint").objOrNull()
        val videoId = watch?.get("videoId")?.strOrNull()
        val browse = nav?.get("browseEndpoint").objOrNull()
        val browseId = browse?.get("browseId")?.strOrNull()
        val pageType = browse?.get("browseEndpointContextSupportedConfigs")
            ?.objOrNull()?.get("browseEndpointContextMusicConfig")
            ?.objOrNull()?.get("pageType")?.strOrNull()

        val artistRuns = extractArtistRuns(item["subtitle"])
        val explicit = isExplicit(item)

        if (videoId != null) {
            return BrowseItem(
                kind = BrowseKind.SONG,
                id = videoId,
                title = title,
                subtitle = subtitle,
                thumbnail = thumb,
                artistRuns = artistRuns,
                explicit = explicit,
                isVideo = isVideoEndpoint(nav)
            )
        }

        if (browseId != null) {
            val kind = when {
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> BrowseKind.ARTIST
                pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> BrowseKind.ALBUM
                else -> BrowseKind.PLAYLIST
            }
            return BrowseItem(
                kind = kind,
                id = browseId,
                title = title,
                subtitle = subtitle,
                thumbnail = thumb,
                artistRuns = artistRuns,
                explicit = explicit
            )
        }
        return null
    }

    fun parseListItemAsBrowse(item: JsonObject): BrowseItem? {
        val nav = item["navigationEndpoint"].objOrNull()
        val browse = nav?.get("browseEndpoint").objOrNull()
        val browseId = browse?.get("browseId")?.strOrNull()
        val pageType = browse?.get("browseEndpointContextSupportedConfigs")
            ?.objOrNull()?.get("browseEndpointContextMusicConfig")
            ?.objOrNull()?.get("pageType")?.strOrNull()

        if (browseId != null && (nav?.get("watchEndpoint") == null)) {
            val flexColumns = item["flexColumns"].arrOrNull().orEmpty()
            val title = flexColumns.getOrNull(0)
                ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
                ?.objOrNull()?.get("text")?.let(::runsText) ?: return null
            val subtitle = flexColumns.getOrNull(1)
                ?.objOrNull()?.get("musicResponsiveListItemFlexColumnRenderer")
                ?.objOrNull()?.get("text")?.let(::runsText)
            val kind = when {
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> BrowseKind.ARTIST
                pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> BrowseKind.ALBUM
                else -> BrowseKind.PLAYLIST
            }
            return BrowseItem(
                kind = kind,
                id = browseId,
                title = title,
                subtitle = subtitle,
                thumbnail = lastThumbnail(item["thumbnail"]),
                explicit = isExplicit(item)
            )
        }

        val song = parseResponsiveListItem(item) ?: return null
        return BrowseItem(
            kind = BrowseKind.SONG,
            id = song.videoId,
            title = song.title,
            subtitle = song.artists,
            thumbnail = song.thumbnail,
            duration = song.duration,
            albumId = song.albumId,
            artistRuns = song.artistRuns,
            playCount = song.playCount,
            explicit = song.explicit,
            isVideo = song.isVideo
        )
    }

    fun parseHome(root: JsonElement): HomePage {
        val chips = findAll(root, "chipCloudChipRenderer").mapNotNull { chip ->
            val obj = chip.objOrNull() ?: return@mapNotNull null
            val title = runsText(obj["text"]) ?: return@mapNotNull null
            val params = obj["navigationEndpoint"]?.objOrNull()?.get("browseEndpoint")
                ?.objOrNull()?.get("params")?.strOrNull() ?: return@mapNotNull null
            HomeChip(title = title, params = params)
        }

        val sections = mutableListOf<HomeSection>()
        for (shelfEl in findAll(root, "musicCarouselShelfRenderer")) {
            val shelf = shelfEl.objOrNull() ?: continue
            val header = findAll(shelf, "musicCarouselShelfBasicHeaderRenderer").firstOrNull()?.objOrNull()
            val title = runsText(header?.get("title")) ?: continue
            val contents = shelf["contents"].arrOrNull().orEmpty()
            val items = contents.mapNotNull { itemWrap ->
                val obj = itemWrap.objOrNull() ?: return@mapNotNull null
                obj["musicTwoRowItemRenderer"]?.objOrNull()?.let(::parseTwoRowItem)
                    ?: obj["musicResponsiveListItemRenderer"]?.objOrNull()?.let(::parseListItemAsBrowse)
            }
            if (items.isNotEmpty()) {
                val more = header?.get("moreContentButton")?.let { findAll(it, "browseEndpoint").firstOrNull() }?.objOrNull()
                sections.add(
                    HomeSection(
                        title = title,
                        items = items,
                        moreBrowseId = more?.get("browseId")?.strOrNull(),
                        moreParams = more?.get("params")?.strOrNull()
                    )
                )
            }
        }
        val continuation = findAll(root, "nextContinuationData").firstOrNull()?.objOrNull()?.get("continuation")?.strOrNull()
        return HomePage(chips = chips, sections = sections, continuation = continuation)
    }

    fun parseMoods(root: JsonElement): List<MoodSection> {
        return findAll(root, "gridRenderer").mapNotNull { gridEl ->
            val grid = gridEl.objOrNull() ?: return@mapNotNull null
            val title = findAll(grid["header"], "gridHeaderRenderer").firstOrNull()
                ?.objOrNull()?.get("title")?.let(::runsText) ?: return@mapNotNull null
            val tiles = findAll(grid["items"], "musicNavigationButtonRenderer").mapNotNull { btnEl ->
                val btn = btnEl.objOrNull() ?: return@mapNotNull null
                val label = runsText(btn["buttonText"]) ?: return@mapNotNull null
                val params = findAll(btn["clickCommand"], "browseEndpoint").firstOrNull()
                    ?.objOrNull()?.get("params")?.strOrNull() ?: return@mapNotNull null
                val argb = btn["solid"]?.objOrNull()?.get("leftStripeColor")?.jsonPrimitive?.longOrNull
                val hex = if (argb != null) String.format("#%06x", argb and 0xFFFFFF) else "#EA580C"
                MoodTile(title = label, params = params, colorHex = hex)
            }
            if (tiles.isEmpty()) null else MoodSection(title = title, items = tiles)
        }
    }

    fun parseSearchAll(root: JsonElement): SearchResults {
        val top = mutableListOf<BrowseItem>()
        val songs = mutableListOf<BrowseItem>()
        val albums = mutableListOf<BrowseItem>()
        val artists = mutableListOf<BrowseItem>()
        val playlists = mutableListOf<BrowseItem>()

        for (card in findAll(root, "musicCardShelfRenderer")) {
            val cardObj = card.objOrNull() ?: continue
            val title = runsText(cardObj["title"]) ?: continue
            val subtitle = runsText(cardObj["subtitle"])
            val thumb = lastThumbnail(cardObj["thumbnail"])
            val onTap = cardObj["onTap"].objOrNull()
            val videoId = onTap?.get("watchEndpoint")?.objOrNull()?.get("videoId")?.strOrNull()
            val browseId = onTap?.get("browseEndpoint")?.objOrNull()?.get("browseId")?.strOrNull()
            when {
                videoId != null -> top.add(BrowseItem(BrowseKind.SONG, videoId, title, subtitle, thumb))
                browseId != null -> {
                    val kind = when {
                        browseId.startsWith("UC") -> BrowseKind.ARTIST
                        browseId.startsWith("MPRE") -> BrowseKind.ALBUM
                        else -> BrowseKind.PLAYLIST
                    }
                    top.add(BrowseItem(kind, browseId, title, subtitle, thumb))
                }
            }
        }

        for (shelfEl in findAll(root, "musicShelfRenderer")) {
            val shelf = shelfEl.objOrNull() ?: continue
            val shelfTitle = runsText(shelf["title"])?.lowercase().orEmpty()
            val contents = shelf["contents"].arrOrNull().orEmpty()
            val parsed = contents.mapNotNull {
                it.objOrNull()?.get("musicResponsiveListItemRenderer")?.objOrNull()?.let(::parseListItemAsBrowse)
            }
            when {
                "song" in shelfTitle -> songs.addAll(parsed)
                "album" in shelfTitle -> albums.addAll(parsed)
                "artist" in shelfTitle -> artists.addAll(parsed)
                "playlist" in shelfTitle -> playlists.addAll(parsed)
                else -> {
                    parsed.forEach { item ->
                        when (item.kind) {
                            BrowseKind.SONG -> songs.add(item)
                            BrowseKind.ALBUM -> albums.add(item)
                            BrowseKind.ARTIST -> artists.add(item)
                            BrowseKind.PLAYLIST -> playlists.add(item)
                        }
                    }
                }
            }
        }
        return SearchResults(top = top, songs = songs, albums = albums, artists = artists, playlists = playlists)
    }

    fun parseSearchSongs(root: JsonElement): List<SongItem> =
        findAll(root, "musicResponsiveListItemRenderer").mapNotNull {
            it.objOrNull()?.let(::parseResponsiveListItem)
        }

    fun parseSearchSuggestions(root: JsonElement): SearchSuggestions {
        val queries = findAll(root, "searchSuggestionRenderer").mapNotNull {
            runsText(it.objOrNull()?.get("suggestion"))
        }
        val items = findAll(root, "musicResponsiveListItemRenderer").mapNotNull {
            it.objOrNull()?.let(::parseListItemAsBrowse)
        }
        return SearchSuggestions(queries = queries, items = items)
    }

    fun parseNext(root: JsonElement): Pair<List<SongItem>, String?> {
        val rawRenderers = buildList {
            addAll(findAll(root, "playlistPanelVideoRenderer"))
            findAll(root, "playlistPanelVideoWrapperRenderer").forEach { wrapper ->
                val obj = wrapper.objOrNull() ?: return@forEach
                obj["primaryRenderer"]?.objOrNull()?.get("playlistPanelVideoRenderer")?.let { add(it) }
                (obj["counterpart"] as? JsonArray)?.forEach { cp ->
                    cp.objOrNull()?.get("counterpartRenderer")?.objOrNull()?.get("playlistPanelVideoRenderer")?.let { add(it) }
                }
            }
        }
        val seenIds = hashSetOf<String>()
        val songs = rawRenderers.mapNotNull { panelEl ->
            val p = panelEl.objOrNull() ?: return@mapNotNull null
            val videoId = p["videoId"].strOrNull() ?: return@mapNotNull null
            if (!seenIds.add(videoId)) return@mapNotNull null
            val title = runsText(p["title"]) ?: return@mapNotNull null
            val runs = p["longBylineText"]?.objOrNull()?.get("runs").arrOrNull()
                ?: p["shortBylineText"]?.objOrNull()?.get("runs").arrOrNull()
            val (parsedArtists, albumPacked, _) = splitMetaRuns(runs)
            val artistsText = if (parsedArtists.isNotEmpty()) {
                parsedArtists.joinToString(", ") { it.text }
            } else {
                runsText(p["shortBylineText"]) ?: "Unknown Artist"
            }
            val duration = runsText(p["lengthText"])
            val packed = albumPacked?.split("|", limit = 2)
            SongItem(
                videoId = videoId,
                title = title,
                artists = artistsText,
                artistId = parsedArtists.firstOrNull { it.id != null }?.id,
                artistRuns = parsedArtists,
                album = packed?.getOrNull(0),
                albumId = packed?.getOrNull(1)?.takeIf { it != "null" },
                duration = duration,
                thumbnail = lastThumbnail(p["thumbnail"]),
                isVideo = isVideoEndpoint(p["navigationEndpoint"])
            )
        }

        val lyricsBrowseId = findAll(root, "tabRenderer").firstNotNullOfOrNull { tabEl ->
            val tab = tabEl.objOrNull() ?: return@firstNotNullOfOrNull null
            val browse = tab["endpoint"]?.objOrNull()?.get("browseEndpoint")?.objOrNull()
            val browseId = browse?.get("browseId")?.strOrNull()
            val title = tab["title"]?.strOrNull()?.lowercase().orEmpty()
            if (browseId != null && ("lyric" in title || browseId.startsWith("MPLYt"))) browseId else null
        }
        return songs to lyricsBrowseId
    }

    fun parseAlbum(id: String, root: JsonElement): AlbumPage {
        val header = findAll(root, "musicResponsiveHeaderRenderer").firstOrNull()?.objOrNull()
            ?: findAll(root, "musicDetailHeaderRenderer").firstOrNull()?.objOrNull()
        val title = runsText(header?.get("title")) ?: "Album"
        val subtitle = runsText(header?.get("subtitle"))
        val secondSubtitle = runsText(header?.get("secondSubtitle"))
        val description = findAll(header?.get("description"), "description").firstOrNull()?.let(::runsText)
            ?: runsText(header?.get("description"))
        val thumb = lastThumbnail(header?.get("thumbnail"))
        val strapline = header?.get("straplineTextOne")
        val artistRuns = extractArtistRuns(strapline)
        val artist = runsText(strapline) ?: artistRuns.firstOrNull()?.text
        val artistId = artistRuns.firstOrNull { it.id != null }?.id
        val artistThumb = lastThumbnail(header?.get("straplineThumbnail"))
        val playlistId = findAll(header, "watchPlaylistEndpoint").firstOrNull()
            ?.objOrNull()?.get("playlistId")?.strOrNull()

        val tracks = findAll(root, "musicResponsiveListItemRenderer").mapNotNull {
            it.objOrNull()?.let(::parseResponsiveListItem)?.copy(
                album = title,
                albumId = id,
                thumbnail = lastThumbnail(it.objOrNull()?.get("thumbnail")) ?: thumb
            )
        }

        val sections = findAll(root, "musicCarouselShelfRenderer").mapNotNull { shelfEl ->
            val shelf = shelfEl.objOrNull() ?: return@mapNotNull null
            val sTitle = findAll(shelf, "musicCarouselShelfBasicHeaderRenderer").firstOrNull()
                ?.objOrNull()?.get("title")?.let(::runsText) ?: return@mapNotNull null
            val items = shelf["contents"].arrOrNull().orEmpty().mapNotNull {
                it.objOrNull()?.get("musicTwoRowItemRenderer")?.objOrNull()?.let(::parseTwoRowItem)
            }
            if (items.isEmpty()) null else ArtistCarousel(title = sTitle, items = items)
        }

        return AlbumPage(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            artistRuns = artistRuns,
            artistThumbnail = artistThumb,
            subtitle = subtitle,
            secondSubtitle = secondSubtitle,
            description = description,
            thumbnail = thumb,
            items = tracks,
            explicit = isExplicit(header),
            playlistId = playlistId,
            sections = sections
        )
    }

    fun parseArtist(channelId: String, root: JsonElement): ArtistPage {
        val immersive = findAll(root, "musicImmersiveHeaderRenderer").firstOrNull()?.objOrNull()
        val visual = findAll(root, "musicVisualHeaderRenderer").firstOrNull()?.objOrNull()
        val header = immersive ?: visual
        val name = runsText(header?.get("title")) ?: "Artist"
        val thumb = lastThumbnail(immersive?.get("thumbnail")) ?: lastThumbnail(visual?.get("foregroundThumbnail"))
        val avatar = lastThumbnail(visual?.get("foregroundThumbnail"))
        val description = runsText(header?.get("description"))
        val subscribers = findAll(header?.get("subscriptionButton"), "subscriberCountText").firstOrNull()?.let(::runsText)
        val monthlyListeners = runsText(header?.get("monthlyListenerCount"))
        val radioPlaylistId = findAll(header?.get("startRadioButton"), "watchPlaylistEndpoint").firstOrNull()
            ?.objOrNull()?.get("playlistId")?.strOrNull()

        val topSongsShelf = findAll(root, "musicShelfRenderer").firstOrNull()?.objOrNull()
        val topSongs = topSongsShelf?.get("contents").arrOrNull().orEmpty().mapNotNull {
            it.objOrNull()?.get("musicResponsiveListItemRenderer")?.objOrNull()?.let(::parseResponsiveListItem)
        }
        val topSongsId = topSongsShelf?.get("bottomEndpoint")?.objOrNull()?.get("browseEndpoint")?.objOrNull()?.get("browseId")?.strOrNull()

        val sections = findAll(root, "musicCarouselShelfRenderer").mapNotNull { shelfEl ->
            val shelf = shelfEl.objOrNull() ?: return@mapNotNull null
            val sTitle = findAll(shelf, "musicCarouselShelfBasicHeaderRenderer").firstOrNull()
                ?.objOrNull()?.get("title")?.let(::runsText) ?: return@mapNotNull null
            val items = shelf["contents"].arrOrNull().orEmpty().mapNotNull {
                it.objOrNull()?.get("musicTwoRowItemRenderer")?.objOrNull()?.let(::parseTwoRowItem)
            }
            if (items.isEmpty()) null else ArtistCarousel(title = sTitle, items = items)
        }

        return ArtistPage(
            channelId = channelId,
            name = name,
            thumbnail = thumb,
            avatar = avatar,
            description = description,
            subscribers = subscribers,
            monthlyListeners = monthlyListeners,
            radioPlaylistId = radioPlaylistId,
            topSongs = topSongs,
            topSongsId = topSongsId,
            sections = sections
        )
    }

    fun parsePlaylist(id: String, root: JsonElement): PlaylistPage {
        val header = findAll(root, "musicResponsiveHeaderRenderer").firstOrNull()?.objOrNull()
            ?: findAll(root, "musicDetailHeaderRenderer").firstOrNull()?.objOrNull()
        val title = runsText(header?.get("title")) ?: "Playlist"
        val subtitle = runsText(header?.get("subtitle"))
        val description = runsText(header?.get("description"))
        val thumb = lastThumbnail(header?.get("thumbnail"))
        val tracks = findAll(root, "musicResponsiveListItemRenderer").mapNotNull {
            it.objOrNull()?.let(::parseResponsiveListItem)
        }
        return PlaylistPage(
            id = id,
            title = title,
            subtitle = subtitle,
            description = description,
            thumbnail = thumb,
            items = tracks,
            owned = false,
            isLocal = false
        )
    }

    fun parseTimedLyrics(root: JsonElement): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (data in findAll(root, "timedLyricsData")) {
            val entries = data.arrOrNull() ?: continue
            for (entryEl in entries) {
                val entry = entryEl.objOrNull() ?: continue
                val text = entry["lyricLine"]?.strOrNull() ?: continue
                val ms = entry["cueRange"]?.objOrNull()?.get("startTimeMilliseconds")?.strOrNull()?.toLongOrNull() ?: continue
                val endMs = entry["cueRange"]?.objOrNull()?.get("endTimeMilliseconds")?.strOrNull()?.toLongOrNull()
                out.add(LyricLine(timeMs = ms, endTimeMs = endMs, text = text))
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    fun parsePlainLyrics(root: JsonElement): String? {
        val shelf = findAll(root, "musicDescriptionShelfRenderer").firstOrNull()?.objOrNull() ?: return null
        return runsText(shelf["description"])
    }

    fun parseCommentsToken(root: JsonElement): String? {
        for (tabEl in findAll(root, "tabRenderer")) {
            val tab = tabEl.objOrNull() ?: continue
            val title = tab["title"]?.strOrNull()?.lowercase().orEmpty()
            val token = findAll(tab, "continuationCommand").firstOrNull()?.objOrNull()?.get("token")?.strOrNull()
            if (token != null && ("comment" in title || token.startsWith("Eg0"))) return token
        }
        return findAll(root, "continuationCommand").firstOrNull()?.objOrNull()?.get("token")?.strOrNull()
    }

    fun parseCommentsPage(root: JsonElement): CommentsPage {
        val commentPayloads = mutableMapOf<String, JsonObject>()
        val toolbarPayloads = mutableMapOf<String, JsonObject>()
        for (mutationEl in findAll(root, "mutations")) {
            val arr = mutationEl.arrOrNull() ?: continue
            for (m in arr) {
                val obj = m.objOrNull() ?: continue
                val key = obj["entityKey"]?.strOrNull() ?: continue
                val payload = obj["payload"]?.objOrNull() ?: continue
                payload["commentEntityPayload"]?.objOrNull()?.let { commentPayloads[key] = it }
                payload["engagementToolbarStateEntityPayload"]?.objOrNull()?.let { toolbarPayloads[key] = it }
            }
        }

        val comments = findAll(root, "commentViewModel").mapNotNull { vmEl ->
            val vm = vmEl.objOrNull() ?: return@mapNotNull null
            val key = vm["commentKey"]?.strOrNull() ?: return@mapNotNull null
            val payload = commentPayloads[key] ?: return@mapNotNull null
            val props = payload["properties"]?.objOrNull() ?: return@mapNotNull null
            val authorObj = payload["author"]?.objOrNull()
            val toolbar = payload["toolbar"]?.objOrNull()
            val text = props["content"]?.objOrNull()?.get("content")?.strOrNull() ?: return@mapNotNull null
            val authorName = authorObj?.get("displayName")?.strOrNull() ?: "Listener"
            val avatar = upgradeThumbnailUrl(authorObj?.get("avatarThumbnailUrl")?.strOrNull(), targetPx = 160)
            val published = props["publishedTimeText"]?.strOrNull().orEmpty()
            val likes = toolbar?.get("likeCountNotliked")?.strOrNull()?.takeIf { it != "0" }
            val replies = toolbar?.get("replyCount")?.strOrNull()?.takeIf { it != "0" }
            val isPinned = vm["pinnedText"] != null
            val isHearted = toolbar?.get("heartActiveTooltip") != null
            CommentItem(
                id = key,
                author = authorName,
                authorAvatar = avatar,
                publishedTime = published,
                text = text,
                likeCount = likes,
                replyCount = replies,
                isPinned = isPinned,
                isHearted = isHearted
            )
        }

        val countText = findAll(root, "commentsHeaderRenderer").firstOrNull()
            ?.objOrNull()?.get("countText")?.let(::runsText)
        return CommentsPage(countText = countText, comments = comments, disabled = comments.isEmpty())
    }
}
