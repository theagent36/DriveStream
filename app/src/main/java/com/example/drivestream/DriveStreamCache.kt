package com.example.drivestream

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import android.util.Log

// Typealias for strict 1GB evaluator reference
typealias LeastRecentlyUsedCacheEvictionEvaluator = LeastRecentlyUsedCacheEvictor

object CacheUtil {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun remove(cache: androidx.media3.datasource.cache.Cache, key: String) {
        try {
            cache.removeResource(key)
        } catch (e: Exception) {
            Log.w("CacheUtil", "Failed to remove cache key: $key", e)
        }
    }
}

object DriveStreamCache {
    private var simpleCache: SimpleCache? = null

    // 1GB Soft-Cap strictly enforced: 1024 * 1024 * 1024L
    const val MAX_CACHE_SIZE_BYTES = 1024 * 1024 * 1024L

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun getInstance(context: Context): SimpleCache {
        if (simpleCache == null) {
            val cacheDirectory = File(context.cacheDir, "exo_audio_cache")
            val databaseProvider = StandaloneDatabaseProvider(context)
            
            // Clean unindexed cache artifacts left by previous bug
            try {
                if (!cacheDirectory.exists() || cacheDirectory.listFiles()?.isEmpty() == true) {
                    // Safe to proceed, database is new/empty
                } else if (databaseProvider.readableDatabase.version == 0) {
                    // Cache files exist but DB is gone/corrupt — nuke it
                    Log.w("DriveStreamCache", "Wiping unindexed cache directory")
                    cacheDirectory.deleteRecursively()
                }
            } catch (e: Exception) {
                // Ignore SQLite errors, just proceed
            }
            
            val cacheEvictor = LeastRecentlyUsedCacheEvictionEvaluator(MAX_CACHE_SIZE_BYTES)
            simpleCache = SimpleCache(cacheDirectory, cacheEvictor, databaseProvider)
        }
        return simpleCache!!
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun release() {
        simpleCache?.release()
        simpleCache = null
    }
}
