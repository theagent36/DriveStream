package com.example.drivestream

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import android.content.ComponentName
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

sealed class DriveUiState {
    object Idle : DriveUiState()
    object Loading : DriveUiState()
    data class Success(val files: List<DriveFile>) : DriveUiState()
    data class Error(val message: String) : DriveUiState()
}

enum class SortProperty { NAME, DATE }
enum class SortDirection { ASCENDING, DESCENDING }
data class SortState(val property: SortProperty, val direction: SortDirection)

sealed class SearchState {
    object Idle : SearchState()        // No active search
    object Loading : SearchState()     // Debounce fired, waiting for API
    data class Results(val files: List<DriveFile>) : SearchState()
    data class Error(val message: String) : SearchState()
}

enum class SessionRole { NONE, HOST, GUEST }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(FlowPreview::class)
class DriveViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DriveRepository(application)
    lateinit var themeRepository: ThemeRepository
    
    private val _theme = MutableStateFlow(AppTheme.AMOLED)
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    private val _isDataSaverEnabled = MutableStateFlow(false)
    val isDataSaverEnabled: StateFlow<Boolean> = _isDataSaverEnabled.asStateFlow()

    // Centralized MP3 folder ID cache
    private var mp3FolderId: String? = null

    // Tracking for cache sweeper
    private val knownLosslessFileIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val knownMp3FileIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var activeAudioPlaylist: List<DriveFile> = emptyList()
    private var prefetchJob: kotlinx.coroutines.Job? = null

    private fun resolveMp3FolderIdAsync() {
        viewModelScope.launch {
            try {
                mp3FolderId = repository.resolveMp3FolderId()
                android.util.Log.d("DriveViewModel", "Resolved centralized mp3FolderId: $mp3FolderId")
            } catch (e: Exception) {
                android.util.Log.w("DriveViewModel", "Failed to resolve mp3FolderId", e)
            }
        }
    }
    
    fun setDataSaverEnabled(enabled: Boolean) {
        viewModelScope.launch { themeRepository.saveDataSaver(enabled) }
    }

    val eqBands = PlaybackService.eqBandsFlow.asStateFlow()
    val eqRange = PlaybackService.eqRangeFlow.asStateFlow()
    val eqPresets = PlaybackService.eqPresetsFlow.asStateFlow()
    val eqError = PlaybackService.eqErrorFlow.asStateFlow()
    
    private val _eqSettings = MutableStateFlow<Map<Short, Short>>(emptyMap())
    val eqSettings: StateFlow<Map<Short, Short>> = _eqSettings.asStateFlow()

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqPresetName = MutableStateFlow("Custom")
    val eqPresetName: StateFlow<String> = _eqPresetName.asStateFlow()

    fun toggleEqualizer(enabled: Boolean) {
        viewModelScope.launch {
            themeRepository.saveEqualizerEnabled(enabled)
        }
    }

    private var eqSaveJob: kotlinx.coroutines.Job? = null

    fun setEqBandLevel(band: Short, level: Short) {
        PlaybackService.setBandLevel(band, level)
        val current = _eqSettings.value.toMutableMap()
        current[band] = level
        _eqSettings.value = current
        
        eqSaveJob?.cancel()
        eqSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            themeRepository.saveEqualizerSettings(current)
            themeRepository.saveEqualizerPreset("Custom")
        }
    }

    fun useEqPreset(presetIndex: Short) {
        val newSettings = PlaybackService.usePreset(presetIndex)
        if (newSettings != null) {
            _eqSettings.value = newSettings
            val presetName = eqPresets.value.find { it.first == presetIndex }?.second ?: "Custom"
            eqSaveJob?.cancel()
            eqSaveJob = viewModelScope.launch {
                themeRepository.saveEqualizerSettings(newSettings)
                themeRepository.saveEqualizerPreset(presetName)
            }
        }
    }

    fun resetEqualizer() {
        val flat = mutableMapOf<Short, Short>()
        eqBands.value.forEach { (band, _) ->
            flat[band] = 0
            PlaybackService.setBandLevel(band, 0)
        }
        _eqSettings.value = flat
        eqSaveJob?.cancel()
        eqSaveJob = viewModelScope.launch {
            themeRepository.saveEqualizerSettings(flat)
            themeRepository.saveEqualizerPreset("Custom")
        }
    }

    private val _accentColor = MutableStateFlow(AccentColor.BLUE)
    val accentColor: StateFlow<AccentColor> = _accentColor.asStateFlow()
    
    private val cachedAccount: GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(application)

    private val _activeAccount = MutableStateFlow<GoogleSignInAccount?>(cachedAccount)
    val activeAccount: StateFlow<GoogleSignInAccount?> = _activeAccount.asStateFlow()

    private val _uiState = MutableStateFlow<DriveUiState>(
        if (cachedAccount != null) DriveUiState.Loading else DriveUiState.Idle
    )
    val uiState: StateFlow<DriveUiState> = _uiState.asStateFlow()

    private val _isInitialFolderReady = MutableStateFlow(cachedAccount == null)
    val isInitialFolderReady: StateFlow<Boolean> = _isInitialFolderReady.asStateFlow()

    private val folderPageCache = FolderPageCache(application)

    private val _isBackgroundRefreshing = MutableStateFlow(false)
    val isBackgroundRefreshing: StateFlow<Boolean> = _isBackgroundRefreshing.asStateFlow()

    private val _sortState = MutableStateFlow(SortState(SortProperty.DATE, SortDirection.DESCENDING))
    val sortState: StateFlow<SortState> = _sortState.asStateFlow()

    private val myDriveStack = mutableListOf<Pair<String, String>>(Pair("root", "My Drive"))
    
    private val _currentFolder = MutableStateFlow<Pair<String, String>>(Pair("root", "My Drive"))
    val currentFolder: StateFlow<Pair<String, String>> = _currentFolder.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _originalPlaylist = MutableStateFlow<List<DriveFile>>(emptyList())
    val originalPlaylist: StateFlow<List<DriveFile>> = _originalPlaylist.asStateFlow()

    private val _activeQueue = MutableStateFlow<List<DriveFile>>(emptyList())
    val activeQueue: StateFlow<List<DriveFile>> = _activeQueue.asStateFlow()

    private var activeSourceFolderId: String? = null

    fun toggleShuffle() {
        hostController?.shuffleModeEnabled = false // Disable ExoPlayer's native shuffle mode completely
        val newState = !_isShuffleEnabled.value
        _isShuffleEnabled.value = newState
        handleShuffleToggled(newState)
    }

    private fun handleShuffleToggled(enabled: Boolean) {
        val origList = _originalPlaylist.value
        if (origList.isEmpty()) return

        val currentQueue = _activeQueue.value
        val currIdx = _currentIndex.value
        val currentSong = if (currIdx in currentQueue.indices) currentQueue[currIdx] else null

        if (enabled) {
            val shuffled = if (currentSong != null) {
                listOf(currentSong) + origList.filter { it.fileId != currentSong.fileId }.shuffled()
            } else {
                origList.shuffled()
            }
            _activeQueue.value = shuffled
            _currentIndex.value = 0
        } else {
            _activeQueue.value = origList
            val restoredIndex = if (currentSong != null) {
                origList.indexOfFirst { it.fileId == currentSong.fileId }.coerceAtLeast(0)
            } else {
                0
            }
            _currentIndex.value = restoredIndex
        }
        activeAudioPlaylist = _activeQueue.value

        hostController?.let { player ->
            val pIdx = player.currentMediaItemIndex
            val count = player.mediaItemCount
            if (pIdx != androidx.media3.common.C.INDEX_UNSET && count > pIdx + 1) {
                player.removeMediaItems(pIdx + 1, count)
            }
        }

        prefetchNextSongLookAhead()
    }
    
    private val _currentPlayingFolderName = MutableStateFlow<String?>("My Drive")
    val currentPlayingFolderName: StateFlow<String?> = _currentPlayingFolderName.asStateFlow()
    
    private val _player = MutableStateFlow<androidx.media3.common.Player?>(null)
    val player: StateFlow<androidx.media3.common.Player?> = _player.asStateFlow()

    private var hostController: MediaController? = null
    private var guestPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    // Currently playing track indicators
    private val _currentPlayingFileId = MutableStateFlow<String?>(null)
    val currentPlayingFileId: StateFlow<String?> = _currentPlayingFileId.asStateFlow()

    private val _currentPlayingTrack = MutableStateFlow<DriveFile?>(null)
    val currentPlayingTrack: StateFlow<DriveFile?> = _currentPlayingTrack.asStateFlow()

    private val _activeTrackDurationMs = MutableStateFlow(0L)
    val activeTrackDurationMs: StateFlow<Long> = _activeTrackDurationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Playback error surfacing
    private val _playbackErrorMessage = MutableStateFlow<String?>(null)
    val playbackErrorMessage: StateFlow<String?> = _playbackErrorMessage.asStateFlow()

    fun clearPlaybackError() { _playbackErrorMessage.value = null }

    // Master Playlist & Index State
    val masterPlaylist: StateFlow<List<DriveFile>> = _activeQueue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // Derived UI Upcoming Queue
    val upcomingQueue: StateFlow<List<DriveFile>> = combine(
        _activeQueue,
        _currentIndex
    ) { queue, index ->
        if (queue.isEmpty() || index < 0 || index >= queue.size - 1) {
            emptyList()
        } else {
            queue.subList(index + 1, queue.size)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val queueState: StateFlow<List<DriveFile>> = upcomingQueue

    // Spotify Cover Art State
    private val _currentSpotifyArtUrl = MutableStateFlow<String?>(null)
    val currentSpotifyArtUrl: StateFlow<String?> = _currentSpotifyArtUrl.asStateFlow()

    // Song Thumbnails Map (fileId -> thumbnailUrl)
    private val _songThumbnails = MutableStateFlow<Map<String, String>>(emptyMap())
    val songThumbnails: StateFlow<Map<String, String>> = _songThumbnails.asStateFlow()

    // Jam Session
    private val _sessionRole = MutableStateFlow(SessionRole.NONE)
    val sessionRole: StateFlow<SessionRole> = _sessionRole.asStateFlow()

    private val _roomId = MutableStateFlow<String?>(null)
    val roomId: StateFlow<String?> = _roomId.asStateFlow()

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    // Sleep Timer
    private val _sleepTimerRemaining = MutableStateFlow<Long?>(null)
    val sleepTimerRemaining: StateFlow<Long?> = _sleepTimerRemaining.asStateFlow()
    
    private val _sleepTimerTotal = MutableStateFlow<Long?>(null)
    val sleepTimerTotal: StateFlow<Long?> = _sleepTimerTotal.asStateFlow()

    private val _cacheSizeText = MutableStateFlow("Calculating...")
    val cacheSizeText: StateFlow<String> = _cacheSizeText.asStateFlow()
    
    // Favourites
    private val _favourites = MutableStateFlow<List<DriveFile>>(emptyList())
    val favourites: StateFlow<List<DriveFile>> = _favourites.asStateFlow()
    
    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    private val _sleepTimerPauseAfterTrack = MutableStateFlow(false)
    val sleepTimerPauseAfterTrack: StateFlow<Boolean> = _sleepTimerPauseAfterTrack.asStateFlow()
    
    private val _sleepTimerPauseAfterTrackPending = MutableStateFlow(false)

    fun setSleepTimerBehavior(pauseAfterTrack: Boolean) {
        _sleepTimerPauseAfterTrack.value = pauseAfterTrack
    }

    // Music Folder Onboarding Prompt & Default Library
    private val _musicFolderPromptShown = MutableStateFlow(true)
    val musicFolderPromptShown: StateFlow<Boolean> = _musicFolderPromptShown.asStateFlow()

    private val _defaultMusicFolderId = MutableStateFlow<String?>(null)
    val defaultMusicFolderId: StateFlow<String?> = _defaultMusicFolderId.asStateFlow()

    private val _defaultMusicFolderName = MutableStateFlow<String?>(null)
    val defaultMusicFolderName: StateFlow<String?> = _defaultMusicFolderName.asStateFlow()

    private var hasAutoNavigatedToDefaultFolder = false

    fun dismissMusicFolderPrompt() {
        viewModelScope.launch {
            themeRepository.saveMusicFolderPromptShown(true)
        }
    }

    fun showMusicFolderPromptAgain() {
        viewModelScope.launch {
            themeRepository.saveMusicFolderPromptShown(false)
        }
    }

    fun setDefaultMusicFolder(folderId: String?, folderName: String?) {
        viewModelScope.launch {
            themeRepository.saveDefaultMusicFolder(folderId, folderName)
        }
    }

    fun refreshCurrentFolder() {
        fetchCurrentFolder(forceShowLoading = false)
    }

    fun refreshCacheSize() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(getApplication<Application>().cacheDir, "exo_audio_cache")
            if (dir.exists()) {
                val sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val mb = sizeBytes / (1024 * 1024)
                _cacheSizeText.value = "Temporary Cache: $mb MB"
            } else {
                _cacheSizeText.value = "Temporary Cache: 0 MB"
            }
        }
    }

    fun clearCacheNow() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            DriveStreamCache.release()
            val dir = java.io.File(getApplication<Application>().cacheDir, "exo_audio_cache")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            refreshCacheSize()
            _playbackErrorMessage.value = "Cache Cleared"
        }
    }

    private val _isShareLoading = MutableStateFlow(false)
    val isShareLoading: StateFlow<Boolean> = _isShareLoading.asStateFlow()

    fun shareTrackWithLink(context: android.content.Context, file: DriveFile) {
        viewModelScope.launch(Dispatchers.IO) {
            _isShareLoading.value = true
            try {
                val link = SpotifyAuthManager.getSpotifyTrackLink(file.name)
                val cleanName = SpotifyAuthManager.cleanFilename(file.name)
                
                val shareMessageString = link ?: cleanName
                
                withContext(Dispatchers.Main) {
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, shareMessageString)
                        type = "text/plain"
                    }
                    val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Track Via")
                    shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(shareIntent)
                }
            } catch (e: Exception) {
                // Fallback on offline/error
                val cleanName = SpotifyAuthManager.cleanFilename(file.name)
                withContext(Dispatchers.Main) {
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, cleanName)
                        type = "text/plain"
                    }
                    val shareIntent = android.content.Intent.createChooser(sendIntent, "Share Track Via")
                    shareIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(shareIntent)
                }
            } finally {
                _isShareLoading.value = false
            }
        }
    }

    init {
        themeRepository = ThemeRepository(application)
        viewModelScope.launch {
            themeRepository.themeFlow.collect { _theme.value = it }
        }
        viewModelScope.launch {
            themeRepository.equalizerSettingsFlow.collect { _eqSettings.value = it }
        }
        viewModelScope.launch {
            themeRepository.equalizerEnabledFlow.collect { _eqEnabled.value = it }
        }
        viewModelScope.launch {
            themeRepository.equalizerPresetFlow.collect { _eqPresetName.value = it }
        }
        viewModelScope.launch {
            var isInitial = true
            themeRepository.dataSaverFlow
                .distinctUntilChanged()
                .collect { newValue ->
                    _isDataSaverEnabled.value = newValue
                    if (isInitial) {
                        isInitial = false
                    } else {
                        handleDataSaverToggleRecovery(newValue)
                    }
                }
        }
        viewModelScope.launch {
            themeRepository.accentColorFlow.collect { _accentColor.value = it }
        }
        viewModelScope.launch {
            themeRepository.sortStateFlow.collect { _sortState.value = it }
        }
        viewModelScope.launch {
            themeRepository.favouritesFlow.collect { favs ->
                _favourites.value = favs
                loadThumbnailsForFiles(favs)
            }
        }
        viewModelScope.launch {
            themeRepository.currentFolderNameFlow.collect { name -> 
                if (name != null) _currentPlayingFolderName.value = name 
            }
        }
        viewModelScope.launch {
            themeRepository.musicFolderPromptShownFlow.collect { _musicFolderPromptShown.value = it }
        }
        viewModelScope.launch {
            themeRepository.defaultMusicFolderIdFlow.collect { _defaultMusicFolderId.value = it }
        }
        viewModelScope.launch {
            themeRepository.defaultMusicFolderNameFlow.collect { _defaultMusicFolderName.value = it }
        }
        SpotifyAuthManager.init(application.applicationContext)
        refreshCacheSize()
        // ── PERSISTENT LOGIN: Restore the previous session on every app launch ──
        // Step 1: Try the cached account (instant, no network call).
        // Step 2: If the token may need refreshing, trigger silentSignIn().
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_READONLY))
            .build()
        val lastAccount = cachedAccount
        if (lastAccount != null) {
            _activeAccount.value = lastAccount
            repository.initializeDriveService(lastAccount)
            resolveMp3FolderIdAsync()
            viewModelScope.launch {
                val savedDefId = themeRepository.defaultMusicFolderIdFlow.firstOrNull()
                val savedDefName = themeRepository.defaultMusicFolderNameFlow.firstOrNull()
                _defaultMusicFolderId.value = savedDefId
                _defaultMusicFolderName.value = savedDefName
                if (!savedDefId.isNullOrEmpty() && !savedDefName.isNullOrEmpty() && !hasAutoNavigatedToDefaultFolder) {
                    hasAutoNavigatedToDefaultFolder = true
                    myDriveStack.clear()
                    myDriveStack.add(Pair("root", "My Drive"))
                    myDriveStack.add(Pair(savedDefId, savedDefName))
                    _currentFolder.value = Pair(savedDefId, savedDefName)
                }
                _isInitialFolderReady.value = true

                val initialFolderId = myDriveStack.last().first
                val cached = folderPageCache.get(initialFolderId)
                if (cached != null) {
                    applySortToFiles(cached, _sortState.value)
                    fetchCurrentFolder(forceShowLoading = false)
                } else {
                    fetchCurrentFolder(forceShowLoading = true)
                }
                restorePlaybackState()
            }
            // Then silently refresh the token in the background to keep it valid
            GoogleSignIn.getClient(application, gso).silentSignIn()
                .addOnSuccessListener { freshAccount ->
                    _activeAccount.value = freshAccount
                    repository.initializeDriveService(freshAccount)
                    resolveMp3FolderIdAsync()
                }
                .addOnFailureListener {
                    // Silent refresh failed (account revoked or user changed password).
                    // Sign the user out cleanly so they see the login screen.
                    android.util.Log.w("DriveViewModel", "Silent sign-in failed, clearing session", it)
                    _activeAccount.value = null
                    _uiState.value = DriveUiState.Idle
                    _isInitialFolderReady.value = true
                }
        }
        // Debounced search — fires 500ms after user stops typing, min 2 chars
        viewModelScope.launch {
            _searchQuery
                .debounce(500L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.length < 2) {
                        _searchState.value = SearchState.Idle
                        return@collectLatest
                    }
                    _searchState.value = SearchState.Loading
                    try {
                        val results = repository.searchAudioFiles(query)
                        results.forEach { file ->
                            activeMediaMap[file.fileId] = file
                            if (file.name.endsWith(".flac", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) ||
                                file.mimeType.contains("flac", ignoreCase = true) || file.mimeType.contains("wav", ignoreCase = true)) {
                                knownLosslessFileIds.add(file.fileId)
                            } else if (file.name.endsWith(".mp3", ignoreCase = true) || file.mimeType.contains("mpeg", ignoreCase = true)) {
                                knownMp3FileIds.add(file.fileId)
                            }
                        }
                        _searchState.value = SearchState.Results(results)
                        loadThumbnailsForFiles(results)
                    } catch (e: Exception) {
                        _searchState.value = SearchState.Error(e.message ?: "Search failed")
                    }
                }
        }
        initializePlayer()

        // Guest: collect Firebase sync updates from JamSessionManager
        viewModelScope.launch {
            JamSessionManager.incomingState.collect { jamState ->
                if (_sessionRole.value != SessionRole.GUEST) return@collect
                val activeGuestPlayer = guestPlayer ?: return@collect
                val clockCorrectedPos = jamState.currentPosition + (System.currentTimeMillis() - jamState.timestamp)
                
                // Load a different track if the fileId changed
                if (activeGuestPlayer.currentMediaItem?.mediaId != jamState.fileId && jamState.fileId.isNotEmpty()) {
                    viewModelScope.launch {
                        try {
                            val token = repository.getBearerToken() ?: return@launch
                            val streamUrl = "https://www.googleapis.com/drive/v3/files/${jamState.fileId}?alt=media"
                            
                            val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
                            
                            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.Builder().setMediaId(jamState.fileId).setUri(android.net.Uri.parse(streamUrl)).build())
                                
                            activeGuestPlayer.setMediaSource(mediaSource)
                            activeGuestPlayer.prepare()
                            activeGuestPlayer.seekTo(clockCorrectedPos.coerceAtLeast(0L))
                            activeGuestPlayer.playWhenReady = jamState.isPlaying
                        } catch (e: Exception) {
                            android.util.Log.w("JamSession", "Guest track load failed", e)
                        }
                    }
                } else {
                    activeGuestPlayer.playWhenReady = jamState.isPlaying
                    // Only seek if significantly out of sync (> 2s drift)
                    val currentPos = activeGuestPlayer.currentPosition
                    if (kotlin.math.abs(currentPos - clockCorrectedPos) > 2000L) {
                        activeGuestPlayer.seekTo(clockCorrectedPos.coerceAtLeast(0L))
                    }
                }
            }
        }
    } // end init

    private fun broadcastHostStateIfNeeded(controller: MediaController) {
        val roomId = _roomId.value ?: return
        if (_sessionRole.value != SessionRole.HOST) return
        val state = JamState(
            fileId   = controller.currentMediaItem?.mediaId ?: "",
            isPlaying = controller.isPlaying,
            currentPosition = controller.currentPosition,
            timestamp = System.currentTimeMillis()
        )
        JamSessionManager.broadcastState(roomId, state)
    }

    // ── JAM SESSION PUBLIC API ─────────────────────────────────────────────

    /** Generates a room ID, registers this user as HOST, and returns the code. */
    fun hostSession(): String {
        val chars = ('A'..'Z') + ('0'..'9')
        val roomId = (1..5).map { chars.random() }.joinToString("")
        _roomId.value = roomId
        _sessionRole.value = SessionRole.HOST
        JamSessionManager.initRoom(roomId) { error ->
            _playbackErrorMessage.value = "Firebase Error: $error"
        }
        android.util.Log.d("JamSession", "Hosting room: $roomId")
        return roomId
    }

    /** Joins an existing host session as a GUEST. */
    fun joinSession(roomId: String) {
        _roomId.value = roomId.trim().uppercase()
        _sessionRole.value = SessionRole.GUEST
        if (guestPlayer == null) {
            guestPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(getApplication()).build()
        }
        _player.value = guestPlayer
        hostController?.pause()
        JamSessionManager.joinRoom(_roomId.value!!)
        android.util.Log.d("JamSession", "Joined room: ${_roomId.value}")
    }

    /** Leaves the current jam session and cleans up Firebase listeners. */
    fun leaveSession() {
        JamSessionManager.detachListeners()
        _sessionRole.value = SessionRole.NONE
        _roomId.value = null
        guestPlayer?.stop()
        guestPlayer?.clearMediaItems()
        guestPlayer?.release()
        guestPlayer = null
        _player.value = hostController
    }

    private fun initializePlayer() {
        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(getApplication(), sessionToken).buildAsync()
        controllerFuture.addListener({
            val controller = controllerFuture.get()
            hostController = controller
            controller.shuffleModeEnabled = false // Disable ExoPlayer's native shuffle mode completely
            _isShuffleEnabled.value = false
            if (_sessionRole.value != SessionRole.GUEST) {
                _player.value = controller
            }

            val activeItem = controller.currentMediaItem
            if (activeItem != null) {
                val mediaId = activeItem.mediaId
                _currentPlayingFileId.value = mediaId
                val fallbackFile = DriveFile(
                    fileId = mediaId,
                    name = activeItem.mediaMetadata.title?.toString() ?: "Unknown Track",
                    size = 0L,
                    mimeType = activeItem.localConfiguration?.mimeType ?: "audio/mpeg",
                    isFolder = false,
                    modifiedTime = "",
                    displaySize = "",
                    displayDate = ""
                )
                val resolvedFile = activeMediaMap[mediaId] ?: fallbackFile
                currentPlayingFile = resolvedFile
                _currentPlayingTrack.value = resolvedFile
                activeMediaMap[mediaId] = resolvedFile
                if (controller.duration > 0L) {
                    _activeTrackDurationMs.value = controller.duration
                } else if (resolvedFile.durationMs > 0L) {
                    _activeTrackDurationMs.value = resolvedFile.durationMs
                }

                val queueIdx = _activeQueue.value.indexOfFirst { it.fileId == mediaId }
                if (queueIdx != -1) {
                    _currentIndex.value = queueIdx
                }

                val artUrl = activeItem.mediaMetadata.artworkUri?.toString()
                    ?: _songThumbnails.value[mediaId]
                    ?: SpotifyAuthManager.getCachedArtUrl(resolvedFile.name)
                    ?: SpotifyAuthManager.getCachedArtUrl(mediaId)
                if (!artUrl.isNullOrEmpty()) {
                    _currentSpotifyArtUrl.value = artUrl
                }
                _isPlaying.value = controller.isPlaying
                hasRestoredPlayback = true
            } else {
                viewModelScope.launch { restorePlaybackState() }
            }
            
            controller.addListener(object : androidx.media3.common.Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (_sleepTimerPauseAfterTrackPending.value) {
                        _sleepTimerPauseAfterTrackPending.value = false
                        controller.pause()
                        return
                    }

                    val currentPlayerIdx = controller.currentMediaItemIndex
                    if (currentPlayerIdx > 0) {
                        controller.removeMediaItems(0, currentPlayerIdx)
                    }

                    // Update currently playing file ID and index in activeQueue without reshuffling
                    val currentId = mediaItem?.mediaId
                    _currentPlayingFileId.value = currentId
                    val resolvedFile = if (currentId != null) {
                        val idx = _activeQueue.value.indexOfFirst { it.fileId == currentId }
                        if (idx != -1) {
                            _currentIndex.value = idx
                            _activeQueue.value[idx].also { currentPlayingFile = it }
                        } else {
                            activeMediaMap[currentId]?.also { currentPlayingFile = it }
                        }
                    } else null
                    _currentPlayingTrack.value = resolvedFile ?: currentPlayingFile
                    _activeTrackDurationMs.value = if (controller.duration > 0L) controller.duration else (currentPlayingFile?.durationMs ?: resolvedFile?.durationMs ?: 0L)

                    val fileId = mediaItem?.mediaId
                    val currentPoster = mediaItem?.mediaMetadata?.artworkUri?.toString()
                        ?: if (fileId != null) _songThumbnails.value[fileId] else null
                        ?: (currentPlayingFile?.name?.let { SpotifyAuthManager.getCachedArtUrl(it) })
                        ?: (fileId?.let { SpotifyAuthManager.getCachedArtUrl(it) })

                    if (!currentPoster.isNullOrBlank()) {
                        _currentSpotifyArtUrl.value = currentPoster
                    } else if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        _currentSpotifyArtUrl.value = null
                        currentPlayingFile?.let { file ->
                            loadThumbnailsForFiles(listOf(file))
                        }
                    }

                    if (controller.playWhenReady) {
                        if (controller.playbackState == Player.STATE_IDLE) {
                            controller.prepare()
                        }
                        if (!controller.isPlaying &&
                            controller.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                        ) {
                            controller.play()
                        }
                    }

                    // Look-Ahead Queue Resolution: Maintain next upcoming MediaItem sequentially from activeQueue
                    prefetchNextSongLookAhead()
                }

                override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                    val artUri = mediaMetadata.artworkUri?.toString()
                    if (!artUri.isNullOrBlank()) {
                        _currentSpotifyArtUrl.value = artUri
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (controller.duration > 0L) {
                            _activeTrackDurationMs.value = controller.duration
                        }
                        if (controller.playWhenReady &&
                            !controller.isPlaying &&
                            controller.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                        ) {
                            controller.play()
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        if (_sleepTimerPauseAfterTrackPending.value) {
                            _sleepTimerPauseAfterTrackPending.value = false
                            controller.pause()
                            return
                        }
                        val queue = _activeQueue.value
                        val nextIdx = _currentIndex.value + 1
                        if (nextIdx in queue.indices) {
                            playAudio(queue[nextIdx], fromQueue = true)
                        } else if (controller.repeatMode == Player.REPEAT_MODE_ALL && queue.isNotEmpty()) {
                            playAudio(queue[0], fromQueue = true)
                        }
                    }
                }

                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    // Strictly preserve current playlist order. No manual list-shuffling on track or timeline transitions.
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    // ExoPlayer native shuffle mode is completely disabled; enforce false on player
                    if (shuffleModeEnabled) {
                        controller.shuffleModeEnabled = false
                    }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    prefetchNextSongLookAhead()
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                    if (playing) {
                        prefetchNextSongLookAhead()
                    }
                    // Host: broadcast isPlaying change to Firebase
                    broadcastHostStateIfNeeded(controller)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    // Host: broadcast on seek, play/pause, or track change
                    val interestingEvents = setOf(
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_POSITION_DISCONTINUITY
                    )
                    val hasInteresting = interestingEvents.any { events.contains(it) }
                    if (hasInteresting) broadcastHostStateIfNeeded(controller)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val cause = error.cause
                    if (cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                            val responseCode = cause.responseCode
                            android.util.Log.e("ExoPlayerError", "HTTP Response Code: $responseCode")
                            _playbackErrorMessage.value = "Drive API Error $responseCode: Stream blocked."
                        }
                    } else {
                        android.util.Log.e("ExoPlayerError", "Other Error: ${error.message}")
                        _playbackErrorMessage.value = "Playback Error: ${error.message}"
                    }
                }
            })
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private var currentPlayingFile: DriveFile? = null
    private val activeMediaMap = mutableMapOf<String, DriveFile>()

    fun toggleCurrentFavourite() {
        val currentId = _currentPlayingFileId.value ?: return
        val existingFav = _favourites.value.find { it.fileId == currentId }
        if (existingFav != null) {
            toggleFavourite(existingFav)
        } else {
            val fileToToggle = activeMediaMap[currentId]
            if (fileToToggle != null) {
                toggleFavourite(fileToToggle)
            }
        }
    }

    fun playFile(file: DriveFile) = playAudio(file)

    fun playAudio(file: DriveFile, fromQueue: Boolean = false) {
        viewModelScope.launch {
            try {
                val existingQueue = _activeQueue.value
                val existingIdx = existingQueue.indexOfFirst { it.fileId == file.fileId }

                val currentScreenSongs = when {
                    _searchQuery.value.length >= 2 && _searchState.value is SearchState.Results ->
                        (_searchState.value as SearchState.Results).files.filter { !it.isFolder }
                    _uiState.value is DriveUiState.Success ->
                        (_uiState.value as DriveUiState.Success).files.filter { !it.isFolder }
                    else -> emptyList()
                }

                val isSameQueue = existingIdx != -1

                currentPlayingFile = file
                _currentPlayingFileId.value = file.fileId
                _currentPlayingTrack.value = file
                _activeTrackDurationMs.value = file.durationMs.coerceAtLeast(0L)

                // BRANCH A: Track belongs to the current active queue -> NEVER reshuffle
                if (isSameQueue) {
                    val updatedQueue: List<DriveFile>
                    val targetIdx: Int

                    if (_isShuffleEnabled.value && !fromQueue) {
                        val prevSong = existingQueue.getOrNull(_currentIndex.value)
                        updatedQueue = if (prevSong != null && prevSong.fileId != file.fileId) {
                            // Pin clicked song at index 0, keep the rest of the shuffled queue in its
                            // exact prior order, and move the previously playing song to the end
                            // so it does NOT appear at the top of "Up Next".
                            val remainingInOrder = existingQueue.filter {
                                it.fileId != file.fileId && it.fileId != prevSong.fileId
                            }
                            listOf(file) + remainingInOrder + listOf(prevSong)
                        } else {
                            listOf(file) + existingQueue.filter { it.fileId != file.fileId }
                        }
                        targetIdx = 0
                        _activeQueue.value = updatedQueue
                    } else {
                        // Sequential mode OR tapped directly inside "Up Next"
                        updatedQueue = existingQueue
                        targetIdx = existingIdx
                    }

                    _currentIndex.value = targetIdx
                    activeAudioPlaylist = updatedQueue

                    val player = hostController
                    if (player != null) {
                        val playerIdx = (0 until player.mediaItemCount)
                            .indexOfFirst { player.getMediaItemAt(it).mediaId == file.fileId }

                        if (playerIdx != -1) {
                            withContext(Dispatchers.Main) {
                                player.shuffleModeEnabled = false
                                val expectedNextId = updatedQueue.getOrNull(targetIdx + 1)?.fileId
                                if (playerIdx + 1 < player.mediaItemCount &&
                                    player.getMediaItemAt(playerIdx + 1).mediaId != expectedNextId
                                ) {
                                    player.removeMediaItems(playerIdx + 1, player.mediaItemCount)
                                }
                                player.seekTo(playerIdx, 0L)
                                player.prepare()
                                player.play()
                                prefetchNextSongLookAhead()
                            }
                            return@launch
                        }
                    }

                    val resolvedItem = buildSingleMediaItem(file) ?: return@launch
                    withContext(Dispatchers.Main) {
                        hostController?.run {
                            shuffleModeEnabled = false
                            setMediaItem(resolvedItem)
                            prepare()
                            play()
                            prefetchNextSongLookAhead()
                        }
                    }
                    return@launch
                }

                // BRANCH B: User started a brand-new playlist/folder -> shuffle ONCE with clicked track at index 0
                val sourceList = if (currentScreenSongs.any { it.fileId == file.fileId }) {
                    currentScreenSongs
                } else {
                    listOf(file)
                }

                _originalPlaylist.value = sourceList
                activeSourceFolderId = _currentFolder.value.first
                _currentPlayingFolderName.value = _currentFolder.value.second

                val targetQueue: List<DriveFile>
                val startIndex: Int

                if (_isShuffleEnabled.value) {
                    val remainingShuffled = sourceList
                        .filter { it.fileId != file.fileId }
                        .shuffled()
                    targetQueue = listOf(file) + remainingShuffled
                    startIndex = 0
                } else {
                    targetQueue = sourceList
                    startIndex = sourceList.indexOfFirst { it.fileId == file.fileId }.coerceAtLeast(0)
                }

                _activeQueue.value = targetQueue
                _currentIndex.value = startIndex
                activeAudioPlaylist = targetQueue

                val resolvedItem = buildSingleMediaItem(file) ?: return@launch
                withContext(Dispatchers.Main) {
                    hostController?.run {
                        shuffleModeEnabled = false
                        setMediaItem(resolvedItem)
                        prepare()
                        play()
                        prefetchNextSongLookAhead()
                    }
                }
            } catch (e: Exception) {
                _playbackErrorMessage.value = "Playback error: ${e.message}"
            }
        }
    }

    private suspend fun buildSingleMediaItem(file: DriveFile): MediaItem? {
        val token = try {
            repository.getBearerToken() ?: run {
                _playbackErrorMessage.value = "Failed to retrieve auth token for playback."
                return null
            }
        } catch (e: UserRecoverableAuthException) {
            _playbackErrorMessage.value = "Drive permission required. Please sign in again."
            return null
        }

        PlaybackService.updateActiveToken(token)
        activeMediaMap[file.fileId] = file

        val isHighBandwidth = file.name.endsWith(".flac", ignoreCase = true) ||
            file.name.endsWith(".wav", ignoreCase = true) ||
            file.mimeType.contains("flac", ignoreCase = true) ||
            file.mimeType.contains("wav", ignoreCase = true)

        var currentFileId = file.fileId
        var finalMimeType = file.mimeType
        var mp3IdToStore: String? = null

        if (_isDataSaverEnabled.value && isHighBandwidth) {
            var targetFolderId = mp3FolderId ?: repository.cachedMp3FolderId
            if (targetFolderId == null) {
                targetFolderId = repository.resolveMp3FolderId()
                mp3FolderId = targetFolderId
            }
            if (targetFolderId != null) {
                val baseName = file.name.substringBeforeLast(".")
                val mp3Equivalent = repository.findMp3InCentralFolder("$baseName.mp3", targetFolderId)
                if (mp3Equivalent != null && mp3Equivalent.fileId.isNotEmpty()) {
                    currentFileId = mp3Equivalent.fileId
                    finalMimeType = "audio/mpeg"
                    mp3IdToStore = mp3Equivalent.fileId
                    knownMp3FileIds.add(mp3Equivalent.fileId)
                    activeMediaMap[mp3Equivalent.fileId] = mp3Equivalent
                } else {
                    knownLosslessFileIds.add(file.fileId)
                }
            }
        } else if (isHighBandwidth) {
            knownLosslessFileIds.add(file.fileId)
        } else {
            knownMp3FileIds.add(file.fileId)
        }

        val uri = Uri.parse("https://www.googleapis.com/drive/v3/files/${currentFileId}?alt=media")
        val artUrl = file.thumbnailUrl
            ?: _songThumbnails.value[file.fileId]
            ?: SpotifyAuthManager.getCachedArtUrl(file.name)

        if (!artUrl.isNullOrEmpty()) {
            _currentSpotifyArtUrl.value = artUrl
        }

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(file.name)
            .setArtist("DriveStream")
            .apply {
                if (!artUrl.isNullOrEmpty()) {
                    setArtworkUri(Uri.parse(artUrl))
                }
            }
            .build()

        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setExtras(Bundle().apply {
                putString("auth_token", token)
                repository.activeAccountEmail?.let { putString("account_email", it) }
                if (mp3IdToStore != null) putString("mp3_id", mp3IdToStore)
            })
            .build()

        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(file.fileId)
            .setMimeType(finalMimeType)
            .setMediaMetadata(mediaMetadata)
            .setRequestMetadata(requestMetadata)
            .setTag(file)
            .build()
    }

    fun playAll() {
        val currentState = _uiState.value
        if (currentState is DriveUiState.Success) {
            val files = currentState.files.filter { !it.isFolder }
            if (files.isEmpty()) return

            currentPlayingFile = null
            val search = _searchState.value
            _currentPlayingFolderName.value = if (search is SearchState.Results) "Search Results" else myDriveStack.lastOrNull()?.second ?: "My Drive"

            viewModelScope.launch {
                try {
                    val token = repository.getBearerToken() ?: run {
                        _uiState.value = DriveUiState.Error("Failed to retrieve auth token for playback.")
                        return@launch
                    }
                    PlaybackService.updateActiveToken(token)

                    _originalPlaylist.value = files
                    activeSourceFolderId = myDriveStack.last().first
                    val queueToPlay = if (_isShuffleEnabled.value) files.shuffled() else files
                    _activeQueue.value = queueToPlay
                    _currentIndex.value = 0
                    activeAudioPlaylist = queueToPlay
                    currentPlayingFile = queueToPlay.firstOrNull()
                    _currentPlayingFileId.value = queueToPlay.firstOrNull()?.fileId
                    _currentPlayingTrack.value = queueToPlay.firstOrNull()
                    _activeTrackDurationMs.value = queueToPlay.firstOrNull()?.durationMs?.coerceAtLeast(0L) ?: 0L

                    val firstArt = queueToPlay.firstOrNull()?.let { f ->
                        f.thumbnailUrl ?: _songThumbnails.value[f.fileId] ?: SpotifyAuthManager.getCachedArtUrl(f.name)
                    }
                    if (!firstArt.isNullOrEmpty()) {
                        _currentSpotifyArtUrl.value = firstArt
                    }

                    var mp3Map: Map<String, DriveFile> = emptyMap()
                    var targetFolderId = mp3FolderId ?: repository.cachedMp3FolderId
                    if (targetFolderId == null) {
                        targetFolderId = repository.resolveMp3FolderId()
                        mp3FolderId = targetFolderId
                    }
                    if (_isDataSaverEnabled.value && targetFolderId != null && queueToPlay.any { it.name.endsWith(".flac", true) || it.name.endsWith(".wav", true) || it.mimeType.contains("flac") || it.mimeType.contains("wav") }) {
                        mp3Map = repository.getMp3sInCentralFolder(targetFolderId)
                        if (mp3Map.isNotEmpty()) {
                            _playbackErrorMessage.value = "Data Saver: Playing MP3 versions"
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(getApplication(), "Data Saver: Playing MP3 versions", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    val mediaItems = queueToPlay.map { file ->
                        activeMediaMap[file.fileId] = file
                        
                        val baseName = file.name.substringBeforeLast(".")
                        val mp3Equivalent = mp3Map["$baseName.mp3"]
                        
                        var currentFileId = file.fileId
                        if (_isDataSaverEnabled.value && mp3Equivalent != null && (file.name.endsWith(".flac", true) || file.name.endsWith(".wav", true) || file.mimeType.contains("flac") || file.mimeType.contains("wav"))) {
                            currentFileId = mp3Equivalent.fileId
                        }
                        
                        val uri = android.net.Uri.parse("https://www.googleapis.com/drive/v3/files/${currentFileId}?alt=media")
                        val artUrl = file.thumbnailUrl ?: _songThumbnails.value[file.fileId] ?: SpotifyAuthManager.getCachedArtUrl(file.name)
                        val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(file.name)
                            .setArtist("DriveStream")
                            .apply {
                                if (!artUrl.isNullOrEmpty()) {
                                    setArtworkUri(android.net.Uri.parse(artUrl))
                                }
                            }
                            .build()
                        val requestMetadata = androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setExtras(android.os.Bundle().apply { 
                                putString("auth_token", token)
                                repository.activeAccountEmail?.let { putString("account_email", it) }
                                if (mp3Equivalent != null) putString("mp3_id", mp3Equivalent.fileId)
                            })
                            .build()
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(uri)
                            .setMediaId(file.fileId)
                            .setMimeType(file.mimeType)
                            .setMediaMetadata(mediaMetadata)
                            .setRequestMetadata(requestMetadata)
                            .setTag(file)
                            .build()
                    }

                    queueToPlay.forEach { file ->
                        activeMediaMap[file.fileId] = file
                        if (file.name.endsWith(".flac", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) ||
                            file.mimeType.contains("flac", ignoreCase = true) || file.mimeType.contains("wav", ignoreCase = true)) {
                            knownLosslessFileIds.add(file.fileId)
                        } else if (file.name.endsWith(".mp3", ignoreCase = true) || file.mimeType.contains("mpeg", ignoreCase = true)) {
                            knownMp3FileIds.add(file.fileId)
                        }
                    }

                    hostController?.run {
                        shuffleModeEnabled = false
                        clearMediaItems()
                        setMediaItems(mediaItems, 0, 0L)
                        prepare()
                        play()
                        prefetchNextSongLookAhead()
                    }
                } catch (e: Exception) {
                    _uiState.value = DriveUiState.Error("Failed to play all: ${e.message}")
                }
            }
        }
    }

    fun playFolder(folderId: String, onEmpty: () -> Unit) {
        currentPlayingFile = null // Reset current single file tracker if playing a sequence
        val search = _searchState.value
        _currentPlayingFolderName.value = if (search is SearchState.Results) "Search Results" else myDriveStack.lastOrNull()?.second ?: "My Drive"

        viewModelScope.launch {
            _uiState.value = DriveUiState.Loading
            try {
                val files = repository.getAudioFilesInFolder(folderId)
                if (files.isEmpty()) {
                    onEmpty()
                    fetchCurrentFolder() // Restore UI state
                    return@launch
                }
                
                val token = repository.getBearerToken() ?: run {
                    _uiState.value = DriveUiState.Error("Failed to retrieve auth token for playback.")
                    return@launch
                }
                PlaybackService.updateActiveToken(token)

                _originalPlaylist.value = files
                activeSourceFolderId = folderId
                val queueToPlay = if (_isShuffleEnabled.value) files.shuffled() else files
                _activeQueue.value = queueToPlay
                _currentIndex.value = 0
                activeAudioPlaylist = queueToPlay
                currentPlayingFile = queueToPlay.firstOrNull()
                _currentPlayingFileId.value = queueToPlay.firstOrNull()?.fileId
                _currentPlayingTrack.value = queueToPlay.firstOrNull()
                _activeTrackDurationMs.value = queueToPlay.firstOrNull()?.durationMs?.coerceAtLeast(0L) ?: 0L

                val firstArt = queueToPlay.firstOrNull()?.let { f ->
                    f.thumbnailUrl ?: _songThumbnails.value[f.fileId] ?: SpotifyAuthManager.getCachedArtUrl(f.name)
                }
                if (!firstArt.isNullOrEmpty()) {
                    _currentSpotifyArtUrl.value = firstArt
                }

                var mp3Map: Map<String, DriveFile> = emptyMap()
                var targetFolderId = mp3FolderId ?: repository.cachedMp3FolderId
                if (targetFolderId == null) {
                    targetFolderId = repository.resolveMp3FolderId()
                    mp3FolderId = targetFolderId
                }
                if (_isDataSaverEnabled.value && targetFolderId != null && queueToPlay.any { it.name.endsWith(".flac", true) || it.name.endsWith(".wav", true) || it.mimeType.contains("flac") || it.mimeType.contains("wav") }) {
                    mp3Map = repository.getMp3sInCentralFolder(targetFolderId)
                    if (mp3Map.isNotEmpty()) {
                        _playbackErrorMessage.value = "Data Saver: Playing MP3 versions"
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(getApplication(), "Data Saver: Playing MP3 versions", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val mediaItems = queueToPlay.map { file ->
                    activeMediaMap[file.fileId] = file
                    
                    val baseName = file.name.substringBeforeLast(".")
                    val mp3Equivalent = mp3Map["$baseName.mp3"]
                    
                    var currentFileId = file.fileId
                    if (_isDataSaverEnabled.value && mp3Equivalent != null && (file.name.endsWith(".flac", true) || file.name.endsWith(".wav", true) || file.mimeType.contains("flac") || file.mimeType.contains("wav"))) {
                        currentFileId = mp3Equivalent.fileId
                    }
                    
                    val uri = android.net.Uri.parse("https://www.googleapis.com/drive/v3/files/${currentFileId}?alt=media")
                    val artUrl = file.thumbnailUrl ?: _songThumbnails.value[file.fileId] ?: SpotifyAuthManager.getCachedArtUrl(file.name)
                    val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(file.name)
                        .setArtist("DriveStream")
                        .apply {
                            if (!artUrl.isNullOrEmpty()) {
                                setArtworkUri(android.net.Uri.parse(artUrl))
                            }
                        }
                        .build()
                    val requestMetadata = androidx.media3.common.MediaItem.RequestMetadata.Builder()
                        .setExtras(android.os.Bundle().apply { 
                            putString("auth_token", token)
                            repository.activeAccountEmail?.let { putString("account_email", it) }
                            if (mp3Equivalent != null) putString("mp3_id", mp3Equivalent.fileId)
                        })
                        .build()
                    androidx.media3.common.MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(file.fileId)
                        .setMimeType(file.mimeType)
                        .setMediaMetadata(mediaMetadata)
                        .setRequestMetadata(requestMetadata)
                        .setTag(file)
                        .build()
                }

                queueToPlay.forEach { file ->
                    activeMediaMap[file.fileId] = file
                    if (file.name.endsWith(".flac", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) ||
                        file.mimeType.contains("flac", ignoreCase = true) || file.mimeType.contains("wav", ignoreCase = true)) {
                        knownLosslessFileIds.add(file.fileId)
                    } else if (file.name.endsWith(".mp3", ignoreCase = true) || file.mimeType.contains("mpeg", ignoreCase = true)) {
                        knownMp3FileIds.add(file.fileId)
                    }
                }

                hostController?.run {
                    shuffleModeEnabled = false // Disable ExoPlayer's native shuffle mode completely
                    clearMediaItems()
                    setMediaItems(mediaItems, 0, 0L)
                    prepare()
                    play()
                    prefetchNextSongLookAhead()
                }
                
                fetchCurrentFolder() // Refresh UI list after queuing
            } catch (e: Exception) {
                _uiState.value = DriveUiState.Error("Failed to play folder: ${e.message}")
            }
        }
    }

    private fun handleDataSaverToggleRecovery(dataSaverEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            val player = hostController
            val activeFile = _activeQueue.value.getOrNull(_currentIndex.value) ?: currentPlayingFile
            if (activeFile != null) {
                currentPlayingFile = activeFile
            }

            val hasActiveTrack = player != null && (
                player.isPlaying ||
                player.playWhenReady ||
                player.currentMediaItem != null ||
                player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            )

            if (player == null || !hasActiveTrack || activeFile == null) {
                // If player is not actively playing or paused on a track, safely execute cache sweep
                executeCacheSweep(dataSaverEnabled)
                return@launch
            }

            // 1. Safe Player Reset:
            val currentPos = player.currentPosition.coerceAtLeast(0L)

            // Stop the player to release the file locks
            player.stop()

            // Clear the media items
            player.clearMediaItems()

            // 2. Execute Cache Sweep:
            executeCacheSweep(dataSaverEnabled)

            // 3. Re-Resolve and Resume:
            val currentStubItem = MediaItem.Builder()
                .setMediaId(activeFile.fileId)
                .setTag(activeFile)
                .setUri(Uri.parse("https://www.googleapis.com/drive/v3/files/${activeFile.fileId}?alt=media"))
                .build()

            val resolvedItems = resolveMediaItemsForDataSaver(listOf(currentStubItem), dataSaverEnabled)

            if (resolvedItems.isNotEmpty()) {
                player.setMediaItems(resolvedItems, 0, currentPos)
                player.seekTo(0, currentPos)
            }

            // Explicitly call player.prepare() to clear any lingering error states before calling player.play().
            player.prepare()
            player.play()
            prefetchNextSongLookAhead()

            _playbackErrorMessage.value = if (dataSaverEnabled) "Data Saver: Playing MP3 version" else "Data Saver: Playing original quality"
        }
    }

    private suspend fun executeCacheSweep(dataSaverEnabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            val cache = DriveStreamCache.getInstance(getApplication())
            val keysToRemove = mutableListOf<String>()

            for (key in cache.keys) {
                val fileId = key.substringAfter("/files/").substringBefore("?")
                val file = activeMediaMap[fileId]

                val isLossless = (file != null && (
                    file.name.endsWith(".flac", ignoreCase = true) ||
                    file.name.endsWith(".wav", ignoreCase = true) ||
                    file.mimeType.contains("flac", ignoreCase = true) ||
                    file.mimeType.contains("wav", ignoreCase = true)
                )) || knownLosslessFileIds.contains(fileId)

                val isMp3 = (file != null && (
                    file.name.endsWith(".mp3", ignoreCase = true) ||
                    file.mimeType.contains("mpeg", ignoreCase = true)
                )) || knownMp3FileIds.contains(fileId)

                if (dataSaverEnabled) {
                    // Deleting FLACs if toggled ON
                    if (isLossless) {
                        keysToRemove.add(key)
                    }
                } else {
                    // Deleting MP3s if toggled OFF
                    if (isMp3) {
                        keysToRemove.add(key)
                    }
                }
            }

            for (k in keysToRemove) {
                CacheUtil.remove(cache, k)
                android.util.Log.d("DriveViewModel", "Cache sweep purged resource: $k")
            }
            refreshCacheSize()
        } catch (e: Exception) {
            android.util.Log.e("DriveViewModel", "Cache sweep failed", e)
        }
    }

    private suspend fun resolveMediaItemsForDataSaver(
        existingItems: List<MediaItem>,
        dataSaverEnabled: Boolean
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val token = try {
            repository.getBearerToken() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (token.isNotEmpty()) {
            PlaybackService.updateActiveToken(token)
        }

        var targetFolderId: String? = null
        if (dataSaverEnabled) {
            targetFolderId = mp3FolderId ?: repository.cachedMp3FolderId
            if (targetFolderId == null) {
                targetFolderId = repository.resolveMp3FolderId()
                mp3FolderId = targetFolderId
            }
        }

        val itemsToResolve = if (existingItems.isNotEmpty()) {
            existingItems
        } else {
            val file = currentPlayingFile
            if (file != null) {
                listOf(
                    MediaItem.Builder()
                        .setMediaId(file.fileId)
                        .setTag(file)
                        .setUri(Uri.parse("https://www.googleapis.com/drive/v3/files/${file.fileId}?alt=media"))
                        .build()
                )
            } else {
                emptyList()
            }
        }

        itemsToResolve.map { item ->
            val fileId = item.mediaId
            val driveFile = (item.localConfiguration?.tag as? DriveFile) ?: activeMediaMap[fileId] ?: currentPlayingFile

            if (driveFile == null) {
                return@map item
            }

            val isHighBandwidth = driveFile.name.endsWith(".flac", ignoreCase = true) ||
                    driveFile.name.endsWith(".wav", ignoreCase = true) ||
                    driveFile.mimeType.contains("flac", ignoreCase = true) ||
                    driveFile.mimeType.contains("wav", ignoreCase = true)

            var finalFileId = driveFile.fileId
            var finalMimeType = driveFile.mimeType
            var mp3IdToStore: String? = item.requestMetadata.extras?.getString("mp3_id")

            if (dataSaverEnabled && isHighBandwidth) {
                if (mp3IdToStore != null) {
                    finalFileId = mp3IdToStore
                    finalMimeType = "audio/mpeg"
                } else if (targetFolderId != null) {
                    val baseName = driveFile.name.substringBeforeLast(".")
                    val targetName = "$baseName.mp3"
                    val mp3Equivalent = repository.findMp3InCentralFolder(targetName, targetFolderId)
                    if (mp3Equivalent != null) {
                        finalFileId = mp3Equivalent.fileId
                        finalMimeType = "audio/mpeg"
                        mp3IdToStore = mp3Equivalent.fileId
                        knownMp3FileIds.add(mp3Equivalent.fileId)
                        activeMediaMap[mp3Equivalent.fileId] = mp3Equivalent
                    }
                }
            } else {
                // Data Saver OFF: stream original FLAC/WAV
                finalFileId = driveFile.fileId
                finalMimeType = driveFile.mimeType
            }

            val uri = Uri.parse("https://www.googleapis.com/drive/v3/files/${finalFileId}?alt=media")
            val artUrl = driveFile.thumbnailUrl ?: _songThumbnails.value[driveFile.fileId] ?: SpotifyAuthManager.getCachedArtUrl(driveFile.name)
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(driveFile.name)
                .setArtist("DriveStream")
                .apply {
                    if (!artUrl.isNullOrEmpty()) {
                        setArtworkUri(Uri.parse(artUrl))
                    }
                }
                .build()
            val requestMetadata = MediaItem.RequestMetadata.Builder()
                .setExtras(Bundle().apply {
                    if (token.isNotEmpty()) putString("auth_token", token)
                    repository.activeAccountEmail?.let { putString("account_email", it) }
                    if (mp3IdToStore != null) putString("mp3_id", mp3IdToStore)
                })
                .build()

            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(driveFile.fileId)
                .setMimeType(finalMimeType)
                .setMediaMetadata(mediaMetadata)
                .setRequestMetadata(requestMetadata)
                .setTag(driveFile)
                .build()
        }
    }

    /**
     * Look-Ahead Queue Resolution: Constantly maintains the upcoming MediaItem in the ExoPlayer
     * queue based on playback state (Shuffle, Repeat, or standard sequence).
     * Automatically queries the hidden "mp3" folder when Data Saver is enabled with immediate
     * fallback to .flac URI, and adds to ExoPlayer so DefaultLoadControl caches it in the background.
     */
    fun prefetchNextSongLookAhead() {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.Main) {
            val player = hostController ?: return@launch
            if (player.playbackState == Player.STATE_IDLE) return@launch

            val totalItems = player.mediaItemCount
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex == androidx.media3.common.C.INDEX_UNSET) return@launch

            // Strictly pull the next song sequentially (currentIndex + 1) from the activeQueue state
            val queue = _activeQueue.value
            if (queue.isEmpty()) return@launch

            val currIdx = _currentIndex.value
            val nextIdx = currIdx + 1
            val nextDriveFile = if (nextIdx in queue.indices) {
                queue[nextIdx]
            } else if (player.repeatMode == Player.REPEAT_MODE_ALL && queue.isNotEmpty()) {
                queue[0]
            } else {
                null
            }

            val targetFile = nextDriveFile ?: return@launch

            // 2. Resolve next song URI in background coroutine (Fallback Logic)
            val resolvedMediaItem = withContext(Dispatchers.IO) {
                val token = try {
                    repository.getBearerToken() ?: ""
                } catch (e: Exception) {
                    ""
                }
                if (token.isNotEmpty()) {
                    PlaybackService.updateActiveToken(token)
                }

                val isDataSaver = _isDataSaverEnabled.value
                val isHighBandwidth = targetFile.name.endsWith(".flac", ignoreCase = true) ||
                        targetFile.name.endsWith(".wav", ignoreCase = true) ||
                        targetFile.mimeType.contains("flac", ignoreCase = true) ||
                        targetFile.mimeType.contains("wav", ignoreCase = true)

                var targetFolderId: String? = null
                if (isDataSaver) {
                    targetFolderId = mp3FolderId ?: repository.cachedMp3FolderId
                    if (targetFolderId == null) {
                        targetFolderId = repository.resolveMp3FolderId()
                        mp3FolderId = targetFolderId
                    }
                }

                var finalFileId = targetFile.fileId
                var finalMimeType = targetFile.mimeType
                var mp3IdToStore: String? = null

                if (isDataSaver && isHighBandwidth) {
                    var mp3Equivalent: DriveFile? = null
                    if (targetFolderId != null) {
                        val baseName = targetFile.name.substringBeforeLast(".")
                        val targetName = "$baseName.mp3"
                        mp3Equivalent = repository.findMp3InCentralFolder(targetName, targetFolderId)
                    }

                    if (mp3Equivalent != null && mp3Equivalent.fileId.isNotEmpty()) {
                        finalFileId = mp3Equivalent.fileId
                        finalMimeType = "audio/mpeg"
                        mp3IdToStore = mp3Equivalent.fileId
                        knownMp3FileIds.add(mp3Equivalent.fileId)
                        activeMediaMap[mp3Equivalent.fileId] = mp3Equivalent
                    } else {
                        // Fallback: If query returns empty/null, immediately fallback to original .flac URI
                        finalFileId = targetFile.fileId
                        finalMimeType = targetFile.mimeType
                        knownLosslessFileIds.add(targetFile.fileId)
                    }
                } else {
                    finalFileId = targetFile.fileId
                    finalMimeType = targetFile.mimeType
                    if (isHighBandwidth) {
                        knownLosslessFileIds.add(targetFile.fileId)
                    } else {
                        knownMp3FileIds.add(targetFile.fileId)
                    }
                }

                val streamUri = Uri.parse("https://www.googleapis.com/drive/v3/files/${finalFileId}?alt=media")
                val artUrl = targetFile.thumbnailUrl ?: _songThumbnails.value[targetFile.fileId] ?: SpotifyAuthManager.getCachedArtUrl(targetFile.name)

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(targetFile.name)
                    .setArtist("DriveStream")
                    .apply {
                        if (!artUrl.isNullOrEmpty()) {
                            setArtworkUri(Uri.parse(artUrl))
                        }
                    }
                    .build()

                val requestMetadata = MediaItem.RequestMetadata.Builder()
                    .setExtras(Bundle().apply {
                        if (token.isNotEmpty()) putString("auth_token", token)
                        repository.activeAccountEmail?.let { putString("account_email", it) }
                        if (mp3IdToStore != null) putString("mp3_id", mp3IdToStore)
                    })
                    .build()

                MediaItem.Builder()
                    .setUri(streamUri)
                    .setMediaId(targetFile.fileId)
                    .setMimeType(finalMimeType)
                    .setMediaMetadata(mediaMetadata)
                    .setRequestMetadata(requestMetadata)
                    .setTag(targetFile)
                    .build()
            }

            // 3. Insert this resolved MediaItem into ExoPlayer using player.addMediaItem()
            // so ExoPlayer's DefaultLoadControl can begin caching it seamlessly in the background
            val upcomingIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0
            if (upcomingIndex < player.mediaItemCount) {
                val existingItem = player.getMediaItemAt(upcomingIndex)
                val existingUri = existingItem.localConfiguration?.uri
                val newUri = resolvedMediaItem.localConfiguration?.uri
                if (existingUri != newUri) {
                    player.removeMediaItem(upcomingIndex)
                    player.addMediaItem(upcomingIndex, resolvedMediaItem)
                }
            } else {
                player.addMediaItem(resolvedMediaItem)
            }
        }
    }

    fun addToQueue(file: DriveFile) {
        viewModelScope.launch {
            val token = try {
                repository.getBearerToken() ?: run {
                    _playbackErrorMessage.value = "Failed to retrieve auth token for adding to queue."
                    return@launch
                }
            } catch (e: Exception) {
                _playbackErrorMessage.value = "Auth token error: ${e.message}"
                return@launch
            }
            PlaybackService.updateActiveToken(token)
            
            var currentFileId = file.fileId
            var mp3Equivalent: DriveFile? = null
            val isHighBandwidth = file.name.endsWith(".flac", true) || file.name.endsWith(".wav", true) ||
                    file.mimeType.contains("flac", true) || file.mimeType.contains("wav", true)
            if (_isDataSaverEnabled.value && isHighBandwidth) {
                var targetFolderId = mp3FolderId ?: repository.cachedMp3FolderId
                if (targetFolderId == null) {
                    targetFolderId = repository.resolveMp3FolderId()
                    mp3FolderId = targetFolderId
                }
                if (targetFolderId != null) {
                    val baseName = file.name.substringBeforeLast(".")
                    val targetName = "$baseName.mp3"
                    mp3Equivalent = repository.findMp3InCentralFolder(targetName, targetFolderId)
                    if (mp3Equivalent != null) {
                        currentFileId = mp3Equivalent.fileId
                        _playbackErrorMessage.value = "Data Saver: Added MP3 version to Queue"
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(getApplication(), "Data Saver: Added MP3 version to Queue", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        _playbackErrorMessage.value = "Added to Queue"
                    }
                } else {
                    _playbackErrorMessage.value = "Added to Queue"
                }
            } else {
                _playbackErrorMessage.value = "Added to Queue"
            }
            
            val uri = Uri.parse("https://www.googleapis.com/drive/v3/files/${currentFileId}?alt=media")
            val artUrl = file.thumbnailUrl ?: _songThumbnails.value[file.fileId] ?: SpotifyAuthManager.getCachedArtUrl(file.name)
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(file.name)
                .setArtist("DriveStream")
                .apply {
                    if (!artUrl.isNullOrEmpty()) {
                        setArtworkUri(Uri.parse(artUrl))
                    }
                }
                .build()
            val requestMetadata = MediaItem.RequestMetadata.Builder()
                .setExtras(Bundle().apply { 
                    putString("auth_token", token)
                    repository.activeAccountEmail?.let { putString("account_email", it) }
                    if (mp3Equivalent != null) putString("mp3_id", mp3Equivalent.fileId)
                }).build()
            val mediaItem = MediaItem.Builder().setUri(uri).setMediaId(file.fileId)
                .setMimeType(if (_isDataSaverEnabled.value && mp3Equivalent != null) "audio/mpeg" else file.mimeType)
                .setMediaMetadata(mediaMetadata).setRequestMetadata(requestMetadata).setTag(file).build()
                
            activeMediaMap[file.fileId] = file
            _originalPlaylist.value = _originalPlaylist.value + file
            _activeQueue.value = _activeQueue.value + file
            activeAudioPlaylist = _activeQueue.value
            if (file.name.endsWith(".flac", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) ||
                file.mimeType.contains("flac", ignoreCase = true) || file.mimeType.contains("wav", ignoreCase = true)) {
                knownLosslessFileIds.add(file.fileId)
            } else if (file.name.endsWith(".mp3", ignoreCase = true) || file.mimeType.contains("mpeg", ignoreCase = true)) {
                knownMp3FileIds.add(file.fileId)
            }
                
            hostController?.let { player ->
                val nextIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0
                player.addMediaItem(nextIndex, mediaItem)
            }
        }
    }

    fun clearQueue() {
        hostController?.let { player ->
            val currentIndex = player.currentMediaItemIndex
            val totalItems = player.mediaItemCount
            if (totalItems > currentIndex + 1) {
                player.removeMediaItems(currentIndex + 1, totalItems)
                _playbackErrorMessage.value = "Queue cleared"
            }
            val currentFile = currentPlayingFile
            val single = if (currentFile != null) listOf(currentFile) else emptyList()
            _originalPlaylist.value = single
            _activeQueue.value = single
            _currentIndex.value = 0
            activeAudioPlaylist = single
        }
    }

    fun downloadFile(file: DriveFile, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val notificationId = file.fileId.hashCode()
            val channelId = "drivestream_downloads"
            val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Active Downloads",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress for downloading files"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading ${file.name}")
                .setContentText("Starting...")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                notificationManager.notify(notificationId, builder.build())
            }

            try {
                var lastUpdateMillis = 0L
                val minUpdateInterval = 500L

                val path = repository.downloadFile(file) { bytesCopied, totalBytes ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMillis > minUpdateInterval || bytesCopied == totalBytes) {
                        lastUpdateMillis = now
                        val progress = if (totalBytes > 0) ((bytesCopied * 100) / totalBytes).toInt() else 0
                        builder.setProgress(100, progress, false)
                        builder.setContentText("$progress%")
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                            notificationManager.notify(notificationId, builder.build())
                        }
                    }
                }
                
                builder.setContentText("Download complete")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    notificationManager.notify(notificationId, builder.build())
                }
                
                withContext(Dispatchers.Main) {
                    onSuccess(path)
                }
            } catch (e: Exception) {
                _playbackErrorMessage.value = "Download failed: ${e.message}"
                builder.setContentText("Download failed")
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    notificationManager.notify(notificationId, builder.build())
                }
            }
        }
    }

    fun sortByName(ascending: Boolean) {
        val direction = if (ascending) SortDirection.ASCENDING else SortDirection.DESCENDING
        val newState = SortState(SortProperty.NAME, direction)
        _sortState.value = newState
        viewModelScope.launch { themeRepository.saveSortState(newState) }
        val currentState = _uiState.value
        if (currentState !is DriveUiState.Success) return
        
        val folders = currentState.files.filter { it.isFolder }
        val songs = currentState.files.filter { !it.isFolder }
        
        val sortedSongs = if (ascending) {
            songs.sortedBy { it.name.lowercase() }
        } else {
            songs.sortedByDescending { it.name.lowercase() }
        }
        
        _uiState.value = DriveUiState.Success(folders + sortedSongs)
        syncQueueToPlayer(sortedSongs)
    }

    fun sortByDate() {
        val current = _sortState.value
        val newDirection = if (current.property == SortProperty.DATE) {
            // Already on date sort - toggle direction
            if (current.direction == SortDirection.DESCENDING) SortDirection.ASCENDING else SortDirection.DESCENDING
        } else {
            // Switching from different property - default to newest first
            SortDirection.DESCENDING
        }
        val newState = SortState(SortProperty.DATE, newDirection)
        _sortState.value = newState
        viewModelScope.launch { themeRepository.saveSortState(newState) }

        val currentState = _uiState.value
        if (currentState !is DriveUiState.Success) return

        val folders = currentState.files.filter { it.isFolder }
        val songs = currentState.files.filter { !it.isFolder }

        val sortedSongs = if (newDirection == SortDirection.DESCENDING) {
            songs.sortedByDescending { parseInstant(it.modifiedTime) }
        } else {
            songs.sortedBy { parseInstant(it.modifiedTime) }
        }

        _uiState.value = DriveUiState.Success(folders + sortedSongs)
        syncQueueToPlayer(sortedSongs)
    }

    @android.annotation.SuppressLint("NewApi")
    private fun parseInstant(iso: String): Long {
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    private fun syncQueueToPlayer(sortedSongs: List<DriveFile>) {
        viewModelScope.launch {
            _originalPlaylist.value = sortedSongs
            _activeQueue.value = sortedSongs
            _isShuffleEnabled.value = false
            val currentId = _currentPlayingFileId.value
            _currentIndex.value = if (currentId != null) {
                sortedSongs.indexOfFirst { it.fileId == currentId }.coerceAtLeast(0)
            } else {
                0
            }
            activeAudioPlaylist = sortedSongs
            sortedSongs.forEach { file ->
                activeMediaMap[file.fileId] = file
                if (file.name.endsWith(".flac", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) ||
                    file.mimeType.contains("flac", ignoreCase = true) || file.mimeType.contains("wav", ignoreCase = true)) {
                    knownLosslessFileIds.add(file.fileId)
                } else if (file.name.endsWith(".mp3", ignoreCase = true) || file.mimeType.contains("mpeg", ignoreCase = true)) {
                    knownMp3FileIds.add(file.fileId)
                }
            }

            val token = repository.getBearerToken() ?: return@launch
            PlaybackService.updateActiveToken(token)

            val mediaItems = sortedSongs.map { file ->
                activeMediaMap[file.fileId] = file
                val uri = Uri.parse("https://www.googleapis.com/drive/v3/files/${file.fileId}?alt=media")
                val mediaMetadata = MediaMetadata.Builder().setTitle(file.name).build()
                val requestMetadata = MediaItem.RequestMetadata.Builder()
                    .setExtras(Bundle().apply { 
                        putString("auth_token", token)
                        repository.activeAccountEmail?.let { putString("account_email", it) }
                    }).build()
                MediaItem.Builder().setUri(uri).setMediaId(file.fileId).setMimeType(file.mimeType)
                    .setMediaMetadata(mediaMetadata).setRequestMetadata(requestMetadata).setTag(file).build()
            }
            
            hostController?.run {
                val currentMediaId = currentMediaItem?.mediaId
                val newIndex = mediaItems.indexOfFirst { it.mediaId == currentMediaId }
                val wasPlaying = isPlaying

                if (newIndex != -1 && playbackState != androidx.media3.common.Player.STATE_IDLE) {
                    setMediaItems(mediaItems, newIndex, currentPosition)
                    prepare()
                    // Only resume playback if it was already actively playing before the sort
                    if (wasPlaying) {
                        play()
                        prefetchNextSongLookAhead()
                    }
                }
                // Do NOT call setMediaItems when idle/no match — it triggers onMediaItemTransition
                // which silently selects the first song in the reordered list.
            }
        }
    }

    fun refreshAuthTokenAndRetry() {
        val file = currentPlayingFile ?: return
        viewModelScope.launch {
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_READONLY))
                    .build()
                val client = GoogleSignIn.getClient(getApplication(), gso)
                
                client.silentSignIn().addOnSuccessListener { account ->
                    _activeAccount.value = account
                    repository.initializeDriveService(account)
                    resolveMp3FolderIdAsync()
                    playAudio(file) // Retry playback with new token
                }.addOnFailureListener {
                    _uiState.value = DriveUiState.Error("Failed to auto-refresh token for playback.")
                }
            } catch (e: Exception) {
                _uiState.value = DriveUiState.Error("Token refresh process error: ${e.toString()}")
            }
        }
    }

    fun onSignInSuccess(account: GoogleSignInAccount) {
        viewModelScope.launch {
            try {
                // Aggressively clear the token cache to prevent the 403 Deleted Project error
                withContext(Dispatchers.IO) {
                    val acc = account.account
                    if (acc != null) {
                        try {
                            val token = GoogleAuthUtil.getToken(getApplication(), acc, "oauth2:${DriveScopes.DRIVE_READONLY}")
                            GoogleAuthUtil.clearToken(getApplication(), token)
                            android.util.Log.d("DriveViewModel", "Successfully flushed old token cache")
                        } catch (e: Exception) {
                            android.util.Log.e("DriveViewModel", "Token flush failed (or none existed)", e)
                        }
                    }
                }
                
                _activeAccount.value = account
                _isInitialFolderReady.value = true
                repository.initializeDriveService(account)
                resolveMp3FolderIdAsync()
                fetchCurrentFolder()
                restorePlaybackState()
            } catch (e: Exception) {
                _uiState.value = DriveUiState.Error("Failed to initialize Drive client: ${e.toString()}")
            }
        }
    }

    fun onSignInFailed(errorMsg: String) {
        _uiState.value = DriveUiState.Error(errorMsg)
    }

    fun signOut() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        val client = GoogleSignIn.getClient(getApplication(), gso)
        client.signOut().addOnCompleteListener {
            _activeAccount.value = null
            _isInitialFolderReady.value = true
            mp3FolderId = null
            repository.cachedMp3FolderId = null
            _uiState.value = DriveUiState.Idle
            _currentPlayingFileId.value = null
            _currentPlayingTrack.value = null
            _activeTrackDurationMs.value = 0L
            currentPlayingFile = null
            myDriveStack.clear()
            myDriveStack.add(Pair("root", "My Drive"))
            _currentFolder.value = Pair("root", "My Drive")
            hostController?.stop()
            hostController?.clearMediaItems()
            _player.value = null
            viewModelScope.launch {
                folderPageCache.clear()
            }
        }
    }

    fun openFolder(folderId: String, folderName: String) {
        val cached = folderPageCache.get(folderId)
        myDriveStack.add(Pair(folderId, folderName))
        _currentFolder.value = Pair(folderId, folderName)
        if (cached != null) {
            applySortToFiles(cached, _sortState.value)
            fetchCurrentFolder(forceShowLoading = false)
        } else {
            _uiState.value = DriveUiState.Loading
            fetchCurrentFolder(forceShowLoading = true)
        }
    }

    /**
     * @return true if navigation was handled (went back), false if already at root
     */
    fun navigateBack(): Boolean {
        if (myDriveStack.size > 1) {
            myDriveStack.removeLast()
            val target = myDriveStack.last()
            val cached = if (target.first == "FAVOURITES") _favourites.value else folderPageCache.get(target.first)
            _currentFolder.value = target
            if (cached != null) {
                applySortToFiles(cached, _sortState.value)
                if (target.first != "FAVOURITES") {
                    fetchCurrentFolder(forceShowLoading = false)
                }
            } else {
                _uiState.value = DriveUiState.Loading
                fetchCurrentFolder(forceShowLoading = true)
            }
            return true
        }
        return false // Let the system handle Back if we are at root
    }

    private fun fetchCurrentFolder(forceShowLoading: Boolean = false) {
        viewModelScope.launch {
            val currentFolderId = myDriveStack.last().first
            if (currentFolderId == "FAVOURITES") {
                applySortToFiles(_favourites.value, _sortState.value)
                return@launch
            }

            val cachedExisting = folderPageCache.get(currentFolderId)
            if (forceShowLoading || cachedExisting == null) {
                _uiState.value = DriveUiState.Loading
            } else {
                _isBackgroundRefreshing.value = true
            }

            try {
                val rawFiles = repository.getFilesAndFolders(folderId = currentFolderId)
                val files = rawFiles.filterNot { it.isFolder && it.name.trim().equals("mp3", ignoreCase = true) }

                if (currentFolderId == "root" && !hasAutoNavigatedToDefaultFolder) {
                    val defId = _defaultMusicFolderId.value
                    val defName = _defaultMusicFolderName.value
                    if (!defId.isNullOrEmpty() && !defName.isNullOrEmpty()) {
                        hasAutoNavigatedToDefaultFolder = true
                        openFolder(defId, defName)
                        return@launch
                    }
                }

                folderPageCache.put(currentFolderId, files)
                if (cachedExisting == null || cachedExisting != files) {
                    applySortToFiles(files, _sortState.value)
                }
            } catch (e: Exception) {
                if (cachedExisting == null) {
                    _uiState.value = DriveUiState.Error("Failed to fetch files: ${e.toString()}")
                } else {
                    android.util.Log.w("DriveViewModel", "Background refresh failed, keeping cached files: ${e.message}")
                }
            } finally {
                _isBackgroundRefreshing.value = false
            }
        }
    }

    private fun applySortToFiles(files: List<DriveFile>, sortState: SortState) {
        val folders = files.filter { it.isFolder }
        val rawSongs = files.filter { !it.isFolder }
        rawSongs.forEach { file ->
            activeMediaMap[file.fileId] = file
            if (file.name.endsWith(".flac", ignoreCase = true) || file.name.endsWith(".wav", ignoreCase = true) ||
                file.mimeType.contains("flac", ignoreCase = true) || file.mimeType.contains("wav", ignoreCase = true)) {
                knownLosslessFileIds.add(file.fileId)
            } else if (file.name.endsWith(".mp3", ignoreCase = true) || file.mimeType.contains("mpeg", ignoreCase = true)) {
                knownMp3FileIds.add(file.fileId)
            }
        }

        // Group by Base Name
        val groupedSongs = rawSongs.groupBy { it.name.substringBeforeLast(".").trim() }
        
        // Prioritize Lossless & Fallback to MP3
        val deduplicatedSongs = groupedSongs.map { (_, group) ->
            val losslessFile = group.find { 
                it.name.endsWith(".flac", ignoreCase = true) || 
                it.name.endsWith(".wav", ignoreCase = true) || 
                it.mimeType.contains("flac", ignoreCase = true) || 
                it.mimeType.contains("wav", ignoreCase = true) 
            }
            losslessFile ?: group.first()
        }

        val sortedSongs = if (sortState.property == SortProperty.NAME) {
            if (sortState.direction == SortDirection.ASCENDING) deduplicatedSongs.sortedBy { it.name.lowercase() }
            else deduplicatedSongs.sortedByDescending { it.name.lowercase() }
        } else {
            if (sortState.direction == SortDirection.ASCENDING) deduplicatedSongs.sortedBy { parseInstant(it.modifiedTime) }
            else deduplicatedSongs.sortedByDescending { parseInstant(it.modifiedTime) }
        }

        // Emit State
        _uiState.value = DriveUiState.Success(folders + sortedSongs)
        loadThumbnailsForFiles(sortedSongs)
    }

    fun updateActiveMediaItemPoster(fileId: String, posterUrl: String) {
        if (_currentPlayingFileId.value == fileId) {
            _currentSpotifyArtUrl.value = posterUrl
            val player = hostController ?: return
            val currIdx = player.currentMediaItemIndex
            if (currIdx != androidx.media3.common.C.INDEX_UNSET && currIdx in 0 until player.mediaItemCount) {
                val currentItem = player.getMediaItemAt(currIdx)
                if (currentItem.mediaId == fileId) {
                    val currentArt = currentItem.mediaMetadata.artworkUri?.toString()
                    if (currentArt != posterUrl) {
                        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                            .setArtworkUri(Uri.parse(posterUrl))
                            .build()
                        val updatedItem = currentItem.buildUpon()
                            .setMediaMetadata(updatedMetadata)
                            .build()
                        viewModelScope.launch(Dispatchers.Main) {
                            try {
                                player.replaceMediaItem(currIdx, updatedItem)
                            } catch (e: Exception) {
                                android.util.Log.w("DriveViewModel", "replaceMediaItem failed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads album art / thumbnails for a list of audio files asynchronously.
     * Synchronously resolves any already-cached thumbnails, then queries
     * Spotify and music databases in the background with controlled concurrency.
     */
    fun loadThumbnailsForFiles(files: List<DriveFile>) {
        val audioFiles = files.filter { !it.isFolder }
        ThumbnailDiagnosticManager.log("DriveViewModel", "loadThumbnailsForFiles called for ${audioFiles.size} songs")
        if (audioFiles.isEmpty()) return

        // 1. Immediately extract from cache synchronously
        val cachedMap = mutableMapOf<String, String>()
        val toFetch = mutableListOf<DriveFile>()
        for (file in audioFiles) {
            val cached = SpotifyAuthManager.getCachedArtUrl(file.name)
            if (cached != null) {
                cachedMap[file.fileId] = cached
            } else {
                toFetch.add(file)
            }
        }
        if (cachedMap.isNotEmpty()) {
            _songThumbnails.update { it + cachedMap }
            cachedMap.forEach { (id, url) -> updateActiveMediaItemPoster(id, url) }
        }

        ThumbnailDiagnosticManager.log("DriveViewModel", "${cachedMap.size} cached, ${toFetch.size} to fetch online")

        if (toFetch.isEmpty()) return

        // 2. Fetch uncached tracks in background — sequential to avoid R8 coroutine issues
        viewModelScope.launch(Dispatchers.IO) {
            for (file in toFetch) {
                try {
                    ThumbnailDiagnosticManager.log("DriveViewModel", "Fetching: ${file.name}")
                    val url = SpotifyAuthManager.getAlbumArtUrl(file.name)
                    if (!url.isNullOrEmpty()) {
                        _songThumbnails.update { it + (file.fileId to url) }
                        withContext(Dispatchers.Main) {
                            updateActiveMediaItemPoster(file.fileId, url)
                        }
                        ThumbnailDiagnosticManager.log("DriveViewModel", "Loaded: ${file.name} -> $url")
                    } else {
                        ThumbnailDiagnosticManager.log("DriveViewModel", "No art found: ${file.name}")
                    }
                } catch (e: Exception) {
                    ThumbnailDiagnosticManager.log("DriveViewModel", "Error fetching ${file.name}: ${e.message}")
                }
            }
            ThumbnailDiagnosticManager.log("DriveViewModel", "Batch complete for ${toFetch.size} songs")
        }
    }

    fun retryLoadingCurrentThumbnails() {
        val currentFiles = (_uiState.value as? DriveUiState.Success)?.files ?: emptyList()
        val songs = currentFiles.filter { !it.isFolder }
        ThumbnailDiagnosticManager.log("DriveViewModel", "Manual retry requested for ${songs.size} songs")
        viewModelScope.launch(Dispatchers.IO) {
            for (file in songs) {
                try {
                    val url = SpotifyAuthManager.getAlbumArtUrl(file.name)
                    if (!url.isNullOrEmpty()) {
                        _songThumbnails.update { it + (file.fileId to url) }
                        withContext(Dispatchers.Main) {
                            updateActiveMediaItemPoster(file.fileId, url)
                        }
                    }
                } catch (e: Exception) {
                    ThumbnailDiagnosticManager.log("DriveViewModel", "Retry error for ${file.name}: ${e.message}")
                }
            }
        }
    }
    fun toggleFavourite(file: DriveFile) {
        val currentFavs = _favourites.value.toMutableList()
        val existingIndex = currentFavs.indexOfFirst { it.fileId == file.fileId }
        
        if (existingIndex != -1) {
            currentFavs.removeAt(existingIndex)
        } else {
            currentFavs.add(0, file)
        }
        
        _favourites.value = currentFavs
        viewModelScope.launch {
            themeRepository.saveFavourites(currentFavs)
            // If user is currently looking at Favourites folder, visually update it immediately
            if (myDriveStack.lastOrNull()?.first == "FAVOURITES") {
                applySortToFiles(currentFavs, _sortState.value)
            }
        }
    }

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            themeRepository.saveTheme(theme)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        // Immediately show idle if user clears
        if (query.isEmpty()) _searchState.value = SearchState.Idle
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchState.value = SearchState.Idle
    }

    fun setAccentColor(accent: AccentColor) {
        viewModelScope.launch {
            themeRepository.saveAccentColor(accent)
        }
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer() // Clear any existing timer
        val durationMillis = minutes * 60 * 1000L
        _sleepTimerTotal.value = durationMillis
        
        sleepTimerJob = viewModelScope.launch {
            var remaining = durationMillis
            while (remaining > 0) {
                _sleepTimerRemaining.value = remaining
                kotlinx.coroutines.delay(1000L)
                remaining -= 1000L
            }
            // Timer expired
            _sleepTimerRemaining.value = null
            _sleepTimerTotal.value = null
            
            val player = hostController
            if (player != null) {
                if (_sleepTimerPauseAfterTrack.value) {
                    _sleepTimerPauseAfterTrackPending.value = true
                } else {
                    // Fade out over 5 seconds
                    val startVolume = player.volume
                    val fadeSteps = 50
                    for (i in 0..fadeSteps) {
                        val progress = i.toFloat() / fadeSteps.toFloat()
                        player.volume = startVolume * (1f - progress)
                        kotlinx.coroutines.delay(100L)
                    }
                    player.pause()
                    player.volume = startVolume
                }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemaining.value = null
        _sleepTimerTotal.value = null
        _sleepTimerPauseAfterTrackPending.value = false
    }

    private var hasRestoredPlayback = false

    private suspend fun restorePlaybackState() {
        if (hasRestoredPlayback) return
        val existingPlayer = hostController
        if (existingPlayer != null && (
                existingPlayer.currentMediaItem != null ||
                existingPlayer.isPlaying ||
                existingPlayer.playWhenReady ||
                existingPlayer.playbackState == Player.STATE_BUFFERING
            )
        ) {
            hasRestoredPlayback = true
            if (existingPlayer.duration > 0L) {
                _activeTrackDurationMs.value = existingPlayer.duration
            }
            return
        }
        
        val queue = themeRepository.playbackQueueFlow.firstOrNull() ?: emptyList()
        if (queue.isEmpty()) return
        
        val trackId = themeRepository.currentTrackIdFlow.firstOrNull()
        val position = themeRepository.currentPositionFlow.firstOrNull() ?: 0L
        val savedDuration = themeRepository.currentDurationFlow.firstOrNull() ?: 0L
        
        _originalPlaylist.value = queue
        _activeQueue.value = queue
        activeSourceFolderId = null
        val startIndex = if (trackId != null) queue.indexOfFirst { it.fileId == trackId }.coerceAtLeast(0) else 0
        _currentIndex.value = startIndex
        _currentPlayingFileId.value = trackId
        _currentPlayingTrack.value = queue.getOrNull(startIndex)
        activeAudioPlaylist = queue

        val fallbackDuration = if (savedDuration > 0L) savedDuration else (queue.getOrNull(startIndex)?.durationMs ?: 0L)
        _activeTrackDurationMs.value = fallbackDuration
        
        val token = repository.getBearerToken() ?: return
        PlaybackService.updateActiveToken(token)
        
        val mediaItems = queue.map { file ->
            activeMediaMap[file.fileId] = file
            val artUrl = file.thumbnailUrl
                ?: _songThumbnails.value[file.fileId]
                ?: SpotifyAuthManager.getCachedArtUrl(file.name)
            val uri = Uri.parse("https://www.googleapis.com/drive/v3/files/${file.fileId}?alt=media")
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(file.name)
                .setArtist("DriveStream")
                .apply {
                    if (!artUrl.isNullOrEmpty()) {
                        setArtworkUri(Uri.parse(artUrl))
                    }
                }
                .build()
            val requestMetadata = MediaItem.RequestMetadata.Builder()
                .setExtras(Bundle().apply { 
                    putString("auth_token", token)
                    repository.activeAccountEmail?.let { putString("account_email", it) }
                }).build()
            MediaItem.Builder().setUri(uri).setMediaId(file.fileId).setMimeType(file.mimeType)
                .setMediaMetadata(mediaMetadata).setRequestMetadata(requestMetadata).setTag(file).build()
        }
        
        val initialArt = queue.getOrNull(startIndex)?.let { f ->
            f.thumbnailUrl ?: _songThumbnails.value[f.fileId] ?: SpotifyAuthManager.getCachedArtUrl(f.name)
        }
        if (!initialArt.isNullOrEmpty()) {
            _currentSpotifyArtUrl.value = initialArt
        }
        
        withContext(Dispatchers.Main) {
            val player = hostController ?: return@withContext
            player.setMediaItems(mediaItems, startIndex, position)
            player.prepare()
            player.playWhenReady = false // Resume paused
            hasRestoredPlayback = true
        }
    }

    fun saveSessionPlaybackState() {
        val player = hostController ?: return
        viewModelScope.launch {
            val fullQueue = if (_activeQueue.value.isNotEmpty()) {
                _activeQueue.value
            } else {
                val totalItems = player.mediaItemCount
                val queueList = mutableListOf<DriveFile>()
                for (i in 0 until totalItems) {
                    val item = player.getMediaItemAt(i)
                    val title = item.mediaMetadata.title?.toString() ?: "Unknown Track"
                    queueList.add(
                        DriveFile(
                            fileId = item.mediaId,
                            name = title,
                            size = 0L,
                            mimeType = item.localConfiguration?.mimeType ?: "",
                            isFolder = false,
                            modifiedTime = "",
                            displaySize = "",
                            displayDate = ""
                        )
                    )
                }
                queueList
            }
            if (fullQueue.isNotEmpty()) {
                val currentTrackId = player.currentMediaItem?.mediaId
                val position = player.currentPosition
                val folderName = _currentPlayingFolderName.value
                val validDuration = if (player.duration > 0L) player.duration else _activeTrackDurationMs.value
                themeRepository.savePlaybackState(fullQueue, currentTrackId, position, folderName, validDuration)
            }
        }
    }
}


