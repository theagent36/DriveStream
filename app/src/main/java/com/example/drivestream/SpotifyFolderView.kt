package com.example.drivestream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import androidx.media3.common.Player

private val SpotifyDarkBackground = Color(0xFF121212)
private val SpotifySecondaryText = Color(0xFFB3B3B3)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyFolderView(
    folderId: String,
    folderName: String,
    files: List<DriveFile>,
    songThumbnails: Map<String, String>,
    currentPlayingFileId: String?,
    isPlaying: Boolean,
    isShuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    activeAccount: GoogleSignInAccount?,
    onBackClick: () -> Unit,
    onFolderClick: (String, String) -> Unit,
    onFileClick: (DriveFile) -> Unit,
    onPlayFolderClick: (String, () -> Unit) -> Unit,
    onAddToQueue: (DriveFile) -> Unit,
    onDownload: (DriveFile) -> Unit,
    onShareTrack: (DriveFile) -> Unit,
    onToggleFavourite: (DriveFile) -> Unit,
    favourites: List<DriveFile>,
    onOpenSortMenu: () -> Unit,
    onRetryThumbnails: () -> Unit = {},
    isLoading: Boolean = false,
    player: Player? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    var filterQuery by remember { mutableStateOf("") }
    var showDetailsDialog by remember { mutableStateOf(false) }
    var lastPlayButtonClickTime by remember { mutableLongStateOf(0L) }

    // Rich atmospheric top gradient dynamically derived from the user's selected accent color
    val colorTop = remember(primaryColor) {
        Color(
            red = primaryColor.red * 0.45f,
            green = primaryColor.green * 0.45f,
            blue = primaryColor.blue * 0.45f,
            alpha = 1f
        )
    }
    val colorMid = remember(primaryColor) {
        Color(
            red = primaryColor.red * 0.20f,
            green = primaryColor.green * 0.20f,
            blue = primaryColor.blue * 0.20f,
            alpha = 1f
        )
    }

    val songs = remember(files) { files.filter { !it.isFolder } }
    val subfolders = remember(files) { files.filter { it.isFolder } }

    val filteredSongs = remember(songs, filterQuery) {
        if (filterQuery.isBlank()) songs
        else songs.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    TrackScrollStateForFling(listState)
    ThumbnailLookaheadPreloader(listState = listState, items = filteredSongs, lookaheadCount = 5)
    val musicNotePainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.MusicNote)

    // 2x2 Collage album covers: pick the first 4 unique covers
    val coverUrls = remember(songs, songThumbnails) {
        songs.mapNotNull { it.thumbnailUrl ?: songThumbnails[it.fileId] ?: it.albumArtUrl }
            .distinct()
            .take(4)
    }

    // Calculate total size for metadata row
    val totalSizeBytes = remember(songs) { songs.sumOf { it.size } }
    val formattedTotalSize = remember(totalSizeBytes) {
        if (totalSizeBytes >= 1024L * 1024L * 1024L) {
            String.format("%.1f GB", totalSizeBytes / (1024.0 * 1024.0 * 1024.0))
        } else if (totalSizeBytes >= 1024L * 1024L) {
            String.format("%d MB", totalSizeBytes / (1024 * 1024))
        } else {
            "${totalSizeBytes / 1024} KB"
        }
    }



    val isPlayingThisFolder = remember(songs, currentPlayingFileId) {
        songs.any { it.fileId == currentPlayingFileId }
    }

    // Full screen background with smooth Spotify vertical gradient
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to colorTop,
                    0.28f to colorMid,
                    0.55f to SpotifyDarkBackground,
                    1.0f to SpotifyDarkBackground
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── TOP NAVIGATION ROW (Back Arrow & "Find in playlist" search pill) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            // "Find in playlist" search pill
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { innerTextField ->
                            if (filterQuery.isEmpty()) {
                                Text(
                                    text = "Find in playlist",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (filterQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { filterQuery = "" },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── SCROLLABLE PLAYLIST CONTENT ──
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                // 1. CENTERED COLLAGE COVER (Only displayed when music files exist in the playlist)
                if (songs.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            SpotifyPlaylistCollage(
                                coverUrls = coverUrls,
                                modifier = Modifier
                                    .size(210.dp)
                                    .shadow(elevation = 20.dp, shape = RoundedCornerShape(4.dp))
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                    }
                }

                // 2. PLAYLIST TITLE & METADATA
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = folderName,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Creator row (User profile avatar + name)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF333333)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (activeAccount?.photoUrl != null) {
                                    AsyncImage(
                                        model = activeAccount.photoUrl,
                                        contentDescription = "Profile",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = activeAccount?.displayName ?: "DriveStream",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Stats row (Public/Globe icon + songs count + size)
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = SpotifySecondaryText,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${songs.size} songs • $formattedTotalSize",
                                color = SpotifySecondaryText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }

                // 3. CONSOLIDATED ACTION ROW (Chips on left, Shuffle + Dynamic Accent Play Button on right)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Horizontally scrollable chips on the left
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState())
                                .padding(end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SpotifyPillChip(
                                icon = Icons.Default.Add,
                                label = "Add",
                                onClick = {
                                    android.widget.Toast.makeText(context, "Upload songs to '$folderName' in Google Drive to add them.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            )
                            SpotifyPillChip(
                                icon = Icons.Default.Edit,
                                label = "Name & details",
                                onClick = {
                                    showDetailsDialog = true
                                }
                            )
                            SpotifyPillChip(
                                icon = Icons.AutoMirrored.Filled.Sort,
                                label = "Sort",
                                onClick = onOpenSortMenu
                            )
                        }

                        // Right action buttons: Shuffle + Play
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Shuffle Toggle Button
                            IconButton(onClick = onToggleShuffle) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffleEnabled) primaryColor else SpotifySecondaryText,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            // Big Dynamic Accent Play Button
                            val loadPlaylistAndPlay: () -> Unit = {
                                if (songs.isNotEmpty()) {
                                    onFileClick(songs.first())
                                } else {
                                    onPlayFolderClick(folderId) {
                                        android.widget.Toast.makeText(context, "No audio files found in this folder.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .shadow(8.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(primaryColor)
                                    .clickable {
                                        // 3. Debounce the Input: ignore subsequent clicks within 300ms
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastPlayButtonClickTime < 300L) {
                                            return@clickable
                                        }
                                        lastPlayButtonClickTime = now

                                        if (player == null) {
                                            loadPlaylistAndPlay()
                                            return@clickable
                                        }

                                        // 1. Refactor Click Listener: Read exact state directly from the player
                                        val isCurrentlyPlaying = player.isPlaying

                                        val playlistFileIds = songs.map { it.fileId }.toSet()
                                        val currentMediaId = player.currentMediaItem?.mediaId
                                        val queueContainsThisPlaylist = playlistFileIds.isNotEmpty() && (
                                            (currentMediaId != null && playlistFileIds.contains(currentMediaId)) ||
                                            (0 until player.mediaItemCount).any { idx ->
                                                playlistFileIds.contains(player.getMediaItemAt(idx).mediaId)
                                            }
                                        )
                                        val isPlayerActive = player.playbackState != Player.STATE_IDLE &&
                                                player.playbackState != Player.STATE_ENDED &&
                                                player.mediaItemCount > 0

                                        // 2. Implement Strict Execution Branches:
                                        if (queueContainsThisPlaylist && isPlayerActive) {
                                            if (isCurrentlyPlaying) {
                                                // Branch A (Pause): If isCurrentlyPlaying is true, strictly execute player.pause() and return@onClick.
                                                player.pause()
                                                return@clickable
                                            } else {
                                                // Branch B (Resume): If isCurrentlyPlaying is false AND the current ExoPlayer queue already contains this playlist's media items, strictly execute player.play() and return@onClick.
                                                player.play()
                                                return@clickable
                                            }
                                        } else {
                                            // Branch C (Start Fresh): Only if the player is idle, stopped, or playing a completely different playlist, execute the heavy loadPlaylistAndPlay() sequence.
                                            loadPlaylistAndPlay()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlayingThisFolder && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingThisFolder && isPlaying) "Pause" else "Play",
                                    tint = onPrimaryColor,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                // 5. SUBFOLDERS SECTION (if folder has subfolders)
                if (subfolders.isNotEmpty()) {
                    items(subfolders, key = { it.fileId }) { subfolder ->
                        SpotifySubfolderRow(
                            folder = subfolder,
                            onClick = { onFolderClick(subfolder.fileId, subfolder.name) }
                        )
                    }
                }

                // 6. SONGS LIST
                if (isLoading) {
                    items(6) {
                        SkeletonFileRow()
                    }
                } else if (filteredSongs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (filterQuery.isNotEmpty()) "No songs match \"$filterQuery\"" else "No songs in this folder",
                                color = SpotifySecondaryText,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(
                        items = filteredSongs,
                        key = { if (it.fileId.isNotEmpty()) it.fileId else it.hashCode() },
                        contentType = { 1 }
                    ) { song ->
                        val isCurrentlyPlaying = song.fileId == currentPlayingFileId
                        SpotifySongItem(
                            song = song,
                            thumbnailUrl = song.thumbnailUrl ?: songThumbnails[song.fileId] ?: song.albumArtUrl,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isPlaying = isPlaying,
                            isFavourite = favourites.any { it.fileId == song.fileId },
                            onToggleFavourite = { onToggleFavourite(song) },
                            onClick = { onFileClick(song) },
                            onAddToQueue = { onAddToQueue(song) },
                            onDownload = { onDownload(song) },
                            onShare = { onShareTrack(song) },
                            musicNotePainter = musicNotePainter
                        )
                    }
                }
            }
        }
    }

    // Name & Details Dialog
    if (showDetailsDialog) {
        AlertDialog(
            onDismissRequest = { showDetailsDialog = false },
            title = { Text(text = "Playlist Details", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name: $folderName", fontWeight = FontWeight.SemiBold)
                    Text("Total Tracks: ${songs.size}")
                    Text("Total Size: $formattedTotalSize")
                    Text("Account: ${activeAccount?.email ?: "Unknown"}")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetailsDialog = false }) {
                    Text("OK", color = primaryColor)
                }
            }
        )
    }
}

/**
 * 2x2 Collage of up to 4 album artwork covers or single cover
 */
@Composable
private fun SpotifyPlaylistCollage(
    coverUrls: List<String>,
    modifier: Modifier = Modifier
) {
    fun toCoilData(url: String): Any {
        return if (url.startsWith("/")) java.io.File(url) else url
    }

    Box(
        modifier = modifier.background(Color(0xFF282828)),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrls.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(toCoilData(coverUrls[0]))
                            .crossfade(true)
                            .listener(onError = { _, r -> ThumbnailDiagnosticManager.log("Coil", "Collage[0] failed: ${r.throwable.message}") })
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(toCoilData(coverUrls[1]))
                            .crossfade(true)
                            .listener(onError = { _, r -> ThumbnailDiagnosticManager.log("Coil", "Collage[1] failed: ${r.throwable.message}") })
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(toCoilData(coverUrls[2]))
                            .crossfade(true)
                            .listener(onError = { _, r -> ThumbnailDiagnosticManager.log("Coil", "Collage[2] failed: ${r.throwable.message}") })
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(toCoilData(coverUrls[3]))
                            .crossfade(true)
                            .listener(onError = { _, r -> ThumbnailDiagnosticManager.log("Coil", "Collage[3] failed: ${r.throwable.message}") })
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        } else if (coverUrls.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(toCoilData(coverUrls.first()))
                    .crossfade(true)
                    .listener(onError = { _, r -> ThumbnailDiagnosticManager.log("Coil", "Collage single failed: ${r.throwable.message}") })
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF333333), Color(0xFF1E1E1E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }
}

/**
 * Spotify rounded pill chip button for secondary actions
 */
@Composable
private fun SpotifyPillChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.12f),
        border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Clean Spotify-styled song row
 */
@Composable
private fun SpotifySongItem(
    song: DriveFile,
    thumbnailUrl: String?,
    isCurrentlyPlaying: Boolean,
    isPlaying: Boolean,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    musicNotePainter: androidx.compose.ui.graphics.painter.Painter? = null
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    var expandedMenu by remember { mutableStateOf(false) }

    val cleanTitle = remember(song.name) {
        SpotifyAuthManager.cleanFilename(song.name).ifBlank { song.name.substringBeforeLast(".") }
    }

    val subtitle = remember(song.displaySize, song.displayDate) {
        when {
            song.displaySize.isNotEmpty() && song.displayDate.isNotEmpty() ->
                "${song.displaySize}  ·  ${song.displayDate}"
            song.displaySize.isNotEmpty() -> song.displaySize
            song.displayDate.isNotEmpty() -> song.displayDate
            else -> "DriveStream Audio"
        }
    }

    val effectiveThumbnail = remember(song.thumbnailUrl, thumbnailUrl, song.albumArtUrl) {
        song.thumbnailUrl ?: thumbnailUrl ?: song.albumArtUrl
    }
    val placeholderPainter = musicNotePainter ?: androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.MusicNote)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Left: 48x48 Thumbnail with rounded corners and playing overlay
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF282828)),
            contentAlignment = Alignment.Center
        ) {
            if (!effectiveThumbnail.isNullOrEmpty()) {
                AsyncImage(
                    model = effectiveThumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = placeholderPainter,
                    error = placeholderPainter
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isCurrentlyPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedEqualizer(isPlaying = isPlaying)
                }
            }
        }

        // Center: Title (Accent color if playing) and Subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanTitle,
                fontSize = 15.sp,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrentlyPlaying) primaryColor else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = SpotifySecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right: Three dots vertical menu
        Box {
            IconButton(onClick = { expandedMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = SpotifySecondaryText
                )
            }
            if (expandedMenu) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { expandedMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isFavourite) "Remove from Favourites" else "Add to Favourites") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isFavourite) Color.Red else Color.Unspecified,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            expandedMenu = false
                            onToggleFavourite()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue") },
                        leadingIcon = { Icon(Icons.Default.QueueMusic, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            expandedMenu = false
                            onAddToQueue()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download Track") },
                        leadingIcon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            expandedMenu = false
                            onDownload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share Track") },
                        leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            expandedMenu = false
                            onShare()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Subfolder row item inside a Spotify playlist
 */
@Composable
private fun SpotifySubfolderRow(
    folder: DriveFile,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Folder",
                tint = primaryColor,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Folder · Tap to open",
                fontSize = 13.sp,
                color = SpotifySecondaryText
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = SpotifySecondaryText,
            modifier = Modifier.size(20.dp)
        )
    }
}
