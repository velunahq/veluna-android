package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrowseItem
import com.example.model.BrowseKind
import com.example.model.DailyListening
import com.example.model.SongItem
import com.example.model.StatsRange
import com.example.ui.components.MediaSquareCard
import com.example.ui.components.SectionHeading
import com.example.ui.components.TrackRow
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.rememberVelunaFlingBehavior
import com.example.ui.theme.rememberVelunaLazyListState
import com.example.ui.theme.velunaFilterChipBorder
import com.example.ui.theme.velunaFilterChipColors
import com.example.ui.viewmodel.StatsUiState

@Composable
fun StatsScreen(
    state: StatsUiState,
    nowPlayingId: String?,
    onSelectRange: (StatsRange) -> Unit,
    onOpenHistory: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onTrackMore: (SongItem) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = state.summary
    val listState = rememberVelunaLazyListState()
    val smoothFling = rememberVelunaFlingBehavior()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = smoothFling,
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .testTag("stats_screen")
        ) {
            item(key = "stats_header", contentType = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.stats_title),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.testTag("stats_open_history_btn")
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.home_history_action), maxLines = 1, softWrap = false)
                    }
                }

                val chipColors = velunaFilterChipColors()
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatsRange.entries.forEach { r ->
                        val selected = state.range == r
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectRange(r) },
                            colors = chipColors,
                            border = velunaFilterChipBorder(selected),
                            label = { Text(r.label, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("stats_range_${r.name.lowercase()}")
                        )
                    }
                }
            }

            if (state.isLoading) {
                item(key = "stats_loading", contentType = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item(key = "stats_kpis", contentType = "kpis") {
                    val deltaText = summary.prevSecs?.takeIf { it > 0 }?.let { prev ->
                        val pct = (((summary.totalSecs - prev).toDouble() / prev.toDouble()) * 100.0).toInt()
                        val sign = if (pct >= 0) "+" else ""
                        stringResource(R.string.stats_vs_previous, sign, pct)
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            StatKpiCard(
                                label = stringResource(R.string.stats_kpi_time),
                                value = formatHoursMinutes(summary.totalSecs),
                                subtitle = deltaText ?: stringResource(R.string.stats_plays_count, summary.totalPlays),
                                modifier = Modifier.weight(1f)
                            )
                            StatKpiCard(
                                label = stringResource(R.string.stats_kpi_streak),
                                value = stringResource(R.string.stats_streak_days, summary.currentStreak),
                                subtitle = stringResource(R.string.stats_streak_best, summary.bestStreak),
                                icon = Icons.Rounded.LocalFireDepartment,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            StatKpiCard(
                                label = stringResource(R.string.stats_kpi_unique_songs),
                                value = summary.uniqueSongs.toString(),
                                subtitle = stringResource(R.string.stats_total_plays_count, summary.totalPlays),
                                modifier = Modifier.weight(1f)
                            )
                            StatKpiCard(
                                label = stringResource(R.string.stats_kpi_artists),
                                value = summary.uniqueArtists.toString(),
                                subtitle = state.range.label,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (summary.dailySeries.isNotEmpty()) {
                    item(key = "stats_daily_chart", contentType = "daily_chart") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.stats_daily_activity),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                DailyBarChart(series = summary.dailySeries)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        summary.dailySeries.first().label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        summary.dailySeries.last().label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "stats_heatmap", contentType = "heatmap") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.stats_listening_clock),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            WeekdayHourHeatmap(cells = summary.hourlyHeatmap)
                        }
                    }
                }

                if (summary.topSongs.isNotEmpty()) {
                    item(key = "stats_top_songs_hdr", contentType = "section_heading") {
                        SectionHeading(title = stringResource(R.string.stats_top_songs))
                    }
                    itemsIndexed(
                        items = summary.topSongs,
                        key = { idx, s -> "top_s_${idx}_${s.song.videoId}" },
                        contentType = { _, _ -> "track_row" }
                    ) { idx, stat ->
                        val displaySong = remember(stat) { stat.song.copy(duration = "${stat.plays}x") }
                        TrackRow(
                            song = displaySong,
                            isPlaying = nowPlayingId == stat.song.videoId,
                            trackNumber = idx + 1,
                            onClick = { onPlaySong(stat.song) },
                            onMoreClick = { onTrackMore(stat.song) }
                        )
                    }
                }

                if (summary.topArtists.isNotEmpty()) {
                    item(key = "stats_top_artists", contentType = "carousel") {
                        Column {
                            SectionHeading(title = stringResource(R.string.stats_top_artists))
                            LazyRow(
                                flingBehavior = smoothFling,
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                items(
                                    items = summary.topArtists,
                                    key = { it.name },
                                    contentType = { "media_card" }
                                ) { ar ->
                                    MediaSquareCard(
                                        item = BrowseItem(
                                            kind = BrowseKind.ARTIST,
                                            id = ar.id ?: ar.name,
                                            title = ar.name,
                                            subtitle = "${ar.plays} • ${formatHoursMinutes(ar.secs)}",
                                            thumbnail = ar.thumbnail
                                        ),
                                        onClick = { ar.id?.let(onOpenArtist) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (summary.topAlbums.isNotEmpty()) {
                    item(key = "stats_top_albums", contentType = "carousel") {
                        Column {
                            SectionHeading(title = stringResource(R.string.stats_top_albums))
                            LazyRow(
                                flingBehavior = smoothFling,
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                items(
                                    items = summary.topAlbums,
                                    key = { it.id },
                                    contentType = { "media_card" }
                                ) { al ->
                                    MediaSquareCard(
                                        item = BrowseItem(
                                            kind = BrowseKind.ALBUM,
                                            id = al.id,
                                            title = al.title,
                                            subtitle = "${al.artist} • ${al.plays}x",
                                            thumbnail = al.thumbnail
                                        ),
                                        onClick = { onOpenAlbum(al.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatKpiCard(
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (icon != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DailyBarChart(series: List<DailyListening>) {
    val primary = MaterialTheme.colorScheme.primary
    val trackBg = MaterialTheme.colorScheme.surfaceVariant
    val maxSecs = remember(series) { (series.maxOfOrNull { it.secs } ?: 1L).coerceAtLeast(1L) }

    var revealed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val growth by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = VelunaMotion.expressiveSpatialSpring(),
        label = "bar_growth"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
    ) {
        val count = series.size.coerceAtLeast(1)
        val gap = (size.width * 0.22f) / count
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(3f)

        series.forEachIndexed { idx, point ->
            val x = idx * (barWidth + gap)
            drawRoundRect(
                color = trackBg,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(4f, 4f)
            )
            if (point.secs > 0) {
                val targetH = (point.secs.toFloat() / maxSecs.toFloat()) * size.height
                val h = (targetH * growth).coerceAtLeast(4f)
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}

@Composable
private fun WeekdayHourHeatmap(cells: List<Long>) {
    val primary = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val cellRatios = remember(cells) {
        val maxVal = (cells.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        FloatArray(168) { idx ->
            val value = cells.getOrElse(idx) { 0L }
            if (value <= 0L) 0f else (value.toFloat() / maxVal).coerceIn(0.18f, 1f)
        }
    }
    val weekdays = remember { listOf("S", "M", "T", "W", "T", "F", "S") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.height(98.dp)
        ) {
            weekdays.forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(98.dp)
                .graphicsLayer { }
        ) {
            val cols = 24
            val rows = 7
            val gap = 4f
            val cellW = (size.width - gap * (cols - 1)) / cols
            val cellH = (size.height - gap * (rows - 1)) / rows

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val ratio = cellRatios[r * 24 + c]
                    val color = if (ratio == 0f) emptyColor else primary.copy(alpha = ratio)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(c * (cellW + gap), r * (cellH + gap)),
                        size = Size(cellW, cellH),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }
            }
        }
    }
}

private fun formatHoursMinutes(secs: Long): String {
    if (secs < 60L) return "${secs}s"
    val mins = secs / 60L
    val h = mins / 60L
    val m = mins % 60L
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
