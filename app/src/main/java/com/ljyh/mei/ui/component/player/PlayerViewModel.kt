package com.ljyh.mei.ui.component.player

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.datastore.dataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ljyh.mei.AppContext
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.DownloadPathKey
import com.ljyh.mei.constants.DownloadQualityKey
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.Lyric
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.Tracks
import com.ljyh.mei.data.model.UserPlaylist
import com.ljyh.mei.data.model.api.CreatePlaylistResult
import com.ljyh.mei.data.model.api.Intelligence
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.room.QQSong
import com.ljyh.mei.data.model.weapi.Radio
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.network.neteaseSessionFingerprint
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.data.repository.UserRepository
import com.ljyh.mei.di.repository.LocalPlaylistRepository
import com.ljyh.mei.di.repository.observeOwnedPlaylists
import com.ljyh.mei.di.repository.toLocalPlaylist
import com.ljyh.mei.di.repository.ColorRepository
import com.ljyh.mei.di.repository.LikeRepository
import com.ljyh.mei.di.repository.QQSongRepository
import com.ljyh.mei.playback.MediaUriProvider
import com.ljyh.mei.data.localsync.LocalMetadataSync
import com.ljyh.mei.data.localsync.TrackSourceOverride
import com.ljyh.mei.data.localmetadata.MetadataCandidate
import com.ljyh.mei.data.localmetadata.MetadataQuery
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.localSongStorageId
import com.ljyh.mei.ui.model.LyricCandidate
import com.ljyh.mei.ui.model.LyricData
import com.ljyh.mei.ui.model.MoreAction
import com.ljyh.mei.ui.model.SortOrder
import com.ljyh.mei.ui.model.resolveMoreActionOrder
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import com.ljyh.mei.utils.lyric.LyricManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: PlayerRepository,
    private val qqSongRepository: QQSongRepository,
    private val userRepository: UserRepository,
    private val localPlaylistRepository: LocalPlaylistRepository,
    private val playlistRepository: PlaylistRepository,
    private val likeRepository: LikeRepository,
    private val colorRepository: ColorRepository,
    val lyricManager: LyricManager,
    private val mediaUriProvider: MediaUriProvider,
    private val collections: LibraryCollectionsRepository,
    private val localMetadataSync: LocalMetadataSync,
    private val database: AppDatabase,
) : ViewModel() {
    val searchResult: StateFlow<Resource<SearchResult>> = lyricManager.qqSearchResult
    val lyric: StateFlow<LyricData> = lyricManager.lyricData
    val lyricCandidates: StateFlow<List<LyricCandidate>> = lyricManager.lyricCandidates
    val lyricOffsetMs: StateFlow<Int> = lyricManager.lyricOffsetMs
    val songLikes = repository.songLikes
    val songLikeGeneration = repository.songLikeGeneration
    val songLikeAccount = collections.accounts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = null,
    )

    suspend fun ensureSongLikes(ids: Set<Long>, expectedSession: String): Resource<Unit> =
        repository.ensureSongLikes(ids, expectedSession)

    private val _like = MutableStateFlow<Resource<Boolean>>(Resource.Loading)
    val like: StateFlow<Resource<Boolean>> = _like
    private var selectedLikeId: String? = null
    private var likeSelectionGeneration = 0L
    private var likeStatusJob: Job? = null

    init {
        viewModelScope.launch {
            repository.songLikes.collect { likes ->
                _like.value = likes[selectedLikeId]?.let { Resource.Success(it) } ?: Resource.Loading
            }
        }
    }

    private var playlistChoicesJob: Job? = null

    private val _createPlaylist = MutableStateFlow<Resource<CreatePlaylistResult>>(Resource.Loading)
    val createPlaylist: StateFlow<Resource<CreatePlaylistResult>> = _createPlaylist


    private val _intelligenceList = MutableStateFlow<Resource<Intelligence>>(Resource.Loading)
    val intelligenceList: StateFlow<Resource<Intelligence>> = _intelligenceList


    private val _songDetail = MutableStateFlow<Resource<Tracks>>(Resource.Loading)
    val songDetail: StateFlow<Resource<Tracks>> = _songDetail

    private var intelligenceJob: Job? = null


    var mediaMetadata: MediaMetadata? = null

    suspend fun getAvailableDownloadQualities(mediaId: Long): List<MusicQuality> =
        withContext(Dispatchers.IO) {
            mediaUriProvider.getAvailableDownloadQualities(mediaId.toString())
        }

    val localPlaylists: StateFlow<List<Playlist>> = localPlaylistRepository.getAllPlaylist()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val myPlaylists: StateFlow<List<Playlist>> = observeOwnedPlaylists(
        localPlaylistRepository.getAllPlaylist(), collections.accounts,
    )
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun getLike(id: Long) {
        if (mediaMetadata?.isLocal == true || id < 0) {
            _like.value = Resource.Success(false)
            return
        }
        val expectedSession = neteaseSessionFingerprint(AppContext.instance.dataStore[CookieKey].orEmpty())
        val key = id.toString()
        selectedLikeId = key
        val generation = ++likeSelectionGeneration
        _like.value = repository.songLikes.value[key]?.let { Resource.Success(it) } ?: Resource.Loading
        likeStatusJob?.cancel()
        likeStatusJob = viewModelScope.launch {
            val result = repository.checkSongLike(id, expectedSession)
            if (selectedLikeId == key && likeSelectionGeneration == generation) {
                _like.value = repository.songLikes.value[key]?.let { Resource.Success(it) } ?: result
            }
        }
    }

    fun like(id: String) {
        if (mediaMetadata?.isLocal == true || (id.toLongOrNull() ?: 0L) < 0L) return
        val expectedSession = neteaseSessionFingerprint(AppContext.instance.dataStore[CookieKey].orEmpty())
        viewModelScope.launch {
            val result = repository.toggleLike(id, expectedSession)
            if (result is Resource.Error) {
                Timber.tag("PlayerViewModel").w("Unable to toggle song like: %s", result.message)
            }
        }
    }


    private val _qqSong = MutableStateFlow<QQSong?>(null)
    val qqSong: StateFlow<QQSong?> = _qqSong

    fun searchQQSong(keyword: String) {
        lyricManager.searchQQSong(keyword)
    }

    fun selectQQSong(
        metadata: MediaMetadata,
        song: SearchResult.Request.Data.Body.ItemSong,
    ) {
        lyricManager.selectQQSongForLyric(metadata, song)
    }

    private val _localMetadataCandidates = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val localMetadataCandidates = _localMetadataCandidates.asStateFlow()
    private val _localMetadataBusy = MutableStateFlow(false)
    val localMetadataBusy = _localMetadataBusy.asStateFlow()
    private val _localMetadataMessage = MutableStateFlow<String?>(null)
    val localMetadataMessage = _localMetadataMessage.asStateFlow()

    private fun storedLocalSongId(mediaId: String): String = localSongStorageId(mediaId)

    fun trackSourceOverrides(songId: String) = localMetadataSync.overrides(storedLocalSongId(songId))

    fun saveTrackSourceOverrides(songId: String, override: TrackSourceOverride) {
        viewModelScope.launch { localMetadataSync.setOverrides(storedLocalSongId(songId), override) }
    }

    fun searchLocalMetadata(metadata: MediaMetadata, queryText: String? = null) {
        if (!metadata.isLocal) return
        viewModelScope.launch {
            _localMetadataBusy.value = true
            _localMetadataMessage.value = null
            try {
                val song = database.songDao().getSong(storedLocalSongId(metadata.id.toString())).firstOrNull()
                if (song == null) {
                    _localMetadataMessage.value = "找不到本地歌曲记录"
                } else {
                    val customQuery = editedLocalMetadataQuery(song, metadata, queryText)
                    val result = localMetadataSync.search(song, customQuery = customQuery)
                    _localMetadataCandidates.value = result.candidates
                    _localMetadataMessage.value = when {
                        result.candidates.isNotEmpty() -> null
                        result.failures.isNotEmpty() -> result.failures.joinToString("\n") {
                            "${it.source}: ${it.message}"
                        }
                        else -> "未找到符合的信息来源，请检查全局或单曲来源设置"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _localMetadataMessage.value = error.message ?: "查询失败"
            } finally {
                _localMetadataBusy.value = false
            }
        }
    }

    fun applyLocalMetadata(metadata: MediaMetadata, candidate: MetadataCandidate) {
        viewModelScope.launch {
            _localMetadataBusy.value = true
            try {
                val song = database.songDao().getSong(storedLocalSongId(metadata.id.toString())).firstOrNull() ?: return@launch
                localMetadataSync.syncSong(song, candidate = candidate)
                lyricManager.loadLyrics(
                    metadata = metadata,
                    forceReload = true,
                    allowMetadataRefresh = false,
                )
                _localMetadataMessage.value = "已应用 ${candidate.source} 的信息"
            } finally {
                _localMetadataBusy.value = false
            }
        }
    }

    fun selectLyricCandidate(metadata: MediaMetadata, candidate: LyricCandidate) {
        lyricManager.selectLyricCandidate(metadata, candidate)
    }

    fun adjustLyricOffset(metadata: MediaMetadata, deltaMs: Int) {
        lyricManager.adjustLyricOffset(metadata, deltaMs)
    }

    fun resetLyricOffset(metadata: MediaMetadata) {
        lyricManager.resetLyricOffset(metadata)
    }

    fun resetLyricSource(metadata: MediaMetadata) {
        lyricManager.resetLyricSource(metadata)
    }

    fun insertSong(song: QQSong) {
        viewModelScope.launch {
            qqSongRepository.insertSong(song)
        }
    }

    fun deleteSongById(id: String) {
        val metadata = mediaMetadata ?: return
        if (metadata.id.toString() == id) lyricManager.resetLyricSource(metadata)
    }

    suspend fun getQQSongId(metadataId: Long): String? {
        return qqSongRepository.getQQSong(metadataId.toString()).firstOrNull()?.qid
    }



    fun refreshPlaylistChoices() {
        if (mediaMetadata?.isLocal == true) return
        playlistChoicesJob?.cancel()
        playlistChoicesJob = viewModelScope.launch {
            collections.accounts.collectLatest { account ->
                if (account == null) return@collectLatest
                try {
                    val playlists = collections.allPlaylists(account)
                    if (collections.accounts.first() == account) {
                        localPlaylistRepository.mergeRemotePlaylists(playlists.map { it.toLocalPlaylist() })
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Unable to refresh playlist choices")
                }
            }
        }
    }

    fun createPlaylist(
        name: String,
        privacy: Boolean = false, // 0 普通歌单, 10 隐私歌单
        type: String = "NORMAL" // 默认 NORMAL, VIDEO 视频歌单, SHARED 共享歌单
    ) {
        viewModelScope.launch {
            _createPlaylist.value = Resource.Loading
            _createPlaylist.value = playlistRepository.createPlaylist(name, privacy, type)
        }
    }

    fun intelligenceList(id: String, playlistId: String, startSongId: String) {
        startIntelligenceMode(id, playlistId, startSongId)
    }

    fun startIntelligenceMode(id: String, playlistId: String, startSongId: String) {
        intelligenceJob?.cancel()
        intelligenceJob = viewModelScope.launch {
            _songDetail.value = Resource.Loading
            _intelligenceList.value = Resource.Loading

            // Fetch the seed song before publishing the list result so the UI can build one
            // complete queue instead of reacting to two independently completing requests.
            val songDetailResult = repository.getSongDetail(startSongId)
            currentCoroutineContext().ensureActive()
            _songDetail.value = songDetailResult

            val intelligenceListResult =
                repository.getIntelligenceList(id, playlistId, startSongId)
            currentCoroutineContext().ensureActive()
            _intelligenceList.value = intelligenceListResult
        }
    }

    fun consumeIntelligencePlayback() {
        _intelligenceList.value = Resource.Loading
        _songDetail.value = Resource.Loading
    }

    fun getSongDetail(id:String){
        viewModelScope.launch {
            _songDetail.value = Resource.Loading
            val result = repository.getSongDetail(id)
            Timber.tag("songDetail").d("getSongDetail: $result")
            _songDetail.value = result
        }
    }

    fun downloadSong(metadata: MediaMetadata, context: android.content.Context, requestedQuality: MusicQuality? = null) {
        // Local files are already on device; MeiloO has no online-audio download path.
        android.widget.Toast.makeText(context, "本地歌曲无需下载", android.widget.Toast.LENGTH_SHORT).show()
    }

    private val moreSortKey = stringPreferencesKey("moreActions.sort")
    private val moreOrderKey = stringPreferencesKey("moreActions.order")
    val moreSortOrder = AppContext.instance.dataStore.data.map { preferences ->
        runCatching { SortOrder.valueOf(preferences[moreSortKey].orEmpty()) }.getOrDefault(SortOrder.FREQUENCY)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.FREQUENCY)

    val sortedMoreActions = AppContext.instance.dataStore.data.map { preferences ->
        val order = runCatching { SortOrder.valueOf(preferences[moreSortKey].orEmpty()) }.getOrDefault(SortOrder.FREQUENCY)
        resolveMoreActionOrder(order, preferences[moreOrderKey].orEmpty().split(','))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), resolveMoreActionOrder(SortOrder.FREQUENCY, emptyList()))

    fun setMoreSortOrder(order: SortOrder) {
        viewModelScope.launch { AppContext.instance.dataStore.edit { it[moreSortKey] = order.name } }
    }

    fun saveMoreActionOrder(actions: List<MoreAction>) {
        val ids = resolveMoreActionOrder(SortOrder.CUSTOM, actions.map { it.id }).joinToString(",") { it.id }
        viewModelScope.launch {
            AppContext.instance.dataStore.edit {
                it[moreOrderKey] = ids
                it[moreSortKey] = SortOrder.CUSTOM.name
            }
        }
    }


}

internal fun defaultLocalMetadataSearchText(metadata: MediaMetadata): String =
    listOf(metadata.title, metadata.artists.joinToString(" ") { it.name })
        .filter(String::isNotBlank)
        .joinToString(" ")

/** The sheet's unchanged display text is not an edited title; preserve the structured query. */
internal fun editedLocalMetadataQuery(
    song: Song,
    metadata: MediaMetadata,
    queryText: String?,
): MetadataQuery? {
    val edited = queryText?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (edited == defaultLocalMetadataSearchText(metadata)) return null
    return MetadataQuery(
        title = edited,
        artists = song.artist,
        album = song.album.takeIf(String::isNotBlank),
        durationMs = song.duration.takeIf { it > 0 },
    )
}
