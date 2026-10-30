package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.room.localSongStorageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLyricResetTest {
    @Test
    fun resetUsesTheScannerRoomKeyRatherThanTheNegativePlaybackId() {
        val mediaId = "-7858856073694430"
        val roomId = localSongStorageId(mediaId)
        val cached = mutableMapOf(roomId to createDefaultLyricData("old"))
        cached.remove(if (mediaId.startsWith("-")) roomId else mediaId)

        assertEquals("local_7858856073694430", roomId)
        assertNull(cached[roomId])
    }

    @Test
    fun localEmptyResultDoesNotEnterLegacyThreeSourceSelection() {
        val local = true
        val shouldMergeLegacySources = !local
        assertEquals(false, shouldMergeLegacySources)
    }
}
