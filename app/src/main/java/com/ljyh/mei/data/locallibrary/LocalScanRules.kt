package com.ljyh.mei.data.locallibrary

import java.security.MessageDigest

/** Extensions accepted by the SAF scanner. Provider MIME types are not trusted for filtering. */
val SUPPORTED_LOCAL_AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "alac", "ape", "dsf", "dff",
)

fun localAudioExtension(fileName: String?): String? = fileName
    ?.substringAfterLast('.', "")
    ?.lowercase()
    ?.takeIf { it in SUPPORTED_LOCAL_AUDIO_EXTENSIONS }

fun isSupportedLocalAudio(fileName: String?): Boolean = localAudioExtension(fileName) != null

/**
 * A Room identity derived only from the provider document URI.
 *
 * The 60-bit numeric suffix remains compatible with the app's negative local MediaMetadata IDs,
 * while avoiding the much smaller String.hashCode identity space.
 */
fun stableLocalSongId(documentUri: String): String {
    require(documentUri.isNotBlank()) { "A document URI is required" }
    val numericSuffix = sha256Hex(documentUri).substring(0, 15).toLong(radix = 16).coerceAtLeast(1L)
    return "local_$numericSuffix"
}

/** Finds an LRC file with the same base name, ignoring case for FAT/provider compatibility. */
fun matchingSidecarLyricName(audioFileName: String?, siblingNames: Iterable<String>): String? {
    if (!isSupportedLocalAudio(audioFileName)) return null
    val target = audioFileName.orEmpty().substringBeforeLast('.', "") + ".lrc"
    if (target == ".lrc") return null
    return siblingNames.firstOrNull { it.equals(target, ignoreCase = true) }
}

private fun sha256Hex(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0f])
        }
    }
}
