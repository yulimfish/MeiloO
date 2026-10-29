package com.ljyh.mei.data.locallibrary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanCachePolicyTest {
    @Test
    fun confirmedMissingSidecarEvictsOnlySidecarCache() {
        assertTrue(shouldClearLocalLyricCache(null, "Local"))
        assertTrue(shouldClearLocalLyricCache(null, "Empty"))
    }

    @Test
    fun remoteSelectionAndExistingSidecarAreNeverEvictedByThisRule() {
        assertFalse(shouldClearLocalLyricCache(null, "LRCLIB"))
        assertFalse(shouldClearLocalLyricCache(null, "ITUNES"))
        assertFalse(shouldClearLocalLyricCache(null, null))
        assertFalse(shouldClearLocalLyricCache("[00:00.00] lyric", "Local"))
        assertFalse(shouldClearLocalLyricCache("[00:00.00] lyric", "Empty"))
    }
}
