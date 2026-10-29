package com.ljyh.mei.data.model.room

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSongIdentityTest {
    @Test
    fun negativeMediaIdRestoresScannerRoomKey() {
        assertEquals("local_7858856073694430", localSongStorageId("-7858856073694430"))
        assertEquals("local_1", localSongStorageId("local_1"))
        assertEquals("42", localSongStorageId("42"))
        assertEquals(Long.MIN_VALUE.toString(), localSongStorageId(Long.MIN_VALUE.toString()))
    }
}
