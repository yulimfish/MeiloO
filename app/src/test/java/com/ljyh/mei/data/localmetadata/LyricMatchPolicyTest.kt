package com.ljyh.mei.data.localmetadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMatchPolicyTest {
    private val query = MetadataQuery(
        title = "A Song",
        artists = listOf("Main Artist", "Featured Artist"),
        album = "An Album",
        durationMs = 180_000,
    )

    @Test
    fun `exact gate normalizes title and primary artist with two second duration tolerance`() {
        val exact = candidate(
            title = "á sóng!",
            artists = listOf("máin, artist!", "Another Artist"),
            durationMs = 182_000,
        )

        assertTrue(isExactLyricMatch(query, exact))
        assertFalse(isExactLyricMatch(query, exact.copy(durationMs = 182_001)))
        assertFalse(isExactLyricMatch(query, exact.copy(title = "Another Song")))
        assertFalse(isExactLyricMatch(query, exact.copy(artists = listOf("Featured Artist"))))
        assertFalse(isExactLyricMatch(query, exact.copy(durationMs = null)))
    }

    @Test
    fun `rejected exact result leaves a usable fuzzy match`() {
        val wrongExact = candidate(title = "Wrong Song", sourceId = "wrong-exact")
        val fuzzy = candidate(sourceId = "fuzzy")

        assertFalse(isExactLyricMatch(query, wrongExact))
        val ranked = rankLyricCandidates(query, listOf(fuzzy))
        assertEquals(listOf("fuzzy"), ranked.map { it.sourceId })
        assertEquals(MetadataMatchTier.FUZZY, ranked.single().matchTier)
    }

    @Test
    fun `fuzzy match requires lyrics and rejects only known durations over five seconds apart`() {
        val atLimit = candidate(durationMs = 185_000, sourceId = "at-limit")
        val overLimit = candidate(durationMs = 185_001, sourceId = "over-limit")
        val unknownDuration = candidate(durationMs = null, sourceId = "unknown-duration")
        val noLyrics = candidate(sourceId = "no-lyrics", lyrics = null)

        val ranked = rankLyricCandidates(query, listOf(atLimit, overLimit, unknownDuration, noLyrics))

        assertEquals(setOf("at-limit", "unknown-duration"), ranked.map { it.sourceId }.toSet())
    }

    @Test
    fun `version tags must agree but matching versions remain eligible`() {
        listOf("Live", "Remix", "Instrumental").forEach { version ->
            val conflict = candidate(title = "A Song ($version)", sourceId = version)
            assertTrue(version, rankLyricCandidates(query, listOf(conflict)).isEmpty())
        }

        val liveQuery = query.copy(title = "A Song (Live)")
        val live = candidate(title = "a song live")
        assertEquals(1, rankLyricCandidates(liveQuery, listOf(live)).size)
    }

    @Test
    fun `low title and total scores are rejected independently`() {
        val titleQuery = MetadataQuery(title = "abcd", artists = listOf("Artist"))
        val lowTitle = candidate(
            title = "abzz",
            artists = listOf("Artist"),
            album = null,
            durationMs = null,
        )
        assertTrue(rankLyricCandidates(titleQuery, listOf(lowTitle)).isEmpty())

        val lowTotal = candidate(
            artists = listOf("Someone Else"),
            album = "Another Album",
            durationMs = null,
        )
        assertTrue(rankLyricCandidates(query, listOf(lowTotal)).isEmpty())
    }

    @Test
    fun `best metadata match is first and synchronized lyrics win equal comparisons`() {
        val exactPlain = candidate(sourceId = "exact-plain")
        val exactSynced = candidate(
            sourceId = "exact-synced",
            lyrics = MetadataLyrics(plain = "line", synced = "[00:01.00]line"),
        )
        val weakerSynced = candidate(
            title = "A Song 2",
            sourceId = "weaker-synced",
            lyrics = MetadataLyrics(synced = "[00:01.00]line"),
        )

        val tied = rankLyricCandidates(query, listOf(exactPlain, exactSynced))
        assertEquals(listOf("exact-synced", "exact-plain"), tied.map { it.sourceId })

        val ranked = rankLyricCandidates(query, listOf(weakerSynced, exactPlain))
        assertEquals(listOf("exact-plain", "weaker-synced"), ranked.map { it.sourceId })
        assertTrue(ranked[0].score > ranked[1].score)
    }

    private fun candidate(
        title: String = query.title,
        artists: List<String> = query.artists,
        album: String? = query.album,
        durationMs: Long? = query.durationMs,
        sourceId: String = title,
        lyrics: MetadataLyrics? = MetadataLyrics(plain = "line"),
    ) = MetadataCandidate(
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        lyrics = lyrics,
        source = MetadataSource.LRCLIB,
        sourceId = sourceId,
    )
}
