package com.ljyh.mei.ui.screen.meilo

import com.ljyh.mei.data.model.room.Song
import java.util.Locale

/** Sorting is kept in this UI scope so the local vertical slice does not alter the shared DAO. */
enum class MeiloSongSort(val label: String) {
    TITLE("标题"),
    ARTIST("歌手"),
    ALBUM("专辑"),
    DURATION("时长"),
    ADDED_AT("加入时间"),
    CUSTOM("自定义"),
}

internal data class MeiloAlbumEntry(
    /** Existing LocalSongList album route/filter uses the original tag, including blanks. */
    val routeName: String,
    val name: String,
    val artists: String,
    val cover: String,
    val songCount: Int,
)

internal data class MeiloArtistEntry(
    /** Existing LocalSongList artist route/filter uses the original tag. */
    val routeName: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    val albumCount: Int,
)

internal fun Song.meiloArtistText(): String = artist.joinToString(" / ").ifBlank { "未知歌手" }

internal fun Song.meiloAlbumText(): String = album.ifBlank { "未知专辑" }

internal fun filterMeiloSongs(songs: List<Song>, query: String): List<Song> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return songs
    return songs.filter { song ->
        song.title.contains(normalized, ignoreCase = true) ||
            song.artist.any { it.contains(normalized, ignoreCase = true) } ||
            song.album.contains(normalized, ignoreCase = true)
    }
}

internal fun sortMeiloSongs(
    songs: List<Song>,
    sort: MeiloSongSort,
    descending: Boolean = false,
    customOrder: List<String> = emptyList(),
): List<Song> {
    if (sort == MeiloSongSort.CUSTOM) {
        val byId = songs.associateBy(Song::id)
        val included = HashSet<String>(songs.size)
        val ordered = customOrder.asSequence()
            .distinct()
            .mapNotNull(byId::get)
            .onEach { included += it.id }
            .toMutableList()
        // Newly scanned tracks are appended without discarding the user's saved order.
        songs.filterNot { it.id in included }.forEach(ordered::add)
        return ordered
    }

    val comparator: Comparator<Song> = when (sort) {
        MeiloSongSort.TITLE -> compareBy { song: Song -> song.title.meiloSortKey() }
            .thenBy { it.meiloArtistText().meiloSortKey() }
        MeiloSongSort.ARTIST -> compareBy { song: Song -> song.meiloArtistText().meiloSortKey() }
            .thenBy { it.title.meiloSortKey() }
        MeiloSongSort.ALBUM -> compareBy { song: Song -> song.meiloAlbumText().meiloSortKey() }
            .thenBy { it.title.meiloSortKey() }
        MeiloSongSort.DURATION -> compareBy(Song::duration)
            .thenBy { it.title.meiloSortKey() }
        MeiloSongSort.ADDED_AT -> compareBy(Song::addedAt)
            .thenBy { it.title.meiloSortKey() }
        MeiloSongSort.CUSTOM -> error("Handled above")
    }
    val sorted = songs.sortedWith(comparator.thenBy(Song::id))
    return if (descending) sorted.asReversed() else sorted
}

internal fun reconcileMeiloSongOrder(songs: List<Song>, savedOrder: List<String>): List<String> =
    sortMeiloSongs(songs, MeiloSongSort.CUSTOM, customOrder = savedOrder).map(Song::id)

internal fun moveMeiloSong(order: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    if (fromIndex !in order.indices || toIndex !in order.indices || fromIndex == toIndex) return order
    return order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}

internal fun buildMeiloAlbums(songs: List<Song>): List<MeiloAlbumEntry> = songs
    .groupBy(Song::album)
    .map { (routeName, albumSongs) ->
        MeiloAlbumEntry(
            routeName = routeName,
            name = routeName.ifBlank { "未知专辑" },
            artists = albumSongs.flatMap(Song::artist).distinct().joinToString(" / ").ifBlank { "未知歌手" },
            cover = albumSongs.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
            songCount = albumSongs.size,
        )
    }
    .sortedWith(compareBy { album: MeiloAlbumEntry -> album.name.meiloSortKey() }.thenBy { it.artists.meiloSortKey() })

internal fun buildMeiloArtists(songs: List<Song>): List<MeiloArtistEntry> {
    val names = songs.flatMap { song -> song.artist.ifEmpty { listOf("未知歌手") } }.distinct()
    return names.map { name ->
        val artistSongs = songs.filter { song ->
            if (name == "未知歌手") song.artist.isEmpty() else name in song.artist
        }
        MeiloArtistEntry(
            routeName = name,
            name = name.ifBlank { "未知歌手" },
            cover = artistSongs.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
            songCount = artistSongs.size,
            albumCount = artistSongs.map(Song::meiloAlbumText).distinct().size,
        )
    }.sortedBy { it.name.meiloSortKey() }
}

internal fun formatMeiloDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun String.meiloSortKey(): String = trim().lowercase(Locale.getDefault())
