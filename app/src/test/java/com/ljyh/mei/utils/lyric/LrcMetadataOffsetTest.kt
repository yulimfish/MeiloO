package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.localsync.PLAIN_LYRIC_PARSER_TYPE
import com.ljyh.mei.data.model.room.CachedLyric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LrcMetadataOffsetTest {
    @Test
    fun metadataOffsetIsAppliedOnceInTheFileDirection() {
        val lyrics = LRCParser.parse("[offset:500]\n[00:01.00]First\n[00:03.00]Second", null)
        assertEquals(listOf(1_500, 3_500), lyrics.lines.map { it.start })
        assertEquals(500, parseLrcMetadataOffset("[offset:+500]"))
        assertEquals(-250, parseLrcMetadataOffset("[OFFSET:-250]"))
    }

    @Test
    fun plainLyricsRemainStaticAndIgnoreTimingAdjustment() {
        val data = CachedLyric(
            songId = "local_1", content = "First\nSecond", translation = null,
            isVerbatim = false, isPureMusic = false, sourceName = "LRCLIB",
            parserType = PLAIN_LYRIC_PARSER_TYPE, updatedAt = 0,
        ).toLyricData()!!
        assertEquals(true, data.isStatic)
        assertSame(data, data.withTimingOffset(500))
    }
}
