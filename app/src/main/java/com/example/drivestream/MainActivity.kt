package com.example.drivestream

import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.drivestream.ui.theme.DriveStreamTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.api.services.drive.DriveScopes

fun Modifier.bounceClick(
    scaleDown: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bounce_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled,
            onClick = onClick
        )
}

@Composable
fun AnimatedPlayPauseIcon(
    isPlaying: Boolean,
    contentDescription: String?,
    tint: Color = LocalContentColor.current,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (scaleIn(initialScale = 0.72f, animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                fadeIn(animationSpec = tween(140))) togetherWith
                (scaleOut(targetScale = 0.72f, animationSpec = tween(130)) +
                    fadeOut(animationSpec = tween(120)))
        },
        label = "play_pause_morph"
    ) { playing ->
        Icon(
            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier
        )
    }
}

@Composable
fun AnimatedFavoriteButton(
    isFavourite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    activeColor: Color = Color.Red,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    var firstRender by remember { mutableStateOf(true) }
    val favScale = remember { Animatable(1f) }
    LaunchedEffect(isFavourite) {
        if (firstRender) {
            firstRender = false
        } else {
            favScale.snapTo(1.28f)
            favScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }
    val favColor by animateColorAsState(
        targetValue = if (isFavourite) activeColor else inactiveColor,
        animationSpec = tween(durationMillis = 200),
        label = "fav_color"
    )

    IconButton(
        onClick = onToggle,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavourite) "Remove from Favourites" else "Add to Favourites",
            tint = favColor,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = favScale.value
                    scaleY = favScale.value
                }
        )
    }
}

data class FolderRouteState(
    val folderId: String,
    val folderName: String,
    val isSpotifyView: Boolean,
    val files: List<DriveFile>,
    val isLoading: Boolean,
    val errorMessage: String?
)

