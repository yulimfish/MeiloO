package com.ljyh.mei.ui.component.player

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMetadataSearchQueryTest {
    private val metadata = MediaMetadata(
        id = -42,
        title = "A Song",
        coverUrl = "file:///cover.jpg",
        artists = listOf(MediaMetadata.Artist(id = 1, name = "Main Artist")),
        duration = 180_000,
        album = MediaMetadata.Album(id = 1, title = "An Album"),
        isLocal = true,
    )
    private val song = Song(
        id = "local_42",
        title = metadata.title,
        artist = metadata.artists.map { it.name },
        album = metadata.album.title,
        cover = metadata.coverUrl,
        duration = metadata.duration,
        path = "content://provider/song",
    )

    @Test
    fun unchangedSheetTextUsesStructuredSongQuery() {
        val defaultText = defaultLocalMetadataSearchText(metadata)

        assertEquals("A Song Main Artist", defaultText)
        assertNull(editedLocalMetadataQuery(song, metadata, defaultText))
        assertNull(editedLocalMetadataQuery(song, metadata, "  $defaultText  "))
    }

    @Test
    fun genuinelyEditedTextKeepsTheOtherStructuredFields() {
        val query = editedLocalMetadataQuery(song, metadata, "Another Song")

        assertEquals("Another Song", query?.title)
        assertEquals(listOf("Main Artist"), query?.artists)
        assertEquals("An Album", query?.album)
        assertEquals(180_000L, query?.durationMs)
    }

    @Test
    fun blankTextAlsoUsesStructuredSongQuery() {
        assertNull(editedLocalMetadataQuery(song, metadata, "   "))
        assertNull(editedLocalMetadataQuery(song, metadata, null))
    }
}
