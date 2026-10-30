package com.ljyh.mei.ui.screen.local

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.room.Song
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

internal fun localAudioPathExists(path: String?): Boolean = when {
    path.isNullOrBlank() -> false
    path.startsWith("content://") -> true
    else -> File(path.removePrefix("file://")).exists()
}

/** Embedded values win; automatic fills survive only while the corresponding file tag is absent. */
internal fun mergeScannedSong(existing: Song?, scanned: Song): Song = existing?.copy(
    path = scanned.path,
    duration = scanned.duration.takeIf { it > 0 } ?: existing.duration,
    fileSize = scanned.fileSize,
    fileFormat = scanned.fileFormat ?: existing.fileFormat,
    bitrate = scanned.bitrate ?: existing.bitrate,
    sampleRate = scanned.sampleRate ?: existing.sampleRate,
    folderPath = scanned.folderPath ?: existing.folderPath,
    title = when {
        existing.metadataOverride -> existing.title
        scanned.title.isNotBlank() -> scanned.title
        else -> existing.title
    },
    artist = when {
        existing.metadataOverride -> existing.artist
        scanned.artist.any(String::isNotBlank) -> scanned.artist
        else -> existing.artist
    },
    album = when {
        existing.metadataOverride -> existing.album
        scanned.album.isNotBlank() -> scanned.album
        else -> existing.album
    },
    cover = when {
        existing.metadataOverride -> existing.cover
        scanned.cover.isNotBlank() -> scanned.cover
        else -> existing.cover
    },
    updatedAt = scanned.updatedAt,
) ?: scanned

/** Preserve the file identity and measured duration when applying catalog information. */
internal fun Song.withMatchedMetadata(candidate: MediaMetadata): Song = copy(
    title = candidate.title,
    artist = candidate.artists.map { it.name }.filter(String::isNotBlank),
    album = candidate.album.title,
    cover = candidate.coverUrl.ifBlank { cover },
    metadataOverride = true,
    updatedAt = System.currentTimeMillis(),
)

internal fun rankLocalMatches(song: Song, candidates: List<MediaMetadata>): List<MediaMetadata> {
    val title = normalizeLocalMatchText(song.title)
    val artists = song.artist.map(::normalizeLocalMatchText).filter(String::isNotBlank)
    return candidates.distinctBy(MediaMetadata::id).sortedByDescending { candidate ->
        val candidateTitle = normalizeLocalMatchText(candidate.title)
        val titleScore = when {
            candidateTitle == title -> 100
            title.isNotBlank() && (candidateTitle.contains(title) || title.contains(candidateTitle)) -> 45
            else -> 0
        }
        val candidateArtists = candidate.artists.map { normalizeLocalMatchText(it.name) }
        val artistScore = if (artists.any { it in candidateArtists }) 50 else 0
        val durationScore = if (song.duration > 0 && candidate.duration > 0) {
            when (abs(song.duration - candidate.duration)) {
                in 0..2_000 -> 30
                in 2_001..5_000 -> 15
                else -> 0
            }
        } else 0
        titleScore + artistScore + durationScore
    }
}

private fun normalizeLocalMatchText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
