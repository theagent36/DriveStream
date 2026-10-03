package com.example.drivestream

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class DriveStreamApp : Application(), ImageLoaderFactory {

    private var appImageLoader: ImageLoader? = null

    override fun onCreate() {
        super.onCreate()
        // Initialize permanent thumbnail cache directory and preferences
        SpotifyAuthManager.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val loader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of app memory
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true) // Pool and reuse memory to prevent GC thrashing
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024) // 200 MB disk cache
                    .build()
            }
            .components {
                add(FlingPauseInterceptor { appImageLoader?.memoryCache })
            }
            .allowRgb565(true) // Halve bitmap memory footprint for thumbnails, reducing GC pressure
            .respectCacheHeaders(false) // Cache indefinitely on disk, ignore server max-age headers
            .crossfade(true)
            .build()
        appImageLoader = loader
        return loader
    }
}
