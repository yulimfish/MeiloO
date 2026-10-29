package com.ljyh.mei.data.localmetadata

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Pure normalisation, scoring, ranking, and best-candidate selection functions. */
object MetadataMatcher {
    private const val TITLE_WEIGHT = 0.50
    private const val ARTIST_WEIGHT = 0.25
    private const val ALBUM_WEIGHT = 0.15
    private const val DURATION_WEIGHT = 0.10
    private const val DURATION_EXACT_TOLERANCE_MS = 2_000L
    private const val DURATION_MAX_DIFFERENCE_MS = 30_000L

    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")

    /** Case-, accent-, punctuation-, and width-insensitive text normalisation. */
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(combiningMarks, "")
        .replace(nonAlphanumeric, " ")
        .trim()
        .replace(whitespace, " ")

    /** Returns a weighted score in 0..1 using every available query field. */
    fun score(query: MetadataQuery, candidate: MetadataCandidate): Double {
        var weightedScore = TITLE_WEIGHT * similarity(
            normalize(query.title),
            normalize(candidate.title),
        )
        var totalWeight = TITLE_WEIGHT

        val queryArtists = normalizeArtists(query.artists)
        if (queryArtists.isNotEmpty()) {
            weightedScore += ARTIST_WEIGHT * similarity(
                queryArtists,
                normalizeArtists(candidate.artists),
            )
            totalWeight += ARTIST_WEIGHT
        }

        val queryAlbum = query.album?.let(::normalize).orEmpty()
        if (queryAlbum.isNotEmpty()) {
            weightedScore += ALBUM_WEIGHT * similarity(
                queryAlbum,
                candidate.album?.let(::normalize).orEmpty(),
            )
            totalWeight += ALBUM_WEIGHT
        }

        if (query.durationMs != null) {
            weightedScore += DURATION_WEIGHT * durationSimilarity(
                query.durationMs,
                candidate.durationMs,
            )
            totalWeight += DURATION_WEIGHT
        }

        return (weightedScore / totalWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Scores and deterministically sorts candidates. Match quality is primary; source preference
     * breaks ties, which keeps embedded/local results ahead without accepting a poor local match.
     */
    fun rank(
        query: MetadataQuery,
        candidates: Iterable<MetadataCandidate>,
        sourceOrder: List<MetadataSource> = SourcePreference.DEFAULT_ORDER,
    ): List<MetadataCandidate> {
        val sourcePositions = sourceOrder.withIndex().associate { (index, source) -> source to index }
        return candidates
            .map { it.copy(score = score(query, it)) }
            .sortedWith(
                compareByDescending<MetadataCandidate> { it.score }
                    .thenBy { sourcePositions[it.source] ?: Int.MAX_VALUE }
                    .thenBy { normalize(it.title) }
                    .thenBy { it.source.name }
                    .thenBy { it.sourceId },
            )
    }

    fun selectBest(
        query: MetadataQuery,
        candidates: Iterable<MetadataCandidate>,
        sourceOrder: List<MetadataSource> = SourcePreference.DEFAULT_ORDER,
    ): MetadataCandidate? = rank(query, candidates, sourceOrder).firstOrNull()

    private fun normalizeArtists(artists: List<String>): String = artists
        .map(::normalize)
        .filter(String::isNotEmpty)
        .sorted()
        .joinToString(" ")

    private fun similarity(expected: String, actual: String): Double = when {
        expected.isEmpty() || actual.isEmpty() -> 0.0
        expected == actual -> 1.0
        else -> 1.0 - levenshtein(expected, actual).toDouble() / max(expected.length, actual.length)
    }.coerceIn(0.0, 1.0)

    private fun durationSimilarity(expected: Long, actual: Long?): Double {
        if (actual == null) return 0.0
        val difference = abs(expected - actual)
        return when {
            difference <= DURATION_EXACT_TOLERANCE_MS -> 1.0
            difference >= DURATION_MAX_DIFFERENCE_MS -> 0.0
            else -> 1.0 - (difference - DURATION_EXACT_TOLERANCE_MS).toDouble() /
                (DURATION_MAX_DIFFERENCE_MS - DURATION_EXACT_TOLERANCE_MS)
        }
    }

    private fun levenshtein(first: String, second: String): Int {
        if (first.length < second.length) return levenshtein(second, first)
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        first.forEachIndexed { firstIndex, firstChar ->
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondChar ->
                val substitution = previous[secondIndex] + if (firstChar == secondChar) 0 else 1
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }
}
