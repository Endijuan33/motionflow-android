# MotionFlow Architecture

This document describes how the application is put together today, why it is put together that way,
and where the next phases attach. It is the reference for anyone adding code to this repository.

---

## 1. Shape of the project

MotionFlow is currently **one Gradle module** (`:app`) organised internally by package.

That is a deliberate choice, not a placeholder. A multi-module split pays off when it buys parallel
builds, enforced boundaries or independently reusable artifacts. At Phase 0 there is one feature
surface, one consumer, and no build-time problem to solve — so a module split would only add
ceremony and slow every build down. The package structure below is the seam along which modules are
extracted later, so extraction is a move rather than a redesign.

### Package responsibilities

| Package | Owns | Must not contain |
| --- | --- | --- |
| `com.motionflow.player` | Process entry point, single activity, application-level Compose host | Feature UI, business rules |
| `core/designsystem/theme` | Design tokens and the theme application | Feature-specific styling, layout of screens |
| `core/media/player` | The playback engine: how it is built, who owns it, how its state and failures are classified | Compose, screens, navigation |
| `core/media/metadata` | Describing a media source: container, tracks, frame rate, colour, audio | Compose, playback, navigation |
| `core/media/session` | Publishing playback to Android through a media session service | UI, feature state |
| `feature/home` | Home destination state and UI, including the media picker | Navigation graph knowledge |
| `feature/player` | Player destination: playback state, route and screen | Creating or releasing players |
| `feature/settings` | Settings destination UI | Navigation graph knowledge |
| `navigation` | Destination identities and the graph | Feature UI internals, business rules |

### Reserved packages

These directories do not exist yet because they would be empty. They are named here so that later
phases have an agreed home, and so that no phase invents a competing structure:

| Reserved package (or module) | Arrives with | Responsibility |
| --- | --- | --- |
| `core/common` | when needed | Dispatchers, qualifiers, small shared primitives |
| `core/foundation` | when needed | Process-wide services: result types, time source, capability reporting |
| `core/media/display` | Phase 3 | Display mode enumeration and refresh-rate requests |
| `core/media/framerate` | Phase 4 | Frame pacing, presentation timestamps, cadence matching |
| `rendering` | Phase 5 | OpenGL ES / Vulkan surface and shader pipeline |
| `interpolation` | Phase 6 | Interpolator contract, frame queueing, A/V sync |
| `inference` | Phase 7 | ONNX Runtime / NCNN model loading and execution |
| `performance` | Phase 8 | Thermal and battery adaptation |

## 2. Layering and dependency rules

```
        feature/*            navigation
            │                    │
            └────────┬───────────┘
                     ▼
        core/designsystem, core/media
                     │
                     ▼
        core/common, core/foundation
```

1. **Features depend on `core`, never on each other.** Two features that need the same thing push it
   down into `core`, or communicate through the navigation layer.
2. **`core` never depends on a feature.** A design token must not know what a player is, and the
   playback engine must not know that a player screen exists.
3. **Only `navigation` knows the graph.** Destinations accept lambdas (`onOpenVideo`,
   `onNavigateBack`) so screens stay previewable and testable in isolation.
4. **One direction of state.** Data flows down as immutable state, events flow up as lambdas.
5. **No Android framework types in `core/designsystem`.** Tokens are plain values; only the theme
   application touches Compose.
6. **`core/media/player` stays free of Compose.** It is the playback domain: it may be driven by a
   service, a test or a future headless component, none of which should depend on a UI toolkit.

## 3. Application structure

### Entry point and activity

- `MotionFlowApplication` is registered in the manifest and is the reserved location for
  process-wide setup (dependency graph, logging, media session configuration). It is intentionally
  empty today.
- `MainActivity` is the only activity. It calls `enableEdgeToEdge()`, then `setContent { }` with
  `MotionFlowTheme` and `MotionFlowNavHost`. Insets are consumed once, at the navigation host, so
  individual screens never repeat inset handling.

Edge-to-edge is not cosmetic here: a video surface must be able to reach the physical edges of the
display, so the application is built edge-to-edge from the first commit rather than retrofitted.

### State management

- State is held in a `ViewModel` and exposed as `StateFlow`, collected with
  `collectAsStateWithLifecycle()`.
- Every destination follows a two-layer pattern:
  - a **stateful** composable that owns the `ViewModel` and adapts it to callbacks, and
  - a **stateless** `*Content` composable that renders immutable state and is directly previewable.
- `@Immutable` state classes are used so Compose can skip recomposition when nothing changed.

