package com.ljyh.mei.data.localsync

import android.content.Context
import com.ljyh.mei.data.locallibrary.LocalLibraryFolder
import com.ljyh.mei.data.locallibrary.LocalLibraryFolderRepository
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.ui.screen.local.LocalLibraryScanner
import com.ljyh.mei.ui.screen.local.LocalScanState
import com.ljyh.mei.ui.screen.meilo.MeiloSettingsStore
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns local-library scans and their optional metadata sync for the lifetime of the app process. */
@Singleton
internal class LocalLibraryCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val folders: suspend () -> List<LocalLibraryFolder>,
    private val scan: suspend ((LocalScanState) -> Unit) -> LocalScanState,
    private val autoSyncEnabled: suspend () -> Boolean,
    private val syncLibrary: suspend (Boolean) -> Unit,
    val state: StateFlow<LocalSyncState> = MutableStateFlow(LocalSyncState()),
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: AppDatabase,
        metadataSync: LocalMetadataSync,
    ) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        folders = { LocalLibraryFolderRepository(context).getFolders() },
        scan = { onProgress -> LocalLibraryScanner(context, database).scan(onProgress) },
        autoSyncEnabled = {
            MeiloSettingsStore(context).settings.first().autoAnalysisSyncEnabled
        },
        syncLibrary = metadataSync::syncLibrary,
        state = metadataSync.state,
    )

    private val operationMutex = Mutex()
    private val scanLock = Any()
    private var scanJob: Job? = null
    private var manualSyncJob: Job? = null
    private val _scanState = MutableStateFlow(LocalScanState())
    val scanState: StateFlow<LocalScanState> = _scanState.asStateFlow()

    /** Called once for a visible page entry. Active or folder-loading requests are single-flight. */
    fun requestEntryScan() = requestScan()

    /** Explicit rescan action; it shares the same gate as automatic entry scans. */
    fun requestManualScan() = requestScan()

    /** Explicit user action remains forced even when automatic metadata analysis is disabled. */
    fun requestManualSync() {
        synchronized(scanLock) {
            if (manualSyncJob?.isActive == true) return
            manualSyncJob = scope.launch {
                operationMutex.withLock { syncLibrary(true) }
            }
        }
    }

    fun cancelScan() {
        synchronized(scanLock) { scanJob?.cancel() }
    }

    private fun requestScan() {
        synchronized(scanLock) {
            if (scanJob?.isActive == true) return
            scanJob = scope.launch {
                operationMutex.withLock {
                    if (folders().isEmpty()) return@withLock
                    _scanState.value = LocalScanState(isScanning = true)
                    try {
                        val result = scan { _scanState.value = it }
                        _scanState.value = result
                        if (autoSyncEnabled()) syncLibrary(false)
                    } catch (cancelled: CancellationException) {
                        _scanState.value = _scanState.value.copy(
                            isScanning = false,
                            error = null,
                            message = "扫描已停止，已发现的歌曲已保留",
                        )
                        throw cancelled
                    } catch (error: Exception) {
                        _scanState.value = _scanState.value.copy(
                            isScanning = false,
                            error = error.message ?: "扫描失败，请重试",
                        )
                    }
                }
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LocalLibraryCoordinatorEntryPoint {
    fun localLibraryCoordinator(): LocalLibraryCoordinator
}

internal fun localLibraryCoordinator(context: Context): LocalLibraryCoordinator =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        LocalLibraryCoordinatorEntryPoint::class.java,
    ).localLibraryCoordinator()
