package com.ljyh.mei.data.localsync

import com.ljyh.mei.data.localmetadata.MetadataCandidate
import com.ljyh.mei.data.localmetadata.MetadataSource
import com.ljyh.mei.data.model.room.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMetadataMergeTest {
    @Test
    fun automaticSyncPreservesEveryNonEmptyFieldIncludingManualTextAndCover() {
        val song = song().copy(metadataOverride = true)
        val candidates = listOf(
            candidate(
                title = "Remote title",
                artists = listOf("Remote artist"),
                album = "Remote album",
                artwork = "https://images.test/remote.jpg",
                score = 0.99,
                source = MetadataSource.ITUNES,
            ),
        )

        val update = selectMetadataUpdate(song, candidates, onlyMissing = true, minTextScore = 0.55, minArtworkScore = 0.55)
        val merged = mergeSyncedSong(song, update, onlyMissing = true, now = 123L)

        assertNull(update.text)
        assertNull(update.artwork)
        assertEquals(song.title, merged.title)
        assertEquals(song.artist, merged.artist)
        assertEquals(song.album, merged.album)
        assertEquals(song.cover, merged.cover)
        assertTrue(merged.metadataOverride)
        assertEquals(123L, merged.updatedAt)
    }

    @Test
    fun automaticSyncOnlyFillsMissingFieldsAndDoesNotInventOverride() {
        val song = song().copy(title = "", artist = emptyList(), album = "", cover = "")
        val candidate = candidate(
            title = "Remote title",
            artists = listOf("Remote artist"),
            album = "Remote album",
            artwork = "https://images.test/remote.jpg",
            score = 0.99,
            source = MetadataSource.ITUNES,
        )

        val update = selectMetadataUpdate(song, listOf(candidate), onlyMissing = true, minTextScore = 0.55, minArtworkScore = 0.55)
        val merged = mergeSyncedSong(song, update, onlyMissing = true)

        assertEquals("Remote title", merged.title)
        assertEquals(listOf("Remote artist"), merged.artist)
        assertEquals("Remote album", merged.album)
        assertEquals("https://images.test/remote.jpg", merged.cover)
        assertFalse(merged.metadataOverride)
    }

    @Test
    fun manualOrForcedUpdateCanReplaceExistingFields() {
        val song = song()
        val chosen = candidate(
            title = "Chosen",
            artists = listOf("Chosen artist"),
            album = "Chosen album",
            artwork = "https://images.test/chosen.jpg",
            score = 0.40,
            source = MetadataSource.ITUNES,
        )

        val update = selectMetadataUpdate(song, listOf(chosen), onlyMissing = false, minTextScore = Double.NEGATIVE_INFINITY, minArtworkScore = Double.NEGATIVE_INFINITY)
        val merged = mergeSyncedSong(song, update, onlyMissing = false)

        assertEquals("Chosen", merged.title)
        assertEquals(listOf("Chosen artist"), merged.artist)
        assertEquals("Chosen album", merged.album)
        assertEquals("https://images.test/chosen.jpg", merged.cover)
        assertTrue(merged.metadataOverride)
    }

    @Test
    fun artworkFallsBackAcrossSourcesWhileTextComesFromBestCandidate() {
        val bestText = candidate(
            title = "Best text without artwork",
            artwork = null,
            score = 0.95,
            source = MetadataSource.MUSICBRAINZ,
        )
        val lowerScoreWithCover = candidate(
            title = "Second source with artwork",
            artwork = "https://images.test/cover.jpg",
            score = 0.75,
            source = MetadataSource.ITUNES,
        )
        val lowScoreCover = candidate(
            title = "Low confidence artwork",
            artwork = "https://images.test/wrong.jpg",
            score = 0.20,
            source = MetadataSource.ITUNES,
        )
        val song = song().copy(title = "", artist = emptyList(), album = "", cover = "")
        val candidates = listOf(bestText, lowerScoreWithCover, lowScoreCover)

        val update = selectMetadataUpdate(song, candidates, onlyMissing = true, minTextScore = 0.55, minArtworkScore = 0.55)
        val merged = mergeSyncedSong(song, update, onlyMissing = true)

        assertEquals(bestText, update.text)
        assertEquals(lowerScoreWithCover, update.artwork)
        assertEquals("Best text without artwork", merged.title)
        assertEquals("https://images.test/cover.jpg", merged.cover)
    }

    @Test
    fun lyricOnlyAndLowScoreCandidatesCannotSupplyArtwork() {
        val song = song().copy(cover = "")
        val candidates = listOf(
            candidate(title = "Lyrics", artwork = "https://images.test/lyric.jpg", score = 1.0, source = MetadataSource.LRCLIB),
            candidate(title = "Wrong", artwork = "https://images.test/wrong.jpg", score = 0.2, source = MetadataSource.ITUNES),
        )

        val update = selectMetadataUpdate(song, candidates, onlyMissing = true, minTextScore = 0.55, minArtworkScore = 0.55)

        assertNull(update.artwork)
        assertEquals(song.cover, mergeSyncedSong(song, update, onlyMissing = true).cover)
    }

    private fun song() = Song(
        id = "local_1",
        title = "Original title",
        artist = listOf("Original artist"),
        album = "Original album",
        cover = "file:///cover.jpg",
        duration = 100_000,
        path = "content://provider/song",
    )

    private fun candidate(
        title: String,
        artists: List<String> = listOf("Artist"),
        album: String = "Album",
        artwork: String?,
        score: Double,
        source: MetadataSource,
    ) = MetadataCandidate(
        title = title,
        artists = artists,
        album = album,
        artworkUrl = artwork,
        source = source,
        sourceId = title,
        score = score,
    )
}
