package com.ljyh.mei.data.locallibrary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.localLibraryFolderStore by preferencesDataStore(name = "local_library_folders")
private val TreeUrisKey = stringSetPreferencesKey("tree_uris")

data class LocalLibraryFolder(
    val treeUri: String,
    val name: String,
    val addedAt: Long,
)

/** Stores only user-selected SAF roots; no MediaStore or filesystem root is inferred. */
class LocalLibraryFolderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.localLibraryFolderStore

    val folders: Flow<List<LocalLibraryFolder>> = store.data.map { preferences ->
        preferences[TreeUrisKey].orEmpty().map { treeUri ->
            LocalLibraryFolder(
                treeUri = treeUri,
                name = preferences[folderNameKey(treeUri)] ?: defaultFolderName(treeUri),
                addedAt = preferences[folderAddedAtKey(treeUri)] ?: 0L,
            )
        }.sortedWith(compareBy(LocalLibraryFolder::addedAt, LocalLibraryFolder::treeUri))
    }.distinctUntilChanged()

    suspend fun getFolders(): List<LocalLibraryFolder> = folders.first()

    /**
     * Persists both the tree record and its read grant. Throws SecurityException when the provider
     * did not offer a persistable grant, preventing a folder that would break after a restart.
     */
    suspend fun addFolder(treeUri: Uri, displayName: String? = null): LocalLibraryFolder {
        require(DocumentsContract.isTreeUri(treeUri)) { "Only an ACTION_OPEN_DOCUMENT_TREE URI is supported" }
        val tree = treeUri.toString()
        val alreadyPersisted = hasPersistedReadPermission(treeUri)
        if (!alreadyPersisted) {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (error: SecurityException) {
                throw SecurityException("无法持久保存此文件夹的读取授权", error)
            }
        }
        val now = System.currentTimeMillis()
        var result: LocalLibraryFolder? = null
        try {
            store.edit { preferences ->
                val addedAt = preferences[folderAddedAtKey(tree)] ?: now
                val name = displayName?.trim()?.takeIf(String::isNotEmpty)
                    ?: preferences[folderNameKey(tree)]
                    ?: defaultFolderName(tree)
                preferences[TreeUrisKey] = preferences[TreeUrisKey].orEmpty() + tree
                preferences[folderNameKey(tree)] = name
                preferences[folderAddedAtKey(tree)] = addedAt
                result = LocalLibraryFolder(tree, name, addedAt)
            }
        } catch (error: Throwable) {
            if (!alreadyPersisted) {
                runCatching {
                    appContext.contentResolver.releasePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            throw error
        }
        return checkNotNull(result)
    }

    suspend fun removeFolder(treeUri: Uri) {
        val tree = treeUri.toString()
        store.edit { preferences ->
            preferences[TreeUrisKey] = preferences[TreeUrisKey].orEmpty() - tree
            preferences.remove(folderNameKey(tree))
            preferences.remove(folderAddedAtKey(tree))
        }
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun hasPersistedReadPermission(treeUri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission
        }
}

private fun folderNameKey(treeUri: String) = stringPreferencesKey("folder_name:$treeUri")
private fun folderAddedAtKey(treeUri: String) = longPreferencesKey("folder_added_at:$treeUri")

private fun defaultFolderName(treeUri: String): String {
    val encodedTreeId = treeUri.substringAfterLast("/tree/").substringBefore("/document/")
    val decoded = Uri.decode(encodedTreeId).ifBlank { treeUri }
    return decoded.substringAfterLast('/').substringAfterLast(':')
        .ifBlank { decoded }
        .ifBlank { "已选文件夹" }
}
