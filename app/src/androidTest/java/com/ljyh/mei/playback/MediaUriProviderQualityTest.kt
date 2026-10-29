package com.ljyh.mei.playback

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.dao.SongDao
import com.ljyh.mei.di.repository.SongRepository
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side verification that MeiloO never resolves an online audio source. */
@RunWith(AndroidJUnit4::class)
class MediaUriProviderQualityTest {
    @Test
    fun resolvesOnlyLocalFileAndRejectsOnlineSchemes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(context.cacheDir, "local-source.wav").apply { writeBytes(ByteArray(44)) }
        val song = Song(
            id = "local_1", title = "Local", artist = emptyList(), album = "", cover = "",
            duration = 1, path = audio.absolutePath, sourceType = SourceType.LOCAL,
        )
        val provider = MediaUriProvider(SongRepository(fakeSongDao(song)), context)

        assertEquals(Uri.fromFile(audio), provider.resolveMediaUri("local_1", "lossless"))
        assertTrue(provider.getAvailableMusicQualities("local_1", true).isEmpty())
        assertTrue(provider.getAvailableDownloadQualities("local_1").isEmpty())

        listOf("https://example.test/song.mp3", "http://example.test/song.mp3", "rtsp://example.test/live").forEach {
            assertEquals(null, localMediaPath(song.copy(path = it)))
        }
        assertEquals(null, localMediaPath(song.copy(sourceType = SourceType.STREAM)))
    }

    private fun fakeSongDao(song: Song): SongDao = Proxy.newProxyInstance(
        SongDao::class.java.classLoader,
        arrayOf(SongDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getSong" -> flowOf(song.takeIf { it.id == args?.firstOrNull() })
            "getById" -> song.takeIf { it.id == args?.firstOrNull() }
            "getByPath" -> song.takeIf { it.path == args?.firstOrNull() }
            else -> defaultValue(method.returnType)
        }
    } as SongDao

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        Flow::class.java -> flowOf(null)
        else -> null
    }
}
