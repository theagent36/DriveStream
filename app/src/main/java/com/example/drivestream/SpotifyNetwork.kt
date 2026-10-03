package com.example.drivestream

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import com.google.gson.annotations.SerializedName

// --- Data Models ---

data class SpotifyAuthResponse(
    @SerializedName("access_token") val access_token: String = "",
    @SerializedName("token_type") val token_type: String = "",
    @SerializedName("expires_in") val expires_in: Int = 3600
)

data class SpotifySearchResponse(
    @SerializedName("tracks") val tracks: SpotifyTracks? = null
)

data class SpotifyTracks(
    @SerializedName("items") val items: List<SpotifyTrack> = emptyList()
)

data class SpotifyTrack(
    @SerializedName("album") val album: SpotifyAlbum? = null,
    @SerializedName("external_urls") val external_urls: Map<String, String>? = null
)

data class SpotifyAlbum(
    @SerializedName("images") val images: List<SpotifyImage>? = null
)

data class SpotifyImage(
    @SerializedName("url") val url: String = "",
    @SerializedName("height") val height: Int = 0,
    @SerializedName("width") val width: Int = 0
)

data class SpotifyOEmbedResponse(
    @SerializedName("title") val title: String? = null,
    @SerializedName("thumbnail_url") val thumbnail_url: String? = null,
    @SerializedName("thumbnail_width") val thumbnail_width: Int? = null,
    @SerializedName("thumbnail_height") val thumbnail_height: Int? = null
)

data class ITunesSearchResponse(
    @SerializedName("resultCount") val resultCount: Int = 0,
    @SerializedName("results") val results: List<ITunesTrackResult>? = null
)

data class ITunesTrackResult(
    @SerializedName("trackName") val trackName: String? = null,
    @SerializedName("artistName") val artistName: String? = null,
    @SerializedName("artworkUrl100") val artworkUrl100: String? = null
)

// --- Retrofit Interfaces ---

interface SpotifyAuthService {
    @FormUrlEncoded
    @POST("api/token")
    suspend fun getAccessToken(
        @Header("Authorization") authorization: String,
        @Field("grant_type") grantType: String = "client_credentials"
    ): SpotifyAuthResponse
}

interface SpotifySearchService {
    @GET("v1/search")
    suspend fun searchTrack(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("type") type: String = "track",
        @Query("limit") limit: Int = 1
    ): SpotifySearchResponse
}

interface SpotifyOEmbedService {
    @GET("oembed")
    suspend fun getOEmbed(
        @Query("url") url: String
    ): SpotifyOEmbedResponse
}

interface ITunesSearchService {
    @GET("search")
    suspend fun searchTrack(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 1
    ): ITunesSearchResponse
}

// --- Spotify Manager ---

object SpotifyAuthManager {
    private val CLIENT_ID = BuildConfig.SPOTIFY_CLIENT_ID
    private val CLIENT_SECRET = BuildConfig.SPOTIFY_CLIENT_SECRET
    private const val AUTH_BASE_URL = "https://accounts.spotify.com/"
    private const val API_BASE_URL = "https://api.spotify.com/"
    private const val OEMBED_BASE_URL = "https://open.spotify.com/"
    private const val ITUNES_BASE_URL = "https://itunes.apple.com/"
    private const val PREFS_NAME = "spotify_art_cache"

    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0
    private val authMutex = kotlinx.coroutines.sync.Mutex()

    // Persistent storage directory for thumbnail images
    private var thumbnailsDir: File? = null