`HomeViewModel` currently fronts real build metadata (`BuildConfig`). It exists as the reference
implementation of the pattern, and as the place where playback state will be published in Phase 2.

### Navigation

`MotionFlowDestination` is the single declaration of destination identities; `MotionFlowNavHost` maps
them to composables. String routes are used because they are the API that navigation-compose
guarantees today. When type-safe routes become the default, only `navigation/` changes — destinations
already receive lambdas, so features are unaffected.

The player destination carries its media source as a route argument rather than as shared mutable
state, so the destination is reproducible and survives process death. A media URI cannot travel as a
path segment unchanged — it contains `/`, `:` and `%` — so `PlayerRoute` percent-encodes it and
Navigation decodes it once when the destination is created. That round trip is asserted by unit
tests, because a source that does not survive it builds fine and fails only as unplayable media.

## 4. Playback pipeline

```
PlayerScreen ── PlayerViewModel ── MediaController
                                        │  (binder connection)
                                        ▼
                        MotionFlowMediaSessionService
                                        │  owns exactly one
                                        ▼
                        MotionFlowPlayer → PlayerFactory → ExoPlayer
                                                                │
                                                    PlayerView (SurfaceView)
```

### Ownership

Playback belongs to the process, not to a screen. `MotionFlowMediaSessionService` creates one
`MotionFlowPlayer` in `onCreate` and releases it in `onDestroy`; no other component constructs a
player. Because the session outlives the UI, a configuration change or a trip back to Home tears
down and rebuilds the screen while the engine and its decoded buffers carry on — and a second player
can never be created by navigating.

The UI reaches the engine through a `MediaController`, a `Player` implementation that proxies across
the session binder. That is why `feature/player` never imports `ExoPlayer`: the screen could not
create a player even by accident.

### Configuration

`PlayerFactory` is the only place that decides how media is decoded and rendered. It leaves Media3's
standard pipeline in place, which means `MediaCodec`-backed hardware decoders are chosen per track
with an automatic software fallback (`setEnableDecoderFallback(true)`) when a hardware decoder is
absent or fails to initialise. No codec selection is overridden and no decoder internals are touched.
Load control is tuned for local files — a smaller minimum buffer than the streaming default — and
audio focus plus "become noisy" handling are left to the player.

### Lifecycle policy

| Event | Behaviour |
| --- | --- |
| Configuration change | Screen and controller are rebuilt; the session and playback continue |
| Leaving the player screen | `onCleared` pauses playback and releases the controller connection |
| Backgrounding the app | Playback continues, controlled from the media notification |
| Task removed while idle | The service stops itself (`onTaskRemoved`) and releases the player |
| Process death | Nothing survives; the route can be restored, the grant cannot |

`MotionFlowPlayer` is deliberately thin — it creates the engine and releases it. The abstraction is
worth its two lines because it is the boundary that a custom rendering pipeline replaces: swap the
renderers factory inside `PlayerFactory`, or replace the engine wholesale, and nothing in
`core/media/session`, `feature/player` or `navigation` changes.

### State and failures

`PlayerViewModel` translates engine callbacks into an immutable `PlayerUiState` published as a
`StateFlow`. Position is polled at the seek bar's resolution rather than pushed on every frame, and
because a `StateFlow` drops unchanged values a paused player costs no recomposition. The screen is
split so that a position tick invalidates the progress readout, not the video surface.

Playback errors are classified once, in `PlayerError`, into the seven things a person can act on
(missing file, lost permission, unsupported format, decoder fault, unreadable file, unsupported
source, generic failure) plus a technical detail that goes to logcat and never to the screen.
Media paths are not logged: a URI identifies what someone is watching.

## 5. Metadata engine

```
PlayerViewModel ──┬── VideoMetadataRepository ── AndroidVideoMetadataReader
                  │        (process-scoped)          ├── ContentResolver   label, size, MIME
                  │                                  ├── MediaExtractor    container + track headers
                  │                                  ├── timestamp probe   frame rate
                  │                                  └── MediaCodecList    decoder name (query only)
                  └── TrackFormatHint.from(Tracks) ──┘   (codec, colour, bitrate from Media3)
```

### Responsibilities

The engine answers one question: **what is this file?** It produces an immutable `VideoMetadata`
describing the container, the video track, the audio track and the source document, or an explicit
`MetadataError` saying why it could not. It is deliberately usable without a player — the library
screen and the performance phases describe sources nothing is playing — and it holds no Compose
types, so it can be driven by a service, a test or a future headless component.

### Sources, and what each can prove

