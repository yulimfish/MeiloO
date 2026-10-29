package com.ljyh.mei.data.model.room

/** Maps the negative Media3 ID back to the stable Room key used by scanner, lyrics and settings. */
fun localSongStorageId(mediaId: String): String = mediaId.toLongOrNull()
    ?.takeIf { it < 0L && it != Long.MIN_VALUE }
    ?.let { "local_${-it}" }
    ?: mediaId
