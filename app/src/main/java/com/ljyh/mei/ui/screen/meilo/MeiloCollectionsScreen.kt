package com.ljyh.mei.ui.screen.meilo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ljyh.mei.di.AppDatabase
import java.net.URLEncoder
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import com.ljyh.mei.ui.navigation.MeiloXPinnedListPage
import com.ljyh.mei.ui.screen.Screen
import io.github.neoruaa.glasmio.ui.GlasmioListRow

/** Local album categories; selecting one uses the existing local-only song-list destination. */
@Composable
fun MeiloAlbumsScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val navController = LocalNavController.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val songs by database.songDao().getLocalSongs().collectAsState(initial = emptyList())
    val albums = remember(songs) { buildMeiloAlbums(songs) }

    MeiloXPinnedListPage(
        title = "专辑",
        subtitle = "${albums.size} 张本地专辑",
        verticalArrangement = Arrangement.spacedBy(8.dp),
        onNavigateBack = onNavigateBack,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
    ) {
        items(albums, key = { it.routeName }) { album ->
            GlasmioListRow(
                title = album.name,
                subtitle = "${album.artists} · ${album.songCount} 首",
                leading = { MeiloArtwork(album.cover, size = 52.dp, symbol = "opticaldisc") },
                onClick = {
                    Screen.LocalSongList.navigate(navController) {
                        addPath("album")
                        addPath(URLEncoder.encode(album.routeName, "UTF-8"))
                    }
                },
            )
        }
        if (albums.isEmpty()) {
            item(key = "meilo-albums-empty") {
                MeiloEmptyState(
                    title = "没有本地专辑",
                    description = "在歌曲页添加音乐文件夹后，专辑会自动归类。",
                    symbol = "opticaldisc",
                )
            }
        }
    }
}

/** Local artist categories; no online artist profile or catalogue is opened. */
@Composable
fun MeiloArtistsScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val navController = LocalNavController.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val songs by database.songDao().getLocalSongs().collectAsState(initial = emptyList())
    val artists = remember(songs) { buildMeiloArtists(songs) }

    MeiloXPinnedListPage(
        title = "艺术家",
        subtitle = "${artists.size} 位本地艺术家",
        verticalArrangement = Arrangement.spacedBy(8.dp),
        onNavigateBack = onNavigateBack,
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
    ) {
        items(artists, key = { it.routeName }) { artist ->
            GlasmioListRow(
                title = artist.name,
                subtitle = "${artist.albumCount} 张专辑 · ${artist.songCount} 首",
                leading = { MeiloArtwork(artist.cover, size = 52.dp, symbol = "person") },
                onClick = {
                    Screen.LocalSongList.navigate(navController) {
                        addPath("artist")
                        addPath(URLEncoder.encode(artist.routeName, "UTF-8"))
                    }
                },
            )
        }
        if (artists.isEmpty()) {
            item(key = "meilo-artists-empty") {
                MeiloEmptyState(
                    title = "没有本地艺术家",
                    description = "在歌曲页添加音乐文件夹后，艺术家会自动归类。",
                    symbol = "person",
                )
            }
        }
    }
}
