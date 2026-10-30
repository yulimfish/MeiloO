package com.ljyh.mei.data.localsync

import com.ljyh.mei.data.localmetadata.MetadataCandidate
import com.ljyh.mei.data.localmetadata.MetadataSource
import com.ljyh.mei.data.model.room.Song

/** Every missing field can use the best candidate that actually supplies that field. */
internal data class LocalMetadataUpdate(
    val title: MetadataCandidate?,
    val artists: MetadataCandidate?,
    val album: MetadataCandidate?,
    val artwork: MetadataCandidate?,
)

/** A cover-only check would skip tracks whose embedded artwork exists but whose artist/album does not. */
internal fun shouldLookupSongMetadata(song: Song, onlyMissing: Boolean): Boolean =
    !onlyMissing || song.title.isBlank() || song.artist.none(String::isNotBlank) ||
        song.album.isBlank() || song.cover.isBlank()

internal fun selectMetadataUpdate(
    song: Song,
    candidates: List<MetadataCandidate>,
    onlyMissing: Boolean,
    minTextScore: Double,
    minArtworkScore: Double,
): LocalMetadataUpdate {
    fun bestTextCandidate(predicate: (MetadataCandidate) -> Boolean): MetadataCandidate? =
        candidates.firstOrNull {
            it.source != MetadataSource.LRCLIB && it.score >= minTextScore && predicate(it)
        }

    return LocalMetadataUpdate(
        title = if (onlyMissing && song.title.isNotBlank()) {
            null
        } else {
            bestTextCandidate { it.title.isNotBlank() }
        },
        artists = if (onlyMissing && song.artist.any(String::isNotBlank)) {
            null
        } else {
            bestTextCandidate { it.artists.any(String::isNotBlank) }
        },
        album = if (onlyMissing && song.album.isNotBlank()) {
            null
        } else {
            bestTextCandidate { !it.album.isNullOrBlank() }
        },
        artwork = if (onlyMissing && song.cover.isNotBlank()) {
            null
        } else {
            candidates.firstOrNull {
                it.source != MetadataSource.LRCLIB && it.score >= minArtworkScore &&
                    !it.artworkUrl.isNullOrBlank()
            }
        },
    )
}

internal fun mergeSyncedSong(
    song: Song,
    update: LocalMetadataUpdate,
    onlyMissing: Boolean,
    now: Long = System.currentTimeMillis(),
): Song = song.copy(
    title = if (onlyMissing && song.title.isNotBlank()) {
        song.title
    } else {
        update.title?.title?.takeIf(String::isNotBlank) ?: song.title
    },
    artist = if (onlyMissing && song.artist.any(String::isNotBlank)) {
        song.artist
    } else {
        update.artists?.artists?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() } ?: song.artist
    },
    album = if (onlyMissing && song.album.isNotBlank()) {
        song.album
    } else {
        update.album?.album?.takeIf(String::isNotBlank) ?: song.album
    },
    cover = if (onlyMissing && song.cover.isNotBlank()) {
        song.cover
    } else {
        update.artwork?.artworkUrl?.takeIf(String::isNotBlank) ?: song.cover
    },
    metadataOverride = song.metadataOverride || !onlyMissing,
    updatedAt = now,
)
