# MotionFlow

**Adaptive Video Player with high-refresh-rate playback and future real-time frame interpolation.**

MotionFlow is an Android video player built around one idea: motion should look right. It targets
displays that can switch refresh rates, plays back content whose frame rate rarely matches the panel,
and is structured so that real-time frame interpolation can be added later without rewriting the
player around it.

---

## Vision

Most Android players treat the display as a fixed 60 Hz surface and hand video to the platform with
default settings. The result is judder when a 24 fps film meets a 60 Hz panel, stutter when a 60 fps
source meets a 120 Hz panel, and no visibility into what the hardware is actually doing.

MotionFlow aims to close that gap in three steps:

1. **Know the hardware.** Detect the display's supported refresh rates and the content's real frame
   rate and cadence.
2. **Match them.** Drive the display refresh rate and pace frames so that source cadence and panel
   cadence agree.
3. **Exceed the source.** Interpolate intermediate frames on the GPU so that low-frame-rate content
   can be presented at a high, stable frame rate.

Steps 1 and 2 are timing problems. Step 3 is a rendering and inference problem. The foundation in
this repository exists so that both can be solved inside a codebase that is already stable,
testable and shipped continuously.

## Current Status

**Phase 2 — Video Metadata Detection. Complete.**

The application plays a local video end to end and can describe it. The metadata engine reads the
container and track headers, measures the frame rate from sample timing, names the codec and the
decoder the platform would use, and reports colour, rotation, audio layout and file size — showing
"Unknown" for anything it genuinely does not know.

What exists now:

- **Phase 0** — reproducible Gradle build, dark-first Material 3 design system, Compose navigation,
  adaptive launcher icon, CI that lints, tests, assembles and publishes a debug APK.
- **Phase 1** — Media3 ExoPlayer playback: centralized player factory and ownership, media session,
  local media opening through the Storage Access Framework, player state model, player UI, error
  classification, lifecycle and resource management.
- **Phase 2** — video metadata engine: a player-independent reader, a process-scoped cache, frame
  rate measured from sample timing with fractional rates preserved, explicit unknown states
  throughout, and a metadata panel on the player surface.

Explicitly **not** implemented yet: frame interpolation, AI models, optical flow, OpenGL/Vulkan
rendering, custom GPU processing, refresh-rate forcing and FPS conversion. Metadata detection is
read-only: it **does not change playback frame rate**, refresh rate or pacing. Those are later phases.

## Technology Stack

| Area | Choice | Version |
| --- | --- | --- |
| Language | Kotlin | 2.3.21 |
| Build | Android Gradle Plugin / Gradle | 9.4.0 / 9.7.1 |
| UI | Jetpack Compose (BOM) + Material 3 | 2026.06.01 |
| Playback | AndroidX Media3 (ExoPlayer, Session, UI) | 1.11.1 |
| Architecture | AndroidX Lifecycle (ViewModel, `StateFlow`) | 2.10.0 |
| Navigation | Navigation Compose | 2.9.8 |
| Concurrency | Kotlin Coroutines | 1.11.0 |
| Compose host | Activity Compose | 1.13.0 |
| Support | AndroidX Core KTX | 1.18.0 |
| SDK levels | `minSdk` / `compileSdk` / `targetSdk` | 26 / 36 / 36 |
| JDK | Java toolchain and `jvmTarget` | 17 |

Dependency versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Nothing is
added to that catalog until the phase that uses it.

> **Why AndroidX is not on its newest release.** The newest `core`, `lifecycle`, `navigation` and
> Compose releases declare `minCompileSdk 37` in their AAR metadata. Platform 37 is published only
> on the SDK canary channel, so building against it would mean depending on a preview platform
> contrary to the stable-only rule. The pins above are the newest releases that compile against the
> latest **stable** platform (36); they move up together with `compileSdk` once platform 37 is
> stable.

**Kotlin is compiled by the Android Gradle Plugin's built-in Kotlin support.** AGP 9 compiles
Kotlin sources directly, so the `org.jetbrains.kotlin.android` plugin is intentionally absent; the
Compose compiler plugin is the only Kotlin plugin applied.

## Architecture

A single Gradle module (`:app`) organised by package, with the seams that later phases depend on
already in place:

```
app/src/main/java/com/motionflow/player/
├── MotionFlowApplication.kt      process entry point
├── MainActivity.kt               single activity, edge-to-edge, hosts the graph
├── core/
│   ├── designsystem/theme/       colour, type, shape, spacing, elevation, motion tokens
│   └── media/
│       ├── metadata/             describing a source: container, tracks, frame rate, colour, audio
│       ├── player/               player engine: factory, ownership, state and error mapping
│       └── session/              media session service
├── feature/
│   ├── home/                     home destination (state holder + picker + screen)
│   ├── player/                   player destination (state, view model, route, screen)
│   └── settings/                 settings destination (placeholder)
└── navigation/                   destinations and the navigation graph
```

