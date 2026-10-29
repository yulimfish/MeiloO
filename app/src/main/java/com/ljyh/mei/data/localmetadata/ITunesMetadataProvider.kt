package com.ljyh.mei.data.localmetadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

/** Searches iTunes song metadata and artwork; audio preview fields are never read. */
class ITunesMetadataProvider(
    private val client: HttpClient,
    private val baseUrl: String = "https://itunes.apple.com/search",
    private val resultLimit: Int = 10,
) : MetadataProvider {
    init {
        require(resultLimit in 1..25) { "resultLimit must be in 1..25" }
    }

    override val source: MetadataSource = MetadataSource.ITUNES

    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val term = buildList {
            add(query.title)
            addAll(query.artists)
            query.album?.let(::add)
        }.joinToString(" ")
        val body = client.get(baseUrl) {
            parameter("term", term)
            parameter("media", "music")
            parameter("entity", "song")
            parameter("limit", resultLimit)
        }.requireSuccessBody()
        return metadataJson.decodeFromString<ITunesSearchResponse>(body).results.mapNotNull {
            it.toCandidate()
        }
    }
}

@Serializable
internal data class ITunesSearchResponse(
    val results: List<ITunesTrack> = emptyList(),
)

@Serializable
internal data class ITunesTrack(
    val trackId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
)

private fun ITunesTrack.toCandidate(): MetadataCandidate? {
    val id = trackId ?: return null
    val title = trackName?.takeIf(String::isNotBlank) ?: return null
    return MetadataCandidate(
        title = title,
        artists = artistName?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
        album = collectionName?.takeIf(String::isNotBlank),
        durationMs = trackTimeMillis?.takeIf { it > 0 },
        artworkUrl = artworkUrl100
            ?.takeIf(String::isNotBlank)
            ?.replace("100x100bb", "600x600bb"),
        source = MetadataSource.ITUNES,
        sourceId = id.toString(),
    )
}
