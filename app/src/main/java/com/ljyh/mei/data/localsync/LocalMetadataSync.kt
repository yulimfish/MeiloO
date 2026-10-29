package com.ljyh.mei.data.localsync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ljyh.mei.data.localmetadata.MetadataCandidate
import com.ljyh.mei.data.localmetadata.MetadataEngine
import com.ljyh.mei.data.localmetadata.MetadataQuery
import com.ljyh.mei.data.localmetadata.MetadataSearchResult
import com.ljyh.mei.data.localmetadata.MetadataSource
import com.ljyh.mei.data.localmetadata.SourcePreference
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.ui.screen.meilo.MeiloCoverSource
import com.ljyh.mei.ui.screen.meilo.MeiloLyricsSource
import com.ljyh.mei.ui.screen.meilo.MeiloSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trackMetadataStore by preferencesDataStore(name = "meilo_track_metadata")

/** INHERIT follows Settings; LOCAL means embedded/sidecar only and never performs a remote lookup. */
enum class TrackSourceSelection { INHERIT, AUTO, LOCAL, ITUNES, MUSICBRAINZ, LRCLIB }

data class TrackSourceOverride(
    val cover: TrackSourceSelection = TrackSourceSelection.INHERIT,
    val lyrics: TrackSourceSelection = TrackSourceSelection.INHERIT,
)

data class LocalSyncState(
    val isRunning: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val updated: Int = 0,
    val message: String? = null,
)

