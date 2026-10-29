package com.ljyh.mei.data.localmetadata

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEngineTest {
    private val query = MetadataQuery(
        title = "A Song",
        artists = listOf("An Artist"),
        album = "An Album",
        durationMs = 180_000,
    )

    @Test
    fun `source preference supports global per-track and disabled lookups`() = runTest {
        val embedded = FakeMetadataProvider(MetadataSource.EMBEDDED) { listOf(match(MetadataSource.EMBEDDED)) }
        val local = FakeMetadataProvider(MetadataSource.LOCAL) { listOf(match(MetadataSource.LOCAL)) }
        val remote = FakeMetadataProvider(MetadataSource.ITUNES) { listOf(match(MetadataSource.ITUNES)) }
        val engine = MetadataEngine(listOf(embedded, local, remote))

        assertEquals(
            SourcePreference.DEFAULT_ORDER,
            SourcePreference().sourcesFor(),
        )
        assertEquals(
            MetadataSource.EMBEDDED,
            engine.findBest(query)?.source,
        )
        assertEquals(1, embedded.callCount)
        assertEquals(1, local.callCount)
        assertEquals(1, remote.callCount)

        val preference = SourcePreference(
            global = listOf(MetadataSource.LOCAL),
            perTrack = mapOf(
                "remote-track" to listOf(MetadataSource.ITUNES),
                "disabled-track" to emptyList(),
            ),
        )
        assertEquals(listOf(MetadataSource.LOCAL), preference.sourcesFor("unknown"))
        assertEquals(listOf(MetadataSource.ITUNES), preference.sourcesFor("remote-track"))
        assertEquals(
            MetadataSource.ITUNES,
            engine.findBest(query, trackId = "remote-track", preference = preference)?.source,
        )
        assertTrue(engine.search(query, trackId = "disabled-track", preference = preference).candidates.isEmpty())
        assertEquals(2, remote.callCount)
        assertEquals(1, embedded.callCount)
        assertEquals(1, local.callCount)
    }

    @Test
    fun `one failed provider does not suppress successful candidates`() = runTest {
        val failed = FakeMetadataProvider(MetadataSource.LOCAL) {
            throw IOException("offline")
        }
        val embedded = FakeMetadataProvider(MetadataSource.EMBEDDED) {
            listOf(match(MetadataSource.EMBEDDED).copy(title = "Wrong title"))
        }
        val remote = FakeMetadataProvider(MetadataSource.ITUNES) {
            listOf(match(MetadataSource.ITUNES))
        }
        val result = MetadataEngine(listOf(failed, embedded, remote)).search(query)

        assertEquals(2, result.candidates.size)
        assertEquals(MetadataSource.ITUNES, result.best?.source)
        assertEquals(listOf(MetadataSource.LOCAL), result.failures.map { it.source })
        assertEquals("offline", result.failures.single().message)
    }

    @Test
    fun `timed out provider is isolated and other results survive`() = runTest {
        val slow = FakeMetadataProvider(MetadataSource.LOCAL) {
            delay(60_000)
            listOf(match(MetadataSource.LOCAL))
        }
        val remote = FakeMetadataProvider(MetadataSource.ITUNES) {
            listOf(match(MetadataSource.ITUNES))
        }
        val engine = MetadataEngine(
            providers = listOf(slow, remote),
            requestTimeoutMs = 1_000,
        )

        val result = engine.search(query)

        assertEquals(MetadataSource.ITUNES, result.best?.source)
        assertEquals(listOf(MetadataSource.LOCAL), result.failures.map { it.source })
    }

    @Test
    fun `caller cancellation is not reported as a provider timeout`() = runTest {
        val waiting = FakeMetadataProvider(MetadataSource.LOCAL) {
            awaitCancellation()
        }
        val engine = MetadataEngine(listOf(waiting), requestTimeoutMs = 60_000)

        val job = launch { engine.search(query) }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `lyrics source can be selected for one track`() = runTest {
        val lyrics = MetadataLyrics(
            plain = "first line\nsecond line",
            synced = "[00:01.00]first line\n[00:03.00]second line",
        )
        val metadata = FakeMetadataProvider(MetadataSource.ITUNES) {
            listOf(match(MetadataSource.ITUNES))
        }
        val lyricProvider = FakeMetadataProvider(MetadataSource.LRCLIB) {
            listOf(match(MetadataSource.LRCLIB).copy(lyrics = lyrics))
        }
        val engine = MetadataEngine(listOf(metadata, lyricProvider))
        val preference = SourcePreference(
            perTrack = mapOf("lyrics-track" to listOf(MetadataSource.LRCLIB)),
        )

        val result = engine.findBest(query, trackId = "lyrics-track", preference = preference)

        assertEquals(MetadataSource.LRCLIB, result?.source)
        assertEquals(lyrics, result?.lyrics)
        assertEquals(0, metadata.callCount)
        assertEquals(1, lyricProvider.callCount)
    }

    @Test
    fun `unselected or empty lyric payload remains absent`() = runTest {
        val provider = FakeMetadataProvider(MetadataSource.LRCLIB) {
            listOf(match(MetadataSource.LRCLIB).copy(lyrics = null))
        }

        val result = MetadataEngine(listOf(provider)).findBest(query)

        assertNull(result?.lyrics)
    }

    private fun match(source: MetadataSource) = MetadataCandidate(
        title = query.title,
        artists = query.artists,
        album = query.album,
        durationMs = query.durationMs,
        artworkUrl = "https://images.example/cover.jpg",
        source = source,
        sourceId = source.name,
    )
}

private class FakeMetadataProvider(
    override val source: MetadataSource,
    private val handler: suspend (MetadataQuery) -> List<MetadataCandidate>,
) : MetadataProvider {
    var callCount: Int = 0
        private set

    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        callCount++
        return handler(query)
    }
}
