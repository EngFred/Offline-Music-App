# Music

**Music** — A fully offline, modern, modularized music player app built with **Kotlin** and **Jetpack Compose**. This project demonstrates Clean Architecture, feature-based modularization, and modern Android development best practices.

---

## 📥 Download APK

Get the latest release of **Music** here:

[Download Music APK](https://github.com/EngFred/Offline-Music-App/releases/download/v2.2.0/Musicv2.2.0.apk)

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
include(":features:audio_trim")
```

---

## Key Dependencies & Versions

The project uses a centralized version catalog. Selected versions used in the project:

* Kotlin: `2.1.0`
* Android Gradle Plugin (AGP): `8.11.1`
* Jetpack Compose BOM: `2025.07.00`
* Hilt: `2.55`
* Media3: `1.8.0`
* Room: `2.6.1`
* Coroutines: `1.10.2`
* Coil: `2.7.0`
* KSP: `2.1.0-1.0.29`
* accompanist-systemuicontroller: `0.36.0`

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

```text
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
 └─ audio_trim/
```

Each feature follows the same `data/domain/presentation` pattern. Keeping public interfaces in `domain` and implementation details in `data`.

---

## Contact

For questions or collaboration reach me at:

Email: engfred88@gmail.com

WhatsApp: 0754348118

LinkedIn: https://www.linkedin.com/in/fred-omongole-a5943b2b0/
