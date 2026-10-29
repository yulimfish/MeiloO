package com.ljyh.mei.data.locallibrary

/** Splits collaboration tags into artists without treating the whole string as one person. */
private val artistSeparators = Regex("\\s*(?:/|、|，|,|;|×)\\s*|\\s+(?:feat\\.?|ft\\.?)\\s+", RegexOption.IGNORE_CASE)

fun parseLocalArtists(tag: String?): List<String> = tag
    ?.split(artistSeparators)
    ?.flatMap { part ->
        // Keep conventional one-word band names (Simon & Garfunkel) intact.
        if (' ' in part.trim()) listOf(part) else part.split(Regex("\\s*&\\s*"))
    }
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    .orEmpty()
