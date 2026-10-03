package com.example.drivestream

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class AppTheme {
    SYSTEM, LIGHT, DARK, AMOLED
}

enum class AccentColor {
    BLUE, GREEN, GREY, PURPLE
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class ThemeRepository(private val context: Context) {
    private val THEME_KEY = stringPreferencesKey("app_theme")
    private val ACCENT_KEY = stringPreferencesKey("accent_color")
    private val DATA_SAVER_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("data_saver_enabled")
    
    // Sort State
    private val SORT_PROPERTY_KEY = stringPreferencesKey("sort_property")
    private val SORT_DIRECTION_KEY = stringPreferencesKey("sort_direction")
    
    // Playback State
    private val PLAYBACK_QUEUE_KEY = stringPreferencesKey("playback_queue")
    private val CURRENT_TRACK_ID_KEY = stringPreferencesKey("current_track_id")
    private val CURRENT_POSITION_KEY = longPreferencesKey("current_position")
    private val CURRENT_FOLDER_NAME_KEY = stringPreferencesKey("current_folder_name")
    
    // Favourites
    private val FAVOURITES_LIST_KEY = stringPreferencesKey("favourites_list")
    
    // Equalizer
    private val EQUALIZER_SETTINGS_KEY = stringPreferencesKey("equalizer_settings")
    private val EQUALIZER_ENABLED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("equalizer_enabled")
    private val EQUALIZER_PRESET_KEY = stringPreferencesKey("equalizer_preset")
    
    // Music Folder Onboarding & Default Library
    private val MUSIC_FOLDER_PROMPT_SHOWN_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("music_folder_prompt_shown")
    private val DEFAULT_MUSIC_FOLDER_ID_KEY = stringPreferencesKey("default_music_folder_id")
    private val DEFAULT_MUSIC_FOLDER_NAME_KEY = stringPreferencesKey("default_music_folder_name")
    
    private val gson = Gson()

    val themeFlow: Flow<AppTheme> = context.dataStore.data
        .map { preferences ->
            val themeName = preferences[THEME_KEY] ?: AppTheme.AMOLED.name
            try { AppTheme.valueOf(themeName) } catch (e: Exception) { AppTheme.AMOLED }
        }

    val accentColorFlow: Flow<AccentColor> = context.dataStore.data
        .map { preferences ->
            val name = preferences[ACCENT_KEY] ?: AccentColor.BLUE.name
            try { AccentColor.valueOf(name) } catch (e: Exception) { AccentColor.BLUE }
        }

    val dataSaverFlow: Flow<Boolean> = context.dataStore.data.map { it[DATA_SAVER_KEY] ?: false }

    val sortStateFlow: Flow<SortState> = context.dataStore.data.map { preferences ->
        val prop = try { SortProperty.valueOf(preferences[SORT_PROPERTY_KEY] ?: SortProperty.DATE.name) } catch (e: Exception) { SortProperty.DATE }
        val dir = try { SortDirection.valueOf(preferences[SORT_DIRECTION_KEY] ?: SortDirection.DESCENDING.name) } catch (e: Exception) { SortDirection.DESCENDING }
        SortState(prop, dir)
    }
    
    val playbackQueueFlow: Flow<List<DriveFile>> = context.dataStore.data.map { preferences ->
        val json = preferences[PLAYBACK_QUEUE_KEY]
        if (json.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<DriveFile>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    val favouritesFlow: Flow<List<DriveFile>> = context.dataStore.data.map { preferences ->
        val json = preferences[FAVOURITES_LIST_KEY]
        if (json.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<DriveFile>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    
    val equalizerSettingsFlow: Flow<Map<Short, Short>> = context.dataStore.data
        .map { preferences ->
            val json = preferences[EQUALIZER_SETTINGS_KEY]
            if (json != null) {
                try {
                    val type = object : TypeToken<Map<String, Short>>() {}.type
                    val parsed: Map<String, Short> = gson.fromJson(json, type)
                    val shortMap = mutableMapOf<Short, Short>()
                    for ((k, v) in parsed) {
                        shortMap[k.toShort()] = v
                    }
                    shortMap
                } catch (e: Exception) { emptyMap() }
            } else {
                emptyMap()
            }
        }
        
    val equalizerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[EQUALIZER_ENABLED_KEY] ?: false }
    val equalizerPresetFlow: Flow<String> = context.dataStore.data.map { it[EQUALIZER_PRESET_KEY] ?: "Custom" }
    
    val currentTrackIdFlow: Flow<String?> = context.dataStore.data.map { it[CURRENT_TRACK_ID_KEY] }
    val currentPositionFlow: Flow<Long> = context.dataStore.data.map { it[CURRENT_POSITION_KEY] ?: 0L }
    val currentFolderNameFlow: Flow<String?> = context.dataStore.data.map { it[CURRENT_FOLDER_NAME_KEY] }

    val musicFolderPromptShownFlow: Flow<Boolean> = context.dataStore.data.map { it[MUSIC_FOLDER_PROMPT_SHOWN_KEY] ?: false }
    val defaultMusicFolderIdFlow: Flow<String?> = context.dataStore.data.map { it[DEFAULT_MUSIC_FOLDER_ID_KEY] }
    val defaultMusicFolderNameFlow: Flow<String?> = context.dataStore.data.map { it[DEFAULT_MUSIC_FOLDER_NAME_KEY] }

    suspend fun saveTheme(theme: AppTheme) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme.name
        }
    }

    suspend fun saveAccentColor(accent: AccentColor) {
        context.dataStore.edit { preferences ->
            preferences[ACCENT_KEY] = accent.name
        }
    }

    suspend fun saveDataSaver(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DATA_SAVER_KEY] = enabled
        }
    }
    suspend fun saveSortState(state: SortState) {
        context.dataStore.edit { preferences ->
            preferences[SORT_PROPERTY_KEY] = state.property.name
            preferences[SORT_DIRECTION_KEY] = state.direction.name
        }
    }
    
