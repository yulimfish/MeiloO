package com.ljyh.mei.data.locallibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanRulesTest {
    @Test
    fun audioRulesAcceptEveryRequestedExtensionCaseInsensitively() {
        val expected = setOf("mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "alac", "ape", "dsf", "dff")
        assertEquals(expected, SUPPORTED_LOCAL_AUDIO_EXTENSIONS)
        expected.forEach { extension ->
            assertTrue(isSupportedLocalAudio("folder/Track.${extension.uppercase()}"))
            assertEquals(extension, localAudioExtension("Track.$extension"))
        }
        assertFalse(isSupportedLocalAudio("Track.mp3.lrc"))
        assertFalse(isSupportedLocalAudio("Track.wma"))
        assertFalse(isSupportedLocalAudio(null))
        assertNull(localAudioExtension("README"))
    }

    @Test
    fun documentUriProducesStableNumericLocalIdentity() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.mp3"
        val id = stableLocalSongId(uri)
        assertEquals(id, stableLocalSongId(uri))
        assertTrue(id.matches(Regex("local_[1-9][0-9]*")))
        assertTrue(id.removePrefix("local_").toLong() > 0)
        assertNotEquals(id, stableLocalSongId("$uri?other=1"))
    }

    @Test
    fun sidecarMustHaveTheSameBaseNameAndSupportsCaseInsensitiveProviders() {
        val siblings = listOf("cover.jpg", "Track.LRC", "Track.txt")
        assertEquals("Track.LRC", matchingSidecarLyricName("Track.flac", siblings))
        assertEquals("Track.LRC", matchingSidecarLyricName("Track.mp3", siblings))
        assertNull(matchingSidecarLyricName("Other.flac", siblings))
        assertNull(matchingSidecarLyricName("Track.lrc", siblings))
        assertNull(matchingSidecarLyricName(".flac", listOf(".lrc")))
    }
}
