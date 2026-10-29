package com.ljyh.mei.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ljyh.mei.constants.MusicQuality
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.repository.SongRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class SourceNotFoundException(message: String) : IOException(message)

data class LocalPlaybackInfo(
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
)

internal data class ResolvedMediaSource(
    val uri: Uri,
    val actualQuality: String,
    val cacheKey: String?,
    val sourceIdentity: String? = null,
    val localPlaybackInfo: LocalPlaybackInfo? = null,
    val contentLength: Long? = null,
)

internal enum class LocalMediaScheme { FILE, CONTENT }

/** A parsed local path, kept free of Android Uri so scheme policy is JVM-testable. */
internal data class LocalMediaPath(
    val value: String,
    val scheme: LocalMediaScheme,
    val isUri: Boolean,
)

private val uriSchemePattern = Regex("[A-Za-z][A-Za-z0-9+.-]*")

internal fun localMediaPath(song: Song): LocalMediaPath? {
    if (song.sourceType != SourceType.LOCAL) return null
    val path = song.path?.takeIf(String::isNotBlank) ?: return null
    if (path.startsWith("//")) return null
    if (path.startsWith('/')) {
        return LocalMediaPath(path, LocalMediaScheme.FILE, isUri = false)
    }

    val schemeEnd = path.indexOf(':')
    if (schemeEnd <= 0) return null
    val scheme = path.substring(0, schemeEnd)
    if (!uriSchemePattern.matches(scheme)) return null
    val remainder = path.substring(schemeEnd + 1)
    return when (scheme.lowercase()) {
        "file" -> remainder
            .takeIf { it.startsWith('/') }
            ?.let { LocalMediaPath(path, LocalMediaScheme.FILE, isUri = true) }
        "content" -> remainder
            .takeIf {
                it.startsWith("//") && it.substring(2).substringBefore('/').isNotBlank()
            }
            ?.let { LocalMediaPath(path, LocalMediaScheme.CONTENT, isUri = true) }
        else -> null
    }
}

@Singleton
class MediaUriProvider @Inject constructor(
    private val songRepository: SongRepository,
    @ApplicationContext private val context: Context,
) {
    private val resolvedSources = ConcurrentHashMap<String, ResolvedMediaSource>()

    @Volatile
    internal var onSourceResolved: ((String, ResolvedMediaSource) -> Unit)? = null

    internal fun resolvedMediaSource(mediaId: String): ResolvedMediaSource? =
        resolvedSources[mediaId]?.takeIf { it.cacheKey == null && it.uri.isLocalMediaUri() }

    /** Online disk-cache entries are deliberately never restored as playback sources. */
    internal fun rememberCachedSource(mediaId: String, quality: String, cacheKey: String, uri: Uri) = Unit

    /** Returning true prevents MusicService from serving an online URI from its disk cache. */
    internal fun isRejectedCachedSource(mediaId: String, cacheKey: String): Boolean = true

    suspend fun resolveMediaUri(mediaId: String, quality: String): Uri =
        resolveMediaSource(mediaId, quality).uri

    /** Kept for source compatibility; online source selection is not supported. */
    internal fun preferOnlineSource(mediaId: String) = Unit

    internal fun preferLocalSource(mediaId: String) {
        resolvedSources.remove(mediaId)
    }

    suspend fun getLocalPlaybackInfo(mediaId: String): LocalPlaybackInfo? {
        val song = localSong(mediaId) ?: return null
        val uri = localUri(song) ?: return null
        return readLocalPlaybackInfo(uri)
    }

    internal suspend fun resolveLocalSource(mediaId: String): ResolvedMediaSource? {
        val song = localSong(mediaId) ?: return null
        val uri = localUri(song) ?: return null
        val info = readLocalPlaybackInfo(uri)
        return rememberResolvedSource(
            mediaId,
            ResolvedMediaSource(uri, actualQuality = "", cacheKey = null, localPlaybackInfo = info),
        )
    }

    internal suspend fun resolveMediaSource(mediaId: String, quality: String): ResolvedMediaSource =
        resolveLocalSource(mediaId)
            ?: throw SourceNotFoundException("No local source for $mediaId")

    /** Online quality catalogs and probes are unavailable in local-only playback. */
    suspend fun getAvailableMusicQualities(mediaId: String, supportDolby: Boolean): List<MusicQuality> =
        emptyList()

    suspend fun getAvailableDownloadQualities(mediaId: String): List<MusicQuality> = emptyList()

    /** Local sources have no online identity or alternate quality to reject. */
    fun rejectCurrentSource(mediaId: String): Boolean = false

    fun resetRejectedSources() = Unit

    internal fun clearQualityState() {
        resolvedSources.clear()
    }

    fun invalidate(mediaId: String) {
        resolvedSources.remove(mediaId)
    }

    private suspend fun localSong(mediaId: String): Song? {
        val exact = songRepository.getSong(mediaId).firstOrNull()
        if (!exact?.path.isNullOrBlank()) return exact
        val localId = mediaId.toLongOrNull()?.takeIf { it < 0 && it != Long.MIN_VALUE }?.let { -it }
            ?: return exact
        val prefixed = songRepository.getSong("local_$localId").firstOrNull()
        return prefixed?.takeIf { !it.path.isNullOrBlank() } ?: exact ?: prefixed
    }

    private fun localUri(song: Song): Uri? {
        val localPath = localMediaPath(song) ?: return null
        val uri = Uri.parse(localPath.value)
        return when (localPath.scheme) {
            LocalMediaScheme.CONTENT -> uri.takeIf { it.isLocalMediaUri() }
            LocalMediaScheme.FILE -> {
                val file = if (localPath.isUri) {
                    File(uri.path?.takeIf(String::isNotBlank) ?: return null)
                } else {
                    File(localPath.value)
                }
                file.takeIf(File::exists)?.let(Uri::fromFile)
            }
        }
    }

    private fun Uri.isLocalMediaUri(): Boolean =
        scheme.equals("file", ignoreCase = true) || scheme.equals("content", ignoreCase = true)

    private suspend fun readLocalPlaybackInfo(uri: Uri): LocalPlaybackInfo = withContext(Dispatchers.IO) {
        var mimeType: String? = null
        var bitrate: Int? = null
        var sampleRate: Int? = null
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") != true) continue
                mimeType = mime
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }
                }
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                }
                break
            }
        } catch (error: Exception) {
            Timber.tag("MediaUriProvider").d("Unable to inspect local audio: %s", error.javaClass.simpleName)
        } finally {
            runCatching { extractor?.release() }
        }
        if (bitrate == null || mimeType == null) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                if (mimeType == null) {
                    mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                }
                if (bitrate == null) {
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull()?.takeIf { it > 0 }
                }
            } catch (error: Exception) {
                Timber.tag("MediaUriProvider").d("Unable to read local audio metadata: %s", error.javaClass.simpleName)
            } finally {
                runCatching { retriever?.release() }
            }
        }
        LocalPlaybackInfo(mimeType, bitrate, sampleRate)
    }

    private fun rememberResolvedSource(mediaId: String, source: ResolvedMediaSource): ResolvedMediaSource {
        resolvedSources[mediaId] = source
        onSourceResolved?.invoke(mediaId, source)
        return source
    }
}
