package com.example.drivestream

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class CacheCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val cacheDirectory = File(applicationContext.cacheDir, "exo_audio_cache")
            if (!cacheDirectory.exists()) return Result.success()

            val stats = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stats.availableBlocksLong * stats.blockSizeLong
            val freeGb = availableBytes / (1024 * 1024 * 1024.0)

            val files = cacheDirectory.walkTopDown().filter { it.isFile }.toList()
            val totalSizeBytes = files.sumOf { it.length() }
            val totalSizeMb = totalSizeBytes / (1024 * 1024)

            // Trigger threshold: 500MB cache or less than 1GB device storage
            if (totalSizeMb > 500 || freeGb < 1.0) {
                Log.i("CacheJanitor", "Triggering cleanup. Cache size: $totalSizeMb MB, Free space: $freeGb GB")
                val sortedFiles = files.sortedBy { it.lastModified() }
                var currentSizeBytes = totalSizeBytes
                val targetSizeBytes = 250 * 1024 * 1024L
                
                for (file in sortedFiles) {
                    if (currentSizeBytes <= targetSizeBytes) break
                    val size = file.length()
                    if (file.delete()) {
                        currentSizeBytes -= size
                    }
                }
                Log.i("CacheJanitor", "Cleanup complete. New size: ${currentSizeBytes / (1024 * 1024)} MB")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("CacheJanitor", "Error during cache cleanup", e)
            Result.failure()
        }
    }
}
