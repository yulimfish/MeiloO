package com.ljyh.mei.data.localmetadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** LRCLIB exact-get with a search fallback for plain and synchronised lyrics. */
class LrcLibMetadataProvider(
    private val client: HttpClient,
    private val baseUrl: String = "https://lrclib.net",
) : MetadataProvider {
    override val source: MetadataSource = MetadataSource.LRCLIB

    /** Uses LRCLIB's exact endpoint when artist and duration are known, then searches. */
    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val exact = try {
            getExact(query)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // A failed exact lookup can still succeed through LRCLIB's search index.
            null
        }
        return if (exact == null) searchLyrics(query) else listOf(exact)
    }

    /** Returns null for a 404, insufficient exact-query fields, or an invalid response item. */
    suspend fun getExact(query: MetadataQuery): MetadataCandidate? {
        if (query.durationMs == null || query.artists.isEmpty()) return null
        val response = client.get("$baseUrl/api/get") {
            parameter("track_name", query.title)
            parameter("artist_name", query.artists.joinToString(" "))
            query.album?.let { parameter("album_name", it) }
            parameter("duration", query.durationMs / 1_000.0)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        return metadataJson.decodeFromString<LrcLibTrack>(response.requireSuccessBody()).toCandidate()
    }

    suspend fun searchLyrics(query: MetadataQuery): List<MetadataCandidate> {
        val body = client.get("$baseUrl/api/search") {
            parameter("track_name", query.title)
            if (query.artists.isNotEmpty()) {
                parameter("artist_name", query.artists.joinToString(" "))
            }
            query.album?.let { parameter("album_name", it) }
        }.requireSuccessBody()
        return metadataJson.decodeFromString<List<LrcLibTrack>>(body).mapNotNull { it.toCandidate() }
    }
}

@Serializable
internal data class LrcLibTrack(
    val id: Long,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

private fun LrcLibTrack.toCandidate(): MetadataCandidate? {
    val title = trackName?.takeIf(String::isNotBlank) ?: return null
    val lyrics = MetadataLyrics(
        plain = plainLyrics?.takeIf(String::isNotBlank),
        synced = syncedLyrics?.takeIf(String::isNotBlank),
    ).takeUnless(MetadataLyrics::isEmpty)
    return MetadataCandidate(
        title = title,
        artists = artistName?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
        album = albumName?.takeIf(String::isNotBlank),
        durationMs = duration?.takeIf { it > 0 }?.let { (it * 1_000).toLong().coerceAtLeast(1) },
        lyrics = lyrics,
        source = MetadataSource.LRCLIB,
        sourceId = id.toString(),
    )
}
