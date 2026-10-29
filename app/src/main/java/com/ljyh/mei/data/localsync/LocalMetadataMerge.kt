package com.ljyh.mei.data.localsync

import com.ljyh.mei.data.localmetadata.MetadataCandidate
import com.ljyh.mei.data.localmetadata.MetadataSource
import com.ljyh.mei.data.model.room.Song

/** Text and artwork may come from different candidates; only explicit replacement may overwrite. */
internal data class LocalMetadataUpdate(
    val text: MetadataCandidate?,
    val artwork: MetadataCandidate?,
)

internal fun selectMetadataUpdate(
    song: Song,
    candidates: List<MetadataCandidate>,
    onlyMissing: Boolean,
    minTextScore: Double,
    minArtworkScore: Double,
): LocalMetadataUpdate = LocalMetadataUpdate(
    text = candidates.firstOrNull {
        it.source != MetadataSource.LRCLIB && it.score >= minTextScore &&
            (!onlyMissing || song.title.isBlank() || song.artist.isEmpty() || song.album.isBlank())
    },
    artwork = if (onlyMissing && song.cover.isNotBlank()) {
        null
    } else {
        candidates.firstOrNull {
            it.source != MetadataSource.LRCLIB && it.score >= minArtworkScore && !it.artworkUrl.isNullOrBlank()
        }
    },
)

internal fun mergeSyncedSong(
    song: Song,
    update: LocalMetadataUpdate,
    onlyMissing: Boolean,
    now: Long = System.currentTimeMillis(),
): Song {
    val candidate = update.text
    return song.copy(
        title = if (candidate == null || (onlyMissing && song.title.isNotBlank())) {
            song.title
        } else {
            candidate.title.ifBlank { song.title }
        },
        artist = if (candidate == null || (onlyMissing && song.artist.isNotEmpty())) {
            song.artist
        } else {
            candidate.artists.filter(String::isNotBlank).ifEmpty { song.artist }
        },
        album = if (candidate == null || (onlyMissing && song.album.isNotBlank())) {
            song.album
        } else {
            candidate.album?.takeIf(String::isNotBlank) ?: song.album
        },
        cover = if (onlyMissing && song.cover.isNotBlank()) {
            song.cover
        } else {
            update.artwork?.artworkUrl?.takeIf(String::isNotBlank) ?: song.cover
        },
        metadataOverride = song.metadataOverride || !onlyMissing,
        updatedAt = now,
    )
}
