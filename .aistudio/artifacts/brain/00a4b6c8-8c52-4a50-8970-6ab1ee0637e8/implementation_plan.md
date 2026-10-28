# Host App Overhaul & Audiophile Player Polish

Elevate the Android host streamer into an ultra-fluid, minimalist, and jitter-free audiophile music player tailored for direct ESP32 + PCM5102A streaming.

### User Review & Critical Decisions

> [!IMPORTANT]
> The clarifying questions were skipped, so we are proceeding with an executive design and engineering plan focused on **minimalist elegance, strict layout alignment, silky audio transitions, and rock-solid PCM streaming**. Review the architectural decisions below:

- **Confirmed Aesthetic**: Pure OLED dark aesthetic (`#0A0A0C` background, `#14141B` card containers, `#242432` crisp hairline borders, emerald/ice-blue status accents) with zero cluttered labels or wrapping text.
- **Audio Transitions**: Frame-aligned 15ms Hann window soft fade-out and fade-in for track skips, pause, resume, and seeks across all bit depths (16-bit, 24-bit, and 32-bit) to eliminate any harsh click or DAC transient.
- **Queue & Navigation**: Full native system gesture navigation compliance without redundant back buttons, combined with smooth swipe-to-remove and instant track-switching in the playlist queue.
- **Live Link Telemetry**: Subtle real-time transmission pill on the player showing latency, packet pacing, and buffer health at a glance.

---

### 1. Overview & Core Concept

* **What It Does**: Transforms the host Android app into a dedicated Hi-Res audio streamer and player that decodes local lossless/compressed files (FLAC, WAV, OPUS, MP3, AAC) and transmits 48 kHz 16/24/32-bit PCM directly over UDP to the ESP32 + PCM5102A DAC with zero phone speaker leak, real-time Audiophile DSP, and smooth touch controls.
* **Target Audience**: Audiophiles and DIY audio builders streaming high-fidelity audio to ESP32-powered home speakers and DAC amplifiers.
* **Key Value**: Combines clean, distraction-free visual design with studio-grade audio delivery, zero DAC muting, and instant real-time DSP tuning.

---

### 2. User Experience & Visual Design

* **Visual Identity & Theme**:
  * **Background**: Deep OLED Black (`#0A0A0C`) with subtle radial gradients for depth.
  * **Surfaces**: Glass-morphic rounded cards (`#13131A`) with 1dp border (`#22222E`) and 16dp corner radius.
  * **Accents**: Neon Emerald (`#00E676`) for active streaming/DSP badges, Glacier Blue (`#80D8FF`) for track progress and volume, and warm gray (`#8E8E98`) for secondary metadata.
  * **Typography**: Clean, tabular numerics for timestamps and bitrate; strict single-line truncation with marquee for long track titles to prevent messy line wrapping.

* **Key Screen Improvements**:
  1. **Player Screen (`PlayerScreen.kt`)**:
     * **Artwork & Visualizer**: High-resolution centered album art with rounded corners and subtle drop shadow, accompanied by a dynamic 16-bar audio visualizer reacting to playback.
     * **Clean Control Cluster**: Balanced 48dp touch targets for Play/Pause, Skip Next, Skip Previous, Shuffle, and Repeat with micro-haptic ripples.
     * **Format & DSP Pill Bar**: Symmetrical row hosting the DAC Format chip (`16-bit` / `24-bit` / `32-bit • 48 kHz`) and the one-tap DSP pop-up trigger.
     * **Playlist Queue**: Redesigned list items with crisp typography, track index badges, format pills, and swipe-to-dismiss.
  2. **Home Screen (`HomeScreen.kt`)**:
     * Perfectly aligned network connection header and direct stream action cards.
     * Unified Audiophile DSP trigger sharing the exact bottom sheet modal as the player.
  3. **Settings Screen (`SettingsScreen.kt`)**:
     * Fluid continuous slider from **15 ms to 300 ms** for target buffer/latency with real-time feedback.
     * Clean categorization: *Network & Receiver*, *Audio Pipeline & DAC Format*, *Audiophile DSP Engine*, and *Diagnostics*.

