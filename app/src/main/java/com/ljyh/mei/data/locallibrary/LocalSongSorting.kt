package com.ljyh.mei.data.locallibrary

import com.ljyh.mei.data.model.room.Song
import java.net.URLDecoder

enum class LocalSongSortKey {
    TITLE,
    ARTIST,
    ALBUM,
    DURATION,
    ADDED_AT,
    FILE_NAME,
    CUSTOM,
}

/**
 * Sorts without touching Room. CUSTOM uses customOrder as a list of Song IDs; IDs absent from that
 * list keep their original relative order after the explicitly ordered songs. Direction is ignored
 * for CUSTOM because reversing a manually arranged list is not usually the requested operation.
 */
fun sortLocalSongs(
    songs: List<Song>,
    key: LocalSongSortKey,
    ascending: Boolean = true,
    customOrder: List<String> = emptyList(),
): List<Song> {
    if (key == LocalSongSortKey.CUSTOM) {
        val ranks = buildMap {
            customOrder.forEachIndexed { index, id -> putIfAbsent(id, index) }
        }
        return songs.withIndex().sortedWith(
            compareBy(
                { indexed -> ranks[indexed.value.id] ?: Int.MAX_VALUE },
                { indexed -> indexed.index },
            ),
        ).map { it.value }
    }

    val comparator: Comparator<Song> = when (key) {
        LocalSongSortKey.TITLE -> textComparator(Song::title)
        LocalSongSortKey.ARTIST -> textComparator { it.artist.joinToString(" / ") }
        LocalSongSortKey.ALBUM -> textComparator(Song::album)
        LocalSongSortKey.DURATION -> compareBy(Song::duration)
        LocalSongSortKey.ADDED_AT -> compareBy(Song::addedAt)
        LocalSongSortKey.FILE_NAME -> textComparator {
            localAudioFileName(it.path).ifBlank { it.title }
        }
        LocalSongSortKey.CUSTOM -> error("Handled above")
    }
    return songs.sortedWith((if (ascending) comparator else comparator.reversed()).thenBy(Song::id))
}

fun localAudioFileName(path: String?): String {
    if (path.isNullOrBlank()) return ""
    val raw = path.substringBefore('?').substringBefore('#').substringAfterLast('/')
    // URLDecoder implements form decoding, so protect literal '+' which is legal in a URI path.
    val decoded = runCatching {
        URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(raw)
    return decoded.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
}

private fun textComparator(selector: (Song) -> String): Comparator<Song> =
    compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER, selector).thenBy(selector)
