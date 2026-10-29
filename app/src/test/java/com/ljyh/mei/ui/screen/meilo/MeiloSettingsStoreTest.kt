package com.ljyh.mei.ui.screen.meilo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeiloSettingsStoreTest {
    @Test
    fun `custom song order survives JSON reserved characters`() {
        val order = listOf("local:1", "comma,id", "quote\"id", "line\nbreak", "本地/歌曲")
        assertEquals(order, decodeMeiloSongOrder(encodeMeiloSongOrder(order)))
    }

    @Test
    fun `invalid persisted order safely falls back to empty`() {
        assertTrue(decodeMeiloSongOrder("not-json").isEmpty())
        assertTrue(decodeMeiloSongOrder("").isEmpty())
    }

    @Test
    fun `unknown or missing enum preferences use defaults`() {
        assertEquals(MeiloCoverSource.AUTO, null.meiloEnumOrDefault(MeiloCoverSource.AUTO))
        assertEquals(MeiloCoverSource.AUTO, "REMOVED".meiloEnumOrDefault(MeiloCoverSource.AUTO))
        assertEquals(MeiloLyricsSource.LRCLIB, "LRCLIB".meiloEnumOrDefault(MeiloLyricsSource.AUTO))
    }
}
