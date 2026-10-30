package com.ljyh.mei.data.localmetadata

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val EXACT_DURATION_TOLERANCE_MS = 2_000L
private const val FUZZY_DURATION_TOLERANCE_MS = 5_000L
private const val MIN_TITLE_SCORE = 0.60
private const val MIN_TOTAL_SCORE = 0.65

private const val TITLE_WEIGHT = 0.50
private const val ARTIST_WEIGHT = 0.25
private const val ALBUM_WEIGHT = 0.15
private const val DURATION_WEIGHT = 0.10

private val lyricCombiningMarks = Regex("\\p{M}+")
private val lyricNonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
private val lyricWhitespace = Regex("\\s+")

/** Exact LRCLIB results must identify the same track, not merely share a similar title. */
internal fun isExactLyricMatch(query: MetadataQuery, candidate: MetadataCandidate): Boolean {
    val queryArtist = query.artists.firstOrNull()?.let(::normalizeLyricText).orEmpty()
    val candidateArtist = candidate.artists.firstOrNull()?.let(::normalizeLyricText).orEmpty()
    val queryTitle = normalizeLyricText(query.title)
    val queryDuration = query.durationMs ?: return false
    val candidateDuration = candidate.durationMs ?: return false
    return queryTitle.isNotEmpty() && queryTitle == normalizeLyricText(candidate.title) &&
        queryArtist.isNotEmpty() && queryArtist == candidateArtist &&
        abs(queryDuration - candidateDuration) <= EXACT_DURATION_TOLERANCE_MS
}

/**
 * Filters and ranks fuzzy lyric matches without performing I/O. The returned scores describe
 * metadata similarity; synchronised lyrics win an otherwise equal comparison with plain lyrics.
 */
fun rankLyricCandidates(
    query: MetadataQuery,
    candidates: Iterable<MetadataCandidate>,
): List<MetadataCandidate> = candidates.mapNotNull { candidate ->
    if (candidate.lyrics?.isEmpty != false) return@mapNotNull null
    if (hasVersionConflict(query.title, candidate.title)) return@mapNotNull null
    if (query.durationMs != null && candidate.durationMs != null &&
        abs(query.durationMs - candidate.durationMs) > FUZZY_DURATION_TOLERANCE_MS
    ) {
        return@mapNotNull null
    }

    val titleScore = lyricSimilarity(
        normalizeLyricText(query.title),
        normalizeLyricText(candidate.title),
    )
    if (titleScore < MIN_TITLE_SCORE) return@mapNotNull null

    var weightedScore = TITLE_WEIGHT * titleScore
    var totalWeight = TITLE_WEIGHT
    if (query.artists.isNotEmpty()) {
        weightedScore += ARTIST_WEIGHT * lyricSimilarity(
            normalizeLyricArtists(query.artists),
            normalizeLyricArtists(candidate.artists),
        )
        totalWeight += ARTIST_WEIGHT
    }

    val queryAlbum = query.album?.let(::normalizeLyricText).orEmpty()
    if (queryAlbum.isNotEmpty()) {
        weightedScore += ALBUM_WEIGHT * lyricSimilarity(
            queryAlbum,
            candidate.album?.let(::normalizeLyricText).orEmpty(),
        )
        totalWeight += ALBUM_WEIGHT
    }

    if (query.durationMs != null) {
        weightedScore += DURATION_WEIGHT * lyricDurationSimilarity(
            query.durationMs,
            candidate.durationMs,
        )
        totalWeight += DURATION_WEIGHT
    }

    val score = weightedScore / totalWeight
    if (score < MIN_TOTAL_SCORE) return@mapNotNull null
    candidate.copy(score = score.coerceIn(0.0, 1.0), matchTier = MetadataMatchTier.FUZZY)
}.sortedWith(
    compareByDescending<MetadataCandidate> { it.score }
        .thenByDescending { if (it.lyrics?.synced?.isNotBlank() == true) 1 else 0 },
)

private fun normalizeLyricText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT)
    .replace(lyricCombiningMarks, "")
    .replace(lyricNonAlphanumeric, " ")
    .trim()
    .replace(lyricWhitespace, " ")

private fun normalizeLyricArtists(artists: List<String>): String = artists
    .map(::normalizeLyricText)
    .filter(String::isNotEmpty)
    .sorted()
    .joinToString(" ")

private fun lyricSimilarity(expected: String, actual: String): Double = when {
    expected.isEmpty() || actual.isEmpty() -> 0.0
    expected == actual -> 1.0
    else -> 1.0 - lyricEditDistance(expected, actual).toDouble() / max(expected.length, actual.length)
}.coerceIn(0.0, 1.0)

private fun lyricDurationSimilarity(expected: Long, actual: Long?): Double {
    if (actual == null) return 0.0
    val difference = abs(expected - actual)
    return when {
        difference <= EXACT_DURATION_TOLERANCE_MS -> 1.0
        else -> 1.0 - (difference - EXACT_DURATION_TOLERANCE_MS).toDouble() /
            (FUZZY_DURATION_TOLERANCE_MS - EXACT_DURATION_TOLERANCE_MS)
    }.coerceIn(0.0, 1.0)
}

private fun hasVersionConflict(queryTitle: String, candidateTitle: String): Boolean =
    lyricVersionTags(queryTitle) != lyricVersionTags(candidateTitle)

private fun lyricVersionTags(title: String): Set<String> {
    val tokens = normalizeLyricText(title).split(' ').toSet()
    return buildSet {
        if (tokens.contains("live")) add("live")
        if (tokens.any { it == "remix" || it == "remixed" }) add("remix")
        if (tokens.any { it == "instrumental" || it == "karaoke" }) add("instrumental")
        if (tokens.contains("acoustic")) add("acoustic")
        if (tokens.contains("demo")) add("demo")
        if (tokens.contains("cover")) add("cover")
        if (tokens.any { it == "remaster" || it == "remastered" }) add("remaster")
    }
}

private fun lyricEditDistance(first: String, second: String): Int {
    if (first.length < second.length) return lyricEditDistance(second, first)
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
