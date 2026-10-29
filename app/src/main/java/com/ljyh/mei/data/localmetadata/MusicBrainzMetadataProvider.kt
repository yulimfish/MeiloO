package com.ljyh.mei.data.localmetadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Searches MusicBrainz recordings and resolves front artwork through Cover Art Archive. */
class MusicBrainzMetadataProvider(
    private val client: HttpClient,
    private val baseUrl: String = "https://musicbrainz.org/ws/2/recording/",
    private val coverArtBaseUrl: String = "https://coverartarchive.org",
    private val resultLimit: Int = 8,
) : MetadataProvider {
    init {
        require(resultLimit in 1..25) { "resultLimit must be in 1..25" }
    }

    override val source: MetadataSource = MetadataSource.MUSICBRAINZ

    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val body = client.get(baseUrl) {
            parameter("query", query.toMusicBrainzSearch())
            parameter("fmt", "json")
            parameter("limit", resultLimit)
        }.requireSuccessBody()
        val recordings = metadataJson.decodeFromString<MusicBrainzSearchResponse>(body).recordings

        return coroutineScope {
            recordings.map { recording ->
                async {
                    val release = recording.selectRelease(query)
                    val artworkUrl = release?.let { fetchFrontArtwork(it.id) }
                    recording.toCandidate(release, artworkUrl)
                }
            }.awaitAll().mapNotNull { it }
        }
    }

    private suspend fun fetchFrontArtwork(releaseId: String): String? = try {
        val response = client.get("$coverArtBaseUrl/release/$releaseId")
        if (!response.status.isSuccess()) {
            null
        } else {
            val artwork = metadataJson.decodeFromString<CoverArtResponse>(response.requireSuccessBody())
            val image = artwork.images.firstOrNull { it.front } ?: artwork.images.firstOrNull()
            image?.thumbnails?.get("500")
                ?: image?.thumbnails?.get("large")
                ?: image?.image
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Missing artwork must not discard an otherwise useful MusicBrainz match.
        null
    }
}

private fun MetadataQuery.toMusicBrainzSearch(): String = buildList {
    add("recording:\"${title.escapeMusicBrainzPhrase()}\"")
    if (artists.isNotEmpty()) {
        add("artist:\"${artists.joinToString(" ").escapeMusicBrainzPhrase()}\"")
    }
    album?.takeIf(String::isNotBlank)?.let {
        add("release:\"${it.escapeMusicBrainzPhrase()}\"")
    }
}.joinToString(" AND ")

private fun String.escapeMusicBrainzPhrase(): String = buildString(length) {
    this@escapeMusicBrainzPhrase.forEach { character ->
        if (character == '\\' || character == '"') append('\\')
        append(character)
    }
}

private fun MusicBrainzRecording.selectRelease(query: MetadataQuery): MusicBrainzRelease? {
    val normalizedAlbum = query.album?.let(MetadataMatcher::normalize)
    return releases.firstOrNull {
        normalizedAlbum != null && MetadataMatcher.normalize(it.title) == normalizedAlbum
    } ?: releases.firstOrNull()
}

private fun MusicBrainzRecording.toCandidate(
    release: MusicBrainzRelease?,
    artworkUrl: String?,
): MetadataCandidate? {
    if (id.isBlank() || title.isBlank()) return null
    return MetadataCandidate(
        title = title,
        artists = artistCredit.mapNotNull { credit ->
            (credit.artist?.name ?: credit.name)?.takeIf(String::isNotBlank)
        }.distinct(),
        album = release?.title?.takeIf(String::isNotBlank),
        durationMs = length?.takeIf { it > 0 },
        artworkUrl = artworkUrl?.takeIf(String::isNotBlank),
        source = MetadataSource.MUSICBRAINZ,
        sourceId = id,
    )
}

@Serializable
internal data class MusicBrainzSearchResponse(
    val recordings: List<MusicBrainzRecording> = emptyList(),
)

@Serializable
internal data class MusicBrainzRecording(
    val id: String,
    val title: String,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCredit> = emptyList(),
    val releases: List<MusicBrainzRelease> = emptyList(),
)

@Serializable
internal data class MusicBrainzArtistCredit(
    val name: String? = null,
    val artist: MusicBrainzArtist? = null,
)

@Serializable
internal data class MusicBrainzArtist(
    val name: String? = null,
)

@Serializable
internal data class MusicBrainzRelease(
    val id: String,
    val title: String,
)

@Serializable
internal data class CoverArtResponse(
    val images: List<CoverArtImage> = emptyList(),
)

@Serializable
internal data class CoverArtImage(
    val front: Boolean = false,
    val image: String? = null,
    val thumbnails: Map<String, String> = emptyMap(),
)
