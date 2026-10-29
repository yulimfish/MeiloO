package com.ljyh.mei.data.localsync

import com.ljyh.mei.data.localmetadata.MetadataLyrics
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.utils.lyric.LRCParser
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricSemanticsTest {
    @Test
    fun embeddedTitleKeepsDotsAndOnlyFilenameFallbackLosesAudioSuffix() {
        assertEquals("Mr. Brightside", metadataTitleForQuery(song("Mr. Brightside", artist = "The Killers")))
        assertEquals("Mr. Brightside", metadataTitleForQuery(song("Mr. Brightside.mp3")))
        assertEquals("01. Intro", metadataTitleForQuery(song("01. Intro.flac")))
    }

    @Test
    fun plainLyricsRemainPlainTextWithoutFabricatedZeroTimestamps() {
        val source = "First line\n\nSecond line"
        val payload = lyricCachePayload(MetadataLyrics(plain = source))
        assertEquals("PLAIN", payload?.parserType)
        assertEquals(source, payload?.content)

        val parsed = parsePlainLyrics(requireNotNull(payload).content)
        assertEquals(listOf("First line", "Second line"), parsed.lines.map { (it as SyncedLine).content })
        assertEquals(listOf(0, 3_600_000), parsed.lines.map(ISyncedLine::start))
        assertTrue(parsed.lines[1].start > parsed.lines[0].start)
        assertFalse(payload.content.contains("[00:00.00]"))
    }

    @Test
    fun synchronizedLyricsTakePrecedenceOverPlainText() {
        val synced = "[00:01.00]First line"
        val payload = lyricCachePayload(MetadataLyrics(plain = "plain", synced = synced))
        assertEquals("LRC", payload?.parserType)
        assertEquals(synced, payload?.content)
        assertEquals("First line", (LRCParser.parse(requireNotNull(payload).content, null).lines.single() as SyncedLine).content)
    }

    @Test
    fun blankLyricsProduceNoCacheEntry() {
        assertNull(lyricCachePayload(MetadataLyrics(plain = " \n", synced = null)))
    }

    @Test
    fun cacheAcceptanceFollowsEffectiveLyricSource() {
        for (source in listOf("Local", "Empty")) {
            assertTrue(acceptsCachedLyricSource(source, EffectiveLyricSource.LOCAL))
            assertFalse(acceptsCachedLyricSource(source, EffectiveLyricSource.LRCLIB))
            assertTrue(acceptsCachedLyricSource(source, EffectiveLyricSource.AUTO))
        }
        assertFalse(acceptsCachedLyricSource("LRCLIB", EffectiveLyricSource.LOCAL))
        assertTrue(acceptsCachedLyricSource("LRCLIB", EffectiveLyricSource.LRCLIB))
        assertTrue(acceptsCachedLyricSource("LRCLIB", EffectiveLyricSource.AUTO))
        assertFalse(acceptsCachedLyricSource("Empty", EffectiveLyricSource.LRCLIB))
    }

    private fun song(title: String, artist: String = "", album: String = "") = Song(
        id = "local_1",
        title = title,
        artist = listOf(artist).filter(String::isNotBlank),
        album = album,
        cover = "",
        duration = 1_000,
        path = "content://provider/song",
    )
}
