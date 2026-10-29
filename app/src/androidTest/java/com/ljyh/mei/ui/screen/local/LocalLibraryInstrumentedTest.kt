package com.ljyh.mei.ui.screen.local

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.net.toUri
import com.ljyh.mei.data.locallibrary.LocalLibraryFolderRepository
import com.ljyh.mei.data.locallibrary.stableLocalSongId
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SAF trees and isolated Room storage; no MediaStore or account endpoint is used. */
@RunWith(AndroidJUnit4::class)
class LocalLibraryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun selectedSafTreeScansIdempotentlyAndCanBeRemoved() = runBlocking {
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "saf-library-$token").apply { mkdirs() }
        val audio = createPcmWave()
        val fixture = File(root, "fixture.wav").apply { writeBytes(audio) }
        File(root, "fixture.lrc").writeText("[00:00.00]SAF lyric")
        val testFiles = File(context.cacheDir, "saf-storage-$token").apply { mkdirs() }
        val scannerContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = testFiles
            override fun getContentResolver() = object : android.content.ContentResolver(context) {
                override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) = Unit
                override fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) = Unit
            }
        }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val tree = DocumentFile.fromTreeUri(scannerContext, Uri.fromFile(root))
                ?: error("External storage SAF provider unavailable")
            val folderRepository = LocalLibraryFolderRepository(scannerContext)
            folderRepository.addFolder(tree.uri)
            val scanner = LocalLibraryScanner(scannerContext, database)
            val progress = mutableListOf<LocalScanState>()
            val firstState = scanner.scan(progress::add)
            val fixtureId = stableLocalSongId(fixture.toUri().toString())
            val first = checkNotNull(database.songDao().getById(fixtureId))

            assertEquals("fixture", first.title)
            assertEquals(SourceType.LOCAL, first.sourceType)
            assertEquals("wav", first.fileFormat)
            assertEquals(audio.size.toLong(), first.fileSize)
            assertTrue(first.duration in 1_490L..1_510L)
            assertEquals("SAF lyric", database.cachedLyricDao().get(fixtureId).first()?.content)
            assertEquals(firstState.total, firstState.scanned)
            assertFalse(firstState.isScanning)
            assertTrue(progress.zipWithNext().all { (a, b) -> a.scanned <= b.scanned })

            scanner.scan {}
            assertEquals(1, database.songDao().getLocalSongs().first().size)
            File(root, "fixture.lrc").delete()
            scanner.scan {}
            assertEquals(null, database.cachedLyricDao().get(fixtureId).first())

            folderRepository.removeFolder(tree.uri)
            scanner.scan {}
            assertTrue(database.songDao().getLocalSongs().first().isEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
            testFiles.deleteRecursively()
        }
    }

    private fun createPcmWave(): ByteArray {
        val sampleRate = 8_000
        val sampleCount = sampleRate * 3 / 2
        val dataSize = sampleCount * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
            repeat(sampleCount) { index ->
                putShort((sin(2 * PI * 440 * index / sampleRate) * 2_000).toInt().toShort())
            }
        }.array()
    }
}
