package com.ljyh.mei.data.locallibrary

/** DocumentFile.exists() has three states; only a confirmed false is evidence of deletion. */
enum class LocalDocumentExistence { PRESENT, MISSING, READ_FAILED }

fun localDocumentExistence(exists: Boolean?, readFailed: Boolean = false): LocalDocumentExistence = when {
    readFailed || exists == null -> LocalDocumentExistence.READ_FAILED
    exists -> LocalDocumentExistence.PRESENT
    else -> LocalDocumentExistence.MISSING
}

fun shouldRetainLocalDocument(existence: LocalDocumentExistence): Boolean =
    existence != LocalDocumentExistence.MISSING

fun shouldClearLocalLyricCache(sidecarContent: String?, cachedSourceName: String?): Boolean =
    sidecarContent == null && cachedSourceName in setOf("Local", "Empty")
