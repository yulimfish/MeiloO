package com.ljyh.mei.ui.screen.meilo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ljyh.mei.data.locallibrary.LocalLibraryFolderRepository
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.data.localsync.LocalMetadataSync
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.navigation.meiloxHeaderAction
import com.ljyh.mei.ui.screen.Screen
import com.ljyh.mei.ui.screen.local.LocalLibraryScanner
import com.ljyh.mei.ui.screen.local.LocalScanState
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Local songs tab. [onNavigateBack] is only needed when hosted as a pushed page.
 * The search action opens the local-only search slice without touching the online search route.
 */
@Composable
fun MeiloSongsScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val database = remember(appContext) { AppDatabase.getDatabase(appContext) }
    val settingsStore = remember(appContext) { MeiloSettingsStore(appContext) }
    val folderRepository = remember(appContext) { LocalLibraryFolderRepository(appContext) }
    val scanner = remember(appContext, database) { LocalLibraryScanner(appContext, database) }
    val metadataSync = remember(appContext, database) { LocalMetadataSync(appContext, database) }
    val syncState by metadataSync.state.collectAsState()
    val player = LocalPlayerConnection.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val songs by database.songDao().getLocalSongs().collectAsState(initial = emptyList())
    val settings by settingsStore.settings.collectAsState(initial = MeiloSettings())
    val folders by folderRepository.folders.collectAsState(initial = null)
    var initialFolders by remember { mutableStateOf<List<com.ljyh.mei.data.locallibrary.LocalLibraryFolder>?>(null) }
    LaunchedEffect(folders) {
        if (initialFolders == null) folders?.let { initialFolders = it }
    }
    var scanState by remember { mutableStateOf(LocalScanState()) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var customOrderDraft by remember { mutableStateOf<List<String>?>(null) }

    fun startScan() {
        if (scanJob?.isActive == true || initialFolders == null) return
        folderError = null
        scanState = LocalScanState(isScanning = true)
        scanJob = scope.launch {
            try {
                scanner.scan { progress -> scanState = progress }
                metadataSync.syncLibrary()
            } catch (cancelled: CancellationException) {
                scanState = scanState.copy(
                    isScanning = false,
                    error = null,
                    message = "扫描已停止，已发现的歌曲已保留",
                )
                throw cancelled
            } catch (error: Exception) {
                scanState = scanState.copy(
                    isScanning = false,
                    error = error.message ?: "扫描失败，请重试",
                )
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    folderError = null
                    folderRepository.addFolder(uri)
                    startScan()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    folderError = error.message ?: "无法添加此文件夹，请重新选择"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanJob?.cancel() }
    }
    LaunchedEffect(settings.songSort) {
        customOrderDraft = null
    }

    val customOrder = customOrderDraft ?: settings.customSongOrder
    val orderedSongs = remember(songs, settings.songSort, settings.songSortDescending, customOrder) {
        sortMeiloSongs(songs, settings.songSort, settings.songSortDescending, customOrder)
    }
    val currentOrderedSongs by rememberUpdatedState(orderedSongs)
    val listState = rememberLazyListState()

    fun moveCustomSong(fromIndex: Int, toIndex: Int) {
        val updated = moveMeiloSong(currentOrderedSongs.map { it.id }, fromIndex, toIndex)
        if (updated == currentOrderedSongs.map { it.id }) return
        customOrderDraft = updated
        scope.launch { settingsStore.saveCustomSongOrder(updated) }
    }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (settings.songSort == MeiloSongSort.CUSTOM) {
            val ids = currentOrderedSongs.map { it.id }
            val fromIndex = ids.indexOf(from.key)
            val toIndex = ids.indexOf(to.key)
            if (fromIndex >= 0 && toIndex >= 0) {
                moveCustomSong(fromIndex, toIndex)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    }

    MeiloXPinnedListPage(
        title = "歌曲",
        subtitle = "${songs.size} 首本地歌曲",
        listState = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        onNavigateBack = onNavigateBack,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        actions = {
            listOf(
                meiloxHeaderAction(
                    key = "meilo-local-search",
                    contentDescription = "搜索本地歌曲",
                    onClick = { navController.navigate(Screen.Search.route) },
                ) { SfIcon(SfSymbol.Search, null) },
                meiloxHeaderAction(
                    key = "meilo-sync-metadata",
                    contentDescription = "同步歌曲信息",
                    onClick = { scope.launch { metadataSync.syncLibrary(force = true) } },
                    enabled = songs.isNotEmpty() && !syncState.isRunning,
                ) { SfIcon("arrow.clockwise", null) },
            )
        },
    ) {
        item(key = "meilo-sort") {
            MeiloChoiceCard(
                title = "排序方式",
                symbol = "arrow.up.arrow.down",
                selected = settings.songSort,
                values = MeiloSongSort.entries,
                label = { it.label },
                onSelected = { sort -> scope.launch { settingsStore.setSongSort(sort) } },
            )
        }
        if (settings.songSort != MeiloSongSort.CUSTOM) {
            item(key = "meilo-sort-direction") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = "排序方向",
                        systemName = if (settings.songSortDescending) "arrow.down" else "arrow.up",
                        detail = if (settings.songSortDescending) "反向" else "正向",
                        showTopSeparator = false,
                        onClick = {
                            scope.launch {
                                settingsStore.setSongSortDescending(!settings.songSortDescending)
                            }
                        },
                    )
                }
            }
        } else {
            item(key = "meilo-custom-sort-hint") {
                Text(
                    "长按歌曲并拖拽来排列顺序；新扫描的歌曲会追加到末尾。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }

        item(key = "meilo-folder-actions") {
            GlasmioGroupedList {
                GlasmioListRow(
                    title = "添加音乐文件夹",
                    subtitle = "通过系统文件选择器，仅扫描你选择的目录",
                    systemName = "folder",
                    showTopSeparator = false,
                    onClick = if (scanState.isScanning) null else ({ folderPicker.launch(null) }),
                )
                if (folders?.isNotEmpty() == true) {
                    GlasmioListRow(
                        title = "重新扫描所选文件夹",
                        subtitle = "${folders?.size ?: 0} 个文件夹",
                        systemName = "arrow.clockwise",
                        onClick = if (scanState.isScanning) null else (::startScan),
                    )
                }
                if (scanState.isScanning) {
                    GlasmioListRow(
                        title = "取消扫描",
                        systemName = "xmark.circle",
                        onClick = {
                            scanJob?.cancel()
                            scanState = scanState.copy(
                                isScanning = false,
                                error = null,
                                message = "正在停止扫描，已发现的歌曲已保留",
                            )
                        },
                    )
                }
            }
        }
        item(key = "meilo-scan-progress") {
            MeiloScanCard(
                if (folderError == null) scanState else scanState.copy(error = folderError),
                modifier = Modifier.testTag("meilo-scan-status"),
            )
        }

        if (syncState.isRunning || syncState.message != null) {
            item(key = "meilo-metadata-sync") {
                MeiloScanCard(
                    LocalScanState(
                        isScanning = syncState.isRunning,
                        scanned = syncState.current,
                        total = syncState.total,
                        message = syncState.message ?: "正在同步歌曲信息 ${syncState.current}/${syncState.total}",
                    ),
                )
            }
        }

        if (orderedSongs.isNotEmpty()) {
            item(key = "meilo-play-all") {
                GlasmioGroupedList {
                    GlasmioListRow(
                        title = "播放全部",
                        detail = "${orderedSongs.size} 首",
                        systemName = "play.fill",
                        showTopSeparator = false,
                        onClick = if (player == null) null else ({
                            playMeiloSongQueue(player, orderedSongs, 0, "meilo-local-songs", "本地歌曲")
                        }),
                    )
                }
            }
        }

        itemsIndexed(orderedSongs, key = { _, song -> song.id }) { index, song ->
            val row = @Composable {
                MeiloSongRow(
                    song = song,
                    showTopSeparator = index > 0,
                    modifier = Modifier.semantics {
                        if (settings.songSort == MeiloSongSort.CUSTOM) {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("上移") {
                                    moveCustomSong(index, index - 1)
                                    true
                                })
                                if (index < orderedSongs.lastIndex) add(CustomAccessibilityAction("下移") {
                                    moveCustomSong(index, index + 1)
                                    true
                                })
                            }
                        }
                    },
                    trailing = if (settings.songSort == MeiloSongSort.CUSTOM) {
                        { SfIcon("line.3.horizontal", "拖拽排序") }
                    } else null,
                    onClick = {
                        playMeiloSongQueue(player, orderedSongs, index, "meilo-local-songs", "本地歌曲")
                    },
                )
            }
            if (settings.songSort == MeiloSongSort.CUSTOM) {
                ReorderableItem(reorderState, key = song.id) {
                    androidx.compose.foundation.layout.Box(Modifier.longPressDraggableHandle()) { row() }
                }
            } else {
                row()
            }
        }

        if (songs.isEmpty() && !scanState.isScanning) {
            item(key = "meilo-songs-empty") {
                MeiloEmptyState(
                    title = "还没有本地歌曲",
                    description = "添加一个或多个音乐文件夹，扫描结果会显示在这里。",
                    actionLabel = "添加文件夹",
                    onAction = { folderPicker.launch(null) },
                )
            }
        }
    }
}