    // In-memory cache for fast lookups
    private val memoryCache = ConcurrentHashMap<String, String>()
    private var prefs: SharedPreferences? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val authRetrofit = Retrofit.Builder()
        .baseUrl(AUTH_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiRetrofit = Retrofit.Builder()
        .baseUrl(API_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val oembedRetrofit = Retrofit.Builder()
        .baseUrl(OEMBED_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val itunesRetrofit = Retrofit.Builder()
        .baseUrl(ITUNES_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val authService = authRetrofit.create(SpotifyAuthService::class.java)
    private val searchService = apiRetrofit.create(SpotifySearchService::class.java)
    private val oembedService = oembedRetrofit.create(SpotifyOEmbedService::class.java)
    private val itunesService = itunesRetrofit.create(ITunesSearchService::class.java)

    /**
     * Initializes permanent disk cache directory and SharedPreferences mapping.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        if (thumbnailsDir == null) {
            thumbnailsDir = File(appContext.filesDir, "song_thumbnails").apply {
                if (!exists()) mkdirs()
            }
        }
        if (prefs == null) {
            val p = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p

            // Version-based cache invalidation: clear stale data from broken previous versions
            val currentVersion = try {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
            } catch (_: Exception) { 0L }
            val lastVersion = p.getLong("__cache_version__", 0L)
            if (lastVersion < currentVersion) {
                Log.d("SpotifyAuthManager", "Cache version upgraded $lastVersion -> $currentVersion, clearing stale cache")
                clearCache()
                p.edit().putLong("__cache_version__", currentVersion).apply()
                // Re-assign prefs since clearCache() clears everything
                prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                return
            }

            // Pre-populate memory cache from preferences (only valid local files or URLs)
            p.all.forEach { (key, value) ->
                if (key == "__cache_version__") return@forEach
                if (value is String && value.isNotEmpty()) {
                    if (value.startsWith("file://")) {
                        val localPath = value.removePrefix("file://")
                        if (File(localPath).exists() && File(localPath).length() > 0) {
                            memoryCache[key] = value
                        }
                    } else if (value.startsWith("/")) {
                        if (File(value).exists() && File(value).length() > 0) {
                            memoryCache[key] = "file://$value"
                        }
                    } else if (value.startsWith("https://")) {
                        memoryCache[key] = value
                    }
                }
            }
        }
    }

    /**
     * Generates a stable alphanumeric hash for caching keys and filenames.
     */
    private fun hashKey(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    /**
     * Normalizes and cleans music file names for high-accuracy track searching.
     */
    fun cleanFilename(filename: String): String {
        return filename
            // Remove audio file extensions
            .replace(Regex("(?i)\\.(mp3|flac|wav|m4a|aac|ogg|opus|wma|alac)$"), "")
            // Remove leading track numbers like "01 - " or "01. " or "1. "
            .replace(Regex("^\\d+[\\s.\\-_]+"), "")
            // Replace underscores with spaces
            .replace("_", " ")
            // Replace semicolons with commas
            .replace(";", ", ")
            // Remove video/audio bracket tags: (Official Video), [HQ], [1080p], (feat. ...), etc.
            .replace(Regex("(?i)[\\(\\[](?:official\\s*(?:video|audio|music\\s*video|lyric\\s*video)?|lyrics?|audio|feat\\.?[^)\\]]*|ft\\.?[^)\\]]*|remaster(?:ed)?(?:\\s*\\d{4})?|deluxe|hq|hd|live|1080p|720p|320kbps|flac)[\\)\\]]"), "")
            // Remove remaining standalone video tags
            .replace(Regex("(?i)\\b(?:official audio|lyric video|music video|official video)\\b"), "")
            // Collapse whitespace
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', '-', '_')
    }

    /**
     * Returns the local thumbnail file if it exists in disk storage.
     */
    private fun getLocalThumbnailFile(cleanName: String): File? {
        val dir = thumbnailsDir ?: return null
        return File(dir, "${hashKey(cleanName)}.jpg")
    }

    /**
     * Synchronously checks if a thumbnail for this file is already cached on disk or in memory.
     * Returns the local file path or cached URL immediately (0 network calls).
     */
    fun getCachedArtUrl(filename: String): String? {
        val key = cleanFilename(filename)
        if (key.isBlank()) return null

        // 1. Check if permanent local image file exists on disk
        val localFile = getLocalThumbnailFile(key)
        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            val uri = "file://${localFile.absolutePath}"
            memoryCache[key] = uri
            return uri
        }

        // 2. Check in-memory cache
        val memoryVal = memoryCache[key]
        if (!memoryVal.isNullOrEmpty()) {
            val formatted = if (memoryVal.startsWith("/")) "file://$memoryVal" else memoryVal
            return formatted
        }

        // 3. Check preferences
        val prefVal = prefs?.getString(key, null)
        if (!prefVal.isNullOrEmpty()) {
            val formatted = if (prefVal.startsWith("/")) "file://$prefVal" else prefVal
            memoryCache[key] = formatted
            return formatted
        }

        return null
    }

    private fun cacheArt(cleanName: String, pathOrUrl: String) {
        if (cleanName.isNotBlank() && pathOrUrl.isNotBlank()) {
            val formatted = if (pathOrUrl.startsWith("/")) "file://$pathOrUrl" else pathOrUrl
            memoryCache[cleanName] = formatted
            prefs?.edit()?.putString(cleanName, formatted)?.apply()
        }
    }

    /**
     * Downloads an image from the remote URL and saves it permanently to disk.
     * Returns the file:// URI on success, or null on failure.
     */
    private fun downloadAndSaveThumbnail(cleanName: String, url: String): String? {
        val dir = thumbnailsDir ?: return null
        val targetFile = File(dir, "${hashKey(cleanName)}.jpg")
        val tempFile = File(dir, "${hashKey(cleanName)}_${System.currentTimeMillis()}.tmp")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ThumbnailDiagnosticManager.log("DiskCache", "Download HTTP ${response.code} for $url")
                    return null
                }
                val body = response.body ?: return null
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                val uri = "file://${targetFile.absolutePath}"
                ThumbnailDiagnosticManager.log("DiskCache", "Saved thumbnail (${targetFile.length()} bytes): $uri")
                return uri
            }
        } catch (e: Exception) {
            val msg = "Failed to cache thumbnail locally: ${e.message}"
            Log.e("SpotifyAuthManager", msg, e)
            ThumbnailDiagnosticManager.log("DiskCache", msg)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        return null
    }

    private suspend fun getValidToken(): String? = withContext(Dispatchers.IO) {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiryTime) {
            return@withContext cachedToken
        }

        authMutex.withLock {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiryTime) {
                return@withLock cachedToken
            }

            try {
                val authString = "$CLIENT_ID:$CLIENT_SECRET"
                val base64Auth = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)
                ThumbnailDiagnosticManager.log("SpotifyAuth", "Requesting token with client_id: ${CLIENT_ID.take(6)}...")
                val response = authService.getAccessToken("Basic $base64Auth")
                cachedToken = response.access_token
                tokenExpiryTime = System.currentTimeMillis() + ((response.expires_in - 60) * 1000L)
                ThumbnailDiagnosticManager.log("SpotifyAuth", "Token acquired successfully, expires in ${response.expires_in}s")
                return@withLock cachedToken
            } catch (e: Exception) {
                val httpEx = e as? retrofit2.HttpException
                val errorBody = httpEx?.response()?.errorBody()?.string()
                val code = httpEx?.code()
                val msg = "Auth Failed: HTTP $code | ${e.message} | Body: $errorBody"
                Log.e("SpotifyError", msg, e)
                ThumbnailDiagnosticManager.log("SpotifyAuth", msg)
                return@withLock null
            }
        }
    }

    /**
     * Resolves the album art for the given song.
     * If already cached on disk, returns the local file path immediately (0 network calls).
     * If not yet cached, fetches from Spotify database (or music database fallback),
     * downloads the image permanently to local storage, and returns the local file path.
     */
    suspend fun getAlbumArtUrl(filename: String): String? = withContext(Dispatchers.IO) {
        val cleanedQuery = cleanFilename(filename)
        if (cleanedQuery.isBlank()) {
            ThumbnailDiagnosticManager.log("AlbumArt", "Cleaned query blank for: $filename")
            return@withContext null
        }

        // 1. Instant check from local disk / memory cache (0 network calls)
        getCachedArtUrl(filename)?.let {
            ThumbnailDiagnosticManager.log("AlbumArt", "Cache HIT for '$cleanedQuery' -> $it")
            return@withContext it
        }

        ThumbnailDiagnosticManager.log("AlbumArt", "Cache MISS for '$cleanedQuery', querying networks...")
        var resolvedRemoteUrl: String? = null

        // 2. Try Spotify Web API first (if token is valid and developer account allows)
        val token = getValidToken()
        if (token != null) {
            try {
                ThumbnailDiagnosticManager.log("SpotifySearch", "Searching Spotify for '$cleanedQuery'...")
                val response = searchService.searchTrack(
                    authorization = "Bearer $token",
                    query = cleanedQuery
                )
                val spotifyArt = response.tracks?.items?.firstOrNull()?.album?.images?.firstOrNull()?.url
                if (!spotifyArt.isNullOrEmpty()) {
                    resolvedRemoteUrl = spotifyArt
                    ThumbnailDiagnosticManager.log("SpotifySearch", "Spotify art found: $spotifyArt")
                } else {
                    ThumbnailDiagnosticManager.log("SpotifySearch", "Spotify 0 matching tracks")
                }
            } catch (e: Exception) {
                val httpEx = e as? retrofit2.HttpException
                val code = httpEx?.code()
                val body = httpEx?.response()?.errorBody()?.string()
                val msg = "Spotify search error: HTTP $code | ${e.message} | Body: $body"
                Log.d("SpotifyAuthManager", msg)
                ThumbnailDiagnosticManager.log("SpotifySearch", msg)
            }
        } else {
            ThumbnailDiagnosticManager.log("SpotifySearch", "No valid token, skipping Spotify search")
        }

        // 3. Try Spotify track link scraping + Spotify oEmbed CDN (directly from Spotify's database)
        if (resolvedRemoteUrl.isNullOrEmpty()) {
            try {
                val trackLink = getSpotifyTrackLink(filename)
                if (trackLink != null && trackLink.contains("open.spotify.com/track/")) {
                    ThumbnailDiagnosticManager.log("oEmbed", "Found track link: $trackLink, fetching oEmbed...")
                    val oEmbed = oembedService.getOEmbed(trackLink)
                    val oEmbedThumb = oEmbed.thumbnail_url
                    if (!oEmbedThumb.isNullOrEmpty()) {
                        // Upgrade 300x300 Spotify image to 640x640 primary artwork
                        resolvedRemoteUrl = oEmbedThumb.replace("1e02", "b273")
                        ThumbnailDiagnosticManager.log("oEmbed", "oEmbed art found: $resolvedRemoteUrl")
                    }
                }
            } catch (e: Exception) {
                val msg = "oEmbed error: ${e.message}"
                Log.d("SpotifyAuthManager", msg)
                ThumbnailDiagnosticManager.log("oEmbed", msg)
            }
        }

        // 4. Fallback to High-Definition Music Database (iTunes API 600x600 HD studio artwork)
        if (resolvedRemoteUrl.isNullOrEmpty()) {
            try {
                val itunesQuery = cleanedQuery.replace("-", " ").replace(",", " ").replace(Regex("\\s+"), " ").trim()
                ThumbnailDiagnosticManager.log("iTunes", "Searching iTunes for '$itunesQuery'...")
                val itunesResponse = itunesService.searchTrack(term = itunesQuery)
                val itunesArt = itunesResponse.results?.firstOrNull()?.artworkUrl100?.replace("100x100bb", "600x600bb")
                if (!itunesArt.isNullOrEmpty()) {
                    resolvedRemoteUrl = itunesArt
                    ThumbnailDiagnosticManager.log("iTunes", "iTunes art found: $itunesArt")
                } else {
                    ThumbnailDiagnosticManager.log("iTunes", "iTunes 0 results")
                }
            } catch (e: Exception) {
                val httpEx = e as? retrofit2.HttpException
                val code = httpEx?.code()
                val body = httpEx?.response()?.errorBody()?.string()
                val msg = "iTunes search error: HTTP $code | ${e.message} | Body: $body"
                Log.d("SpotifyAuthManager", msg)
                ThumbnailDiagnosticManager.log("iTunes", msg)
            }
        }

        if (resolvedRemoteUrl.isNullOrEmpty()) {
            ThumbnailDiagnosticManager.log("AlbumArt", "FAILED: No art found on any service for '$cleanedQuery'")
            return@withContext null
        }

        // 5. Download image permanently to local storage so it NEVER downloads again
        val localPath = downloadAndSaveThumbnail(cleanedQuery, resolvedRemoteUrl)
        if (localPath != null) {
            ThumbnailDiagnosticManager.log("AlbumArt", "SUCCESS: Saved to disk -> $localPath")
            cacheArt(cleanedQuery, localPath)
            return@withContext localPath
        } else {
            ThumbnailDiagnosticManager.log("AlbumArt", "Disk save failed, using remote URL: $resolvedRemoteUrl")
            cacheArt(cleanedQuery, resolvedRemoteUrl)
            return@withContext resolvedRemoteUrl
        }
    }

    suspend fun testSpotifyAuth(): String = withContext(Dispatchers.IO) {
        try {
            val authString = "$CLIENT_ID:$CLIENT_SECRET"
            val base64Auth = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)
            val response = authService.getAccessToken("Basic $base64Auth")
            "SUCCESS: Token acquired! Expires in ${response.expires_in}s, Type: ${response.token_type}"
        } catch (e: Exception) {
            val httpEx = e as? retrofit2.HttpException
            val code = httpEx?.code()
            val body = httpEx?.response()?.errorBody()?.string()
            "FAILED: HTTP $code | ${e.message} | Body: $body"
        }
    }

    suspend fun testITunesSearch(): String = withContext(Dispatchers.IO) {
        try {
            val response = itunesService.searchTrack(term = "Coldplay Yellow")
            val first = response.results?.firstOrNull()
            "SUCCESS: Found ${response.resultCount} results! Track: ${first?.trackName} by ${first?.artistName}"
        } catch (e: Exception) {
            val httpEx = e as? retrofit2.HttpException
            val code = httpEx?.code()
            val body = httpEx?.response()?.errorBody()?.string()
            "FAILED: HTTP $code | ${e.message} | Body: $body"
        }
    }

    /**
     * Resolves the shareable Spotify track link.
     */
    suspend fun getSpotifyTrackLink(filename: String): String? = withContext(Dispatchers.IO) {
        val cleanedQuery = cleanFilename(filename)
        if (cleanedQuery.isBlank()) return@withContext null

        // Try Spotify Web API if token is valid
        val token = getValidToken()
        if (token != null) {
            try {
                val response = searchService.searchTrack(
                    authorization = "Bearer $token",
                    query = cleanedQuery
                )
                val directLink = response.tracks?.items?.firstOrNull()?.external_urls?.get("spotify")
                if (!directLink.isNullOrEmpty()) {
                    return@withContext directLink
                }
            } catch (e: Exception) {
                // Ignore and proceed to web search fallback
            }
        }

        // Scrape DuckDuckGo Lite for the exact Spotify track link
        try {
            val url = java.net.URL("https://lite.duckduckgo.com/lite/")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "q=" + java.net.URLEncoder.encode("site:open.spotify.com/track $cleanedQuery", "UTF-8")
            connection.outputStream.use { it.write(postData.toByteArray()) }

            if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val matcher = java.util.regex.Pattern.compile("open\\.spotify\\.com(?:/|%2F)track(?:/|%2F)([a-zA-Z0-9]{22})").matcher(response)
                if (matcher.find()) {
                    return@withContext "https://open.spotify.com/track/${matcher.group(1)}"
                }
            }
        } catch (e: Exception) {
            Log.e("SpotifyError", "DDG Fallback Failed: ${e.message}", e)
        }

        // No valid track link found
        return@withContext null
    }

    /**
     * Clears all cached thumbnails in memory and on disk.
     */
    fun clearCache() {
        memoryCache.clear()
        prefs?.edit()?.clear()?.apply()
        try {
            thumbnailsDir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e("SpotifyAuthManager", "Error clearing local thumbnail files: ${e.message}", e)
        }
    }
}
