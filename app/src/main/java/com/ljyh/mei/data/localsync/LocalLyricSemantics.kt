package com.ljyh.mei.data.localsync

import com.ljyh.mei.data.localmetadata.MetadataLyrics
import com.ljyh.mei.data.locallibrary.SUPPORTED_LOCAL_AUDIO_EXTENSIONS
import com.ljyh.mei.data.model.room.Song
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

internal const val PLAIN_LYRIC_PARSER_TYPE = "PLAIN"
private const val STATIC_LINE_STEP_MS = 60 * 60 * 1_000L

/** Only strips an audio suffix from a filename fallback, never from a real embedded/catalog title. */
internal fun metadataTitleForQuery(song: Song): String {
    val candidate = song.title.trim().ifBlank { "Unknown" }
    val suffix = candidate.substringAfterLast('.', "").lowercase()
    val hasEmbeddedTitle = song.artist.isNotEmpty() || song.album.isNotBlank()
    return if (!hasEmbeddedTitle && suffix in SUPPORTED_LOCAL_AUDIO_EXTENSIONS) {
        candidate.substringBeforeLast('.').ifBlank { candidate }
    } else candidate
}

internal enum class EffectiveLyricSource { LOCAL, LRCLIB, AUTO }

internal fun acceptsCachedLyricSource(sourceName: String, effectiveSource: EffectiveLyricSource): Boolean =
    when (effectiveSource) {
        EffectiveLyricSource.LOCAL -> sourceName == "Local" || sourceName == "Empty"
        EffectiveLyricSource.LRCLIB -> sourceName == "LRCLIB"
        EffectiveLyricSource.AUTO -> true
    }

internal data class LyricCachePayload(
    val content: String,
    val parserType: String,
)

internal fun lyricCachePayload(lyrics: MetadataLyrics): LyricCachePayload? {
    lyrics.synced?.takeIf(String::isNotBlank)?.let {
        return LyricCachePayload(it, "LRC")
    }
    return lyrics.plain?.takeIf(String::isNotBlank)?.let {
        LyricCachePayload(it, PLAIN_LYRIC_PARSER_TYPE)
    }
}

/** Static text uses monotonically increasing layout markers, not fabricated playback timestamps. */
internal fun parsePlainLyrics(content: String): SyncedLyrics {
    val lines = content.lines().map(String::trim).filter(String::isNotEmpty)
    return SyncedLyrics(
        lines = lines.mapIndexed { index, text ->
            val start = index.toLong() * STATIC_LINE_STEP_MS
            SyncedLine(
                content = text,
                translation = null,
                start = start.toInt(),
                end = if (index == lines.lastIndex) Int.MAX_VALUE else (start + STATIC_LINE_STEP_MS).toInt(),
            )
        },
    )
}
