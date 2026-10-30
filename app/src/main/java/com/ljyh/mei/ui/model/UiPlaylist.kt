package com.ljyh.mei.ui.model

import com.ljyh.mei.data.model.AlbumDetail
import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.toMediaMetadata

data class UiPlaylist(
    val id: Long,
    val title: String,
    val count: Int,
    val subscriberCount: Long,
    val cover: String,
    val coverList: List<String>, // 或者 List<String> 如果要支持拼贴画
    val creatorName: String,
    val isCreator: Boolean = false,
    val description: String? = null,
    val tracks: List<MediaMetadata>, // 假设所有的列表都转成了 MediaMetadata
    val trackCount: Int = tracks.size,
    val playCount: Long? = null, // 每日推荐可能没有播放量
    val isSubscribed: Boolean = false, // 收藏状态
)

internal fun AlbumDetail.toUiPlaylist(isSubscribed: Boolean): UiPlaylist {
    val responseAlbum = album
    // Gson can leave nested properties null even when the DTO declares them non-null.
    val albumName: String? = responseAlbum.name
    val albumCover: String? = responseAlbum.picUrl
    val albumArtists: List<AlbumDetail.Album.Artist?>? = responseAlbum.artists
    val responseSongs: List<AlbumDetail.Song?>? = songs
    val safeAlbumName = albumName.orEmpty()
    val safeAlbumCover = albumCover.orEmpty()
    return UiPlaylist(
        id = responseAlbum.id,
        title = safeAlbumName,
        count = responseAlbum.size,
        subscriberCount = -1,
        cover = safeAlbumCover,
        coverList = listOf(safeAlbumCover),
        creatorName = albumArtists.orEmpty().mapNotNull { artist ->
            val artistName: String? = artist?.name
            artistName
        }.joinToString(", "),
        isCreator = false,
        description = responseAlbum.description,
        tracks = responseSongs.orEmpty().mapNotNull { song ->
            song?.toMediaMetadata()?.copy(
                coverUrl = safeAlbumCover,
                album = MediaMetadata.Album(responseAlbum.id, safeAlbumName),
            )
        },
        playCount = -1,
        isSubscribed = isSubscribed,
    )
}
