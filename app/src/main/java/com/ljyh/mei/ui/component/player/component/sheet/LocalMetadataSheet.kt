package com.ljyh.mei.ui.component.player.component.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ljyh.mei.data.localmetadata.MetadataCandidate
import com.ljyh.mei.data.localmetadata.MetadataMatchTier
import com.ljyh.mei.data.localsync.TrackSourceOverride
import com.ljyh.mei.data.localsync.TrackSourceSelection
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.ui.component.player.PlayerViewModel
import com.ljyh.mei.ui.component.sheet.rememberMeiloXSheetDismissal
import com.ljyh.mei.ui.screen.meilo.MeiloChoiceCard
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar
import io.github.neoruaa.glasmio.ui.LocalGlasmioSheetContentPadding

/** Per-track source overrides and manual candidate selection, hosted by the original player sheet. */
@Composable
fun LocalMetadataSheet(
    viewModel: PlayerViewModel,
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
) {
    val songId = mediaMetadata.id.toString()
    val candidates by viewModel.localMetadataCandidates.collectAsState()
    val busy by viewModel.localMetadataBusy.collectAsState()
    val message by viewModel.localMetadataMessage.collectAsState()
    val offsetMs by viewModel.lyricOffsetMs.collectAsState()
    val overrides by viewModel.trackSourceOverrides(songId).collectAsState(initial = TrackSourceOverride())
    var searchQuery by remember(mediaMetadata.id) {
        mutableStateOf(TextFieldValue(
            listOf(mediaMetadata.title, mediaMetadata.artists.joinToString(" ") { it.name })
                .filter(String::isNotBlank).joinToString(" "),
        ))
    }
    var searchActive by remember { mutableStateOf(false) }
    val dismissal = rememberMeiloXSheetDismissal(onDismiss)

    GlasmioBottomSheet(
        onDismissRequest = dismissal::onDismissed,
        sheetState = dismissal.state,
        title = "歌曲信息与歌词来源",
        fitContent = false,
    ) {
        val sheetPadding = LocalGlasmioSheetContentPadding.current
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = sheetPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = sheetPadding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "${mediaMetadata.title} · ${mediaMetadata.artists.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                MeiloChoiceCard(
                    title = "封面 / 信息来源",
                    symbol = "photo",
                    selected = overrides.cover,
                    values = listOf(
                        TrackSourceSelection.INHERIT, TrackSourceSelection.AUTO,
                        TrackSourceSelection.LOCAL, TrackSourceSelection.ITUNES,
                        TrackSourceSelection.MUSICBRAINZ,
                    ),
                    label = { it.label() },
                    onSelected = { source ->
                        viewModel.saveTrackSourceOverrides(songId, overrides.copy(cover = source))
                    },
                )
            }
            item {
                MeiloChoiceCard(
                    title = "歌词来源",
                    symbol = "quote.bubble",
                    selected = overrides.lyrics,
                    values = listOf(
                        TrackSourceSelection.INHERIT, TrackSourceSelection.AUTO,
                        TrackSourceSelection.LOCAL, TrackSourceSelection.LRCLIB,
                    ),
                    label = { it.label() },
                    onSelected = { source ->
                        viewModel.saveTrackSourceOverrides(songId, overrides.copy(lyrics = source))
                    },
                )
            }
            item {
                GlasmioSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    active = searchActive,
                    onActiveChange = { searchActive = it },
                    onClose = { searchActive = false },
                    onSearch = { viewModel.searchLocalMetadata(mediaMetadata, searchQuery.text) },
                    placeholder = "输入歌曲名和歌手，先精确后模糊",
                    closeContentDescription = "收起搜索",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = "提前 0.5 秒",
                        systemName = "minus",
                        showTopSeparator = false,
                        onClick = { viewModel.adjustLyricOffset(mediaMetadata, -500) },
                    )
                    GlasmioListRow(
                        title = "歌词偏移 ${if (offsetMs >= 0) "+" else ""}${offsetMs} ms",
                        subtitle = "正值提前显示，负值延后显示",
                        systemName = "timer",
                        onClick = { viewModel.resetLyricOffset(mediaMetadata) },
                    )
                    GlasmioListRow(
                        title = "延后 0.5 秒",
                        systemName = "plus",
                        onClick = { viewModel.adjustLyricOffset(mediaMetadata, 500) },
                    )
                }
            }
            item {
                GlasmioButton(
                    onClick = { viewModel.searchLocalMetadata(mediaMetadata, searchQuery.text) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "正在分析匹配…" else "按当前来源重新匹配") }
            }
            message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (busy) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(candidates, key = { "${it.source}:${it.sourceId}" }) { candidate ->
                CandidateCard(candidate = candidate, enabled = !busy) {
                    viewModel.applyLocalMetadata(mediaMetadata, candidate)
                }
            }
            if (!busy && candidates.isEmpty()) {
                item {
                    Text(
                        "选择“自动”会综合多个来源；选择“内嵌与本地”只使用文件已有信息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: MetadataCandidate, enabled: Boolean, onClick: () -> Unit) {
    GlasmioCard(Modifier.fillMaxWidth(), onClick = if (enabled) onClick else null) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(candidate.title, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(
                    candidate.artists.joinToString(" / ").takeIf(String::isNotBlank),
                    candidate.album,
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${if (candidate.matchTier == MetadataMatchTier.EXACT) "精确" else "模糊"} · " +
                    "${candidate.source} · 匹配 ${(candidate.score * 100).toInt()}%" +
                    (candidate.durationMs?.let { " · ${it / 1000}s" } ?: "") +
                    if (candidate.lyrics?.synced?.isNotBlank() == true) " · 同步歌词"
                    else if (candidate.lyrics?.isEmpty == false) " · 纯文本" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun TrackSourceSelection.label(): String = when (this) {
    TrackSourceSelection.INHERIT -> "跟随全局"
    TrackSourceSelection.AUTO -> "自动最佳匹配"
    TrackSourceSelection.LOCAL -> "内嵌与本地"
    TrackSourceSelection.ITUNES -> "iTunes"
    TrackSourceSelection.MUSICBRAINZ -> "MusicBrainz / CAA"
    TrackSourceSelection.LRCLIB -> "LRCLIB"
}