| Source | Provides | Cost |
| --- | --- | --- |
| `ContentResolver` | Display name, size, MIME type | One provider query per source |
| `MediaExtractor` | Container and track headers: dimensions, rotation, bitrate, channel count, sample rate, colour | Header parse; no decoding, no sample data copied |
| Timestamp probe | Frame rate and in-window variability | Up to 240 timestamps or two seconds of content, whichever comes first |
| `MediaCodecList` | Which decoder the platform would choose | A registry lookup; no codec is created |
| Media3 `Tracks` | Parsed codec string, colour, bitrate, pixel aspect ratio | Free — the player has already parsed them |

Media3's parsed formats arrive *after* playback starts, so the first description is built from the
provider and the container alone and is refined once when the player reports its tracks. The
repository treats a description read without that hint as improvable, and one read with it as final,
so the refinement happens exactly once rather than on every callback.

### Frame rate

The rate is **measured, not read**. Container headers store whole numbers far more often than
fractional ones — an MP4 holding 23.976 fps very often declares "24" — so a header value is reported
with low confidence and used only as a fallback. The primary measurement walks consecutive sample
timestamps and derives the rate from the intervals:

- Intervals are filtered against their **median** before averaging. The filter drops stream
  discontinuities (an edit, a dropped run of frames) and makes the rate robust on containers with
  coarse timestamps, where 23.976 fps arrives as alternating 41 ms and 42 ms intervals. Averaging
  those recovers 23.976 where a single interval would report 23 or 24.
- The result is a `Float`, never rounded to an integer, and compared against the named rates
  (23.976, 24, 25, 29.97, 30, 50, 59.94, 60) with a 0.01 fps tolerance — far tighter than the gap
  between neighbours, far looser than the measurement error.
- An unrecognised rate is reported as itself ("15 fps"), not forced into the nearest name.

### What the engine cannot prove

**Variable frame rate is only ever reported when it is observed.** A bounded window can show that a
source varies; it can never show that it does not. So:

- Variability found in the window → `isVariableFrameRate = true`, with the source recorded as a
  measurement.
- A uniform window → `isVariableFrameRate = null`, meaning *not determined*. It is never reported as
  `false`, because proving a constant rate requires reading the whole timing table, which is exactly
  the full-file scan this phase rules out.
- `fps` and `isVariableFrameRate` are both nullable for the same reason: no measurement is ever
  reported as zero.

The same honesty applies to every other field. Unknown dimensions are `null` and the panel shows
"Unknown"; nothing is inferred from resolution, bit depth or filename.

### What the engine does not do

It **does not change playback**. Detecting that a file holds 23.976 fps does not select a refresh
rate, pace a frame or alter the output in any way — the description is read-only, and the player
renders exactly as it did before. Acting on what was measured belongs to the refresh-rate and
frame-pacing phases, which will read this model rather than measure again.

### Caching

`VideoMetadataRepository` holds a **single** entry, owned by the application, keyed by source. It is
why re-entering the player screen, a recomposition or a retry does not re-read the file. Failures are
not cached, so retrying after the user re-grants access works. Stale reads are cancelled by the view
model: each new source replaces the collection job that publishes into the player's state.

## 6. Design system

The theme is the contract between design and code, so it is centralised from the start:

| Token set | Type | Provided through |
| --- | --- | --- |
| Colour | `MotionFlowColorTokens` (framework-independent ARGB) → `ColorScheme` | `MaterialTheme.colorScheme` |
| Type | `MotionFlowTypography` | `MaterialTheme.typography` |
| Shape | `MotionFlowShapes` | `MaterialTheme.shapes` |
| Spacing | `MotionFlowSpacing` | `MotionFlowTheme.spacing` |
| Elevation | `MotionFlowElevation` | `MotionFlowTheme.elevation` |
| Motion | `MotionFlowMotion` | `MotionFlowTheme.motion` |

Design decisions worth keeping:

- **Dark first.** A player is watched in the dark, and dark chrome keeps attention on the picture.
  The colour scheme is dark; a light scheme is a mapping over the same tokens away.
- **Colour values are plain ARGB.** `MotionFlowColorTokens` holds no Compose types, which lets the
  palette be unit tested on the JVM — including the contrast thresholds asserted in
  `MotionFlowColorTokensContrastTest`.
- **Contrast is enforced, not reviewed.** The palette test fails the build if a text/background
  pairing drops below WCAG 2.1 AA.
- **Colour roles are semantic.** `primary` (cyan) is motion and playback, `secondary` (blue) is
  timing information, `tertiary` (violet) is interpolation and processing. New UI picks a role, not
  a hex value.
