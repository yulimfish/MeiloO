package com.ljyh.mei.ui.screen.meilo

import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeiloLibraryModelsTest {
    private val songs = listOf(
        song("a", "Zulu", "Beta", "Second", 180_000L, 30L),
        song("b", "alpha", "Alpha", "First", 60_000L, 20L),
        song("c", "Middle", listOf("Alpha", "Guest"), "First", 120_000L, 10L),
    )

    @Test
    fun `local search matches title artist and album without changing order`() {
        assertEquals(listOf("b", "c"), filterMeiloSongs(songs, "ALPH").map(Song::id))
        assertEquals(listOf("b", "c"), filterMeiloSongs(songs, "alpha").map(Song::id))
        assertEquals(listOf("a"), filterMeiloSongs(songs, "zul").map(Song::id))
        assertEquals(listOf("b", "c"), filterMeiloSongs(songs, "first").map(Song::id))
        assertTrue(filterMeiloSongs(songs, "not present").isEmpty())
    }

    @Test
    fun `common sorts support forward and reverse`() {
        assertEquals(
            listOf("b", "c", "a"),
            sortMeiloSongs(songs, MeiloSongSort.TITLE).map(Song::id),
        )
        assertEquals(
            listOf("a", "c", "b"),
            sortMeiloSongs(songs, MeiloSongSort.TITLE, descending = true).map(Song::id),
        )
        assertEquals(
            listOf("b", "c", "a"),
            sortMeiloSongs(songs, MeiloSongSort.DURATION).map(Song::id),
        )
        assertEquals(
            listOf("a", "b", "c"),
            sortMeiloSongs(songs, MeiloSongSort.ADDED_AT, descending = true).map(Song::id),
        )
        assertEquals(
            listOf("b", "c", "a"),
            sortMeiloSongs(songs, MeiloSongSort.ARTIST).map(Song::id),
        )
        assertEquals(
            listOf("b", "c", "a"),
            sortMeiloSongs(songs, MeiloSongSort.ALBUM).map(Song::id),
        )
    }

    @Test
    fun `custom order drops missing ids and appends newly scanned songs`() {
        assertEquals(
            listOf("c", "a", "b"),
            sortMeiloSongs(songs, MeiloSongSort.CUSTOM, customOrder = listOf("missing", "c", "c", "a")).map(Song::id),
        )
        assertEquals(listOf("a", "b", "c"), reconcileMeiloSongOrder(songs, listOf("a", "b", "c")))
    }

    @Test
    fun `moving custom songs does not mutate saved input`() {
        val original = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), moveMeiloSong(original, 0, 2))
        assertEquals(listOf("a", "b", "c"), original)
        assertEquals(original, moveMeiloSong(original, -1, 2))
    }

    @Test
    fun `album and artist categories use only local tags`() {
        val albums = buildMeiloAlbums(songs)
        assertEquals(listOf("First", "Second"), albums.map(MeiloAlbumEntry::name))
        assertEquals(2, albums.first().songCount)
        assertEquals("Alpha / Guest", albums.first().artists)

        val artists = buildMeiloArtists(songs)
        assertEquals(listOf("Alpha", "Beta", "Guest"), artists.map(MeiloArtistEntry::name))
        assertEquals(2, artists.first().songCount)
        assertEquals(1, artists.first().albumCount)
    }

    @Test
    fun `same album name remains separated by artist`() {
        val entries = buildMeiloAlbums(listOf(
            song("a", "One", "Artist A", "Greatest Hits", 1, 1),
            song("b", "Two", "Artist B", "Greatest Hits", 1, 1),
        ))
        assertEquals(2, entries.size)
        assertEquals(setOf("Artist A", "Artist B"), entries.map(MeiloAlbumEntry::routeArtists).toSet())
    }

    @Test
    fun `duration formatting supports hours and unknown values`() {
        assertEquals("2:03", formatMeiloDuration(123_000L))
        assertEquals("1:02:03", formatMeiloDuration(3_723_000L))
        assertEquals("--:--", formatMeiloDuration(0L))
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        album: String,
        duration: Long,
        addedAt: Long,
    ) = song(id, title, listOf(artist), album, duration, addedAt)

    private fun song(
        id: String,
        title: String,
        artist: List<String>,
        album: String,
        duration: Long,
        addedAt: Long,
    ) = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        cover = "",
        duration = duration,
        path = "content://test/$id",
        sourceType = SourceType.LOCAL,
        addedAt = addedAt,
        updatedAt = addedAt,
    )
}
