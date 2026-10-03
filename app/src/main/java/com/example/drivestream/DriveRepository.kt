package com.example.drivestream

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class DriveRepository(private val context: Context) {
    
    private var driveService: Drive? = null
    private var credential: GoogleAccountCredential? = null

    private var currentAccount: android.accounts.Account? = null
    val activeAccountEmail: String? get() = currentAccount?.name

    /**
     * Initializes the Google Drive v3 REST Client.
     */
    fun initializeDriveService(account: GoogleSignInAccount) {
        currentAccount = account.account
        credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_READONLY)
        ).apply {
            selectedAccount = account.account
        }

        driveService = Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("DriveStream")
            .build()
    }

    /**
     * Fetches folders and files matching audio MIME types from the specified folder.
     */
    suspend fun getFilesAndFolders(folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive API Client not initialized. Sign in first.")
        
        // Query for folders OR any audio files, restrict to the given folder
        val query = "'$folderId' in parents and (mimeType = 'application/vnd.google-apps.folder' or mimeType contains 'audio/' or name contains '.flac' or name contains '.wav') and trashed = false"
        
        val aggregatedFiles: MutableList<File> = mutableListOf()
        var pageToken: String? = null

        do {
            val response = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                // Re-fetch essential fields: id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata and thumbnailLink
                .setFields("nextPageToken, files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
                .set("supportsAllDrives", true)
                .set("includeItemsFromAllDrives", true)
                .setPageSize(1000)
                .setPageToken(pageToken)
                .execute()

            response.files?.let { aggregatedFiles.addAll(it) }
            pageToken = response.nextPageToken
        } while (pageToken != null)
            
        val apiFiles = deduplicateAudioFiles(aggregatedFiles)
        
        apiFiles.map { file ->
            val trackSize = file.getSize() ?: 0L
            val iso = file.modifiedTime?.toStringRfc3339() ?: ""
            DriveFile(
                fileId = file.id ?: "",
                name = file.name ?: "Unknown Track",
                size = trackSize,
                mimeType = file.mimeType ?: "",
                isFolder = file.mimeType == "application/vnd.google-apps.folder",
                parentId = file.parents?.firstOrNull() ?: "",
                modifiedTime = iso,
                displaySize = formatSize(trackSize),
                displayDate = formatDate(iso),
                thumbnailUrl = file.thumbnailLink,
                durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
            )
        }.sortedByDescending { it.isFolder } // Show folders first
    }

    /**
     * Fetches only audio files from a specific folder for playlist sequencing.
     */
    suspend fun getAudioFilesInFolder(folderId: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive API Client not initialized. Sign in first.")
        
        val query = "'$folderId' in parents and (mimeType contains 'audio/' or name contains '.flac' or name contains '.wav') and trashed = false"
        
        val aggregatedFiles: MutableList<File> = mutableListOf()
        var pageToken: String? = null

        do {
            val response = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("nextPageToken, files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
                .set("supportsAllDrives", true)
                .set("includeItemsFromAllDrives", true)
                .setPageSize(1000)
                .setPageToken(pageToken)
                .execute()

            response.files?.let { aggregatedFiles.addAll(it) }
            pageToken = response.nextPageToken
        } while (pageToken != null)
            
        val apiFiles = deduplicateAudioFiles(aggregatedFiles)
        
        apiFiles.map { file ->
            val trackSize = file.getSize() ?: 0L
            val iso = file.modifiedTime?.toStringRfc3339() ?: ""
            DriveFile(
                fileId = file.id ?: "",
                name = file.name ?: "Unknown Track",
                size = trackSize,
                mimeType = file.mimeType ?: "",
                isFolder = false,
                parentId = file.parents?.firstOrNull() ?: "",
                modifiedTime = iso,
                displaySize = formatSize(trackSize),
                displayDate = formatDate(iso),
                thumbnailUrl = file.thumbnailLink,
                durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
            )
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "Unknown Size"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val instant = Instant.parse(iso)
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(instant)
        } catch (e: Exception) {
            ""
        }
    }


    /**
     * Searches the entire Google Drive library for audio files matching the query string.
     * Not restricted to any folder — searches across all of the user's Drive.
     */
    suspend fun searchAudioFiles(query: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive API Client not initialized. Sign in first.")

        val safeQuery = query.replace("'", "\\'")
        val driveQuery = "name contains '$safeQuery' and (mimeType contains 'audio/' or name contains '.flac' or name contains '.wav') and trashed = false"

        val result = service.files().list()
            .setQ(driveQuery)
            .setSpaces("drive")
            .setFields("nextPageToken, files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
            .setOrderBy("name")
            .setPageSize(50)
            .execute()

        val apiFiles = deduplicateAudioFiles(result.files ?: emptyList())
        apiFiles.map { file ->
            val trackSize = file.getSize() ?: 0L
            val iso = file.modifiedTime?.toStringRfc3339() ?: ""
            DriveFile(
                fileId = file.id ?: "",
                name = file.name ?: "Unknown Track",
                size = trackSize,
                mimeType = file.mimeType ?: "",
                isFolder = false,
                parentId = file.parents?.firstOrNull() ?: "",
                modifiedTime = iso,
                displaySize = formatSize(trackSize),
                displayDate = formatDate(iso),
                thumbnailUrl = file.thumbnailLink,
                durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
            )
        }
    }

    /**
     * Finds an MP3 equivalent for a given lossless audio file based on its name.
     * Uses the parentId to search efficiently in the same folder.
     */
    suspend fun findMp3Equivalent(baseName: String, parentId: String): DriveFile? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null
        if (parentId.isEmpty()) return@withContext null
        
        val targetName = "$baseName.mp3"
        val safeQuery = targetName.replace("'", "\\'")
        val query = "name = '$safeQuery' and '$parentId' in parents and trashed = false"

        try {
            val result = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
                .set("supportsAllDrives", true)
                .set("includeItemsFromAllDrives", true)
                .setPageSize(1)
                .execute()
                
            val file = result.files?.firstOrNull() ?: return@withContext null
            val trackSize = file.getSize() ?: 0L
            val iso = file.modifiedTime?.toStringRfc3339() ?: ""
            DriveFile(
                fileId = file.id ?: "",
                name = file.name ?: targetName,
                size = trackSize,
                mimeType = file.mimeType ?: "audio/mpeg",
                isFolder = false,
                parentId = file.parents?.firstOrNull() ?: parentId,
                modifiedTime = iso,
                displaySize = formatSize(trackSize),
                displayDate = formatDate(iso),
                thumbnailUrl = file.thumbnailLink,
                durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds all MP3 files in a given folder in a single query.
     * Returns a map of filename to DriveFile for fast equivalent lookups.
     */
    suspend fun getMp3sInFolder(folderId: String): Map<String, DriveFile> = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext emptyMap()
        if (folderId.isEmpty()) return@withContext emptyMap()
        
        val query = "'$folderId' in parents and name contains '.mp3' and trashed = false"

        try {
            val aggregatedFiles: MutableList<File> = mutableListOf()
            var pageToken: String? = null

            do {
                val response = service.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
                    .set("supportsAllDrives", true)
                    .set("includeItemsFromAllDrives", true)
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.let { aggregatedFiles.addAll(it) }
                pageToken = response.nextPageToken
            } while (pageToken != null)
                
            aggregatedFiles.mapNotNull { file ->
                val trackSize = file.getSize() ?: 0L
                val iso = file.modifiedTime?.toStringRfc3339() ?: ""
                val name = file.name ?: return@mapNotNull null
                
                DriveFile(
                    fileId = file.id ?: "",
                    name = name,
                    size = trackSize,
                    mimeType = file.mimeType ?: "audio/mpeg",
                    isFolder = false,
                    parentId = file.parents?.firstOrNull() ?: folderId,
                    modifiedTime = iso,
                    displaySize = formatSize(trackSize),
                    displayDate = formatDate(iso),
                    thumbnailUrl = file.thumbnailLink,
                    durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
                )
            }.associateBy { it.name }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    var cachedMp3FolderId: String? = null

    /**
     * Resolves the folder ID of the centralized hidden "mp3" folder at the Drive root.
     * Returns null if no such folder exists.
     */
    suspend fun resolveMp3FolderId(): String? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null
        try {
            val result = service.files().list()
                .setQ("mimeType = 'application/vnd.google-apps.folder' and name = 'mp3' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id, name)")
                .set("supportsAllDrives", true)
                .set("includeItemsFromAllDrives", true)
                .setPageSize(1)
                .execute()
            val id = result.files?.firstOrNull()?.id
            cachedMp3FolderId = id
            id
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Looks up a single MP3 file by exact name inside the centralized mp3 folder.
     */
    suspend fun findMp3InCentralFolder(mp3Name: String, mp3FolderId: String): DriveFile? = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext null
        val safeName = mp3Name.replace("'", "\\'")
        val query = "'$mp3FolderId' in parents and name = '$safeName' and trashed = false"
        try {
            val result = service.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
                .set("supportsAllDrives", true)
                .set("includeItemsFromAllDrives", true)
                .setPageSize(1)
                .execute()
            val file = result.files?.firstOrNull() ?: return@withContext null
            val trackSize = file.getSize() ?: 0L
            val iso = file.modifiedTime?.toStringRfc3339() ?: ""
            DriveFile(
                fileId = file.id ?: "",
                name = file.name ?: mp3Name,
                size = trackSize,
                mimeType = file.mimeType ?: "audio/mpeg",
                isFolder = false,
                parentId = file.parents?.firstOrNull() ?: mp3FolderId,
                modifiedTime = iso,
                displaySize = formatSize(trackSize),
                displayDate = formatDate(iso),
                thumbnailUrl = file.thumbnailLink,
                durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Batch-fetches all MP3 files from the centralized mp3 folder.
     * Returns a map of filename to DriveFile for fast equivalent lookups.
     */
    suspend fun getMp3sInCentralFolder(mp3FolderId: String): Map<String, DriveFile> = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext emptyMap()
        if (mp3FolderId.isEmpty()) return@withContext emptyMap()

        val query = "'$mp3FolderId' in parents and name contains '.mp3' and trashed = false"

        try {
            val aggregatedFiles: MutableList<File> = mutableListOf()
            var pageToken: String? = null

            do {
                val response = service.files().list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id, name, size, mimeType, modifiedTime, parents, videoMediaMetadata, thumbnailLink)")
                    .set("supportsAllDrives", true)
                    .set("includeItemsFromAllDrives", true)
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.let { aggregatedFiles.addAll(it) }
                pageToken = response.nextPageToken
            } while (pageToken != null)

            aggregatedFiles.mapNotNull { file ->
                val trackSize = file.getSize() ?: 0L
                val iso = file.modifiedTime?.toStringRfc3339() ?: ""
                val name = file.name ?: return@mapNotNull null
                DriveFile(
                    fileId = file.id ?: "",
                    name = name,
                    size = trackSize,
                    mimeType = file.mimeType ?: "audio/mpeg",
                    isFolder = false,
                    parentId = file.parents?.firstOrNull() ?: mp3FolderId,
                    modifiedTime = iso,
                    displaySize = formatSize(trackSize),
                    displayDate = formatDate(iso),
                    thumbnailUrl = file.thumbnailLink,
                    durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
                )
            }.associateBy { it.name }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun deduplicateAudioFiles(apiFiles: List<File>): List<File> {
        val (folders, audio) = apiFiles.partition { it.mimeType == "application/vnd.google-apps.folder" }
        val deduplicatedAudio = audio.groupBy { it.name?.substringBeforeLast(".")?.trim() ?: "" }
            .map { (_, list) ->
                list.find { it.name?.endsWith(".flac", ignoreCase = true) == true || it.mimeType?.contains("flac") == true } 
                    ?: list.find { it.name?.endsWith(".wav", ignoreCase = true) == true || it.mimeType?.contains("wav") == true }
                    ?: list.first()
            }
        return folders + deduplicatedAudio
    }

    /**
     * Retrieves the true OAuth 2.0 Access Token for ExoPlayer streaming overriding the JWT idToken.
     */
    suspend fun getBearerToken(): String? = withContext(Dispatchers.IO) {
        val account = currentAccount ?: return@withContext null
        try {
            GoogleAuthUtil.getToken(
                context, 
                account, 
                "oauth2:${DriveScopes.DRIVE_READONLY}"
            )
        } catch (e: Exception) {
            // Rethrow UserRecoverableAuthException to be handled by the UI for consent
            if (e is UserRecoverableAuthException) {
                throw e
            }
            null
        }
    }

    /**
     * Streams the requested fileId directly from the Google Drive API into the device's public Music directory.
     * Overwrites any existing file with the same name. Returns the absolute path of the saved file on success.
     */
    suspend fun downloadFile(file: DriveFile, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive API Client not initialized.")
        val contentResolver = context.contentResolver
        
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, file.mimeType)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/DriveStream")
        }
        
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Unable to create MediaStore entry. Is storage available?")
            
        try {
            service.files().get(file.fileId)
                .set("acknowledgeAbuse", true)
                .set("supportsAllDrives", true)
                .executeMediaAsInputStream().use { inputStream ->
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesCopied = 0L
                        val totalBytes = file.size
                        var bytesRead: Int
                        
                        while (inputStream.read(buffer).also { bytesRead = it } >= 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesCopied += bytesRead
                            onProgress(bytesCopied, totalBytes)
                        }
                    } ?: throw IllegalStateException("Unable to open output stream to device storage.")
                }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
            
        return@withContext uri.toString()
    }
}

