package com.ljyh.mei.ui.screen.meilo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Global artwork policy used by the local metadata engine integration. */
enum class MeiloCoverSource(val label: String) {
    AUTO("自动"),
    LOCAL("内嵌与本地"),
    ITUNES("iTunes"),
    MUSICBRAINZ("MusicBrainz"),
}

/** Global lyric policy used by the local metadata engine integration. */
enum class MeiloLyricsSource(val label: String) {
    AUTO("自动"),
    LOCAL("内嵌与本地"),
    LRCLIB("LRCLIB"),
}

data class MeiloSettings(
    val autoAnalysisSyncEnabled: Boolean = false,
    val coverSource: MeiloCoverSource = MeiloCoverSource.AUTO,
    val lyricsSource: MeiloLyricsSource = MeiloLyricsSource.AUTO,
    val songSort: MeiloSongSort = MeiloSongSort.TITLE,
    val songSortDescending: Boolean = false,
    val customSongOrder: List<String> = emptyList(),
)

/**
 * Local-only preferences for the MeiloO vertical slice.
 *
 * The synchronization engine should collect [settings] and map [MeiloSettings.coverSource] and
 * [MeiloSettings.lyricsSource] to its source preference. This store deliberately performs no
 * network, metadata, scanning, or playback work itself.
 */
class MeiloSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.meiloLocalSettingsDataStore

    val settings: Flow<MeiloSettings> = dataStore.data.map { preferences ->
        MeiloSettings(
            autoAnalysisSyncEnabled = preferences[MeiloSettingKeys.AutoAnalysisSync] ?: false,
            coverSource = preferences[MeiloSettingKeys.CoverSource].meiloEnumOrDefault(MeiloCoverSource.AUTO),
            lyricsSource = preferences[MeiloSettingKeys.LyricsSource].meiloEnumOrDefault(MeiloLyricsSource.AUTO),
            songSort = preferences[MeiloSettingKeys.SongSort].meiloEnumOrDefault(MeiloSongSort.TITLE),
            songSortDescending = preferences[MeiloSettingKeys.SongSortDescending] ?: false,
            customSongOrder = decodeMeiloSongOrder(preferences[MeiloSettingKeys.CustomSongOrder].orEmpty()),
        )
    }.distinctUntilChanged()

    suspend fun setAutoAnalysisSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[MeiloSettingKeys.AutoAnalysisSync] = enabled }
    }

    suspend fun setCoverSource(source: MeiloCoverSource) {
        dataStore.edit { it[MeiloSettingKeys.CoverSource] = source.name }
    }

    suspend fun setLyricsSource(source: MeiloLyricsSource) {
        dataStore.edit { it[MeiloSettingKeys.LyricsSource] = source.name }
    }

    suspend fun setSongSort(sort: MeiloSongSort) {
        dataStore.edit { it[MeiloSettingKeys.SongSort] = sort.name }
    }

    suspend fun setSongSortDescending(descending: Boolean) {
        dataStore.edit { it[MeiloSettingKeys.SongSortDescending] = descending }
    }

    suspend fun saveCustomSongOrder(songIds: List<String>) {
        val encoded = encodeMeiloSongOrder(songIds.distinct())
        dataStore.edit { it[MeiloSettingKeys.CustomSongOrder] = encoded }
    }
}

private object MeiloSettingKeys {
    val AutoAnalysisSync = booleanPreferencesKey("auto_analysis_sync_enabled")
    val CoverSource = stringPreferencesKey("global_cover_source")
    val LyricsSource = stringPreferencesKey("global_lyrics_source")
    val SongSort = stringPreferencesKey("song_sort")
    val SongSortDescending = booleanPreferencesKey("song_sort_descending")
    val CustomSongOrder = stringPreferencesKey("custom_song_order")
}

private val Context.meiloLocalSettingsDataStore by preferencesDataStore(name = "meilo_local_ui")

internal inline fun <reified T : Enum<T>> String?.meiloEnumOrDefault(default: T): T =
    this?.let { saved -> enumValues<T>().firstOrNull { it.name == saved } } ?: default

internal fun encodeMeiloSongOrder(songIds: List<String>): String = Json.encodeToString(songIds)

internal fun decodeMeiloSongOrder(encoded: String): List<String> {
    if (encoded.isBlank()) return emptyList()
    return runCatching { Json.decodeFromString<List<String>>(encoded) }
        .getOrElse { emptyList() }
        .distinct()
}
