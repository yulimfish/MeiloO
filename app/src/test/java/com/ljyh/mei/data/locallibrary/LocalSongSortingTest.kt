package com.ljyh.mei.data.locallibrary

import com.ljyh.mei.data.model.room.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSongSortingTest {
    @Test
    fun textSortingIsCaseInsensitiveAndHasStableTieBreakers() {
        val songs = listOf(
            song("b", title = "banana", artist = "Zoe", album = "Second"),
            song("a", title = "Apple", artist = "amy", album = "first"),
            song("c", title = "apple", artist = "Bob", album = "Third"),
        )
        assertEquals(listOf("a", "c", "b"), ids(sortLocalSongs(songs, LocalSongSortKey.TITLE)))
        assertEquals(listOf("a", "c", "b"), ids(sortLocalSongs(songs, LocalSongSortKey.ARTIST)))
        assertEquals(listOf("a", "b", "c"), ids(sortLocalSongs(songs, LocalSongSortKey.ALBUM)))
        assertEquals(listOf("b", "c", "a"), ids(sortLocalSongs(songs, LocalSongSortKey.TITLE, ascending = false)))
    }

    @Test
    fun numericSortingSupportsDurationAndAddedTimeInBothDirections() {
        val songs = listOf(
            song("a", duration = 300, addedAt = 10),
            song("b", duration = 100, addedAt = 30),
            song("c", duration = 200, addedAt = 20),
        )
        assertEquals(listOf("b", "c", "a"), ids(sortLocalSongs(songs, LocalSongSortKey.DURATION)))
        assertEquals(listOf("a", "c", "b"), ids(sortLocalSongs(songs, LocalSongSortKey.DURATION, ascending = false)))
        assertEquals(listOf("a", "c", "b"), ids(sortLocalSongs(songs, LocalSongSortKey.ADDED_AT)))
        assertEquals(listOf("b", "c", "a"), ids(sortLocalSongs(songs, LocalSongSortKey.ADDED_AT, ascending = false)))
    }

    @Test
    fun fileNameSortingDecodesSafDocumentIds() {
        val songs = listOf(
            song("a", title = "Ignored", path = "content://provider/tree/root/document/primary%3AMusic%2FZulu.mp3"),
            song("b", title = "Ignored", path = "content://provider/tree/root/document/primary%3AMusic%2Falpha.flac"),
            song("c", title = "Fallback", path = null),
        )
        assertEquals(listOf("b", "c", "a"), ids(sortLocalSongs(songs, LocalSongSortKey.FILE_NAME)))
        assertEquals("A+B.mp3", localAudioFileName("file:///Music/A+B.mp3"))
        assertEquals("Zulu.mp3", localAudioFileName(songs[0].path))
    }

    @Test
    fun customOrderUsesIdsAndLeavesUnlistedSongsInOriginalOrder() {
        val songs = listOf(song("a"), song("b"), song("c"), song("d"))
        val sorted = sortLocalSongs(
            songs,
            LocalSongSortKey.CUSTOM,
            ascending = false,
            customOrder = listOf("c", "a", "c"),
        )
        assertEquals(listOf("c", "a", "b", "d"), ids(sorted))
        assertEquals("Input must not be mutated", listOf("a", "b", "c", "d"), ids(songs))
    }

    private fun ids(songs: List<Song>) = songs.map(Song::id)

    private fun song(
        id: String,
        title: String = id,
        artist: String = id,
        album: String = id,
        duration: Long = 0,
        addedAt: Long = 0,
        path: String? = "file:///Music/$id.mp3",
    ) = Song(
        id = id,
        title = title,
        artist = listOf(artist),
        album = album,
        cover = "",
        duration = duration,
        path = path,
        addedAt = addedAt,
    )
}
