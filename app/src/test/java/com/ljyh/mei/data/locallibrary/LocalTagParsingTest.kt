package com.ljyh.mei.data.locallibrary

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTagParsingTest {
    @Test
    fun collaborationTagsBecomeSeparateArtists() {
        assertEquals(listOf("A", "B"), parseLocalArtists("A / B"))
        assertEquals(listOf("A", "B"), parseLocalArtists("A feat. B"))
        assertEquals(listOf("A", "B"), parseLocalArtists("A&B"))
        assertEquals(listOf("Simon & Garfunkel"), parseLocalArtists("Simon & Garfunkel"))
    }
}
