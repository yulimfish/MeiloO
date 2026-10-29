package com.ljyh.mei.ui.screen.local

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.ljyh.mei.data.locallibrary.LocalLibraryFolder
import com.ljyh.mei.data.locallibrary.LocalLibraryFolderRepository
import com.ljyh.mei.data.locallibrary.localAudioExtension
import com.ljyh.mei.data.locallibrary.localDocumentExistence
import com.ljyh.mei.data.locallibrary.matchingSidecarLyricName
import com.ljyh.mei.data.locallibrary.parseLocalArtists
import com.ljyh.mei.data.locallibrary.shouldClearLocalLyricCache
import com.ljyh.mei.data.locallibrary.shouldRetainLocalDocument
import com.ljyh.mei.data.locallibrary.stableLocalSongId
import com.ljyh.mei.data.locallibrary.LocalDocumentExistence
import com.ljyh.mei.data.model.room.CachedLyric
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.localAudioPublicationMutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val MAX_LOCAL_DIRECTORY_DEPTH = 64
private const val MAX_LOCAL_LYRIC_BYTES = 2 * 1024 * 1024

data class LocalScanState(
    val isScanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
    val message: String? = null,
    val removed: Int = 0,
) {
    val progress: Float get() = if (total > 0) scanned.toFloat() / total else 0f
}

/** Scans only SAF trees explicitly persisted by [LocalLibraryFolderRepository]. */
internal class LocalLibraryScanner(private val context: Context, private val database: AppDatabase) {
    private val folderRepository = LocalLibraryFolderRepository(context)

    suspend fun scan(onProgress: (LocalScanState) -> Unit): LocalScanState = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val folders = folderRepository.getFolders()
            var state = LocalScanState(isScanning = true, message = "正在读取所选文件夹")
            onProgress(state)

            val candidates = mutableListOf<AudioCandidate>()
            val retainedPaths = mutableSetOf<String>()
            val completedRoots = mutableSetOf<String>()
            var discoveryErrors = 0

            folders.forEach { folder ->
                currentCoroutineContext().ensureActive()
                var rootComplete = true
                val root = try {
                    DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
                } catch (_: Exception) {
                    null
                }
                if (root == null || !runCatching { root.isDirectory }.getOrDefault(false)) {
                    discoveryErrors++
                    rootComplete = false
                } else {
                    val pending = ArrayDeque<PendingDirectory>()
                    pending.add(PendingDirectory(root, folder.name.ifBlank { "已选文件夹" }, 0))
                    val visitedDirectories = mutableSetOf<String>()
                    while (pending.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val current = pending.removeLast()
                        if (!visitedDirectories.add(current.document.uri.toString())) continue
                        val children = try {
                            current.document.listFiles()
                        } catch (_: Exception) {
                            discoveryErrors++
                            rootComplete = false
                            continue
                        }
                        val names = children.map { child ->
                            try {
                                child.name
                            } catch (_: Exception) {
                                discoveryErrors++
                                rootComplete = false
                                null
                            }
                        }
                        val availableNames = names.filterNotNull()
                        val lyrics = children.mapIndexedNotNull { index, child ->
                            val name = names[index]?.takeIf { it.substringAfterLast('.').equals("lrc", true) }
                            name?.let { it.lowercase() to child }
                        }.toMap()

                        children.forEachIndexed { index, child ->
                            currentCoroutineContext().ensureActive()
                            val name = names[index]
                            val isDirectory = try {
                                child.isDirectory
                            } catch (_: Exception) {
                                discoveryErrors++
                                rootComplete = false
                                // Unknown type does not prove that this document was removed.
                                retainedPaths += child.uri.toString()
                                return@forEachIndexed
                            }
                            if (isDirectory) {
                                if (current.depth >= MAX_LOCAL_DIRECTORY_DEPTH) {
                                    discoveryErrors++
                                    rootComplete = false
                                } else {
                                    val childPath = listOf(current.displayPath, name.orEmpty())
                                        .filter(String::isNotBlank).joinToString("/")
                                    pending.add(PendingDirectory(child, childPath, current.depth + 1))
                                }
                                return@forEachIndexed
                            }
                            if (localAudioExtension(name) == null) return@forEachIndexed
                            val path = child.uri.toString()
                            if (!retainedPaths.add(path)) return@forEachIndexed
                            val lyricName = matchingSidecarLyricName(name, availableNames)
                            candidates += AudioCandidate(
                                document = child,
                                displayName = name.orEmpty(),
                                folderPath = current.displayPath,
                                lyric = lyricName?.lowercase()?.let(lyrics::get),
                            )
                            state = state.copy(total = state.total + 1)
                            onProgress(state)
                        }
                    }
                }
                if (rootComplete) completedRoots += folder.treeUri
            }

