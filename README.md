# DriveStream

DriveStream is a modern Android application built with Jetpack Compose featuring an edge-to-edge responsive layout, fluid media playback via AndroidX Media3 (ExoPlayer), Spotify album art resolution, real-time shared playback Jam Sessions via Firebase, and seamless Google Drive audio streaming.

## Features
- **Google Drive Integration**: Direct, authenticated audio streaming from personal and shared Google Drives.
- **Audio Playback Engine**: Powered by AndroidX Media3 (ExoPlayer) with background playback service and lock screen media controls.
- **Modern Jetpack Compose UI**: Dynamic Material 3 theming, AMOLED dark modes, and anchored fluid bottom sheet player.
- **Rich Metadata & Art**: Automatic high-resolution album art resolution using Spotify Web API and iTunes Search API fallback.
- **Jam Sessions**: Real-time collaborative synchronized listening rooms powered by Firebase Realtime Database.
- **Built-in Equalizer & Audio FX**: Custom presets, 5-band EQ, bass boost, and loudness enhancement.

## Tech Stack & Architecture
- **Language**: Kotlin
- **UI Toolkit**: Jetpack Compose, Material 3
- **Media Engine**: AndroidX Media3 (ExoPlayer) 1.2.1
- **Async & Networking**: Kotlin Coroutines & Flow, Retrofit 2, OkHttp
- **Architecture**: MVVM with unidirectional data flow
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 35 (Android 15)

## Getting Started

### 1. Prerequisites
- **Android Studio** (Ladybug / Jellyfish or newer recommended)
- **JDK 21**
- Android SDK 35 and Build Tools installed

### 2. Configuration & Credentials

For security, API keys and credentials are not tracked in version control. Follow these steps to configure your environment:

1. **Local Properties**:
   Copy `local.properties.example` to `local.properties`:
   ```properties
   sdk.dir=/path/to/your/Android/Sdk
   GOOGLE_CLIENT_ID="your_google_client_id_here"
   SPOTIFY_CLIENT_ID="your_spotify_client_id_here"
   SPOTIFY_CLIENT_SECRET="your_spotify_client_secret_here"
   FIREBASE_DATABASE_URL="https://your-project-default-rtdb.firebaseio.com"
   ```

2. **Google Services**:
   Obtain your `google-services.json` from the Firebase Console / Google Cloud Console with Google Drive API enabled, and place it at:
   ```
   app/google-services.json
   ```
   *(See `app/google-services.json.example` for reference format).*

3. **Release Keystore (Optional for release builds)**:
   If building signed release APKs, create `keystore.properties` using `keystore.properties.example` as a template.

### 3. Building & Running

Open the project in Android Studio, allow Gradle to sync, and run the app:
- **Debug build**: Select the `debug` build variant and click **Run** (`Shift + F10`).
- **Command Line**:
  ```bash
  ./gradlew assembleDebug
  ```

## License
Open source and available under the standard development license.
