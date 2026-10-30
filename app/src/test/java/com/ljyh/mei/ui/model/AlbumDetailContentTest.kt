package com.ljyh.mei.ui.model

import com.google.gson.Gson
import com.ljyh.mei.data.model.AlbumDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDetailContentTest {
    private val gson = Gson()

    @Test
    fun sparseAlbumDetailUsesSafeDisplayDefaults() {
        val detail = gson.fromJson(
            """{"code":200,"album":{"id":3000000001},"songs":[]}""",
            AlbumDetail::class.java,
        )

        val content = detail.toUiPlaylist(isSubscribed = false)

        assertEquals(3_000_000_001L, content.id)
        assertEquals("", content.title)
        assertEquals("", content.cover)
        assertEquals("", content.creatorName)
        assertTrue(content.tracks.isEmpty())
    }

    @Test
    fun missingSongMetadataFallsBackToTheContainingAlbum() {
        val detail = gson.fromJson(
            """{"code":200,"album":{"id":42,"name":"Album","picUrl":"cover","artists":[]},"songs":[{"id":1,"name":null,"dt":180000,"ar":null,"al":null}]}""",
            AlbumDetail::class.java,
        )

        val track = detail.toUiPlaylist(isSubscribed = false).tracks.single()

        assertEquals("", track.title)
        assertEquals("cover", track.coverUrl)
        assertEquals(42L, track.album.id)
        assertEquals("Album", track.album.title)
        assertTrue(track.artists.isEmpty())
    }
}
