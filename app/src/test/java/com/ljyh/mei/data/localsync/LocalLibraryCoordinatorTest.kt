package com.ljyh.mei.data.localsync

import com.ljyh.mei.data.locallibrary.LocalLibraryFolder
import com.ljyh.mei.ui.screen.local.LocalScanState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLibraryCoordinatorTest {
    @Test
    fun waitsForFoldersAndSkipsAnEmptyLoadedDirectorySet() = runTest {
        val foldersLoaded = CompletableDeferred<List<LocalLibraryFolder>>()
        var scans = 0
        val coordinator = coordinator(
            this,
            folders = { foldersLoaded.await() },
            scan = { scans++; LocalScanState() },
        )

        coordinator.requestEntryScan()
        runCurrent()
        assertEquals(0, scans)

        foldersLoaded.complete(emptyList())
        advanceUntilIdle()
        assertEquals(0, scans)
    }

    @Test
    fun firstEntryStartsOneScanAndEntryAfterCompletionStartsAnother() = runTest {
        var scans = 0
        val coordinator = coordinator(
            this,
            folders = { authorizedFolders },
            scan = { scans++; LocalScanState(added = scans) },
        )

        coordinator.requestEntryScan()
        advanceUntilIdle()
        assertEquals(1, scans)
        assertFalse(coordinator.state.value.isRunning)

        coordinator.requestEntryScan()
        advanceUntilIdle()
        assertEquals(2, scans)
    }

    @Test
    fun concurrentAndInFlightEntryRequestsDoNotQueueAnotherScan() = runTest {
        val releaseScan = CompletableDeferred<Unit>()
        var scans = 0
        var active = 0
        var maxActive = 0
        val coordinator = coordinator(
            this,
            folders = { authorizedFolders },
            scan = {
                scans++
                active++
                maxActive = maxOf(maxActive, active)
                releaseScan.await()
                active--
                LocalScanState()
            },
        )

        repeat(4) { coordinator.requestEntryScan() }
        runCurrent()
        assertEquals(1, scans)
        assertEquals(1, maxActive)

        coordinator.requestEntryScan()
        runCurrent()
        assertEquals(1, scans)

        releaseScan.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, scans)

        coordinator.requestEntryScan()
        advanceUntilIdle()
        assertEquals(2, scans)
        assertTrue(maxActive == 1)
    }

    @Test
    fun manualScanUsesTheSameSingleFlightOwner() = runTest {
        val releaseScan = CompletableDeferred<Unit>()
        var scans = 0
        val coordinator = coordinator(
            this,
            folders = { authorizedFolders },
            scan = {
                scans++
                releaseScan.await()
                LocalScanState()
            },
        )

        coordinator.requestEntryScan()
        runCurrent()
        coordinator.requestManualScan()
        runCurrent()

        assertEquals(1, scans)
        releaseScan.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, scans)

        coordinator.requestManualScan()
        advanceUntilIdle()
        assertEquals(2, scans)
    }

    @Test
    fun scanAndStateOutliveThePageScopeAndRemainAvailableAfterReentry() = runTest {
        val scanStarted = CompletableDeferred<Unit>()
        val releaseScan = CompletableDeferred<Unit>()
        var scans = 0
        val coordinator = coordinator(
            this,
            folders = { authorizedFolders },
            scan = {
                scans++
                scanStarted.complete(Unit)
                releaseScan.await()
                LocalScanState(added = 1)
            },
        )
        val pageJob = Job()
        val observedScanState = coordinator.scanState

        coordinator.requestEntryScan()
        runCurrent()
        assertTrue(scanStarted.isCompleted)
        assertTrue(observedScanState.value.isScanning)
        pageJob.cancel()

        coordinator.requestEntryScan()
        runCurrent()
        assertTrue(observedScanState === coordinator.scanState)
        assertEquals(1, scans)
        releaseScan.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, scans)
        assertFalse(observedScanState.value.isScanning)
        assertEquals(1, observedScanState.value.added)
    }

    @Test
    fun automaticSyncRunsOnlyWhenEnabledAndManualSyncRemainsForced() = runTest {
        var enabled = false
        val syncRequests = mutableListOf<Boolean>()
        val coordinator = coordinator(
            this,
            folders = { authorizedFolders },
            scan = { LocalScanState() },
            autoSyncEnabled = { enabled },
            sync = { syncRequests += it },
        )

        coordinator.requestEntryScan()
        advanceUntilIdle()
        assertTrue(syncRequests.isEmpty())

        enabled = true
        coordinator.requestEntryScan()
        advanceUntilIdle()
        assertEquals(listOf(false), syncRequests)

        coordinator.requestManualSync()
        advanceUntilIdle()
        assertEquals(listOf(false, true), syncRequests)
    }

    private fun coordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        folders: suspend () -> List<LocalLibraryFolder>,
        scan: suspend ((LocalScanState) -> Unit) -> LocalScanState,
        autoSyncEnabled: suspend () -> Boolean = { false },
        sync: suspend (Boolean) -> Unit = {},
    ) = LocalLibraryCoordinator(
        scope = scope,
        folders = folders,
        scan = scan,
        autoSyncEnabled = autoSyncEnabled,
        syncLibrary = sync,
    )

    private companion object {
        val authorizedFolders = listOf(LocalLibraryFolder("content://tree/music", "Music", 1L))
    }
}
