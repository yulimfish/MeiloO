package com.ljyh.mei.playback

import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUriProviderLocalPathTest {
    @Test
    fun absolutePathIsRecognizedAsAFileWithoutAndroidUri() {
        val path = "/storage/emulated/0/Music/local song.flac"

        val local = localMediaPath(song(path))

        assertEquals(LocalMediaScheme.FILE, local?.scheme)
        assertEquals(path, local?.value)
        assertEquals(false, local?.isUri)
    }

    @Test
    fun fileUriIsRecognizedCaseInsensitively() {
        val path = "FILE:///storage/emulated/0/Music/local%20song.flac"

        val local = localMediaPath(song(path))

        assertEquals(LocalMediaScheme.FILE, local?.scheme)
        assertEquals(path, local?.value)
        assertTrue(local?.isUri == true)
    }

    @Test
    fun safDocumentContentUriIsRecognized() {
        val path = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2Flocal%20song.flac"

        val local = localMediaPath(song(path))

        assertEquals(LocalMediaScheme.CONTENT, local?.scheme)
        assertEquals(path, local?.value)
        assertTrue(local?.isUri == true)
    }

    @Test
    fun everyOnlineSchemeIsRejected() {
        val paths = listOf(
            "http://example.test/song.mp3",
            "https://example.test/song.flac",
            "rtsp://example.test/song",
            "rtsps://example.test/song",
            "HTTP://example.test/song.mp3",
            "HTTPS://example.test/song.flac",
            "ftp://example.test/song.mp3",
            "//example.test/song.mp3",
        )

        for (path in paths) {
            assertNull(path, localMediaPath(song(path)))
        }
    }

    @Test
    fun nonPlaybackAndMalformedUrisAreRejected() {
        val paths = listOf(
            "android.resource://com.example/1",
            "asset:///song.mp3",
            "data:audio/mpeg;base64,AAAA",
            "file:relative/song.mp3",
            "content:/document/primary:song.mp3",
            "content:///document/primary:song.mp3",
            "relative/song.mp3",
            "",
            " ",
        )

        for (path in paths) {
            assertNull(path, localMediaPath(song(path)))
        }
    }

    @Test
    fun onlyExplicitlyLocalSongsCanProvidePlaybackPaths() {
        for (sourceType in listOf(SourceType.STREAM, SourceType.DOWNLOAD)) {
            assertNull(localMediaPath(song("/storage/emulated/0/Music/song.flac", sourceType)))
            assertNull(
                localMediaPath(
                    song("content://media/external/audio/media/1", sourceType),
                ),
            )
        }
        assertNull(localMediaPath(song(null)))
        assertFalse(localMediaPath(song("/storage/emulated/0/Music/song.flac"))?.isUri == true)
    }

    private fun song(path: String?, sourceType: SourceType = SourceType.LOCAL) = Song(
        id = "local_1",
        title = "Local song",
        artist = emptyList(),
        album = "",
        cover = "",
        duration = 1_000L,
        path = path,
        sourceType = sourceType,
    )
}
