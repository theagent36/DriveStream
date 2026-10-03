package com.example.drivestream

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import com.google.android.gms.auth.GoogleAuthUtil
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@SuppressLint("UnsafeOptInUsageError")
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    private var lastPausedBluetoothAddress: String? = null
    private var pausedDueToDisconnect: Boolean = false
    private var isEqEnabled = false
    private var currentEqSettings: Map<Short, Short> = emptyMap()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val device = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            
            if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                if (pausedDueToDisconnect) {
                    lastPausedBluetoothAddress = device?.address
                }
            } else if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                if (pausedDueToDisconnect && device?.address != null && device.address == lastPausedBluetoothAddress) {
                    lastPausedBluetoothAddress = null
                    pausedDueToDisconnect = false
                    player?.play()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DriveStream::PlaybackWakeLock").apply {
            setReferenceCounted(false)
        }
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DriveStream::PlaybackWifiLock").apply {
            setReferenceCounted(false)
        }
        serviceScope.launch {
            ThemeRepository(this@PlaybackService).equalizerEnabledFlow.collect { enabled ->
                isEqEnabled = enabled
                try {
                    equalizer?.enabled = enabled
                } catch (e: Exception) {
                    Log.e("DriveStreamExoPlayer", "Failed to set Equalizer state", e)
                }
            }
        }
        
        serviceScope.launch {
            ThemeRepository(this@PlaybackService).equalizerSettingsFlow.collect { settings ->
                currentEqSettings = settings
                try {
                    val eq = equalizer ?: return@collect
                    for ((band, level) in settings) {
                        eq.setBandLevel(band, level)
                    }
                } catch (e: Exception) {
                    Log.e("DriveStreamExoPlayer", "Failed to apply EQ settings", e)
                }
            }
        }
        
        // Use standard DefaultHttpDataSource and bypass Google Drive's default block with custom User-Agent
        // Boost connection and read timeouts to 15s to counter severe Drive packet stalls
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(androidx.media3.common.util.Util.getUserAgent(this, "DriveStream"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        activeToken?.let { token ->
            httpFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        }
        PlaybackService.httpDataSourceFactory = httpFactory

        // Implement JIT ResolvingDataSource to ensure the token is 100% fresh immediately before the byte-request hits.
        // ExoPlayer automatically executes resolvers on a background thread preventing NetworkOnMainThread issues.
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(httpFactory, ResolvingDataSource.Resolver { dataSpec ->
            val uriStr = dataSpec.uri.toString()
            if (!uriStr.contains("googleapis.com/drive")) {
                return@Resolver dataSpec
            }

            var tokenToUse: String? = null
            try {
                val googleAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this@PlaybackService)?.account
                if (googleAccount != null) {
                    tokenToUse = GoogleAuthUtil.getToken(
                        this@PlaybackService,
                        googleAccount,
                        "oauth2:https://www.googleapis.com/auth/drive.readonly"
                    )
                    if (!tokenToUse.isNullOrEmpty()) {
                        updateActiveToken(tokenToUse)
                    }
                }
            } catch (e: Exception) {
                Log.w("DriveStreamAuth", "JIT Token Resolution Failed, falling back to cached activeToken", e)
            }

            if (tokenToUse.isNullOrEmpty()) {
                tokenToUse = activeToken
            }

            if (!tokenToUse.isNullOrEmpty()) {
                dataSpec.withRequestHeaders(mapOf("Authorization" to "Bearer $tokenToUse"))
            } else {
                Log.e("DriveStreamAuth", "No auth token available for request: ${dataSpec.uri}")
                dataSpec
            }
        })

        // Prevent Google rate-limiting the stream chunking by aggressively writing active stream bytes to 
        // the physical app cache using SimpleCache with a strict 200MB Least Recently Used evictor.
        // Cache is now managed by a strict global Singleton to prevent multi-instantiation leaks.
        val globalCache = DriveStreamCache.getInstance(this)

        val cacheDataSinkFactory = CacheDataSink.Factory()
            .setCache(globalCache)
            .setFragmentSize(2 * 1024 * 1024L)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(globalCache)
            .setUpstreamDataSourceFactory(resolvingDataSourceFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            
        PlaybackService.cacheDataSourceFactory = cacheDataSourceFactory

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                600000, // 10 minutes max buffer
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        // Build ExoPlayer pointing network requests through the interceptor and local cache mechanism
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory).setLoadErrorHandlingPolicy(InfiniteRetryLoadErrorHandlingPolicy()))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(), true) // handle audio focus
            .setHandleAudioBecomingNoisy(true) // Automatically pause when audio outputs change or disconnect
            .build()
            
        // Attach logging listener to catch specific 401/403 unauthenticated ExoPlayer blocks
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("DriveStreamExoPlayer", "Playback Error: ${error.message}", error)
                val cause = error.cause
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    Log.e("DriveStreamExoPlayer", "HTTP API Error Code: ${cause.responseCode}")
                }
            }

            private fun updateLocks() {
                val state = player?.playbackState ?: Player.STATE_IDLE
                val playing = player?.playWhenReady ?: false
                val isActivelyDownloading = state == Player.STATE_BUFFERING || (playing && state == Player.STATE_READY)
                if (isActivelyDownloading) {
                    wakeLock?.acquire(10 * 60 * 1000L) // 10 min timeout just in case
                    wifiLock?.acquire()
                } else {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    if (wifiLock?.isHeld == true) wifiLock?.release()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateLocks()
                if (playbackState == Player.STATE_READY && player?.playWhenReady == true) {
                    try { equalizer?.enabled = isEqEnabled } catch (e: Exception) {}
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateLocks()
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    pausedDueToDisconnect = true
                } else if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    pausedDueToDisconnect = false
                    lastPausedBluetoothAddress = null
                }
                
                if (playWhenReady && player?.playbackState == Player.STATE_READY) {
                    try { equalizer?.enabled = isEqEnabled } catch (e: Exception) {}
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) {
                    val p = player ?: return
                    val count = p.mediaItemCount
                    val current = p.currentMediaItemIndex
                    if (count > 1 && current != C.INDEX_UNSET) {
                        val indices = IntArray(count) { it }
                        val random = java.util.Random()
                        
                        if (current < count - 1) {
                            // Preserve 0 to current. Shuffle from current + 1 to end.
                            for (i in count - 1 downTo current + 2) {
                                val j = (current + 1) + random.nextInt(i - current)
                                val temp = indices[i]
                                indices[i] = indices[j]
                                indices[j] = temp
                            }
                        } else {
                            // If we are at the very end, we might as well just shuffle everything 
                            // except the current one (which we put at the end).
                            for (i in count - 1 downTo 1) {
                                val j = random.nextInt(i + 1)
                                val temp = indices[i]
                                indices[i] = indices[j]
                                indices[j] = temp
                            }
                            // Swap current to the end
                            val indexOfCurrent = indices.indexOf(current)
                            if (indexOfCurrent != count - 1 && indexOfCurrent != -1) {
                                val temp = indices[count - 1]
                                indices[count - 1] = indices[indexOfCurrent]
                                indices[indexOfCurrent] = temp
                            }
                        }
                        
                        val shuffleOrder = androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(indices, random.nextLong())
                        p.setShuffleOrder(shuffleOrder)
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initAudioEffects(audioSessionId)
            }
        })
        
        // Trigger manually for the initial session ID, as it may have been set before the listener was attached
        player?.audioSessionId?.let { sessionId ->
            if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                initAudioEffects(sessionId)
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, filter)

        // Create the MediaSession
        mediaSession = MediaSession.Builder(this, player!!)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
                    return super.onAddMediaItems(mediaSession, controller, mediaItems)
                }
            })
            .build()
    }

    private fun initAudioEffects(audioSessionId: Int) {
        equalizer?.release()
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            try {
                val eq = android.media.audiofx.Equalizer(1, audioSessionId)
                eq.enabled = isEqEnabled
                equalizer = eq
                
                val numBands = eq.numberOfBands
                val bands = mutableListOf<Pair<Short, Int>>()
                for (i in 0 until numBands) {
                    bands.add(Pair(i.toShort(), eq.getCenterFreq(i.toShort())))
                }
                eqBandsFlow.value = bands
                
                val range = eq.bandLevelRange
                eqRangeFlow.value = Pair(range[0], range[1])
                
                eqErrorFlow.value = null
                
                // Get Presets
                val numPresets = eq.numberOfPresets
                val presetList = mutableListOf<Pair<Short, String>>()
                for (i in 0 until numPresets) {
                    try {
                        val name = eq.getPresetName(i.toShort())
                        presetList.add(Pair(i.toShort(), name))
                    } catch (e: Exception) {}
                }
                eqPresetsFlow.value = presetList
                
                // Apply existing settings
                for ((band, level) in currentEqSettings) {
                    try { eq.setBandLevel(band, level) } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("DriveStreamExoPlayer", "Equalizer initialization failed", e)
                eqErrorFlow.value = "Failed: ${e.message ?: "Device doesn't support Equalizer"}"
                eqBandsFlow.value = emptyList()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e("DriveStreamService", "Failed to unregister bluetooth receiver", e)
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        equalizer?.release()
        serviceScope.cancel()
        DriveStreamCache.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        mediaSession?.player?.pause()
        mediaSession?.player?.stop()
        stopSelf()
    }
    
    companion object {
        @Volatile
        var activeToken: String? = null

        var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null

        fun updateActiveToken(token: String) {
            activeToken = token
            httpDataSourceFactory?.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        }

        var cacheDataSourceFactory: CacheDataSource.Factory? = null
        val eqBandsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<Short, Int>>>(emptyList())
        val eqRangeFlow = kotlinx.coroutines.flow.MutableStateFlow<Pair<Short, Short>>(Pair(0, 0))
        val eqPresetsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<Short, String>>>(emptyList())
        val eqErrorFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        private var equalizer: android.media.audiofx.Equalizer? = null
        
        fun setBandLevel(band: Short, level: Short) {
            try {
                equalizer?.setBandLevel(band, level)
            } catch (e: Exception) {
                Log.e("DriveStreamExoPlayer", "Failed to set band level", e)
            }
        }
        
        fun usePreset(preset: Short): Map<Short, Short>? {
            return try {
                val eq = equalizer ?: return null
                eq.usePreset(preset)
                val newSettings = mutableMapOf<Short, Short>()
                for (i in 0 until eq.numberOfBands) {
                    newSettings[i.toShort()] = eq.getBandLevel(i.toShort())
                }
                newSettings
            } catch (e: Exception) {
                Log.e("DriveStreamExoPlayer", "Failed to use EQ preset", e)
                null
            }
        }
    }
}