data class MiniPlayerTrackSnapshot(
    val fileId: String,
    val title: String,
    val artist: String,
    val artUrl: String?,
    val artworkData: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MiniPlayerTrackSnapshot
        if (fileId != other.fileId) return false
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (artUrl != other.artUrl) return false
        if (artworkData != null) {
            if (other.artworkData == null) return false
            if (!artworkData.contentEquals(other.artworkData)) return false
        } else if (other.artworkData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fileId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + (artUrl?.hashCode() ?: 0)
        result = 31 * result + (artworkData?.contentHashCode() ?: 0)
        return result
    }
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val workRequest = androidx.work.PeriodicWorkRequestBuilder<CacheCleanupWorker>(1, java.util.concurrent.TimeUnit.DAYS)
            .setConstraints(androidx.work.Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
                .build())
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "CacheCleanup",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )

        val shouldBypassSplash = savedInstanceState != null || hasShownColdBootSplash

        setContent {
            val viewModel: DriveViewModel = viewModel()
            // Compose-level splash: show animated overlay for cold boot then fade out
            var showSplash by remember { mutableStateOf(!shouldBypassSplash) }
            LaunchedEffect(Unit) {
                if (showSplash) {
                    hasShownColdBootSplash = true
                    kotlinx.coroutines.delay(1400) // Full ripple ring cascade + logo reveal
                    showSplash = false
                }
            }
            val appTheme by viewModel.theme.collectAsState()
            val accentColor by viewModel.accentColor.collectAsState()
            val sortState by viewModel.sortState.collectAsState()
            val searchQuery by viewModel.searchQuery.collectAsState()
            val searchState by viewModel.searchState.collectAsState()
            val currentPlayingFileId by viewModel.currentPlayingFileId.collectAsState()
            val currentPlayingTrack by viewModel.currentPlayingTrack.collectAsState()
            val activeTrackDurationMs by viewModel.activeTrackDurationMs.collectAsState()
            val isPlaying by viewModel.isPlaying.collectAsState()
            val sessionRole by viewModel.sessionRole.collectAsState()
            val roomId by viewModel.roomId.collectAsState()
            val playbackErrorMessage by viewModel.playbackErrorMessage.collectAsState()
            val currentIndex by viewModel.currentIndex.collectAsState()
            val currentPlayingFolderName by viewModel.currentPlayingFolderName.collectAsState()
            val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
            
            var fileToDownload by remember { mutableStateOf<DriveFile?>(null) }
            val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                fileToDownload?.let { file ->
                    viewModel.downloadFile(file) { path ->
                        android.widget.Toast.makeText(this@MainActivity, "Saved to Android/Media", android.widget.Toast.LENGTH_LONG).show()
                    }
                    fileToDownload = null
                }
            }

            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || 
                        event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                        viewModel.saveSessionPlaybackState()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            DriveStreamTheme(appTheme = appTheme, accentColor = accentColor) {
                val uiState by viewModel.uiState.collectAsState()
                val activeAccount by viewModel.activeAccount.collectAsState()
                val isInitialFolderReady by viewModel.isInitialFolderReady.collectAsState()

                var isPlayerExpanded by remember { mutableStateOf(false) }
                var sheetReleaseVelocityY by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(isPlayerExpanded) {
                    if (isPlayerExpanded) {
                        sheetReleaseVelocityY = 0f
                    }
                }
                val exitVelocityFactor = (sheetReleaseVelocityY / 3500f).coerceIn(0f, 1f)
                val sheetExitDurationMs = (260f - (120f * exitVelocityFactor)).toInt().coerceIn(140, 260)
                val coroutineScope = rememberCoroutineScope()

                val currentFolder by viewModel.currentFolder.collectAsState()
                val isShuffleEnabled by viewModel.isShuffleEnabled.collectAsState()

                androidx.activity.compose.BackHandler(
                    enabled = (currentFolder.first != "root") || (uiState is DriveUiState.Success) || isPlayerExpanded
                ) {
                    if (isPlayerExpanded) {
                        sheetReleaseVelocityY = 0f
                        isPlayerExpanded = false
                    } else {
                        val handled = viewModel.navigateBack()
                        if (!handled) {
                            finish() // Exit app if backstack is empty
                        }
                    }
                }

                val favourites by viewModel.favourites.collectAsState()
                val songThumbnails by viewModel.songThumbnails.collectAsState()
                val musicFolderPromptShown by viewModel.musicFolderPromptShown.collectAsState()
                val defaultMusicFolderName by viewModel.defaultMusicFolderName.collectAsState()
                val isDataSaverEnabled by viewModel.isDataSaverEnabled.collectAsState()
                val cacheSizeText by viewModel.cacheSizeText.collectAsState()
                val isShareLoading by viewModel.isShareLoading.collectAsState()
                val context = androidx.compose.ui.platform.LocalContext.current
                
                val eqBands by viewModel.eqBands.collectAsState()
                val eqRange by viewModel.eqRange.collectAsState()
                val eqSettings by viewModel.eqSettings.collectAsState()
                val eqPresets by viewModel.eqPresets.collectAsState()
                val eqError by viewModel.eqError.collectAsState()
                val eqEnabled by viewModel.eqEnabled.collectAsState()
                val eqPresetName by viewModel.eqPresetName.collectAsState()

                // Show a Snackbar when ExoPlayer surfaces a playback error
                LaunchedEffect(playbackErrorMessage) {
                    val msg = playbackErrorMessage ?: return@LaunchedEffect
                    if (msg.startsWith("Data Saver:")) {
                        val job = launch {
                            snackbarHostState.showSnackbar(message = msg, duration = androidx.compose.material3.SnackbarDuration.Indefinite)
                        }
                        kotlinx.coroutines.delay(1500)
                        job.cancel()
                    } else {
                        snackbarHostState.showSnackbar(message = msg, duration = androidx.compose.material3.SnackbarDuration.Long)
                    }
                    viewModel.clearPlaybackError()
                }

                val controller by viewModel.player.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            MainScreen(
                                modifier = Modifier.fillMaxSize(),
                                player = controller,
                                favourites = favourites,
                        songThumbnails = songThumbnails,
                        currentFolder = currentFolder,
                        isShuffleEnabled = isShuffleEnabled,
                        onToggleShuffle = { viewModel.toggleShuffle() },
                        onBackClick = { viewModel.navigateBack() },
                        musicFolderPromptShown = musicFolderPromptShown,
                        defaultMusicFolderName = defaultMusicFolderName,
                        onDismissMusicFolderPrompt = { viewModel.dismissMusicFolderPrompt() },
                        onSetDefaultMusicFolder = { id, name -> viewModel.setDefaultMusicFolder(id, name) },
                        onClearDefaultMusicFolder = { viewModel.setDefaultMusicFolder(null, null) },
                        onRefreshLibrary = { viewModel.refreshCurrentFolder() },
                        uiState = uiState,
                        activeAccount = activeAccount,
                        isInitialFolderReady = isInitialFolderReady,
                        currentTheme = appTheme,
                        accentColor = accentColor,
                        sortState = sortState,
                        searchQuery = searchQuery,
                        searchState = searchState,
                        currentPlayingFileId = currentPlayingFileId,
                        isPlaying = isPlaying,
                        onSearchQueryChanged = { viewModel.setSearchQuery(it) },
                        onClearSearch = { viewModel.clearSearch() },
                        onSignInClick = { launchGoogleSignIn(viewModel) },
                        onFolderClick = { folderId, folderName -> viewModel.openFolder(folderId, folderName) },
                        onFileClick = { file -> 
                            if (sessionRole == SessionRole.GUEST) {
                                android.widget.Toast.makeText(this@MainActivity, "You are listening along! Leave the session to play other tracks.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.playAudio(file)
                            }
                        },
                        onPlayFolderClick = { folderId, onEmpty -> 
                            if (sessionRole == SessionRole.GUEST) {
                                android.widget.Toast.makeText(this@MainActivity, "You are listening along! Leave the session to play other tracks.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.playFolder(folderId, onEmpty)
                            }
                        },
                        onAddToQueue = { file -> 
                            if (sessionRole == SessionRole.GUEST) {
                                android.widget.Toast.makeText(this@MainActivity, "Guests cannot modify the queue.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.addToQueue(file)
                            }
                        },
                        onDownload = { file ->
                            if (sessionRole == SessionRole.GUEST) {
                                android.widget.Toast.makeText(this@MainActivity, "Guests cannot download files.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(this@MainActivity, "Downloading ${file.name}...", android.widget.Toast.LENGTH_SHORT).show()
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && 
                                    androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    fileToDownload = file
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.downloadFile(file) { path ->
                                        android.widget.Toast.makeText(this@MainActivity, "Saved to Downloads/DriveStream", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onSortByName = { ascending -> viewModel.sortByName(ascending) },
                        onSortByDate = { viewModel.sortByDate() },
                        onPlayAllClick = { 
                            if (sessionRole == SessionRole.GUEST) {
                                android.widget.Toast.makeText(this@MainActivity, "You are listening along! Leave the session to play other tracks.", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.playAll() 
                            }
                        },
                        onSignOutClick = { viewModel.signOut() },
                        onThemeSelected = { newTheme -> viewModel.setTheme(newTheme) },
                        onAccentColorSelected = { newColor -> viewModel.setAccentColor(newColor) },
                        isShareLoading = isShareLoading,
                        onShareTrack = { file -> viewModel.shareTrackWithLink(context, file) },
                        sessionRole = sessionRole,
                        roomId = roomId,
                        onHostSession = { viewModel.hostSession() },
                        onJoinSession = { code -> viewModel.joinSession(code) },
                        onLeaveSession = { viewModel.leaveSession() },
                        onToggleFavourite = { viewModel.toggleFavourite(it) },
                        onOpenFavourites = { viewModel.openFolder("FAVOURITES", "My Favourites") },
                        isDataSaverEnabled = isDataSaverEnabled,
                        onToggleDataSaver = { viewModel.setDataSaverEnabled(it) },
                        cacheSizeText = cacheSizeText,
                        onClearCache = { viewModel.clearCacheNow() },
                        eqBands = eqBands,
                        eqRange = eqRange,
                        eqSettings = eqSettings,
                        eqPresets = eqPresets,
                        eqError = eqError,
                        eqEnabled = eqEnabled,
                        eqPresetName = eqPresetName,
                        onToggleEqualizer = { viewModel.toggleEqualizer(it) },
                        onSetEqBandLevel = { band, level -> viewModel.setEqBandLevel(band, level) },
                        onUseEqPreset = { preset -> viewModel.useEqPreset(preset) },
                        onResetEqualizer = { viewModel.resetEqualizer() },
                        onRetryThumbnails = { viewModel.retryLoadingCurrentThumbnails() }
                    )
                        } // End of MainScreen Box

                        // Mini Player at the bottom of the Column
                        AnimatedVisibility(
                            visible = !currentPlayingFileId.isNullOrEmpty(),
                            enter = slideInVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                initialOffsetY = { fullHeight -> fullHeight }
                            ) + fadeIn(animationSpec = tween(220)),
                            exit = slideOutVertically(
                                animationSpec = tween(200, easing = FastOutLinearInEasing),
                                targetOffsetY = { fullHeight -> fullHeight }
                            ) + fadeOut(animationSpec = tween(180))
                        ) {
                            val controller by viewModel.player.collectAsState()
                            val currentSpotifyArtUrl by viewModel.currentSpotifyArtUrl.collectAsState()
                            MiniPlayer(
                                controller = controller,
                                fallbackDurationMs = activeTrackDurationMs,
                                currentSpotifyArtUrl = currentSpotifyArtUrl,
                                songThumbnails = songThumbnails,
                                sessionRole = sessionRole,
                                currentPlayingFileId = currentPlayingFileId,
                                currentPlayingTrack = currentPlayingTrack,
                                currentTrackIndex = currentIndex,
                                currentPlaylistName = currentPlayingFolderName,
                                onExpand = { isPlayerExpanded = true }
                            )
                        }
                    } // End of Column

                    // Full-screen player overlay at the top Z-level
                    AnimatedVisibility(
                        visible = isPlayerExpanded,
                        enter = slideInVertically(
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            initialOffsetY = { fullHeight -> fullHeight }
                        ) + fadeIn(animationSpec = tween(durationMillis = 220)),
                        exit = slideOutVertically(
                            animationSpec = tween(durationMillis = sheetExitDurationMs, easing = FastOutLinearInEasing),
                            targetOffsetY = { fullHeight -> fullHeight }
                        ) + fadeOut(
                            animationSpec = tween(
                                durationMillis = (sheetExitDurationMs * 0.85f).toInt(),
                                easing = FastOutLinearInEasing
                            )
                        ),
                        modifier = Modifier.fillMaxSize().zIndex(100f) // ensure it overlays everything
                    ) {
                        val controller by viewModel.player.collectAsState()
                        val upcomingQueue by viewModel.upcomingQueue.collectAsState()
                        val currentSpotifyArtUrl by viewModel.currentSpotifyArtUrl.collectAsState()
                        val sleepTimerRemaining by viewModel.sleepTimerRemaining.collectAsState()
                        val sleepTimerTotal by viewModel.sleepTimerTotal.collectAsState()
                        val sleepTimerPauseAfterTrack by viewModel.sleepTimerPauseAfterTrack.collectAsState()
                        val queueListState = androidx.compose.foundation.lazy.rememberLazyListState()

                        Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            PlaybackBottomSheet(
                                controller = controller,
                                fallbackDurationMs = activeTrackDurationMs,
                                upcomingQueue = upcomingQueue,
                                currentSpotifyArtUrl = currentSpotifyArtUrl,
                                songThumbnails = songThumbnails,
                                sleepTimerRemaining = sleepTimerRemaining,
                                sleepTimerTotal = sleepTimerTotal,
                                sleepTimerPauseAfterTrack = sleepTimerPauseAfterTrack,
                                currentPlayingFolderName = currentPlayingFolderName,
                                isCurrentTrackFavourite = favourites.any { it.fileId == currentPlayingFileId },
                                onToggleCurrentFavourite = {
                                    viewModel.toggleCurrentFavourite()
                                },
                                sessionRole = sessionRole,
                                onNeedsTokenRefresh = { viewModel.refreshAuthTokenAndRetry() },
                                onClearQueue = { viewModel.clearQueue() },
                                onPlayQueueItem = { file -> viewModel.playAudio(file, fromQueue = true) },
                                onCollapse = { releaseVelocityY ->
                                    sheetReleaseVelocityY = releaseVelocityY
                                    isPlayerExpanded = false
                                },
                                onSetSleepTimer = { mins -> viewModel.setSleepTimer(mins) },
                                onCancelSleepTimer = { viewModel.cancelSleepTimer() },
                                onSetSleepTimerBehavior = { b -> viewModel.setSleepTimerBehavior(b) },
                                isShuffleEnabled = isShuffleEnabled,
                                onToggleShuffle = { viewModel.toggleShuffle() },
                                listState = queueListState
                            )
                        }
                    } // End of AnimatedVisibility
                    
                    // Top-aligned Snackbar anchored safely above the layout bounds
                    androidx.compose.material3.SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
                    )
                } // End of Box
            } // End of DriveStreamTheme

            // ── COMPOSE SPLASH OVERLAY ──────────────────────────────────────
            // No enter animation — appears instantly to seamlessly match the
            // system launch icon position. Only fades OUT when done.
            AnimatedVisibility(
                visible = showSplash,
                enter = EnterTransition.None,
                exit = fadeOut(animationSpec = tween(600, easing = FastOutSlowInEasing)),
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(999f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    SplashScreenContent()
                }
            }
        } // End of setContent
    } // End of onCreate

    private fun launchGoogleSignIn(viewModel: DriveViewModel) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_READONLY))
            .build()
        val googleSignInClient = GoogleSignIn.getClient(this, gso)
        startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN_REQUEST_CODE) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val viewModel = androidx.lifecycle.ViewModelProvider(this)[DriveViewModel::class.java]
            try {
                val account = task.getResult(ApiException::class.java)
                account?.let { viewModel.onSignInSuccess(it) }
            } catch (e: ApiException) {
                viewModel.onSignInFailed("Sign-in failed: ${e.statusCode}")
            }
        }
    }

    companion object {
        private const val SIGN_IN_REQUEST_CODE = 1001
        private var hasShownColdBootSplash = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    player: androidx.media3.common.Player? = null,
    favourites: List<DriveFile> = emptyList(),
    songThumbnails: Map<String, String> = emptyMap(),
    currentFolder: Pair<String, String> = Pair("root", "My Drive"),
    isShuffleEnabled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    onBackClick: () -> Unit = {},
    musicFolderPromptShown: Boolean = true,
    defaultMusicFolderName: String? = null,
    onDismissMusicFolderPrompt: () -> Unit = {},
    onSetDefaultMusicFolder: (String, String) -> Unit = { _, _ -> },
    onClearDefaultMusicFolder: () -> Unit = {},
    onRefreshLibrary: () -> Unit = {},
    uiState: DriveUiState,
    activeAccount: com.google.android.gms.auth.api.signin.GoogleSignInAccount?,
    isInitialFolderReady: Boolean = true,
    currentTheme: AppTheme,
    accentColor: AccentColor,
    sortState: SortState,
    searchQuery: String,
    searchState: SearchState,
    currentPlayingFileId: String?,
    isPlaying: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSignInClick: () -> Unit,
    onFolderClick: (String, String) -> Unit,
    onFileClick: (DriveFile) -> Unit,
    onPlayFolderClick: (String, () -> Unit) -> Unit,
    onAddToQueue: (DriveFile) -> Unit,
    onDownload: (DriveFile) -> Unit,
    onToggleFavourite: (DriveFile) -> Unit = {},
    onOpenFavourites: () -> Unit = {},
    onSortByName: (Boolean) -> Unit,
    onSortByDate: () -> Unit,
    onPlayAllClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onThemeSelected: (AppTheme) -> Unit,
    onAccentColorSelected: (AccentColor) -> Unit,
    isShareLoading: Boolean = false,
    onShareTrack: (DriveFile) -> Unit = {},
    sessionRole: SessionRole = SessionRole.NONE,
    roomId: String? = null,
    onHostSession: () -> String,
    onJoinSession: (String) -> Unit,
    onLeaveSession: () -> Unit,
    isDataSaverEnabled: Boolean,
    onToggleDataSaver: (Boolean) -> Unit,
    cacheSizeText: String,
    onClearCache: () -> Unit,
    eqBands: List<Pair<Short, Int>> = emptyList(),
    eqRange: Pair<Short, Short> = Pair(0, 0),
    eqSettings: Map<Short, Short> = emptyMap(),
    eqPresets: List<Pair<Short, String>> = emptyList(),
    eqError: String? = null,
    eqEnabled: Boolean = false,
    eqPresetName: String = "Custom",
    onToggleEqualizer: (Boolean) -> Unit = {},
    onSetEqBandLevel: (Short, Short) -> Unit = { _, _ -> },
    onUseEqPreset: (Short) -> Unit = {},
    onResetEqualizer: () -> Unit = {},
    onRetryThumbnails: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isUserSignedIn = activeAccount != null || GoogleSignIn.getLastSignedInAccount(context) != null
    val musicNotePainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.MusicNote)

    var showSortMenu by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showMusicFolderPromptManual by remember { mutableStateOf(false) }

    val currentFiles = (uiState as? DriveUiState.Success)?.files ?: emptyList()
    val knownPlaylistFolders = remember { mutableStateMapOf<String, Boolean>() }
    val isSpotifyPlaylistView = remember(currentFolder.first, uiState, searchQuery) {
        if (uiState is DriveUiState.Idle || currentFolder.first == "root" || searchQuery.length >= 2) {
            false
        } else if (uiState is DriveUiState.Success) {
            val hasSongs = currentFiles.any { !it.isFolder }
            knownPlaylistFolders[currentFolder.first] = hasSongs
            hasSongs
        } else {
            knownPlaylistFolders[currentFolder.first] == true
        }
    }

    val currentRouteState = FolderRouteState(
        folderId = currentFolder.first,
        folderName = currentFolder.second,
        isSpotifyView = isSpotifyPlaylistView,
        files = currentFiles,
        isLoading = uiState is DriveUiState.Loading,
        errorMessage = (uiState as? DriveUiState.Error)?.message
    )

    val navStackHistory = remember { mutableStateListOf<String>() }
    val isNavigatingBack = remember(currentFolder.first) {
        val existingIndex = navStackHistory.indexOf(currentFolder.first)
        if (existingIndex != -1 && existingIndex < navStackHistory.lastIndex) {
            while (navStackHistory.size > existingIndex + 1) {
                navStackHistory.removeAt(navStackHistory.lastIndex)
            }
            true
        } else {
            if (navStackHistory.lastOrNull() != currentFolder.first) {
                navStackHistory.add(currentFolder.first)
            }
            false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = currentRouteState,
            contentKey = { "${it.folderId}_${it.isSpotifyView}" },
            transitionSpec = {
                if (initialState.folderId == targetState.folderId) {
                    // Same folder switching between loading/playlist view: smooth cross-dissolve
                    fadeIn(
                        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing)
                    ) togetherWith fadeOut(
                        animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing)
                    )
                } else if (isNavigatingBack) {
                    // Navigating BACK: current screen fades out & slides right + scales down slightly,
                    // then previous screen fades in & glides from the left
                    val enter = slideInHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> -(fullWidth * 0.16f).toInt() }
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = 240,
                            delayMillis = 70,
                            easing = LinearOutSlowInEasing
                        )
                    )
                    val exit = slideOutHorizontally(
                        animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing),
                        targetOffsetX = { fullWidth -> (fullWidth * 0.18f).toInt() }
                    ) + fadeOut(
                        animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)
                    ) + scaleOut(
                        targetScale = 0.96f,
                        animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                    )
                    enter togetherWith exit
                } else {
                    // Navigating FORWARD: current screen fades out & slides left + scales down slightly,
                    // then new screen fades in & glides from the right
                    val enter = slideInHorizontally(
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        initialOffsetX = { fullWidth -> (fullWidth * 0.16f).toInt() }
                    ) + fadeIn(
                        animationSpec = tween(
                            durationMillis = 240,
                            delayMillis = 70,
                            easing = LinearOutSlowInEasing
                        )
                    )
                    val exit = slideOutHorizontally(
                        animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing),
                        targetOffsetX = { fullWidth -> -(fullWidth * 0.18f).toInt() }
                    ) + fadeOut(
                        animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)
                    ) + scaleOut(
                        targetScale = 0.96f,
                        animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                    )
                    enter togetherWith exit
                }
            },
            label = "folder_page_transition"
        ) { page ->
        if (page.isSpotifyView) {
            SpotifyFolderView(
                folderId = page.folderId,
                folderName = page.folderName,
                files = page.files,
                songThumbnails = songThumbnails,
                currentPlayingFileId = currentPlayingFileId,
                isPlaying = isPlaying,
                isShuffleEnabled = isShuffleEnabled,
                onToggleShuffle = onToggleShuffle,
                activeAccount = activeAccount,
                onBackClick = onBackClick,
                onFolderClick = onFolderClick,
                onFileClick = onFileClick,
                onPlayFolderClick = onPlayFolderClick,
                onAddToQueue = onAddToQueue,
                onDownload = onDownload,
                onShareTrack = onShareTrack,
                onToggleFavourite = onToggleFavourite,
                favourites = favourites,
                onOpenSortMenu = { showSortMenu = true },
                onRetryThumbnails = onRetryThumbnails,
                isLoading = page.isLoading,
                player = player,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val pageListState = remember(page.folderId) { androidx.compose.foundation.lazy.LazyListState() }
            TrackScrollStateForFling(pageListState)
            val filesForPreload = when {
                searchQuery.length >= 2 && searchState is SearchState.Results -> searchState.files
                else -> page.files
            }
            ThumbnailLookaheadPreloader(listState = pageListState, items = filesForPreload, lookaheadCount = 5)

            // After sorting, scroll to the top of the newly sorted list.
            LaunchedEffect(sortState) {
                pageListState.scrollToItem(0)
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (!isInitialFolderReady) "" else if (page.folderId == "root") "Drive Library" else page.folderName,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            navigationIcon = {
                                if (page.folderId != "root") {
                                    IconButton(onClick = onBackClick) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (uiState !is DriveUiState.Idle) {
                                    Box {
                                        IconButton(onClick = { showSortMenu = true }) {
                                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                                        }
                                    }
                                    IconButton(onClick = onOpenFavourites) {
                                        Icon(Icons.Default.FavoriteBorder, contentDescription = "Favourites")
                                    }
                                    IconButton(onClick = { showAccountSheet = true }) {
                                        if (activeAccount?.photoUrl != null) {
                                            coil.compose.AsyncImage(
                                                model = activeAccount.photoUrl,
                                                contentDescription = "Profile Picture",
                                                modifier = Modifier.size(32.dp).clip(CircleShape)
                                            )
                                        } else {
                                            Icon(Icons.Default.AccountCircle, contentDescription = "Account")
                                        }
                                    }
                                }
                            }
                        )
                        // Search bar below TopAppBar, visible when signed in
                        if (uiState !is DriveUiState.Idle) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChanged,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                placeholder = { Text("Search all audio files…") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = onClearSearch) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp)
                            )
                        }
                        if (isShareLoading) {
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } // end Column
                },
                floatingActionButton = {
                    val showFab = if (searchQuery.length >= 2) {
                        (searchState as? SearchState.Results)?.files?.any { !it.isFolder } == true
                    } else {
                        page.files.any { !it.isFolder }
                    }
                    AnimatedVisibility(
                        visible = showFab,
                        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioLowBouncy)) + fadeIn(),
                        exit = scaleOut() + fadeOut()
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = onPlayAllClick,
                            modifier = Modifier.bounceClick(scaleDown = 0.95f, onClick = onPlayAllClick),
                            icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Play All") },
                            text = { Text("Play All") },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            ) { paddingValues ->
                if (!isUserSignedIn) {
                    // ── LANDING / SIGN-IN SCREEN ─────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 40.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .clip(RoundedCornerShape(36.dp))
                                    .background(Color(0xFF1E1E1E)),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                                    contentDescription = "DriveStream Logo",
                                    modifier = Modifier.fillMaxSize(1.8f),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                )
                            }
                            Spacer(modifier = Modifier.height(32.dp))
                            Text(
                                text = "DriveStream",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Stream music directly from your Google Drive",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(48.dp))
                            Button(
                                onClick = onSignInClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Connect Google Drive",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = pageListState,
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                        contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
                    ) {
                        if (searchQuery.length >= 2) {
                            // --- SEARCH MODE ---
                            when (searchState) {
                                is SearchState.Loading -> {
                                    items(10) {
                                        SkeletonFileRow()
                                    }
                                }
                                is SearchState.Results -> {
                                    if (searchState.files.isEmpty()) {
                                        item {
                                            Text(
                                                "No audio files found for \"$searchQuery\".",
                                                modifier = Modifier.padding(16.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        item {
                                            Text(
                                                "${searchState.files.size} result${if (searchState.files.size == 1) "" else "s"}",
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        items(
                                            items = searchState.files, 
                                            key = { if (it.fileId.isNotEmpty()) it.fileId else it.hashCode() },
                                            contentType = { if (it.isFolder) 0 else 1 }
                                        ) { file ->
                                            val clickHandler = remember(file.fileId) { { onFileClick(file) } }
                                            SongListItem(
                                                file = file,
                                                isCurrentlyPlaying = file.fileId == currentPlayingFileId,
                                                isPlaying = isPlaying,
                                                isFavourite = favourites.any { it.fileId == file.fileId },
                                                onToggleFavourite = { onToggleFavourite(file) },
                                                onClick = clickHandler,
                                                onPlayFolderClick = {},
                                                onAddToQueue = { onAddToQueue(file) },
                                                onDownload = { onDownload(file) },
                                                onShare = { onShareTrack(file) },
                                                thumbnailUrl = file.thumbnailUrl ?: songThumbnails[file.fileId] ?: file.albumArtUrl,
                                                musicNotePainter = musicNotePainter,
                                                modifier = Modifier.animateItemPlacement(tween(240, easing = FastOutSlowInEasing))
                                            )
                                        }
                                    }
                                }
                                is SearchState.Error -> {
                                    item {
                                        Text(
                                            text = "Search error: ${searchState.message}",
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                                is SearchState.Idle -> { /* query too short — handled above */ }
                            }
                        } else {
                            // --- NORMAL BROWSE MODE ---
                            when {
                                page.isLoading -> {
                                    items(10) {
                                        SkeletonFileRow()
                                    }
                                }
                                page.errorMessage != null -> {
                                    item {
                                        Text(
                                            text = page.errorMessage,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }
                                page.files.isEmpty() -> {
                                    item {
                                        Text(
                                            "No audio files found in the specified folder.",
                                            modifier = Modifier.padding(16.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                else -> {
                                    items(
                                        items = page.files,
                                        key = { if (it.fileId.isNotEmpty()) it.fileId else it.hashCode() },
                                        contentType = { if (it.isFolder) 0 else 1 }
                                    ) { file ->
                                        val clickHandler = remember(file.fileId) {
                                            {
                                                if (file.isFolder) onFolderClick(file.fileId, file.name)
                                                else onFileClick(file)
                                            }
                                        }
                                        val playFolderHandler = remember(file.fileId) {
                                            {
                                                onPlayFolderClick(file.fileId) {
                                                    android.widget.Toast.makeText(context, "No audio files found in this folder.", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        SongListItem(
                                            file = file,
                                            isCurrentlyPlaying = file.fileId == currentPlayingFileId,
                                            isPlaying = isPlaying,
                                            isFavourite = favourites.any { it.fileId == file.fileId },
                                            onToggleFavourite = { onToggleFavourite(file) },
                                            onClick = clickHandler,
                                            onPlayFolderClick = playFolderHandler,
                                            onAddToQueue = { onAddToQueue(file) },
                                            onDownload = { onDownload(file) },
                                            onShare = { onShareTrack(file) },
                                            thumbnailUrl = file.thumbnailUrl ?: songThumbnails[file.fileId] ?: file.albumArtUrl,
                                            musicNotePainter = musicNotePainter,
                                            modifier = Modifier.animateItemPlacement(tween(240, easing = FastOutSlowInEasing))
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(130.dp)) // Padding to ensure last item is above bottom sheet
                        }
                    }
                } // end else (signed in)
            } // end Scaffold
        } // end else (Scaffold branch)
    } // end AnimatedContent
    } // end Box

    val sortSheetState = rememberModalBottomSheetState()
    val sortScope = rememberCoroutineScope()

    if (showSortMenu) {
        ModalBottomSheet(
            onDismissRequest = { showSortMenu = false },
            sheetState = sortSheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    text = "Sort by",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 24.dp, end = 24.dp)
                )
                
                // Title (A-Z)
                val isNameAsc = sortState.property == SortProperty.NAME && sortState.direction == SortDirection.ASCENDING
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            sortScope.launch { sortSheetState.hide() }.invokeOnCompletion {
                                showSortMenu = false
                                onSortByName(true)
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Title (A-Z)", 
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isNameAsc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isNameAsc) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                // Title (Z-A)
                val isNameDesc = sortState.property == SortProperty.NAME && sortState.direction == SortDirection.DESCENDING
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            sortScope.launch { sortSheetState.hide() }.invokeOnCompletion {
                                showSortMenu = false
                                onSortByName(false)
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Title (Z-A)", 
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isNameDesc) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isNameDesc) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                // Recently added / Date
                val isDateSort = sortState.property == SortProperty.DATE
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            sortScope.launch { sortSheetState.hide() }.invokeOnCompletion {
                                showSortMenu = false
                                onSortByDate()
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recently added", 
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isDateSort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (isDateSort) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (showAccountSheet) {
        val accountSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val accountSheetScope = rememberCoroutineScope()
        val dismissAccountSheet: (afterDismiss: () -> Unit) -> Unit = { afterDismiss ->
            accountSheetScope.launch {
                accountSheetState.hide()
            }.invokeOnCompletion {
                showAccountSheet = false
                afterDismiss()
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showAccountSheet = false },
            sheetState = accountSheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeAccount?.photoUrl != null) {
                        coil.compose.AsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(activeAccount.photoUrl)
                                .crossfade(true)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .build(),
                            contentDescription = "Profile Picture",
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                        )
                    } else {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Account", modifier = Modifier.size(48.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = activeAccount?.displayName ?: "Unknown User",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = activeAccount?.email ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    text = "App Theme",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AppTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = currentTheme == theme,
                            onClick = { onThemeSelected(theme) },
                            label = { Text(theme.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                
                Text(
                    text = "Accent Color",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val colors = listOf(
                        AccentColor.BLUE to Color(0xFF2196F3),
                        AccentColor.GREEN to Color(0xFF4CAF50),
                        AccentColor.GREY to Color(0xFF78909C),
                        AccentColor.PURPLE to Color(0xFF7E57C2)
                    )
                    
                    colors.forEach { (enumVal, displayColor) ->
                        val isSelected = accentColor == enumVal
                        val swatchScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "swatch_scale_${enumVal.name}"
                        )
                        val borderAlpha by animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0f,
                            animationSpec = tween(220),
                            label = "swatch_border_${enumVal.name}"
                        )

                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .graphicsLayer {
                                    scaleX = swatchScale
                                    scaleY = swatchScale
                                }
                                .clip(CircleShape)
                                .background(displayColor)
                                .border(
                                    width = 2.5.dp,
                                    color = Color.White.copy(alpha = borderAlpha),
                                    shape = CircleShape
                                )
                                .clickable { onAccentColorSelected(enumVal) },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isSelected,
                                enter = scaleIn(
                                    initialScale = 0.5f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                ) + fadeIn(animationSpec = tween(160)),
                                exit = scaleOut(targetScale = 0.5f, animationSpec = tween(120)) +
                                    fadeOut(animationSpec = tween(120))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = if (enumVal == AccentColor.GREEN) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                ListItem(
                    headlineContent = { Text("Data Saver Mode") },
                    supportingContent = { Text("Automatically streams MP3 equivalents of lossless files to save bandwidth (if available)") },
                    trailingContent = { 
                        Switch(
                            checked = isDataSaverEnabled,
                            onCheckedChange = onToggleDataSaver
                        ) 
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                
                ListItem(
                    headlineContent = { Text("10-Band Equalizer") },
                    supportingContent = { Text("Fine-tune your audio frequencies.") },
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                    modifier = Modifier.clickable { dismissAccountSheet { showEqualizerSheet = true } }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Music Library",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                ListItem(
                    headlineContent = { Text("Organize Drive Library") },
                    supportingContent = {
                        Text(
                            if (defaultMusicFolderName != null) "Default Library: $defaultMusicFolderName"
                            else "Set a dedicated 'Music' folder to hide personal files"
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    trailingContent = {
                        if (defaultMusicFolderName != null) {
                            TextButton(onClick = onClearDefaultMusicFolder) {
                                Text("Reset")
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        dismissAccountSheet {
                            showMusicFolderPromptManual = true
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                ListItem(
                    headlineContent = { Text(cacheSizeText) },
                    supportingContent = { Text("Offline tracks queued for playback to prevent stream interruptions.") },
                    trailingContent = {
                        OutlinedButton(onClick = onClearCache) {
                            Text("Clear Cache Now")
                        }
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                ListItem(
                    headlineContent = { Text("Sign out") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out") },
                    modifier = Modifier.clickable {
                        dismissAccountSheet {
                            onSignOutClick()
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                var showHostDialog by remember { mutableStateOf(false) }
                var showJoinDialog by remember { mutableStateOf(false) }
                var joinRoomInput by remember { mutableStateOf("") }
                var generatedRoomId by remember { mutableStateOf("") }

                // Host code dialog
                if (showHostDialog) {
                    AlertDialog(
                        onDismissRequest = { showHostDialog = false },
                        title = { Text("Jam Session Started") },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text("Share this code with friends:")
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = generatedRoomId,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showHostDialog = false }) { Text("Got it") }
                        }
                    )
                }

                // Join dialog
                if (showJoinDialog) {
                    AlertDialog(
                        onDismissRequest = { showJoinDialog = false; joinRoomInput = "" },
                        title = { Text("Join a Session") },
                        text = {
                            OutlinedTextField(
                                value = joinRoomInput,
                                onValueChange = { joinRoomInput = it.uppercase() },
                                label = { Text("Room Code") },
                                placeholder = { Text("e.g. AB3X7") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (joinRoomInput.length == 5) {
                                        onJoinSession(joinRoomInput)
                                        showJoinDialog = false
                                        showAccountSheet = false
                                    }
                                },
                                enabled = joinRoomInput.length == 5
                            ) { Text("Join") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showJoinDialog = false; joinRoomInput = "" }) { Text("Cancel") }
                        }
                    )
                }

                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showEqualizerSheet) {
        EqualizerView(
            bands = eqBands,
            range = eqRange,
            settings = eqSettings,
            presets = eqPresets,
            error = eqError,
            enabled = eqEnabled,
            presetName = eqPresetName,
            onToggleEnabled = onToggleEqualizer,
            onBandLevelChange = onSetEqBandLevel,
            onUsePreset = onUseEqPreset,
            onReset = onResetEqualizer,
            onDismiss = { showEqualizerSheet = false }
        )
    }

    val shouldShowMusicPrompt = (!musicFolderPromptShown && currentFolder.first == "root" && uiState is DriveUiState.Success) || showMusicFolderPromptManual
    if (shouldShowMusicPrompt) {
        MusicFolderSetupPrompt(
            files = if (uiState is DriveUiState.Success) uiState.files else emptyList(),
            onOpenMusicFolder = { folderId, folderName ->
                onDismissMusicFolderPrompt()
                showMusicFolderPromptManual = false
                onFolderClick(folderId, folderName)
            },
            onSetDefaultAndOpen = { folderId, folderName ->
                onSetDefaultMusicFolder(folderId, folderName)
                onDismissMusicFolderPrompt()
                showMusicFolderPromptManual = false
                onFolderClick(folderId, folderName)
            },
            onDismiss = {
                onDismissMusicFolderPrompt()
                showMusicFolderPromptManual = false
            },
            onRefresh = onRefreshLibrary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerView(
    bands: List<Pair<Short, Int>>,
    range: Pair<Short, Short>,
    settings: Map<Short, Short>,
    presets: List<Pair<Short, String>>,
    error: String?,
    enabled: Boolean,
    presetName: String,
    onToggleEnabled: (Boolean) -> Unit,
    onBandLevelChange: (Short, Short) -> Unit,
    onUsePreset: (Short) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val eqSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val eqSheetScope = rememberCoroutineScope()
    val dismissEqSheet: () -> Unit = {
        eqSheetScope.launch {
            eqSheetState.hide()
        }.invokeOnCompletion {
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = eqSheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "10-Band Equalizer",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (enabled) "Preset: $presetName" else "Disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggleEnabled
                    )
                    if (bands.isNotEmpty() && range.first != range.second) {
                        IconButton(
                            onClick = onReset,
                            enabled = enabled
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Reset",
                                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                    TextButton(onClick = dismissEqSheet) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else if (bands.isEmpty() || range.first == range.second) {
                Text(
                    text = "Equalizer is not initialized. Play a track first to adjust settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                val controlsAlpha by animateFloatAsState(
                    targetValue = if (enabled) 1f else 0.45f,
                    animationSpec = tween(240),
                    label = "eq_enabled_alpha"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = controlsAlpha }
                ) {
                    if (presets.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = enabled && expanded,
                            onExpandedChange = { if (enabled) expanded = !expanded },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            OutlinedTextField(
                                value = presetName,
                                onValueChange = {},
                                readOnly = true,
                                enabled = enabled,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                label = { Text("Preset") }
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Custom") },
                                    onClick = {
                                        expanded = false
                                    }
                                )
                                presets.forEach { (index, name) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            onUsePreset(index)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(bands) { (band, freqMilliHertz) ->
                            val freqHz = freqMilliHertz / 1000
                            val label = if (freqHz >= 1000) "${freqHz / 1000}k" else "$freqHz"

                            val level = settings[band] ?: 0.toShort()
                            var isDragging by remember(band) { mutableStateOf(false) }
                            var dragValue by remember(band) { mutableFloatStateOf(level.toFloat()) }
                            LaunchedEffect(level) {
                                if (!isDragging) {
                                    dragValue = level.toFloat()
                                }
                            }
                            val animatedSliderValue by animateFloatAsState(
                                targetValue = if (isDragging) dragValue else level.toFloat(),
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "eq_slider_$band"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.width(48.dp)
                            ) {
                                Text(
                                    text = "${animatedSliderValue.toInt() / 100} dB",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .width(40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Slider(
                                        value = animatedSliderValue,
                                        enabled = enabled,
                                        onValueChange = {
                                            isDragging = true
                                            dragValue = it
                                            onBandLevelChange(band, it.toInt().toShort())
                                        },
                                        onValueChangeFinished = {
                                            isDragging = false
                                        },
                                        valueRange = range.first.toFloat()..range.second.toFloat(),
                                        modifier = Modifier
                                            .requiredWidth(160.dp) // length of the slider
                                            .graphicsLayer {
                                                rotationZ = -90f
                                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                            }
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun AnimatedEqualizer(isPlaying: Boolean) {
    val barColor = MaterialTheme.colorScheme.primary
    val minHeight = 4.dp
    val maxHeight = 20.dp
    val durations = listOf(350, 450, 300) // each bar gets a unique tempo

    Row(
        modifier = Modifier.size(24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        durations.forEachIndexed { index, durationMs ->
            val infiniteTransition = rememberInfiniteTransition(label = "eq_bar_$index")
            val animatedHeight by if (isPlaying) {
                infiniteTransition.animateFloat(
                    initialValue = minHeight.value,
                    targetValue = maxHeight.value,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar_height_$index"
                )
            } else {
                // Static minimum height when paused
                remember { mutableStateOf(minHeight.value) }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
fun SongListItem(
    file: DriveFile,
    isCurrentlyPlaying: Boolean = false,
    isPlaying: Boolean = false,
    isFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
    onClick: () -> Unit,
    onPlayFolderClick: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onDownload: () -> Unit = {},
    onShare: () -> Unit = {},
    thumbnailUrl: String? = null,
    musicNotePainter: androidx.compose.ui.graphics.painter.Painter? = null,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val boxBackgroundColor = remember(isCurrentlyPlaying, primaryColor) {
        if (isCurrentlyPlaying) primaryColor.copy(alpha = 0.15f)
        else primaryColor.copy(alpha = 0.2f)
    }
    val effectiveThumbnail = remember(file.thumbnailUrl, thumbnailUrl, file.albumArtUrl) {
        file.thumbnailUrl ?: thumbnailUrl ?: file.albumArtUrl
    }
    val placeholderPainter = musicNotePainter ?: androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.MusicNote)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(scaleDown = 0.98f, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(boxBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            if (file.isFolder) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Folder",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                if (!effectiveThumbnail.isNullOrEmpty()) {
                    coil.compose.AsyncImage(
                        model = effectiveThumbnail,
                        contentDescription = "Song Thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        placeholder = placeholderPainter,
                        error = placeholderPainter
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Audio File",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (isCurrentlyPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedEqualizer(isPlaying = isPlaying)
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 1
            )
            if (!file.isFolder) {
                val subtitle = remember(file.displaySize, file.displayDate) {
                    when {
                        file.displaySize.isNotEmpty() && file.displayDate.isNotEmpty() ->
                            "${file.displaySize}  ·  ${file.displayDate}"
                        file.displaySize.isNotEmpty() -> file.displaySize
                        file.displayDate.isNotEmpty() -> file.displayDate
                        else -> ""
                    }
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (file.isFolder) {
            IconButton(
                onClick = onPlayFolderClick,
                modifier = Modifier.bounceClick(scaleDown = 0.92f, onClick = onPlayFolderClick)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Folder",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedFavoriteButton(
                    isFavourite = isFavourite,
                    onToggle = onToggleFavourite,
                    iconSize = 24.dp,
                    activeColor = androidx.compose.ui.graphics.Color.Red,
                    inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (expanded) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Add to Queue") },
                                leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    expanded = false
                                    onAddToQueue()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Download") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    expanded = false
                                    onDownload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share Song") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = {
                                    expanded = false
                                    onShare()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackBottomSheet(
    controller: androidx.media3.common.Player?,
    fallbackDurationMs: Long = 0L,
    upcomingQueue: List<DriveFile> = emptyList(),
    currentSpotifyArtUrl: String? = null,
    songThumbnails: Map<String, String> = emptyMap(),
    sleepTimerRemaining: Long? = null,
    sleepTimerTotal: Long? = null,
    sleepTimerPauseAfterTrack: Boolean = false,
    currentPlayingFolderName: String? = null,
    isCurrentTrackFavourite: Boolean = false,
    onToggleCurrentFavourite: () -> Unit = {},
    sessionRole: SessionRole = SessionRole.NONE,
    onNeedsTokenRefresh: () -> Unit,
    onClearQueue: () -> Unit = {},
    onPlayQueueItem: (DriveFile) -> Unit = {},
    onCollapse: (releaseVelocityY: Float) -> Unit = {},
    onSetSleepTimer: (Int) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onSetSleepTimerBehavior: (Boolean) -> Unit = {},
    isShuffleEnabled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    var playWhenReady by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSleepTimerSettings by remember { mutableStateOf(false) }
    
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    if (showSleepTimerDialog) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { 
                showSleepTimerDialog = false 
                showSleepTimerSettings = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
            shape = androidx.compose.ui.graphics.RectangleShape,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp, bottom = 24.dp)
                    .navigationBarsPadding()
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (showSleepTimerSettings) {
                        Text("Timer Behavior", style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { showSleepTimerSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        val text = if (sleepTimerRemaining != null) {
                            val mins = sleepTimerRemaining / 60000
                            val secs = (sleepTimerRemaining % 60000) / 1000
                            "Sleep Timer (%02d:%02d)".format(mins, secs)
                        } else {
                            "Sleep Timer"
                        }
                        Text(text, style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { showSleepTimerSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
                
                if (showSleepTimerSettings) {
                    Column {
                        Text("Timer Behavior", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSetSleepTimerBehavior(false) }) {
                            androidx.compose.material3.RadioButton(
                                selected = !sleepTimerPauseAfterTrack,
                                onClick = { onSetSleepTimerBehavior(false) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pause immediately when timer ends", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSetSleepTimerBehavior(true) }) {
                            androidx.compose.material3.RadioButton(
                                selected = sleepTimerPauseAfterTrack,
                                onClick = { onSetSleepTimerBehavior(true) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pause after current song finishes", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    Column {
                        listOf(1, 5, 10, 15, 30, 45, 60).forEach { mins ->
                            TextButton(
                                onClick = { 
                                    onSetSleepTimer(mins)
                                    showSleepTimerDialog = false 
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("$mins minutes")
                            }
                        }
                        if (sleepTimerRemaining != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { 
                                    onCancelSleepTimer()
                                    showSleepTimerDialog = false 
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Turn off timer", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
    var isBuffering by remember { mutableStateOf(false) }
    var currentTrackTitle by remember { mutableStateOf("No Track Selected") }
    var currentTrackArtist by remember { mutableStateOf("Unknown Artist") }
    var currentArtworkData by remember { mutableStateOf<ByteArray?>(null) }
    var progressMs by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableFloatStateOf(if (fallbackDurationMs > 0L) fallbackDurationMs.toFloat() else 0f) }
    var shuffleModeEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(androidx.media3.common.Player.REPEAT_MODE_OFF) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(fallbackDurationMs) {
        if (fallbackDurationMs > 0L && durationMs <= 0f) {
            durationMs = fallbackDurationMs.toFloat()
        }
    }

    DisposableEffect(controller) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReadyParam: Boolean, reason: Int) {
                playWhenReady = playWhenReadyParam
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                currentTrackTitle = mediaMetadata.title?.toString() ?: "Unknown Track"
                currentTrackArtist = mediaMetadata.artist?.toString() ?: "Unknown Artist"
                if (mediaMetadata.artworkData != null) {
                    currentArtworkData = mediaMetadata.artworkData
                }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val duration = controller?.duration ?: 0L
                durationMs = if (duration > 0L) duration.toFloat() else if (fallbackDurationMs > 0L) fallbackDurationMs.toFloat() else 0f
                progressMs = 0f
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabledParam: Boolean) {
                shuffleModeEnabled = shuffleModeEnabledParam
            }
            override fun onRepeatModeChanged(repeatModeParam: Int) {
                repeatMode = repeatModeParam
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    val duration = controller?.duration ?: 0L
                    if (duration > 0L) {
                        durationMs = duration.toFloat()
                    }
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                progressMs = controller?.currentPosition?.toFloat() ?: 0f
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val cause = error.cause
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    android.widget.Toast.makeText(context, "Network Error: ${cause.responseCode}", android.widget.Toast.LENGTH_LONG).show()
                    if (cause.responseCode == 401 || cause.responseCode == 403) {
                        onNeedsTokenRefresh()
                    }
                } else {
                    val rootCause = error.cause?.message ?: "Unknown cause"
                    android.widget.Toast.makeText(context, "Error [${error.errorCodeName}]: ${error.message}\nCause: $rootCause", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        controller?.addListener(listener)
        
        // Initial sync
        controller?.let {
            playWhenReady = it.playWhenReady
            isBuffering = it.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            currentTrackTitle = it.mediaMetadata.title?.toString() ?: "No Track Selected"
            currentTrackArtist = it.mediaMetadata.artist?.toString() ?: "Unknown Artist"
            currentArtworkData = it.mediaMetadata.artworkData
            val duration = it.duration
            if (duration > 0L) {
                durationMs = duration.toFloat()
            } else if (fallbackDurationMs > 0L && durationMs <= 0f) {
                durationMs = fallbackDurationMs.toFloat()
            }
            progressMs = it.currentPosition.toFloat()
            shuffleModeEnabled = it.shuffleModeEnabled
            repeatMode = it.repeatMode
        }

        onDispose {
            controller?.removeListener(listener)
        }
    }

    LaunchedEffect(playWhenReady, controller) {
        while (playWhenReady && controller != null) {
            progressMs = controller.currentPosition.toFloat()
            val d = controller.duration
            if (d > 0L) {
                durationMs = d.toFloat()
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    var dominantColor by remember { mutableStateOf(androidx.compose.ui.graphics.Color(0xFF2B2B68)) } // Default fallback
    
    // Only parse ID3 manually if Spotify hasn't returned a remote HD URL
    if (currentSpotifyArtUrl == null) {
        val currentBitmap = remember(currentArtworkData) {
            currentArtworkData?.let {
                android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
            }
        }
        LaunchedEffect(currentBitmap) {
            if (currentBitmap != null) {
                try {
                    val palette = androidx.palette.graphics.Palette.from(currentBitmap).generate()
                    val swatch = palette.dominantSwatch ?: palette.vibrantSwatch
                    if (swatch != null) {
                        dominantColor = androidx.compose.ui.graphics.Color(swatch.rgb)
                    } else {
                        dominantColor = androidx.compose.ui.graphics.Color(0xFF2B2B68) // Reset to default
                    }
                } catch (e: Exception) {
                    dominantColor = androidx.compose.ui.graphics.Color(0xFF2B2B68)
                }
            } else {
                dominantColor = androidx.compose.ui.graphics.Color(0xFF2B2B68) // Reset to default
            }
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = dominantColor, 
        animationSpec = tween(1000)
    )

    val density = LocalDensity.current
    val dismissBufferPx = with(density) { 140.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val sheetDragOffsetY = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        sheetDragOffsetY.snapTo(0f)
    }

    // Latches true whenever the active finger gesture scrolls the queue list.
    // Prevents a single scroll gesture from bleeding into sheet dismissal when the list hits an edge.
    var listScrolledInCurrentGesture by remember { mutableStateOf(false) }

    val bufferedDismissConnection = remember(listState, dismissBufferPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If the user already pulled the sheet down past 0f and drags back up,
                // return the sheet to 0f first before scrolling the queue.
                if (sheetDragOffsetY.value > 0f && available.y < 0f) {
                    val updated = (sheetDragOffsetY.value + available.y).coerceAtLeast(0f)
                    val consumedY = updated - sheetDragOffsetY.value
                    coroutineScope.launch { sheetDragOffsetY.snapTo(updated) }
                    return Offset(0f, consumedY)
                }

                // If dragging upward (bottom-to-top) or scrolling while the queue is not at the top,
                // latch this gesture so it cannot minimize the sheet.
                val isQueueAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                if (source == NestedScrollSource.Drag && (!isQueueAtTop || available.y < 0f)) {
                    listScrolledInCurrentGesture = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (consumed.y != 0f) {
                    listScrolledInCurrentGesture = true
                }

                val isQueueAtTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0

                // Only pull the sheet downward on an explicit user drag when:
                // 1. Finger is moving downward (available.y > 0f)
                // 2. Queue is already resting at index 0, offset 0
                // 3. This touch gesture did NOT already scroll the queue list
                if (source == NestedScrollSource.Drag &&
                    available.y > 0f &&
                    isQueueAtTop &&
                    !listScrolledInCurrentGesture
                ) {
                    val updated = (sheetDragOffsetY.value + available.y * 0.85f).coerceAtLeast(0f)
                    coroutineScope.launch { sheetDragOffsetY.snapTo(updated) }
                    return Offset(0f, available.y)
                }

                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheetDragOffsetY.value > 0f) {
                    if (sheetDragOffsetY.value >= dismissBufferPx) {
                        onCollapse(available.y.coerceAtLeast(0f))
                    } else {
                        sheetDragOffsetY.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                    }
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                listScrolledInCurrentGesture = false
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = sheetDragOffsetY.value
            }
            .nestedScroll(bufferedDismissConnection)
            .pointerInput(dismissBufferPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (sheetDragOffsetY.value == 0f) {
                        listScrolledInCurrentGesture = false
                    }
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })

                    // Finger lifted: reset the scroll latch so a subsequent downward swipe can minimize
                    listScrolledInCurrentGesture = false
                    if (sheetDragOffsetY.value >= dismissBufferPx) {
                        onCollapse(0f)
                    } else if (sheetDragOffsetY.value > 0f) {
                        coroutineScope.launch {
                            sheetDragOffsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(animatedColor, androidx.compose.ui.graphics.Color(0xFF09090E))))
        ) {
            // 1. Static Elements (Top Bar, Album Art, Metadata, Scrubber, Play Controls)
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 2. The Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, top = 48.dp, end = 24.dp, bottom = 0.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                IconButton(onClick = { onCollapse(0f) }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "NOW PLAYING",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = androidx.compose.ui.graphics.Color(0xFFB0B0D0)
                    )
                    if (currentPlayingFolderName != null) {
                        Text(
                            text = currentPlayingFolderName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            color = androidx.compose.ui.graphics.Color(0xFF9090B0),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = { showSleepTimerDialog = true }) {
                    if (sleepTimerRemaining != null && sleepTimerTotal != null && sleepTimerTotal > 0L) {
                        val progress = sleepTimerRemaining.toFloat() / sleepTimerTotal.toFloat()
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(26.dp).scale(scaleX = -1f, scaleY = 1f),
                                color = androidx.compose.ui.graphics.Color.White,
                                trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f),
                                strokeWidth = 2.dp
                            )
                            Icon(Icons.Default.Timer, contentDescription = "Sleep Timer", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Icon(Icons.Default.Timer, contentDescription = "Sleep Timer", tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }

        Spacer(modifier = Modifier.height(32.dp))

        // 3. The Album Art
        val artScale by animateFloatAsState(
            targetValue = if (playWhenReady) 1.0f else 0.92f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "art_scale"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
                .graphicsLayer {
                    scaleX = artScale
                    scaleY = artScale
                }
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val currentMediaId = controller?.currentMediaItem?.mediaId
            val effectivePlayerArt = currentSpotifyArtUrl ?: (if (!currentMediaId.isNullOrEmpty()) songThumbnails[currentMediaId] else null)
            val resolvedArtworkUrl = effectivePlayerArt ?: if (currentArtworkData != null) "id3:$currentMediaId" else "default"
            Crossfade(
                targetState = resolvedArtworkUrl,
                animationSpec = tween(280),
                label = "now_playing_art_crossfade",
                modifier = Modifier.fillMaxSize()
            ) { _ ->
                if (effectivePlayerArt != null) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var isLoading by remember { mutableStateOf(true) }
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(context)
                            .data(effectivePlayerArt)
                            .crossfade(true)
                            .allowHardware(false) // Safely extract Bitmaps for Palette APIs directly avoiding hardware acceleration crashes
                            .build(),
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        onSuccess = { result ->
                            isLoading = false
                            val drawable = result.result.drawable
                            if (drawable is android.graphics.drawable.BitmapDrawable) {
                                val bitmap = drawable.bitmap
                                androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
                                    val swatch = palette?.dominantSwatch ?: palette?.vibrantSwatch
                                    if (swatch != null) {
                                        dominantColor = androidx.compose.ui.graphics.Color(swatch.rgb)
                                    }
                                }
                            }
                        },
                        onLoading = {
                            isLoading = true
                        },
                        onError = {
                            isLoading = false
                        }
                    )
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // Fallback to local ID3 or default icon
                    val currentBitmap = remember(currentArtworkData) {
                        currentArtworkData?.let {
                            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                        }
                    }
                    if (currentBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = currentBitmap.asImageBitmap(),
                            contentDescription = "Album Art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                                contentDescription = "Default Art",
                                modifier = Modifier.fillMaxSize(1.8f),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }

        // 4. Track Info & Heart
        var isTitleExpanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).animateContentSize()) {
                Text(
                    text = currentTrackTitle,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = if (isTitleExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null // Hide ripple effect for a clean text click
                    ) { isTitleExpanded = !isTitleExpanded }
                )
            }
            AnimatedFavoriteButton(
                isFavourite = isCurrentTrackFavourite,
                onToggle = onToggleCurrentFavourite,
                iconSize = 28.dp,
                activeColor = androidx.compose.ui.graphics.Color.Red,
                inactiveColor = androidx.compose.ui.graphics.Color.White
            )
        }

        // 5. The Thick Scrubber
        val effectiveDurationMs = if (durationMs > 0f) durationMs else fallbackDurationMs.toFloat()
        var sliderPosition by remember { mutableStateOf<Float?>(null) }
        val displayProgress = sliderPosition ?: if (effectiveDurationMs > 0f) (progressMs / effectiveDurationMs).coerceIn(0f, 1f) else 0f

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 24.dp)) {
            @OptIn(ExperimentalMaterial3Api::class)
            Slider(
                value = displayProgress,
                enabled = sessionRole != SessionRole.GUEST,
                onValueChange = { fraction ->
                    sliderPosition = fraction
                },
                onValueChangeFinished = {
                    sliderPosition?.let { fraction ->
                        val newPosition = (fraction * effectiveDurationMs).toLong()
                        controller?.seekTo(newPosition)
                        sliderPosition = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = androidx.compose.ui.graphics.Color.White,
                    activeTrackColor = androidx.compose.ui.graphics.Color.White,
                    inactiveTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f)
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White)
                    )
                },
                track = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = displayProgress.coerceIn(0.001f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(androidx.compose.ui.graphics.Color.White)
                        )
                    }
                }
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val currentMs = if (effectiveDurationMs > 0f) (displayProgress * effectiveDurationMs).toLong() else progressMs.toLong()
                val totalMs = effectiveDurationMs.toLong()
                Text(
                    text = String.format(java.util.Locale.US, "%02d:%02d", (currentMs / 1000) / 60, (currentMs / 1000) % 60),
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
                Text(
                    text = String.format(java.util.Locale.US, "%02d:%02d", (totalMs / 1000) / 60, (totalMs / 1000) % 60),
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        // 6. The Glowing Play Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isShuffled = isShuffleEnabled || shuffleModeEnabled
            IconButton(
                onClick = { 
                    if (onToggleShuffle != {}) {
                        onToggleShuffle()
                    } else {
                        controller?.shuffleModeEnabled = !shuffleModeEnabled
                    }
                },
                enabled = sessionRole != SessionRole.GUEST
            ) {
                Box(contentAlignment = Alignment.TopCenter) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffled) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    if (isShuffled) {
                        val shuffleDotColor = MaterialTheme.colorScheme.primary
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(24.dp)) {
                            drawCircle(
                                color = shuffleDotColor,
                                radius = 3.5f,
                                center = androidx.compose.ui.geometry.Offset(size.width / 2f, 2f)
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = { controller?.seekToPrevious() },
                enabled = sessionRole != SessionRole.GUEST,
                modifier = Modifier.bounceClick(
                    scaleDown = 0.92f,
                    enabled = sessionRole != SessionRole.GUEST
                ) { controller?.seekToPrevious() }
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp))
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(elevation = 24.dp, spotColor = animatedColor, shape = CircleShape)
                    .clip(CircleShape)
                    .background(animatedColor)
                    .bounceClick(
                        scaleDown = 0.92f,
                        enabled = !isBuffering && sessionRole != SessionRole.GUEST
                    ) {
                        if (playWhenReady) controller?.pause() else controller?.play()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    AnimatedPlayPauseIcon(
                        isPlaying = playWhenReady,
                        contentDescription = if (playWhenReady) "Pause" else "Play",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            IconButton(
                onClick = { controller?.seekToNext() },
                enabled = sessionRole != SessionRole.GUEST,
                modifier = Modifier.bounceClick(
                    scaleDown = 0.92f,
                    enabled = sessionRole != SessionRole.GUEST
                ) { controller?.seekToNext() }
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = { 
                    val nextMode = when (repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                        androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                        else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                    }
                    controller?.repeatMode = nextMode
                },
                enabled = sessionRole != SessionRole.GUEST
            ) {
                Icon(
                    imageVector = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Repeat",
                    tint = if (repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        }
        }
        
        if (upcomingQueue.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Up Next",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    TextButton(
                        onClick = onClearQueue,
                        enabled = sessionRole != SessionRole.GUEST
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(
                items = upcomingQueue,
                key = { if (it.fileId.isNotEmpty()) it.fileId else it.hashCode() }
            ) { file ->
                Row(
                    modifier = Modifier
                        .animateItemPlacement(tween(240, easing = FastOutSlowInEasing))
                        .fillMaxWidth()
                        .bounceClick(scaleDown = 0.98f, enabled = sessionRole != SessionRole.GUEST) {
                            onPlayQueueItem(file)
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val qArt = file.thumbnailUrl ?: songThumbnails[file.fileId] ?: file.albumArtUrl
                    if (!qArt.isNullOrEmpty()) {
                        coil.compose.AsyncImage(
                            model = qArt,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.MusicNote),
                            error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.MusicNote)
                        )
                    } else {
                        Icon(
                            imageVector = if (file.displaySize == "Now Playing") Icons.Default.VolumeUp else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (file.displaySize == "Now Playing") androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (file.displaySize == "Now Playing") FontWeight.Bold else FontWeight.Normal,
                            color = if (file.displaySize == "Now Playing") androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Gray,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            // Ensure bottom sheet has padding for navigation bar inset
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).padding(bottom = 32.dp))
        }
    }
    }
}

@Composable
fun MiniPlayer(
    controller: androidx.media3.common.Player?,
    fallbackDurationMs: Long = 0L,
    currentSpotifyArtUrl: String?,
    songThumbnails: Map<String, String> = emptyMap(),
    sessionRole: SessionRole,
    currentPlayingFileId: String? = null,
    currentPlayingTrack: DriveFile? = null,
    currentTrackIndex: Int = 0,
    currentPlaylistName: String? = null,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    var playWhenReady by remember { mutableStateOf(controller?.playWhenReady ?: false) }
    var currentTrackTitle by remember { mutableStateOf(controller?.mediaMetadata?.title?.toString() ?: "No Track Selected") }
    var currentTrackArtist by remember { mutableStateOf(controller?.mediaMetadata?.artist?.toString() ?: "Unknown Artist") }
    var currentArtworkData by remember { mutableStateOf(controller?.mediaMetadata?.artworkData) }
    var durationMs by remember { mutableFloatStateOf(if (fallbackDurationMs > 0L) fallbackDurationMs.toFloat() else 0f) }
    var progressMs by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(fallbackDurationMs) {
        if (fallbackDurationMs > 0L && durationMs <= 0f) {
            durationMs = fallbackDurationMs.toFloat()
        }
    }

    DisposableEffect(controller) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReadyParam: Boolean, reason: Int) {
                playWhenReady = playWhenReadyParam
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                currentTrackTitle = mediaMetadata.title?.toString() ?: "Unknown Track"
                currentTrackArtist = mediaMetadata.artist?.toString() ?: "Unknown Artist"
                if (mediaMetadata.artworkData != null) {
                    currentArtworkData = mediaMetadata.artworkData
                }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val d = controller?.duration ?: 0L
                durationMs = if (d > 0L) d.toFloat() else if (fallbackDurationMs > 0L) fallbackDurationMs.toFloat() else 0f
                progressMs = 0f
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    val d = controller?.duration ?: 0L
                    if (d > 0L) {
                        durationMs = d.toFloat()
                    }
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                progressMs = controller?.currentPosition?.toFloat() ?: 0f
            }
        }
        controller?.addListener(listener)
        controller?.let {
            playWhenReady = it.playWhenReady
            currentTrackTitle = it.mediaMetadata.title?.toString() ?: "No Track Selected"
            currentTrackArtist = it.mediaMetadata.artist?.toString() ?: "Unknown Artist"
            currentArtworkData = it.mediaMetadata.artworkData
            val d = it.duration
            if (d > 0L) {
                durationMs = d.toFloat()
            } else if (fallbackDurationMs > 0L && durationMs <= 0f) {
                durationMs = fallbackDurationMs.toFloat()
            }
            progressMs = it.currentPosition.toFloat()
        }
        onDispose { controller?.removeListener(listener) }
    }

    LaunchedEffect(playWhenReady, controller) {
        while (playWhenReady && controller != null) {
            progressMs = controller.currentPosition.toFloat()
            val d = controller.duration
            if (d > 0L) {
                durationMs = d.toFloat()
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    // Upward spring entrance when playing from another playlist
    val playlistSwitchOffsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val playlistSwitchScale = remember { androidx.compose.animation.core.Animatable(1f) }
    var previousPlaylistName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentPlaylistName) {
        if (previousPlaylistName != null && previousPlaylistName != currentPlaylistName && !currentPlaylistName.isNullOrEmpty()) {
            playlistSwitchOffsetY.snapTo(42f)
            playlistSwitchScale.snapTo(0.96f)
            launch {
                playlistSwitchOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                playlistSwitchScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
        previousPlaylistName = currentPlaylistName
    }

    val resolvedFileId = currentPlayingTrack?.fileId
        ?: currentPlayingFileId
        ?: controller?.currentMediaItem?.mediaId
        ?: ""
    val resolvedTitle = currentPlayingTrack?.name?.takeIf { it.isNotBlank() }
        ?: currentTrackTitle.takeIf { it.isNotBlank() && it != "No Track Selected" }
        ?: "Unknown Track"
    val resolvedArtist = currentTrackArtist.takeIf { it.isNotBlank() && it != "Unknown Artist" }
        ?: "DriveStream"
    val resolvedArtUrl = currentPlayingTrack?.let { track ->
        track.thumbnailUrl ?: songThumbnails[track.fileId] ?: track.albumArtUrl
    } ?: currentSpotifyArtUrl ?: (if (resolvedFileId.isNotEmpty()) songThumbnails[resolvedFileId] else null)

    var lastFileId by remember { mutableStateOf(resolvedFileId) }
    var lastIndex by remember { mutableIntStateOf(currentTrackIndex) }
    var isMovingForward by remember { mutableStateOf(true) }

    if (resolvedFileId.isNotEmpty() && resolvedFileId != lastFileId) {
        isMovingForward = currentTrackIndex >= lastIndex
        lastFileId = resolvedFileId
        lastIndex = currentTrackIndex
    } else if (resolvedFileId == lastFileId) {
        lastIndex = currentTrackIndex
    }

    val trackSnapshot = MiniPlayerTrackSnapshot(
        fileId = resolvedFileId,
        title = resolvedTitle,
        artist = resolvedArtist,
        artUrl = resolvedArtUrl,
        artworkData = currentArtworkData
    )

    var dominantColor by remember { mutableStateOf(androidx.compose.ui.graphics.Color(0xFF1E1E1E)) }
    if (resolvedArtUrl == null) {
        val currentBitmap = remember(currentArtworkData) {
            currentArtworkData?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        LaunchedEffect(currentBitmap) {
            if (currentBitmap != null) {
                try {
                    val palette = androidx.palette.graphics.Palette.from(currentBitmap).generate()
                    val swatch = palette.dominantSwatch ?: palette.vibrantSwatch
                    if (swatch != null) {
                        dominantColor = androidx.compose.ui.graphics.Color(swatch.rgb).copy(alpha = 0.5f)
                    } else {
                        dominantColor = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                    }
                } catch (e: Exception) {
                    dominantColor = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                }
            } else {
                dominantColor = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
            }
        }
    }

    val animatedColor by animateColorAsState(targetValue = dominantColor, animationSpec = tween(1000), label = "miniplayer_color")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = playlistSwitchOffsetY.value
                scaleX = playlistSwitchScale.value
                scaleY = playlistSwitchScale.value
            }
            .background(animatedColor)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .shadow(elevation = 16.dp)
                .background(animatedColor)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -2f) {
                            onExpand()
                        }
                    }
                }
                .bounceClick(scaleDown = 0.98f) { onExpand() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(
                    targetState = trackSnapshot,
                    contentKey = { it.fileId },
                    transitionSpec = {
                        if (isMovingForward) {
                            (slideInVertically(
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                                initialOffsetY = { height -> (height * 0.45f).toInt() }
                            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))) togetherWith
                                (slideOutVertically(
                                    animationSpec = tween(190, easing = FastOutLinearInEasing),
                                    targetOffsetY = { height -> -(height * 0.45f).toInt() }
                                ) + fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)))
                        } else {
                            (slideInVertically(
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                                initialOffsetY = { height -> -(height * 0.45f).toInt() }
                            ) + fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))) togetherWith
                                (slideOutVertically(
                                    animationSpec = tween(190, easing = FastOutLinearInEasing),
                                    targetOffsetY = { height -> (height * 0.45f).toInt() }
                                ) + fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)))
                        }
                    },
                    label = "miniplayer_track_slide",
                    modifier = Modifier.weight(1f).clipToBounds()
                ) { snapshot ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clipToBounds(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = snapshot.artUrl,
                                animationSpec = tween(220),
                                label = "mini_art_crossfade"
                            ) { artUrl ->
                                if (artUrl != null) {
                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(context)
                                            .data(artUrl)
                                            .crossfade(true)
                                            .allowHardware(false)
                                            .build(),
                                        contentDescription = "Album Art",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        onSuccess = { result ->
                                            val drawable = result.result.drawable
                                            if (drawable is android.graphics.drawable.BitmapDrawable) {
                                                androidx.palette.graphics.Palette.from(drawable.bitmap).generate { palette ->
                                                    val swatch = palette?.dominantSwatch ?: palette?.vibrantSwatch
                                                    if (swatch != null) {
                                                        dominantColor = androidx.compose.ui.graphics.Color(swatch.rgb).copy(alpha = 0.5f)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                } else if (snapshot.artworkData != null) {
                                    val bitmap = remember(snapshot.artworkData) {
                                        android.graphics.BitmapFactory.decodeByteArray(snapshot.artworkData, 0, snapshot.artworkData.size)
                                    }
                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Album Art",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        androidx.compose.foundation.Image(
                                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                                            contentDescription = "Default Art",
                                            modifier = Modifier.fillMaxSize(1.8f),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = snapshot.title,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = snapshot.artist,
                                color = androidx.compose.ui.graphics.Color.LightGray,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        if (sessionRole != SessionRole.GUEST) {
                            if (playWhenReady) controller?.pause() else controller?.play()
                        }
                    },
                    modifier = Modifier.bounceClick(
                        scaleDown = 0.88f,
                        enabled = sessionRole != SessionRole.GUEST
                    ) {
                        if (sessionRole != SessionRole.GUEST) {
                            if (playWhenReady) controller?.pause() else controller?.play()
                        }
                    }
                ) {
                    AnimatedPlayPauseIcon(
                        isPlaying = playWhenReady,
                        contentDescription = if (playWhenReady) "Pause" else "Play",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }

                IconButton(
                    onClick = {
                        if (sessionRole != SessionRole.GUEST) {
                            controller?.seekToNext()
                        }
                    },
                    modifier = Modifier.bounceClick(
                        scaleDown = 0.88f,
                        enabled = sessionRole != SessionRole.GUEST
                    ) {
                        if (sessionRole != SessionRole.GUEST) {
                            controller?.seekToNext()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip Next",
                        tint = if (sessionRole != SessionRole.GUEST) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.38f)
                    )
                }
            }
            
            val effectiveDurationMs = if (durationMs > 0f) durationMs else fallbackDurationMs.toFloat()
            val rawProgress = if (effectiveDurationMs > 0f) {
                (progressMs / effectiveDurationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            val animatedProgress by animateFloatAsState(
                targetValue = rawProgress,
                animationSpec = if (effectiveDurationMs <= 0f) snap() else tween(150, easing = LinearEasing),
                label = "miniplayer_progress"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = androidx.compose.ui.graphics.Color.Transparent,
            )
        }
    }
}

fun Modifier.shimmerEffect(): Modifier = composed {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.width.toFloat(),
        targetValue = 2 * size.width.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    background(
        brush = Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
            start = Offset(startOffsetX, 0f),
            end = Offset(startOffsetX + size.width.toFloat(), size.height.toFloat())
        )
    ).onGloballyPositioned { size = it.size }
}

@Composable
fun SkeletonFileRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .shimmerEffect()
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect()
            )
        }
    }
}

@Composable
fun SplashScreenContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    // Ring 1: expands and fades from the logo outward
    val ring1Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )
    // Ring 2: same but offset by half a cycle
    val ring2Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing, delayMillis = 700),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )
    // Ring 3: third offset for denser cascade
    val ring3Progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing, delayMillis = 350),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring3"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A0A2E),
                        Color(0xFF000000)
                    ),
                    radius = 900f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── LAYER 1: Ripple rings (drawn first = behind logo) ────────────────
        val logoSizePx = 140.dp

        // Ring helper — draws a single expanding transparent circle
        @Composable
        fun RippleRing(progress: Float, colorStart: Color) {
            val ringScale = 1f + progress * 2.4f
            val ringAlpha = (1f - progress).coerceIn(0f, 0.55f)
            Box(
                modifier = Modifier
                    .size(logoSizePx)
                    .scale(ringScale)
                    .alpha(ringAlpha)
                    .background(
                        color = colorStart.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(36.dp)
                    )
            )
        }

        RippleRing(progress = ring1Progress, colorStart = Color(0xFF6366F1))
        RippleRing(progress = ring2Progress, colorStart = Color(0xFF06B6D4))
        RippleRing(progress = ring3Progress, colorStart = Color(0xFF10B981))

        // ── LAYER 2: Logo + text (drawn on top of rings) ─────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(logoSizePx)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Color(0xFF1E1E1E)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "DriveStream Logo",
                    modifier = Modifier.fillMaxSize(1.8f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "DriveStream",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Your Drive. Your Music.",
                fontSize = 13.sp,
                color = Color(0xFF9080CC),
                letterSpacing = 1.sp
            )
        }
    }
}
