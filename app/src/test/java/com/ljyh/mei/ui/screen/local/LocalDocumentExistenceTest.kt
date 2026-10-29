package com.ljyh.mei.ui.screen.local

import com.ljyh.mei.data.locallibrary.LocalDocumentExistence
import com.ljyh.mei.data.locallibrary.localDocumentExistence
import com.ljyh.mei.data.locallibrary.shouldRetainLocalDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentExistenceTest {
    @Test
    fun onlyConfirmedMissingDocumentIsNotRetained() {
        val present = localDocumentExistence(exists = true)
        val missing = localDocumentExistence(exists = false)
        val unknown = localDocumentExistence(exists = null)
        val failed = localDocumentExistence(exists = false, readFailed = true)

        assertEquals(LocalDocumentExistence.PRESENT, present)
        assertEquals(LocalDocumentExistence.MISSING, missing)
        assertEquals(LocalDocumentExistence.READ_FAILED, unknown)
        assertEquals(LocalDocumentExistence.READ_FAILED, failed)
        assertTrue(shouldRetainLocalDocument(present))
        assertTrue(shouldRetainLocalDocument(unknown))
        assertTrue(shouldRetainLocalDocument(failed))
        assertFalse(shouldRetainLocalDocument(missing))
    }

    @Test
    fun providerFailureCannotBeDowngradedToConfirmedDeletion() {
        assertEquals(
            LocalDocumentExistence.READ_FAILED,
            localDocumentExistence(exists = true, readFailed = true),
        )
        assertEquals(
            LocalDocumentExistence.READ_FAILED,
            localDocumentExistence(exists = false, readFailed = true),
        )
    }
}
