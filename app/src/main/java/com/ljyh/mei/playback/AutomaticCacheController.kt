package com.ljyh.mei.playback

import androidx.media3.common.MediaItem
import com.ljyh.mei.di.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Counts a queue entry only after actual playback, never from paused restoration or buffering. */
internal class AutomaticCachePlaybackTracker(private val scope: CoroutineScope) {
    private var pending: Job? = null
    private var recorded = false

    fun update(isPlaying: Boolean, recordPlayback: suspend () -> Unit) {
        if (!isPlaying) {
            if (!recorded) {
                pending?.cancel()
                pending = null
            }
            return
        }
        if (recorded || pending != null) return
        pending = scope.launch {
            delay(5_000L)
            recorded = true
            recordPlayback()
        }
    }

    fun reset() {
        pending?.cancel()
        pending = null
        recorded = false
    }
}

@Singleton
class AutomaticCacheController @Inject constructor(
    private val database: AppDatabase,
) {
    suspend fun recordPlayback(mediaItem: MediaItem) {
        val songId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        // MeiloO never downloads or resolves online audio. Keep only local listening statistics.
        database.downloadDao().recordPlayback(songId)
    }
}