/** Applies metadata and artwork to Song and lyrics to the existing shared lyric cache. */
@Singleton
class LocalMetadataSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    private val settingsStore = MeiloSettingsStore(context)
    private val engine by lazy { MetadataEngine.createDefault() }
    private val _state = MutableStateFlow(LocalSyncState())
    val state: StateFlow<LocalSyncState> = _state.asStateFlow()

    fun overrides(songId: String) = context.trackMetadataStore.data.map { preferences ->
        TrackSourceOverride(
            cover = preferences[coverKey(songId)].toSelection(),
            lyrics = preferences[lyricsKey(songId)].toSelection(),
        )
    }

    suspend fun setOverrides(songId: String, override: TrackSourceOverride) {
        context.trackMetadataStore.edit { preferences ->
            preferences[coverKey(songId)] = override.cover.name
            preferences[lyricsKey(songId)] = override.lyrics.name
        }
    }

    suspend fun search(song: Song, override: TrackSourceOverride? = null): MetadataSearchResult {
        val storedSong = database.songDao().getSong(song.id).firstOrNull() ?: song
        val settings = settingsStore.settings.first()
        val sources = sourcesFor(storedSong, settingsStore.settings.first().autoAnalysisSyncEnabled,
            settings.coverSource.name, settings.lyricsSource.name,
            override ?: overrides(song.id).first(), onlyMissing = false)
        if (sources.isEmpty()) return MetadataSearchResult(emptyList())
        return engine.search(
            query = storedSong.toMetadataQuery(),
            preference = SourcePreference(global = sources),
        )
    }

    /** Automatic sync fills missing fields; manual selection may deliberately replace them. */
    suspend fun syncSong(
        song: Song,
        candidate: MetadataCandidate? = null,
        onlyMissing: Boolean = false,
    ): Song {
        val storedSong = database.songDao().getSong(song.id).firstOrNull() ?: song
        val existingLyric = database.cachedLyricDao().get(storedSong.id).first()
        val candidates = if (candidate == null) {
            val settings = settingsStore.settings.first()
            val override = overrides(song.id).first()
            val sources = sourcesFor(storedSong, settings.autoAnalysisSyncEnabled,
                settings.coverSource.name, settings.lyricsSource.name, override, onlyMissing)
            if (sources.isEmpty()) return storedSong
            engine.search(storedSong.toMetadataQuery(), preference = SourcePreference(global = sources)).candidates
        } else listOf(candidate)

        val metadata = candidate?.takeIf { it.source != MetadataSource.LRCLIB } ?: candidates.firstOrNull {
            it.source != MetadataSource.LRCLIB && it.score >= MIN_METADATA_SCORE
        }
        val lyric = candidate?.takeIf { it.lyrics?.isEmpty == false }
            ?: candidates.firstOrNull { it.lyrics?.isEmpty == false && it.score >= MIN_LYRIC_SCORE }

        var updated = storedSong
        if (metadata != null && metadata.source != MetadataSource.LRCLIB) {
            val previous = updated
            val artwork = metadata.artworkUrl?.takeIf(String::isNotBlank)
            updated = updated.copy(
                title = metadata.title.ifBlank { updated.title },
                artist = metadata.artists.filter(String::isNotBlank).ifEmpty { updated.artist },
                album = metadata.album?.takeIf(String::isNotBlank) ?: updated.album,
                cover = if (!onlyMissing || updated.cover.isBlank()) artwork ?: updated.cover else updated.cover,
                updatedAt = System.currentTimeMillis(),
            )
            if (updated != previous) database.songDao().updateSong(updated)
        }
        val lyrics = lyric?.lyrics
        if (lyrics != null && (!onlyMissing || existingLyric == null)) {
            val payload = lyricCachePayload(lyrics)
            if (payload != null) {
                database.cachedLyricDao().insert(
                    CachedLyric(
                        songId = storedSong.id,
                        content = payload.content,
                        translation = null,
                        isVerbatim = false,
                        isPureMusic = false,
                        sourceName = lyric.source.name,
                        parserType = payload.parserType,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return updated
    }

    suspend fun syncLibrary(force: Boolean = false) {
        if (_state.value.isRunning) return
        val settings = settingsStore.settings.first()
        if (!force && !settings.autoAnalysisSyncEnabled) return
        val songs = database.songDao().getLocalSongs().first()
        _state.value = LocalSyncState(isRunning = true, total = songs.size)
        try {
            songs.forEachIndexed { index, song ->
                val updated = syncSong(song, onlyMissing = !force)
                _state.value = _state.value.copy(
                    current = index + 1,
                    updated = _state.value.updated + if (updated != song) 1 else 0,
                )
            }
            _state.value = _state.value.copy(isRunning = false, message = "歌曲信息同步完成")
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(isRunning = false, message = "同步已停止")
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.copy(isRunning = false, message = error.message ?: "同步失败")
        }
    }

    private suspend fun sourcesFor(
        song: Song,
        @Suppress("UNUSED_PARAMETER") autoEnabled: Boolean,
        coverSetting: String,
        lyricsSetting: String,
        override: TrackSourceOverride,
        onlyMissing: Boolean,
        hasLocalLyrics: Boolean = false,
    ): List<MetadataSource> = buildList {
        val cover = if (override.cover == TrackSourceSelection.INHERIT) coverSetting else override.cover.name
        val lyrics = if (override.lyrics == TrackSourceSelection.INHERIT) lyricsSetting else override.lyrics.name
        val hasLyrics = hasLocalLyrics || database.cachedLyricDao().get(song.id).first() != null
        if ((!onlyMissing || song.cover.isBlank()) && cover != TrackSourceSelection.LOCAL.name) {
            when (cover) {
                TrackSourceSelection.ITUNES.name, MeiloCoverSource.ITUNES.name -> add(MetadataSource.ITUNES)
                TrackSourceSelection.MUSICBRAINZ.name, MeiloCoverSource.MUSICBRAINZ.name -> add(MetadataSource.MUSICBRAINZ)
                else -> {
                    add(MetadataSource.ITUNES)
                    add(MetadataSource.MUSICBRAINZ)
                }
            }
        }
        if ((!onlyMissing || !hasLyrics) && lyrics != TrackSourceSelection.LOCAL.name &&
            lyrics != TrackSourceSelection.ITUNES.name && lyrics != TrackSourceSelection.MUSICBRAINZ.name
        ) add(MetadataSource.LRCLIB)
    }.distinct()

    private fun coverKey(songId: String) = stringPreferencesKey("cover_source:$songId")
    private fun lyricsKey(songId: String) = stringPreferencesKey("lyrics_source:$songId")

    private companion object {
        const val MIN_METADATA_SCORE = 0.55
        const val MIN_LYRIC_SCORE = 0.45
    }
}

private fun Song.toMetadataQuery() = MetadataQuery(
    title = metadataTitleForQuery(this),
    artists = artist,
    album = album.takeIf(String::isNotBlank),
    durationMs = duration.takeIf { it > 0 },
)

private fun String?.toSelection(): TrackSourceSelection =
    this?.let { saved -> TrackSourceSelection.entries.firstOrNull { it.name == saved } }
        ?: TrackSourceSelection.INHERIT
