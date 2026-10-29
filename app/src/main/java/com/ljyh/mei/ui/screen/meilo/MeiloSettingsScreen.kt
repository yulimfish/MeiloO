package com.ljyh.mei.ui.screen.meilo

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ljyh.mei.data.locallibrary.LocalLibraryFolderRepository
import com.ljyh.mei.data.localsync.LocalMetadataSync
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.local.LocalLibraryScanner
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.ui.GlasmioCard
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Local-library settings. Folder values come from the shared SAF repository; all other values are
 * exposed through [MeiloSettingsStore] for the lead's synchronization-engine integration.
 */
@Composable
fun MeiloSettingsScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { MeiloSettingsStore(context) }
    val folderRepository = remember(context) { LocalLibraryFolderRepository(context) }
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val scanner = remember(context, database) { LocalLibraryScanner(context, database) }
    val metadataSync = remember(context, database) { LocalMetadataSync(context, database) }
    var cleanupJob by remember { mutableStateOf<Job?>(null) }
    val settings by store.settings.collectAsState(initial = MeiloSettings())
    val folders by folderRepository.folders.collectAsState(initial = emptyList())

    DisposableEffect(Unit) {
        onDispose { cleanupJob?.cancel() }
    }

    MeiloXPinnedListPage(
        title = "设置",
        subtitle = "本地曲库与播放",
        verticalArrangement = Arrangement.spacedBy(8.dp),
        onNavigateBack = onNavigateBack,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
    ) {
        item(key = "meilo-settings-folders-title") { MeiloSettingsSectionTitle("音乐文件夹") }
        if (folders.isEmpty()) {
            item(key = "meilo-settings-folders-empty") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = "尚未选择文件夹",
                        subtitle = "可在歌曲页通过系统文件选择器添加",
                        systemName = "folder",
                        showTopSeparator = false,
                    )
                }
            }
        } else {
            folders.forEachIndexed { index, folder ->
                item(key = "meilo-folder-${folder.treeUri}") {
                    GlasmioListRow(
                        title = folder.name.ifBlank { "音乐文件夹 ${index + 1}" },
                        subtitle = folder.treeUri,
                        systemName = "folder",
                        leading = null,
                        trailing = {
                            GlasmioIconButton(
                                onClick = {
                                    cleanupJob?.cancel()
                                    cleanupJob = scope.launch {
                                        folderRepository.removeFolder(Uri.parse(folder.treeUri))
                                        // Reconcile Room with the remaining SAF roots so removed songs do not linger.
                                        scanner.scan { }
                                    }
                                },
                            ) { SfIcon("minus.circle.fill", "移除此文件夹") }
                        },
                        showTopSeparator = false,
                    )
                }
            }
        }

        item(key = "meilo-settings-analysis-title") { MeiloSettingsSectionTitle("本地元数据") }
        item(key = "meilo-settings-auto-analysis") {
            GlasmioCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SfIcon("sparkles", null)
                    Text(
                        "自动分析与同步",
                        Modifier.weight(1f).padding(horizontal = 13.dp),
                    )
                    GlasmioSwitch(
                        checked = settings.autoAnalysisSyncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                store.setAutoAnalysisSyncEnabled(enabled)
                                if (enabled) metadataSync.syncLibrary()
                            }
                        },
                    )
                }
            }
        }
        item(key = "meilo-settings-cover-source") {
            MeiloChoiceCard(
                title = "全局封面来源",
                symbol = "photo",
                selected = settings.coverSource,
                values = MeiloCoverSource.entries,
                label = { it.label },
                onSelected = { source -> scope.launch { store.setCoverSource(source) } },
            )
        }
        item(key = "meilo-settings-lyrics-source") {
            MeiloChoiceCard(
                title = "全局歌词来源",
                symbol = "quote.bubble",
                selected = settings.lyricsSource,
                values = MeiloLyricsSource.entries,
                label = { it.label },
                onSelected = { source -> scope.launch { store.setLyricsSource(source) } },
            )
        }
        item(key = "meilo-settings-source-note") {
            Text(
                "“本地”使用内嵌标签与同目录文件；自动模式可按需使用 iTunes、MusicBrainz 或 LRCLIB。",
                style = MaterialTheme.typography.bodySmall,
                color = LocalGlassColors.current.secondaryContent,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        item(key = "meilo-settings-more-title") { MeiloSettingsSectionTitle("更多设置") }
        item(key = "meilo-settings-more") {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "外观设置",
                    systemName = "paintbrush",
                    showTopSeparator = false,
                    onClick = { Screen.AppearanceSettings.navigate(navController) },
                )
                GlasmioListRow(
                    title = "播放设置",
                    systemName = "play.circle",
                    onClick = { Screen.PlaySettings.navigate(navController) },
                )
                GlasmioListRow(
                    title = "歌词设置",
                    systemName = "quote.bubble",
                    onClick = { Screen.LyricsSettings.navigate(navController) },
                )
                GlasmioListRow(
                    title = "关于",
                    systemName = "info.circle",
                    onClick = { Screen.About.navigate(navController) },
                )
            }
        }
    }
}

@Composable
private fun MeiloSettingsSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
    )
}