---

### 3. Key Product Decisions & Trade-Offs

* **Decision 1: Frame-Aligned Hann Window Audio Ramping**
  * *Approach*: Compute Hann window curves based on exact frame boundaries (4 bytes for 16-bit, 6 bytes for 24-bit, 8 bytes for 32-bit).
  * *Why*: Previous harsh transitions were caused by unaligned byte truncations when changing tracks. Frame alignment prevents DAC clipping and static.
* **Decision 2: Gesture-Only Sheet Dismissal & Navigation**
  * *Approach*: Decouple all secondary sheets from hardcoded back buttons, relying exclusively on Android Predictive Back and downward swipe gestures.
  * *Why*: Eliminates visual clutter while making the app feel native and responsive.
* **Decision 3: Zero-Allocation Audio Visualizer**
  * *Approach*: Extract peak sample amplitudes directly from the active PCM decoding loop into a thread-safe ring buffer, rendering animated Compose Canvas bars with no memory allocations on the UI thread.
  * *Why*: Delivers visual feedback without generating garbage collection pauses that could cause audio buffer underruns.

---

### 4. Technical Architecture & Data Strategy

```
┌─────────────────────────────────────────────────────────────┐
│                      Host Application                       │
├───────────────────────────────┬─────────────────────────────┤
│        UI Layer               │        Audio Pipeline       │
│  ┌─────────────────────────┐  │  ┌────────────────────────┐ │
│  │ HomeScreen / PlayerView │  │  │ MediaCodec + Extractor │ │
│  │ (Jetpack Compose M3)    │  │  │ (FLAC / OPUS / WAV)    │ │
│  └────────────┬────────────┘  │  └───────────┬────────────┘ │
│               │               │              ▼              │
│               ▼               │  ┌────────────────────────┐ │
│  ┌─────────────────────────┐  │  │   formatPcmStream()    │ │
│  │ DspModalBottomSheet     │◄─┼──┤ (16 / 24 / 32-bit 48k) │ │
│  │ (Air/Bass/Limiter/Width)│  │  └───────────┬────────────┘ │
│  └────────────┬────────────┘  │              ▼              │
│               │               │  ┌────────────────────────┐ │
│               ▼               │  │     AudioDspEngine     │ │
│  ┌─────────────────────────┐  │  │ (Atomic Snapshots/HPF) │ │
│  │ UserPreferencesRepo     │  │  └───────────┬────────────┘ │
│  │ (15-300ms Buffer / Vol) │  │              ▼              │
│  └────────────┬────────────┘  │  ┌────────────────────────┐ │
│               │               │  │    AudioRingBuffer     │ │
│               │               │  └───────────┬────────────┘ │
└───────────────┼───────────────┴──────────────┼──────────────┘
                │                              │
                ▼                              ▼
┌─────────────────────────────────────────────────────────────┐
│        StreamingService & PacedAudioTransmitter             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ Paced UDP Transport (MTU-safe frame aligned datagrams)│  │
│  └───────────────────────────┬───────────────────────────┘  │
└──────────────────────────────┼──────────────────────────────┘
                               │ UDP Port 50005 (15-300ms)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│          ESP32-C3 Receiver + PCM5102A I2S DAC               │
└─────────────────────────────────────────────────────────────┘
```

* **Data Layer**:
  * `UserPreferencesRepository`: Single source of truth for volume, target buffer (15–300 ms), bit depth (16/24/32-bit), and DSP settings.
  * `LocalAudioPlayerManager`: Decodes audio files, applies soft Hann window curves, executes DSP, and transfers PCM directly to `AudioRingBuffer`.
  * `StreamingService`: Manages paced UDP transmission, network monitoring, and system media notifications.