    suspend fun savePlaybackState(queue: List<DriveFile>, currentTrackId: String?, position: Long, folderName: String?) {
        context.dataStore.edit { preferences ->
            // For large folders, we limit to the first 500 tracks to avoid massive JSON strings
            val limitedQueue = queue.take(500)
            preferences[PLAYBACK_QUEUE_KEY] = gson.toJson(limitedQueue)
            
            if (currentTrackId != null) preferences[CURRENT_TRACK_ID_KEY] = currentTrackId
            else preferences.remove(CURRENT_TRACK_ID_KEY)
            
            preferences[CURRENT_POSITION_KEY] = position
            
            if (folderName != null) preferences[CURRENT_FOLDER_NAME_KEY] = folderName
            else preferences.remove(CURRENT_FOLDER_NAME_KEY)
        }
    }

    suspend fun saveFavourites(list: List<DriveFile>) {
        context.dataStore.edit { preferences ->
            try {
                preferences[FAVOURITES_LIST_KEY] = gson.toJson(list)
            } catch (e: Exception) {}
        }
    }

    suspend fun saveEqualizerSettings(settings: Map<Short, Short>) {
        context.dataStore.edit { preferences ->
            preferences[EQUALIZER_SETTINGS_KEY] = gson.toJson(settings)
        }
    }
    
    suspend fun saveEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[EQUALIZER_ENABLED_KEY] = enabled
        }
    }
    
    suspend fun saveEqualizerPreset(presetName: String) {
        context.dataStore.edit { preferences ->
            preferences[EQUALIZER_PRESET_KEY] = presetName
        }
    }

    suspend fun saveMusicFolderPromptShown(shown: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MUSIC_FOLDER_PROMPT_SHOWN_KEY] = shown
        }
    }

    suspend fun saveDefaultMusicFolder(folderId: String?, folderName: String?) {
        context.dataStore.edit { preferences ->
            if (folderId != null && folderName != null) {
                preferences[DEFAULT_MUSIC_FOLDER_ID_KEY] = folderId
                preferences[DEFAULT_MUSIC_FOLDER_NAME_KEY] = folderName
            } else {
                preferences.remove(DEFAULT_MUSIC_FOLDER_ID_KEY)
                preferences.remove(DEFAULT_MUSIC_FOLDER_NAME_KEY)
            }
        }
    }
}
