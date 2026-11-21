package com.example.drivestream

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-tier (L1 ConcurrentHashMap in-memory + L2 persistent disk JSON) cache
 * for Google Drive folder and playlist pages.
 */
class FolderPageCache(private val context: Context) {

    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, List<DriveFile>>()
    private val cacheFile = File(context.filesDir, "folder_pages_cache.json")
    private val lock = Any()
    @Volatile
    private var isLoaded = false

    init {
        loadFromDiskIfNeeded()
    }

    private fun loadFromDiskIfNeeded() {
        if (!isLoaded) {
            synchronized(lock) {
                if (!isLoaded) {
                    try {
                        if (!context.filesDir.exists()) {
                            context.filesDir.mkdirs()
                        }
                        if (cacheFile.exists()) {
                            val json = cacheFile.readText()
                            val type = object : TypeToken<Map<String, List<DriveFile>>>() {}.type
                            val data: Map<String, List<DriveFile>>? = gson.fromJson(json, type)
                            if (data != null) {
                                memoryCache.putAll(data)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FolderPageCache", "Failed to load cache from disk", e)
                    } finally {
                        isLoaded = true
                    }
                }
            }
        }
    }

    /**
     * Ensures disk cache is hydrated if memoryCache is empty, then returns memoryCache[folderId].
     */
    fun get(folderId: String): List<DriveFile>? {
        loadFromDiskIfNeeded()
        return memoryCache[folderId]
    }

    /**
     * Updates memoryCache[folderId] = files immediately and writes the full snapshot
     * atomically (tempFile.writeText + tempFile.renameTo(cacheFile)) on Dispatchers.IO.
     */
    suspend fun put(folderId: String, files: List<DriveFile>) {
        loadFromDiskIfNeeded()
        memoryCache[folderId] = files
        persistToDisk()
    }

    /**
     * Removes a single folder entry from L1 and persists to disk.
     */
    suspend fun remove(folderId: String) {
        loadFromDiskIfNeeded()
        memoryCache.remove(folderId)
        persistToDisk()
    }

    /**
     * Clears memoryCache and deletes cacheFile on Dispatchers.IO (used on signOut).
     */
    suspend fun clear() {
        synchronized(lock) {
            memoryCache.clear()
            isLoaded = true
        }
        withContext(Dispatchers.IO) {
            try {
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                val tempFile = File(context.filesDir, "folder_pages_cache.json.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("FolderPageCache", "Failed to clear cache from disk", e)
            }
        }
    }

    private suspend fun persistToDisk() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                if (!context.filesDir.exists()) {
                    context.filesDir.mkdirs()
                }
                val snapshot = HashMap(memoryCache)
                val json = gson.toJson(snapshot)
                val tempFile = File(context.filesDir, "folder_pages_cache.json.tmp")
                tempFile.writeText(json)
                if (tempFile.exists()) {
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                    tempFile.renameTo(cacheFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("FolderPageCache", "Failed to persist cache to disk", e)
            }
        }
    }
}
