# Music - Modern Offline Audio Player

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![Hilt](https://img.shields.io/badge/Hilt-Dependency%20Injection-orange?style=for-the-badge&logo=google&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-red?style=for-the-badge&logo=googleplay&logoColor=white)
![Clean Architecture](https://img.shields.io/badge/Clean-Architecture-blueviolet?style=for-the-badge)

<br />

[![Download APK](https://img.shields.io/badge/Download%20APK-v2.2.2-success?style=for-the-badge&logo=android&logoColor=white)](https://github.com/EngFred/Offline-Music-App/releases/download/v2.2.2/Music_v2.2.2.apk)
![Size](https://img.shields.io/badge/Size-~15_MB-blue?style=for-the-badge)
![License](https://img.shields.io/github/license/EngFred/Offline-Music-App?style=for-the-badge&color=blue)

---

**Music** is a fully offline, modern, modularized music player built with **Kotlin** and **Jetpack Compose**. 

Designed with **Clean Architecture** principles, it features a robust audio engine powered by **Media3 (ExoPlayer)**, seamless modular navigation, and advanced features like audio trimming and tag editing. This project serves as a showcase for modern Android development best practices.

---

<div align="center">
  <img src="https://github.com/user-attachments/assets/81440998-7936-41d7-894f-4666628eac6b" alt="Music Screenshot 1" width="30%"/>
  <img src="https://github.com/user-attachments/assets/995f5919-aafb-41ec-b2ea-16e48df9909e" alt="Music Screenshot 2" width="30%"/>
  <img src="https://github.com/user-attachments/assets/f3c339a1-6213-47c7-a504-66f3811cefbf" alt="Music Screenshot 3" width="30%"/>
</div>

<div align="center">
  <img src="https://github.com/user-attachments/assets/24d0b54a-2b67-44e8-ab83-db8bc3a11d19" alt="Music Screenshot 4" width="30%"/>
  <img src="https://github.com/user-attachments/assets/9e9e84d2-794a-4572-8a6b-91dd33d0217e" alt="Music Screenshot 5" width="30%"/>
  <img src="https://github.com/user-attachments/assets/1d1d8897-3506-4713-9292-5179cf839ac4" alt="Music Screenshot 6" width="30%"/>
</div>

---

## Project Overview

Music is an offline-first music player focused on a clean, modular architecture. It showcases:

* Feature-first modularization (each feature lives in its own Gradle module).
* Clean Architecture (data, domain, presentation layers per feature).
* Modern Android libraries: Jetpack Compose, Hilt, Media3 (ExoPlayer successor), Room, DataStore, KSP.
* Offline media scanning and playback with Media3 + local metadata handling.

---

## Modules

The project uses Gradle modularization. Key modules included in `settings.gradle.kts`:

```kotlin
rootProject.name = "MusicPlayer"
include(":app")
include(":core")

// Feature modules are organized under the 'features' directory
include(":features:library")
include(":features:player")
include(":features:playlist")
include(":features:settings")
include(":features:trim")
include(":features:edit")
```

---

## Key Dependencies

The project uses a centralized version catalog. Selected versions used in the project:

* Kotlin
* Android Gradle Plugin (AGP)
* Jetpack Compose BOM
* Hilt
* Media3
* Room
* Coroutines
* Coil
* KSP
* accompanist-systemuicontroller

Refer to the `libs.versions.toml` for the full catalog.

---

## Architecture Decisions

### Clean Architecture (feature-first)

* Each feature module (`:features:player`, `:features:library`, etc.) contains `data`, `domain`, and `presentation` packages to enforce separation of concerns.
* `core` module holds cross-cutting concerns (DI setup, common models, utilities, theme, and UI components).

### Dependency Injection

* Hilt is used for DI across modules. Modules expose bindings through interfaces and rely on `@Module` + `@InstallIn` in `core`/feature modules.

### Media Playback

* Media3 (the modern successor to ExoPlayer) is used for playback and session management. Playback logic is isolated in the `:features:player` module with use-cases in `domain`.

### Persistence & Settings

* Room stores user playlists and cached metadata.
* DataStore (preferences) stores persistent settings (theme, playback preferences).

### Build & Compiler Tooling

* KSP is used for code generation where needed (e.g., Room, other annotation processors) — ensure KSP version is compatible with the Kotlin version.

---

## Folder Layout (feature-first example)

```kotlin
app/
core/
 ├─ di/
 ├─ ui/
 └─ utils/
features/
 ├─ library/
 │   ├─ data/
 │   ├─ domain/
 │   └─ presentation/
 ├─ player/
 │   ├─ data/
 │   ├─ domain/
 │   └─ presentation/
 ├─ playlist/
 ├─ settings/
 └─ trim/
```

Each feature follows the same `data/domain/presentation` pattern. Keeping public interfaces in `domain` and implementation details in `data`.

---

## Contact

For questions or collaboration reach me at:

Email: engfred88@gmail.com

WhatsApp: 0754348118

LinkedIn: https://www.linkedin.com/in/fred-omongole-a5943b2b0/
