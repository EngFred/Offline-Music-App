# Music - Modern Offline Audio Player

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![Hilt](https://img.shields.io/badge/Hilt-Dependency%20Injection-orange?style=for-the-badge&logo=google&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-red?style=for-the-badge&logo=googleplay&logoColor=white)
![Clean Architecture](https://img.shields.io/badge/Clean-Architecture-blueviolet?style=for-the-badge)

<br />

[![Download APK](https://img.shields.io/badge/Download%20APK-v2.2.2-success?style=for-the-badge&logo=android&logoColor=white)](https://github.com/EngFred/Offline-Music-App/releases/download/v2.2.2/Music_v2.2.2.apk)
![Size](https://img.shields.io/badge/Size-22.6_MB-blue?style=for-the-badge)

---

**Music** is a fully offline, modern, modularized music player built with **Kotlin** and **Jetpack Compose**. 

Designed with **Clean Architecture** principles, it features a robust audio engine powered by **Media3 (ExoPlayer)**, seamless modular navigation, and advanced features like audio trimming and tag editing. This project serves as a showcase for modern Android development best practices.

---

<div align="center">
  <img src="https://github.com/user-attachments/assets/916a7469-82c9-49dd-81db-5f864ac0dadb" alt="Screenshot 1" width="24%"/>
  <img src="https://github.com/user-attachments/assets/bced2441-5117-4ef7-bffa-8a1e1f2734c6" alt="Screenshot 2" width="24%"/>
  <img src="https://github.com/user-attachments/assets/e4b2a4c1-f3fc-425b-9b35-2aa6f8fca1f2" alt="Screenshot 3" width="24%"/>
  <img src="https://github.com/user-attachments/assets/553d4558-60ce-4c07-99bc-05b70efa73a7" alt="Screenshot 4" width="24%"/>
</div>

<div align="center">
  <img src="https://github.com/user-attachments/assets/4d269786-cc94-42ba-908e-1210eb9c98f5" alt="Screenshot 5" width="24%"/>
  <img src="https://github.com/user-attachments/assets/aaa31e10-3d5a-4a02-98e2-d2ba84779c37" alt="Screenshot 6" width="24%"/>
  <img src="https://github.com/user-attachments/assets/734c8010-c06d-48d8-93af-6bd39a0b9778" alt="Screenshot 10" width="24%"/>
  <img src="https://github.com/user-attachments/assets/f33c04a5-20ca-488a-8ebd-4c313cb7d3c3" alt="Screenshot 8" width="24%"/>
</div>

<div align="center">
  <img src="https://github.com/user-attachments/assets/05929aa1-a6b8-435b-8a8a-ad0c256f7bf4" alt="Screenshot 9" width="24%"/>
  <img src="https://github.com/user-attachments/assets/df7fd80c-e7da-4878-b3d1-3860d3dbfbe9" alt="Screenshot 7" width="24%"/>
  <img src="https://github.com/user-attachments/assets/a745c80d-2a26-48fe-b61b-f18636d96ef0" alt="Screenshot 11" width="24%"/>
  <img src="https://github.com/user-attachments/assets/8bb5dc67-80ed-45c4-a344-26643cf24f84" alt="Screenshot 12" width="24%"/>
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