Rules the code follows:

- **Unidirectional state.** Screen-level state lives in a `ViewModel` and is exposed as a
  `StateFlow`; composables read it with `collectAsStateWithLifecycle()` and never hold it.
- **Stateful/stateless split.** Each destination has a thin stateful entry point and a pure,
  previewable `*Content` composable.
- **Destinations receive lambdas, not controllers.** Feature code never sees the `NavHostController`.
- **The UI never owns the player.** Playback is owned by the session service; the screen drives it
  through a `MediaController`.
- **Tokens, not literals.** Colours, spacing, shapes and durations come from the design system.
- **Dark first, light ready.** The palette is framework-independent ARGB, so a light scheme is a
  mapping away rather than a rewrite.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full rationale, the media pipeline, the reserved
packages, and the rules for adding modules.

## Opening a local video

```
Home (Open Video)
  → Android system document picker      (Storage Access Framework)
  → document URI with a read grant
  → percent-encoded into the player route
  → Player destination                   (MediaController → MediaSessionService → ExoPlayer)
  → hardware decode, render, playback
```

The picker returns a `content://` document URI, never a filesystem path, and MotionFlow never
assumes one: Media3 opens it through its content data source, and the file's display name is read
from the document provider purely as a label. Sources that are neither `content://` nor `file://`
are refused before they reach the player.

## Metadata detection

Once a video is loaded, MotionFlow describes it. The engine is independent of the player — the
library screen and the performance phases will use it for sources nothing is playing — and it is
read-only: **detecting the frame rate does not change the frame rate**, the refresh rate or the
pacing. Acting on what it measures belongs to later phases.

### What is read

| Field | Source |
| --- | --- |
| Title, file size, MIME type | Storage provider (`ContentResolver`) |
| Duration | Container header |
| Resolution | Container header, refined by Media3's parsed format |
| Rotation | Media3's parsed format, else the container header |
| **Frame rate** | **Measured from sample timestamps** (see below) |
| Variable frame rate | Measured, and only ever reported when observed |
| Video codec | Track MIME type, named (H.264, H.265, VP9, AV1, …) |
| Codec string | Media3's parsed format (`avc1.640028`) |
| Decoder | Offered by the platform for that format; looked up, never instantiated |
| Bitrate | Media3's parsed format, else the container header |
| Pixel aspect ratio | Media3's parsed format |
| Colour space, transfer, bit depth | Media3's parsed format, else the container header |
| HDR (derived) | From the transfer characteristic |
| Audio codec, channels, sample rate, bitrate | Container header, refined by Media3 |

Every field is nullable, and anything unknown is shown as "Unknown". A missing bitrate is never
rendered as "0 Mbps", and a missing resolution never as "0 × 0".

### Frame rate, and what it can claim

The rate is measured rather than copied, because container headers store whole numbers far more
often than fractional ones — an MP4 holding 23.976 fps very often declares "24". MotionFlow walks
consecutive sample timestamps for a bounded window (up to 240 timestamps or two seconds of content),
filters the intervals against their median to drop stream discontinuities, and averages what is
left. That recovers 23.976 rather than 24, and keeps 29.97 and 59.94 distinct from 30 and 60.

**Limitations:**

- The measurement describes the sampled window, not the whole file. A file that changes cadence
  later is reported at the rate it starts with.
- **Variable frame rate can only be proven, never disproven.** A window that varies is reported as
  variable; a uniform window is reported as *not determined* rather than as constant, because
  proving a constant rate means reading the entire timing table, and scanning whole files is out of
  scope for this phase.
- Header rates are kept as a fallback and reported with low confidence, since the header itself is
  usually rounded.

### Using it later

The refresh-rate controller (Phase 3) and the frame-pacing engine (Phase 4) read this model rather
than measuring again, and the interpolation phase uses its codec and colour fields to decide what a
source can support. A single cached description per source is held by the application, so those
phases get the same answer without touching the file.

## Build Instructions

### GitHub Actions (primary)

Every push and pull request runs [`.github/workflows/android-build.yml`](.github/workflows/android-build.yml):

| Stage | Command / action |
| --- | --- |
| Checkout | `actions/checkout` |
| JDK | `actions/setup-java` (Temurin 17) |
| Android SDK | `android-actions/setup-android` (platform 36, build-tools 36.0.0) |
| Gradle + cache | `gradle/actions/setup-gradle` |
| Wrapper check | `chmod +x gradlew`, `gradle/actions/wrapper-validation` |
| Lint | `./gradlew :app:lintDebug` |
| Unit tests | `./gradlew :app:testDebugUnitTest` |
| APK | `./gradlew :app:assembleDebug` |
| Artifact | `app/build/outputs/apk/debug/*.apk` uploaded as `motionflow-debug-apk` |