- **Depth comes from tonal surfaces, not shadows.** Elevation stays low by design.
- **Motion is short.** Chrome animates around moving pictures; the token scale caps at 400 ms.

## 7. Build architecture

- **Versions:** every dependency and plugin version lives in `gradle/libs.versions.toml`. Nothing is
  declared inline except the SDK levels and application identity, which belong to the module.
- **Kotlin compilation:** AGP 9's built-in Kotlin support compiles Kotlin sources, so the
  `org.jetbrains.kotlin.android` plugin is not applied. Kotlin compiler options, when needed, go
  through the `kotlin { compilerOptions { } }` DSL rather than the removed `android.kotlinOptions`.
- **SDK levels:** `compileSdk` and `targetSdk` are pinned to the latest **stable** platform (36).
  Preview platforms are not adopted.
- **AndroidX pins are bounded by `compileSdk`.** AndroidX AAR metadata declares a `minCompileSdk`,
  and the newest `core`/`lifecycle`/`navigation`/Compose releases require 37 — a level still on the
  SDK canary channel. The catalog therefore pins the newest releases that compile against 36, and
  the constraint is documented next to those pins so the next contributor raises them as a set
  rather than one at a time.
- **Reproducibility:** the Gradle wrapper is committed and pinned by version and SHA-256, Java is
  pinned by toolchain, and CI builds from a clean checkout with no developer-specific configuration.
- **Media3 is pinned to `media3-common`/`exoplayer`/`session`/`ui` at one version.** The artifacts are
  released together and are not independently versioned in practice, so they move as a set. Only
  `media3-ui` is used for `PlayerView`; no ExoPlayer extensions (network stacks, decoders, cast) are
  declared, because local playback does not need them.
- **CI is the authority.** The workflow lints, tests and assembles on every push; a green workflow is
  the definition of "the foundation works".

## 8. Testing strategy

| Layer | Runs | Covers |
| --- | --- | --- |
| JVM unit tests | `:app:testDebugUnitTest`, every push | Design tokens, contrast guarantees, route round-tripping, player state and error mapping, frame-rate arithmetic, metadata formatting and classification, repository caching policy |
| Android Lint | `:app:lintDebug`, every push | Correctness, API misuse, resource and manifest problems |
| Instrumented tests | not wired up | Would cover surfaces, codecs and session binding — CI has no emulator |

The rule for this repository: **test what does not need a device, and test what will silently break.**
Design tokens, hand-written mappings, the media-URI route round trip and the frame-rate arithmetic
all fail quietly at runtime, so they are asserted. Playback policy is deliberately *not* covered by
fake player tests: a mock `Player` would assert that the code calls the methods it visibly calls,
while the behaviour that matters — hardware decode selection, surface lifetime, session binding — is
device-dependent and is verified on hardware instead.

The metadata engine is split so that its *decisions* are testable and its *I/O* is not: interval
analysis, rate naming, unit scaling, error classification and the repository's caching policy are
pure or fake-driven, while `MediaExtractor`'s behaviour on a real container is only exercised on a
device. That boundary is why `VideoMetadataReader` is an interface.

## 9. Deliberately absent

The following are missing on purpose, and each has a phase that introduces it:

- **Frame interpolation, inference runtimes, native code.** Phases 6 and 7. No stub interfaces are
  defined for them, because an interface written before the problem is understood is a liability.
- **Refresh-rate control and frame pacing.** Phases 3 and 4. The player is left on the platform's
  default display handling so that later measurements have a clean baseline to compare against.
- **A custom rendering pipeline.** Phase 5. `PlayerView` and Media3's default renderers are used
  deliberately: they are the correct, low-risk path for this phase and the reference behaviour the
  custom pipeline will be judged against.
- **Media library, queue, history and persisted URI grants.** Phase 9. Playback is single-file, and
  grants are not persisted across process death.
- **Fullscreen and aspect-ratio controls.** The player layout already separates the video stage from
  the chrome, so fullscreen is a rearrangement of existing pieces rather than a rewrite.
- **Density-specific raster icons.** `minSdk` is 26, so adaptive vector icons resolve everywhere; PNG
  mipmaps would be dead weight that drifts from the vector source.
- **A DI framework.** Nothing needs injection yet. Composition, constructor parameters and the
  Android view model factory are sufficient until the media graph arrives.
- **Formatting plugins (ktlint/Spotless).** Android Lint is the enforced quality gate; a second
  formatter is added when a shared style config is actually needed.
