package com.ljyh.mei.data.localmetadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataMatcherTest {
    private val query = MetadataQuery(
        title = "Héllo, WORLD!",
        artists = listOf("Artist B", "Artist A"),
        album = "The Album",
        durationMs = 200_000,
    )

    @Test
    fun `score normalizes text artist order accents and duration tolerance`() {
        val exact = candidate(
            title = "hello world",
            artists = listOf("artist a", "ARTIST B"),
            album = "Thé Álbúm",
            durationMs = 201_999,
            source = MetadataSource.LOCAL,
        )
        val poor = candidate(
            title = "another title",
            artists = listOf("someone else"),
            album = "another album",
            durationMs = 250_000,
            source = MetadataSource.ITUNES,
        )

        assertEquals(1.0, MetadataMatcher.score(query, exact), 0.000_001)
        assertTrue(MetadataMatcher.score(query, exact) > MetadataMatcher.score(query, poor))
        assertTrue(MetadataMatcher.score(query, poor) >= 0.0)
    }

    @Test
    fun `duration contributes to otherwise equal scores`() {
        val close = candidate(title = "hello world", durationMs = 202_500)
        val distant = candidate(title = "hello world", durationMs = 220_000)

        assertTrue(MetadataMatcher.score(query, close) > MetadataMatcher.score(query, distant))
    }

    @Test
    fun `rank chooses best score then applies source preference to ties`() {
        val exactRemote = candidate(
            title = "hello world",
            artists = listOf("artist a", "artist b"),
            album = "the album",
            durationMs = 200_000,
            source = MetadataSource.ITUNES,
            sourceId = "remote",
        )
        val weakLocal = exactRemote.copy(
            title = "unrelated",
            source = MetadataSource.LOCAL,
            sourceId = "weak-local",
        )
        assertEquals(
            listOf("remote", "weak-local"),
            MetadataMatcher.rank(query, listOf(weakLocal, exactRemote)).map { it.sourceId },
        )

        val exactLocal = exactRemote.copy(source = MetadataSource.LOCAL, sourceId = "local")
        val exactEmbedded = exactRemote.copy(source = MetadataSource.EMBEDDED, sourceId = "embedded")
        assertEquals(
            listOf("embedded", "local", "remote"),
            MetadataMatcher.rank(query, listOf(exactRemote, exactLocal, exactEmbedded)).map { it.sourceId },
        )
        assertEquals("embedded", MetadataMatcher.selectBest(query, listOf(exactRemote, exactLocal, exactEmbedded))?.sourceId)
    }

    private fun candidate(
        title: String,
        artists: List<String> = emptyList(),
        album: String? = null,
        durationMs: Long? = null,
        source: MetadataSource = MetadataSource.ITUNES,
        sourceId: String = title,
    ) = MetadataCandidate(
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        source = source,
        sourceId = sourceId,
    )
}