            state = state.copy(message = "正在读取音频标签")
            onProgress(state)
            candidates.forEach { candidate ->
                currentCoroutineContext().ensureActive()
                val path = candidate.document.uri.toString()
                try {
                    val localId = stableLocalSongId(path)
                    val known = database.songDao().getByPath(path) ?: database.songDao().getById(localId)
                    val now = System.currentTimeMillis()
                    var scanned = Song(
                        id = localId,
                        title = candidate.displayName.substringBeforeLast('.').ifBlank { "未知音频" },
                        artist = emptyList(),
                        album = "",
                        cover = "",
                        duration = 0L,
                        path = path,
                        sourceType = SourceType.LOCAL,
                        fileSize = runCatching { candidate.document.length() }.getOrDefault(0L),
                        fileFormat = localAudioExtension(candidate.displayName),
                        folderPath = candidate.folderPath,
                        addedAt = now,
                        updatedAt = now,
                    )
                    scanned = readMetadata(
                        scanned,
                        candidate.document.uri,
                        readArtwork = known?.metadataOverride != true || known.cover.isBlank(),
                    )
                    val lyric = readSidecarLyric(candidate.lyric)
                    currentCoroutineContext().ensureActive()
                    var publication = Publication.MISSING
                    var publicationReadFailed = false
                    val existence = try {
                        localDocumentExistence(candidate.document.exists())
                    } catch (_: Exception) {
                        // A transient provider error is not proof that the document disappeared.
                        discoveryErrors++
                        localDocumentExistence(exists = null, readFailed = true)
                    }
                    localAudioPublicationMutex.withLock {
                        if (!shouldRetainLocalDocument(existence)) {
                            retainedPaths.remove(path)
                        } else if (existence == LocalDocumentExistence.READ_FAILED) {
                            publicationReadFailed = true
                        } else {
                            database.withTransaction {
                                val current = database.songDao().getByPath(path)
                                    ?: database.songDao().getById(localId)
                                val merged = mergeScannedSong(current, scanned)
                                if (current == null) {
                                    database.songDao().insertSong(merged)
                                    publication = Publication.ADDED
                                } else {
                                    database.songDao().updateSong(merged)
                                    publication = Publication.UPDATED
                                }
                                val cachedLyric = database.cachedLyricDao().get(merged.id).first()
                                if (shouldClearLocalLyricCache(lyric, cachedLyric?.sourceName)) {
                                    database.cachedLyricDao().delete(merged.id)
                                }
                                // A real sidecar is authoritative when the user returns to local lyrics.
                                if (lyric != null) {
                                    database.cachedLyricDao().insert(
                                        CachedLyric(
                                            songId = merged.id,
                                            content = lyric,
                                            translation = null,
                                            isVerbatim = false,
                                            isPureMusic = false,
                                            sourceName = "Local",
                                            parserType = "LRC",
                                            updatedAt = now,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    state = when {
                        publicationReadFailed -> state.copy(skipped = state.skipped + 1)
                        publication == Publication.ADDED -> state.copy(added = state.added + 1)
                        publication == Publication.UPDATED -> state.copy(updated = state.updated + 1)
                        else -> state.copy(skipped = state.skipped + 1)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep the old row, if any: a transient parser/provider error is not a deletion.
                    state = state.copy(skipped = state.skipped + 1)
                }
                state = state.copy(scanned = state.scanned + 1)
                onProgress(state)
            }

            state = state.copy(message = "正在清理失效记录")
            onProgress(state)
            currentCoroutineContext().ensureActive()
            var cleanupErrors = 0
            val deletedArtworkIds = mutableListOf<String>()
            localAudioPublicationMutex.withLock {
                val existingLocal = database.songDao().getSongsBySource(SourceType.LOCAL).first()
                val stale = existingLocal.filter { song ->
                    val path = song.path
                    if (path != null && path in retainedPaths) return@filter false
                    val owningRoot = path?.let { candidatePath ->
                        folders.firstOrNull { folder -> isDocumentInTree(candidatePath, folder) }
                    }
                    // Unknown/outside roots are always stale. Failed roots retain their old rows.
                    owningRoot == null || owningRoot.treeUri in completedRoots
                }
                stale.forEach { old ->
                    try {
                        var deleted = false
                        database.withTransaction {
                            val current = database.songDao().getById(old.id)
                            if (current?.sourceType == SourceType.LOCAL && current.path == old.path) {
                                database.songDao().deleteById(old.id)
                                database.cachedLyricDao().delete(old.id)
                                deleted = true
                            }
                        }
                        if (deleted) {
                            deletedArtworkIds += old.id
                            state = state.copy(removed = state.removed + 1)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        cleanupErrors++
                    }
                }
            }
            deletedArtworkIds.forEach { id ->
                runCatching { File(File(context.filesDir, "local-artwork"), "$id.jpg").delete() }
            }

            val problemCount = discoveryErrors + cleanupErrors
            state.copy(
                isScanning = false,
                error = if (problemCount > 0) {
                    "有 $problemCount 处读取或清理失败，相关旧记录已保留"
                } else null,
                message = if (folders.isEmpty()) {
                    "未选择扫描文件夹，已清理 ${state.removed} 条旧本地记录"
                } else {
                    "扫描完成，新增 ${state.added} 首，更新 ${state.updated} 首，移除 ${state.removed} 首"
                },
            ).also(onProgress)
        }
    }

    private fun readMetadata(song: Song, uri: Uri, readArtwork: Boolean): Song {
        var result = song
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun metadata(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf(String::isNotBlank)
            val artwork = if (readArtwork) {
                runCatching { retriever.embeddedPicture }.getOrNull()?.let { bytes ->
                    runCatching {
                        val directory = File(context.filesDir, "local-artwork").apply { mkdirs() }
                        File(directory, "${song.id}.jpg").apply { writeBytes(bytes) }
                    }.getOrNull()?.let(Uri::fromFile)?.toString()
                }
            } else null
            result = song.copy(
                title = metadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title,
                artist = parseLocalArtists(metadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)).ifEmpty { song.artist },
                album = metadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: song.album,
                cover = artwork ?: song.cover,
                duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                    ?: song.duration,
                bitrate = metadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.takeIf { it > 0 },
            )
        } catch (_: Exception) {
            // A missing/unsupported tag parser must not exclude an otherwise playable document.
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true) continue
                fun positiveInteger(key: String): Int? = if (format.containsKey(key)) {
                    format.getInteger(key).takeIf { it > 0 }
                } else null
                result = result.copy(
                    bitrate = result.bitrate ?: positiveInteger(MediaFormat.KEY_BIT_RATE),
                    sampleRate = positiveInteger(MediaFormat.KEY_SAMPLE_RATE),
                )
                break
            }
        } catch (_: Exception) {
            // Technical information is optional.
        } finally {
            runCatching { extractor.release() }
        }
        return result
    }

    private fun readSidecarLyric(document: DocumentFile?): String? {
        if (document == null) return null
        return runCatching {
            context.contentResolver.openInputStream(document.uri)?.use { input ->
                val bytes = input.readNBytes(MAX_LOCAL_LYRIC_BYTES + 1)
                if (bytes.size > MAX_LOCAL_LYRIC_BYTES) throw IOException("Lyric sidecar is too large")
                decodeLyric(bytes).trim().takeIf(String::isNotEmpty)
            }
        }.getOrNull()
    }

    private fun isDocumentInTree(path: String, folder: LocalLibraryFolder): Boolean = runCatching {
        val document = Uri.parse(path)
        val tree = Uri.parse(folder.treeUri)
        DocumentsContract.isTreeUri(document) && DocumentsContract.isTreeUri(tree) &&
            document.authority == tree.authority &&
            DocumentsContract.getTreeDocumentId(document) == DocumentsContract.getTreeDocumentId(tree)
    }.getOrDefault(false)

    private data class PendingDirectory(
        val document: DocumentFile,
        val displayPath: String,
        val depth: Int,
    )

    private data class AudioCandidate(
        val document: DocumentFile,
        val displayName: String,
        val folderPath: String,
        val lyric: DocumentFile?,
    )

    private enum class Publication { ADDED, UPDATED, MISSING }

    private companion object {
        val scanMutex = Mutex()
    }
}

private fun decodeLyric(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() ->
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    else -> String(bytes, Charsets.UTF_8)
}
