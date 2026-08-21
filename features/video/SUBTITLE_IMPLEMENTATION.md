# Subtitle Implementation Summary

## Overview
This implementation adds comprehensive subtitle support to the video player, following clean architecture principles with maintainable code structure.

## Architecture

### Domain Layer
- **SubtitleTrack.kt**: Core domain models for subtitle tracks and formats
  - `SubtitleTrack`: Represents available subtitle tracks (embedded or external)
  - `SubtitleType`: Enum for EMBEDDED vs EXTERNAL subtitles
  - `SubtitleFormat`: Supported formats (.srt, .vtt, .ttml, .ssa/.ass)

- **VideoPlaybackState.kt**: Extended with subtitle-related state
  - `availableSubtitleTracks`: List of discovered subtitle tracks
  - `selectedSubtitleTrackId`: Currently selected subtitle track
  - `currentCues`: Current subtitle text cues from Media3

- **VideoPlaybackController.kt**: Extended interface with subtitle methods
  - `setSubtitleTracks()`: Set available subtitle tracks
  - `selectSubtitleTrack()`: Select specific subtitle track
  - `getAvailableSubtitleTracks()`: Get current available tracks

### Data Layer
- **SubtitleDiscoveryService.kt**: Service for discovering external subtitle files
  - Auto-matches subtitle files by filename (movie.mp4 → movie.srt)
  - Supports language extraction from filename patterns (movie.en.srt)
  - Handles both file URIs and content URIs

- **VideoPlaybackControllerImpl.kt**: Extended implementation
  - Integrated Media3's `DefaultTrackSelector` for track selection
  - Added `onCues()` listener for subtitle text rendering
  - Added `onTracksChanged()` listener for embedded subtitle discovery
  - Supports both embedded and external subtitle loading

### Presentation Layer
- **VideoPlayerViewModel.kt**: Extended with subtitle events and state
  - Added subtitle discovery on video load
  - Events: `SelectSubtitleTrack`, `ShowSubtitleDialog`, `HideSubtitleDialog`
  - State: `showSubtitleDialog`, `availableSubtitleTracks`

- **VideoPlayerState.kt**: Extended UI state
  - `showSubtitleDialog`: Controls subtitle dialog visibility
  - `availableSubtitleTracks`: List of subtitle tracks for UI

- **SubtitleOverlay.kt**: Compose-native subtitle rendering
  - Renders Media3 cues as Compose Text components
  - Configurable styling (size, color, background, etc.)
  - Enhanced version with `SubtitleStyle` data class for custom styling

- **SubtitleSelectionDialog.kt**: UI for subtitle track selection
  - Shows both embedded and external subtitle tracks
  - "Off" option to disable subtitles
  - Visual indicators for selected track and track type

- **VideoPlayerControls.kt**: Added subtitle button
  - Subtitle icon in top bar controls
  - Visual feedback when subtitles are available/selected
  - Opens subtitle selection dialog

- **VideoPlayerScreen.kt**: Integrated subtitle overlay
  - Added `SubtitleOverlay` to both immersive and portrait views
  - Added `SubtitleSelectionDialog` for track selection

## Features Implemented

### 1. Embedded Subtitle Support
- Automatic detection of embedded subtitle tracks (MKV, etc.)
- Track selection via Media3's DefaultTrackSelector
- Support for multiple embedded subtitle tracks

### 2. External Subtitle Support
- Auto-discovery of external subtitle files by filename
- Support for .srt, .vtt, .ttml, .ssa/.ass formats
- Language detection from filename patterns
- Manual subtitle attachment to MediaItem

### 3. Compose-Native Rendering
- Custom subtitle rendering using Compose Text
- Full control over styling to match app design
- Configurable size, color, background, and positioning
- Better performance and integration than AndroidView wrapper

### 4. User Interface
- Subtitle button in video controls
- Subtitle selection dialog with track information
- Visual feedback for subtitle availability and selection
- Clear indication of embedded vs external tracks

## Technical Decisions

### Clean Architecture
- Domain models in core module for reusability
- Data layer handles file discovery and Media3 integration
- Presentation layer focuses on UI and user interaction
- Clear separation of concerns between layers

### Media3 Integration
- Used DefaultTrackSelector for embedded track selection
- Leveraged onCues() callback for real-time subtitle text
- Used MediaItem.SubtitleConfiguration for external subtitles
- Proper track parameter management for selection

### Compose-Native Approach
- Chose Compose Text over AndroidView for better integration
- Allows full styling control matching app design
- Better performance and memory management
- Easier to maintain and extend

## Usage Flow

1. **Video Loading**: When a video is loaded, the ViewModel:
   - Discovers external subtitle files via SubtitleDiscoveryService
   - Sets subtitle tracks in VideoPlaybackController
   - Prepares video with external subtitle configurations

2. **Track Discovery**: Media3 automatically discovers embedded tracks:
   - onTracksChanged() callback extracts embedded subtitle info
   - Combines embedded and external tracks in available list
   - Updates UI state with available tracks

3. **Subtitle Rendering**: During playback:
   - onCues() callback provides current subtitle text
   - SubtitleOverlay renders cues as Compose Text
   - Updates in real-time as video progresses

4. **User Selection**: User can:
   - Click subtitle button to open selection dialog
   - Choose from available tracks or disable subtitles
   - Selection immediately updates playback

## Future Enhancements

Potential improvements for future iterations:
- Subtitle style settings (size, color, font)
- Subtitle sync adjustment
- Download subtitles from online sources
- Search for subtitles by video name
- Custom subtitle upload via file picker
- Subtitle preview in selection dialog

## Testing Considerations

To test the implementation:
1. Test with videos containing embedded subtitles (MKV files)
2. Test with external subtitle files (.srt, .vtt) matching video filenames
3. Test language detection from filename patterns
4. Test subtitle track switching during playback
5. Test subtitle rendering with different text lengths
6. Test subtitle overlay positioning in both portrait and landscape
7. Test with videos that have no subtitles available

## Code Quality

- Follows existing codebase conventions
- Proper dependency injection with Hilt
- Coroutines for async operations
- State management with StateFlow
- Material3 design components
- Comprehensive error handling
- Well-documented code with clear function purposes