Any failing stage fails the workflow; no step is allowed to continue on error.

### Locally

Prerequisites: JDK 17 and an Android SDK with platform 36 and build-tools 36.0.0.

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest     # JVM unit tests
./gradlew :app:lintDebug             # Android Lint
```

No absolute paths, IDE configuration or machine-specific setup is required; the committed wrapper
resolves Gradle itself.

## Development Roadmap

| # | Phase | Outcome |
| --- | --- | --- |
| 0 | **Project Foundation** | Reproducible build, design system, navigation, CI. **✅ complete** |
| 1 | **Core Video Playback** | Media3 playback, media session, local media flow, player UI. **✅ complete** |
| 2 | **Video Metadata Detection** | Container, codec, frame rate and colour detection. **✅ complete** |
| 3 | Display Refresh Rate Control | Read supported modes and request a matching refresh rate |
| 4 | Frame Pacing Engine | Align frame release with presentation timestamps to remove judder |
| 5 | GPU Rendering Pipeline | OpenGL ES / Vulkan render path with a native surface |
| 6 | Frame Interpolation Architecture | Pluggable interpolator contract, frame queueing, A/V sync |
| 7 | AI-Based Interpolation | RIFE-class models via ONNX Runtime or NCNN, with thermal-aware fallbacks |
| 8 | Adaptive Performance Management | Battery, thermal and load-aware quality scaling |
| 9 | Production Hardening and Release | Accessibility, profiling, signing, Play release |

[`ROADMAP.md`](ROADMAP.md) tracks scope and exit criteria per phase.

## Known Limitations

- **Frame rate is measured over a bounded window**, so a source that changes cadence later is
  reported at the rate it starts with, and a constant rate is never *proven* — only not contradicted.
  See "Metadata detection" above.
- **The reported decoder is the platform's preferred one for that format**, obtained by asking the
  codec registry. It is what Media3 will normally select, but it is not a claim about the codec
  actually instantiated for a given track at runtime.
- **Playback speed and repeat mode are the only tunables.** No aspect-ratio or fullscreen control
  yet; the layout is built so that fullscreen is a rearrangement rather than a rewrite.
- **Leaving the player screen pauses playback.** Backgrounding the application keeps it playing,
  controlled from the media notification. Keeping a video running after navigating away needs a
  "now playing" affordance on Home, which arrives with the media library.
- **Media notification visibility depends on the notification permission** on Android 13+. It is
  requested when the player opens; playback works either way.
- **No instrumented tests.** CI has no emulator, so everything device-dependent — surface handling,
  codec selection, session binding, and `MediaExtractor`'s behaviour on real containers — is
  unverified by the pipeline and must be checked on hardware.
- **Single file playback.** There is no library, queue or history; grants are not persisted across
  process death.

## Engineering Principles

- **Maintainable architecture.** A small number of well-named pieces beats a framework of
  abstractions. Structure is added when a phase needs it.
- **Hardware acceleration where appropriate.** Decode, composite and interpolate on hardware; the
  CPU is for decisions, not pixels.
- **Graceful degradation.** Every capability is queried before it is assumed. Unsupported refresh
  rates, codecs, GPU features and models fall back to a correct, slower path.
- **Robust error handling.** Media is hostile input. Failures surface as recoverable states, never
  as crashes or silent corruption.
- **Memory safety.** Frame buffers are large and numerous. Buffer ownership is explicit, pooling
  replaces allocation, and nothing outlives its surface.
- **Battery and thermal awareness.** Sustained high refresh rates and inference cost power and heat.
  The application measures both and backs off before the platform throttles it.
- **Reproducible builds.** Pinned versions, committed wrapper, CI as the authority.
- **Avoid unnecessary complexity.** No speculative abstractions, no fake implementations of future
  systems.
- **Never sacrifice stability for experimental features.** Experimental work ships behind a
  capability check and off by default.

## Branding

- **Application name:** MotionFlow
- **Application ID / namespace:** `com.motionflow.player`
- **Icon concept:** a monoline "M" drawn as one continuous ribbon with round caps and joins, filled
  with a white-to-cyan gradient that travels along the stroke. The rounded, unbroken stroke reads as
  flow; the gradient reads as a frame moving from one state to the next.

The icon ships as an adaptive icon (`mipmap-anydpi-v26`) with vector foreground and background
layers, plus a monochrome layer in `mipmap-anydpi-v33` for Android 13+ themed icons. Density-specific
raster mipmaps are intentionally absent: `minSdk` is 26, so every supported device resolves the
adaptive icon and legacy PNG fallbacks would be dead resources that drift from the vector source.
