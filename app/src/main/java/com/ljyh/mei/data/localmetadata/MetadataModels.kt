package com.ljyh.mei.data.localmetadata

/** Metadata origins supported by the engine. */
enum class MetadataSource {
    EMBEDDED,
    LOCAL,
    ITUNES,
    MUSICBRAINZ,
    LRCLIB,
}

/** The known local-track fields used to find matching metadata. */
data class MetadataQuery(
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(durationMs == null || durationMs > 0) { "durationMs must be positive" }
    }
}

/** Plain and/or time-synchronised lyrics returned by a metadata source. */
data class MetadataLyrics(
    val plain: String? = null,
    val synced: String? = null,
) {
    val isEmpty: Boolean
        get() = plain.isNullOrBlank() && synced.isNullOrBlank()
}

/** Which lookup stage produced a candidate. */
enum class MetadataMatchTier { EXACT, FUZZY }

/**
 * Source-neutral metadata. [artworkUrl] is an image location only; this model deliberately
 * has no audio or playback URL field.
 */
data class MetadataCandidate(
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val lyrics: MetadataLyrics? = null,
    val source: MetadataSource,
    val sourceId: String,
    val score: Double = 0.0,
    val matchTier: MetadataMatchTier = MetadataMatchTier.FUZZY,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(durationMs == null || durationMs > 0) { "durationMs must be positive" }
    }
}

/**
 * Ordered source selection. A per-track entry replaces the global order, including an empty
 * entry which disables metadata lookups for that track.
 */
data class SourcePreference(
    val global: List<MetadataSource> = DEFAULT_ORDER,
    val perTrack: Map<String, List<MetadataSource>> = emptyMap(),
) {
    init {
        require(global.hasNoDuplicates()) { "global sources must be unique" }
        require(perTrack.keys.none(String::isBlank)) { "track ids must not be blank" }
        require(perTrack.values.all(List<MetadataSource>::hasNoDuplicates)) {
            "per-track sources must be unique"
        }
    }

    /** Pure source-resolution policy; local and embedded metadata precede remote sources. */
    fun sourcesFor(trackId: String? = null): List<MetadataSource> =
        if (trackId == null) global else perTrack[trackId] ?: global

    companion object {
        val DEFAULT_ORDER: List<MetadataSource> = listOf(
            MetadataSource.EMBEDDED,
            MetadataSource.LOCAL,
            MetadataSource.ITUNES,
            MetadataSource.MUSICBRAINZ,
            MetadataSource.LRCLIB,
        )
    }
}

private fun <T> Iterable<T>.hasNoDuplicates(): Boolean = count() == distinct().count()

/** Implementations return metadata only and never initiate audio playback or downloads. */
interface MetadataProvider {
    val source: MetadataSource

    suspend fun search(query: MetadataQuery): List<MetadataCandidate>
}
