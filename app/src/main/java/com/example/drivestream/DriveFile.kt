package com.example.drivestream

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Immutable
data class DriveFile(
    @SerializedName("fileId") val fileId: String,
    @SerializedName("name") val name: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("isFolder") val isFolder: Boolean = false,
    @SerializedName("parentId") val parentId: String = "",
    // Raw fields — kept for ExoPlayer media item construction and sorting logic
    @SerializedName("size") val size: Long = 0L,
    @SerializedName("modifiedTime") val modifiedTime: String = "",
    // Pre-computed display strings — formatted once in the repository, never inside Compose
    @SerializedName("displaySize") val displaySize: String = "",
    @SerializedName("displayDate") val displayDate: String = "",
    @SerializedName("albumArtUrl") val albumArtUrl: String? = null,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("durationMs") val durationMs: Long = 0L
)

typealias DriveAudioFile = DriveFile

