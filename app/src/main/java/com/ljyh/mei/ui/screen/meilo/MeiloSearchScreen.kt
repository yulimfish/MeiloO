package com.ljyh.mei.ui.screen.meilo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.local.LocalPlayerConnection
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar

/** Searches only [AppDatabase.songDao]'s local songs by title, artist, or album. */
@Composable
fun MeiloSearchScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val player = LocalPlayerConnection.current
    val songs by database.songDao().getLocalSongs().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf(TextFieldValue()) }
    var searchActive by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val results = remember(songs, query.text) { filterMeiloSongs(songs, query.text) }

    MeiloXPinnedListPage(
        title = "本地搜索",
        verticalArrangement = Arrangement.spacedBy(8.dp),
        onNavigateBack = onNavigateBack,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
    ) {
        item(key = "meilo-local-search-bar") {
            GlasmioSearchBar(
                query = query,
                onQueryChange = { query = it },
                active = searchActive,
                onActiveChange = { searchActive = it },
                onClose = {
                    query = TextFieldValue()
                    searchActive = false
                },
                placeholder = "搜索标题、歌手或专辑",
                closeContentDescription = "清空并关闭搜索",
                onSearch = { focusManager.clearFocus() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        if (query.text.isBlank()) {
            item(key = "meilo-search-prompt") {
                MeiloEmptyState(
                    title = "搜索本地曲库",
                    description = "输入歌曲标题、歌手或专辑；不会请求在线搜索。",
                    symbol = "magnifyingglass",
                )
            }
        } else if (results.isEmpty()) {
            item(key = "meilo-search-empty") {
                MeiloEmptyState(
                    title = "没有匹配的本地歌曲",
                    description = "请尝试其他标题、歌手或专辑关键词。",
                    symbol = "magnifyingglass",
                )
            }
        } else {
            itemsIndexed(results, key = { _, song -> song.id }) { index, song ->
                MeiloSongRow(
                    song = song,
                    showTopSeparator = index > 0,
                    onClick = {
                        playMeiloSongQueue(
                            player = player,
                            songs = results,
                            startIndex = index,
                            queueId = "meilo-local-search",
                            queueTitle = "本地搜索：${query.text.trim()}",
                        )
                    },
                )
            }
        }
    }
}
