package com.ljyh.mei.ui.screen.meilo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.toMediaItem
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.queue.ListQueue
import com.ljyh.mei.ui.screen.local.LocalScanState
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioListRow

@Composable
internal fun MeiloSongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTopSeparator: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(modifier.testTag("meilo-song-${song.id}")) {
        GlasmioListRow(
            title = song.title.ifBlank { "未命名歌曲" },
            subtitle = listOf(song.meiloArtistText(), song.meiloAlbumText()).distinct().joinToString(" · "),
            detail = formatMeiloDuration(song.duration),
            showTopSeparator = showTopSeparator,
            leading = { MeiloArtwork(song.cover, size = 46.dp, symbol = "music.note") },
            trailing = trailing,
            onClick = onClick,
        )
    }
}

@Composable
internal fun MeiloArtwork(
    cover: String,
    size: androidx.compose.ui.unit.Dp,
    symbol: String,
) {
    val shape = ContinuousRoundedRectangle(10.dp)
    if (cover.isNotBlank()) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            SfIcon(symbol, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MeiloEmptyState(
    title: String,
    description: String,
    symbol: String = "music.note",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SfIcon(symbol, null, size = 44.dp, tint = LocalGlassColors.current.secondaryContent)
        Text(title, style = MaterialTheme.typography.titleMedium, color = LocalGlassColors.current.content)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalGlassColors.current.secondaryContent,
        )
        if (actionLabel != null && onAction != null) {
            GlasmioButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun MeiloScanCard(
    state: LocalScanState,
    modifier: Modifier = Modifier,
) {
    if (!state.isScanning && state.error == null && state.message == null) return
    GlasmioCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (state.isScanning) "正在扫描所选文件夹" else "本地曲库扫描",
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.isScanning) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "已检查 ${state.scanned}/${state.total} · 新增 ${state.added} · 更新 ${state.updated}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalGlassColors.current.secondaryContent,
                )
            }
            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = LocalGlassColors.current.secondaryContent)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun <T> MeiloChoiceCard(
    title: String,
    symbol: String,
    selected: T,
    values: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    GlasmioCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SfIcon(symbol, null)
            Text(title, Modifier.weight(1f).padding(horizontal = 13.dp, vertical = 14.dp))
            io.github.neoruaa.glasmio.ui.GlasmioPopUpButton(
                selected = selected,
                items = values,
                onSelected = onSelected,
                label = label,
            )
        }
    }
}

/** Builds a fully local queue from the exact visible order, including filtered search results. */
internal fun playMeiloSongQueue(
    player: PlayerConnection?,
    songs: List<Song>,
    startIndex: Int,
    queueId: String,
    queueTitle: String,
) {
    if (player == null || songs.isEmpty() || startIndex !in songs.indices) return
    val metadata = songs.map(Song::toMediaMetadata)
    player.playQueue(
        ListQueue(
            id = queueId,
            title = queueTitle,
            items = metadata.map { it.id.toString() to it.toMediaItem() },
            startIndex = startIndex,
        ),
    )
}
