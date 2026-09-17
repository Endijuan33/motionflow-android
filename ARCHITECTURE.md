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
| `feature/home` | Home destination state and UI | Navigation graph knowledge |
| `feature/settings` | Settings destination UI | Navigation graph knowledge |
| `navigation` | Destination identities and the graph | Feature UI internals, business rules |

### Reserved packages

These directories do not exist yet because they would be empty. They are named here so that phases 2
onwards have an agreed home, and so that no phase invents a competing structure:

| Reserved package (or module) | Arrives with | Responsibility |
| --- | --- | --- |
| `core/common` | 2 | Dispatchers, qualifiers, small shared primitives |
| `core/foundation` | 2 | Process-wide services: result types, time source, capability reporting |
| `feature/player` | 2 | Playback destination: transport controls, surface hosting, playback state |
| `media` | 2 | Media3/ExoPlayer integration, source resolution, playback lifecycle |
| `metadata` | 3 | Container/codec/frame-rate and cadence detection |
| `display` | 4 | Display mode enumeration and refresh-rate requests |
| `framerate` | 5 | Frame pacing, presentation timestamps, cadence matching |
| `rendering` | 6 | OpenGL ES / Vulkan surface and shader pipeline |
| `interpolation` | 7 | Interpolator contract, frame queueing, A/V sync |
| `inference` | 8 | ONNX Runtime / NCNN model loading and execution |
| `performance` | 9 | Thermal and battery adaptation |

## 2. Layering and dependency rules

```
        feature/*            navigation
            │                    │
            └────────┬───────────┘
                     ▼
        core/designsystem
                     │
                     ▼
        core/common, core/foundation
```

1. **Features depend on `core`, never on each other.** Two features that need the same thing push it
   down into `core`, or communicate through the navigation layer.
2. **`core` never depends on a feature.** A design token must not know what a player is.
3. **Only `navigation` knows the graph.** Destinations accept lambdas (`onOpenSettings`,
   `onNavigateBack`) so screens stay previewable and testable in isolation.
4. **One direction of state.** Data flows down as immutable state, events flow up as lambdas.
5. **No Android framework types in `core/designsystem`.** Tokens are plain values; only the theme
   application touches Compose.

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

## 4. Design system

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

## 5. Build architecture

- **Versions:** every dependency and plugin version lives in `gradle/libs.versions.toml`. Nothing is
  declared inline except the SDK levels and application identity, which belong to the module.
- **Kotlin compilation:** AGP 9's built-in Kotlin support compiles Kotlin sources, so the
  `org.jetbrains.kotlin.android` plugin is not applied. Kotlin compiler options, when needed, go
  through the `kotlin { compilerOptions { } }` DSL rather than the removed `android.kotlinOptions`.
- **SDK levels:** `compileSdk` and `targetSdk` are pinned to the latest **stable** platform (36).
  Preview platforms are not adopted.
- **Reproducibility:** the Gradle wrapper is committed and pinned by version and SHA-256, Java is
  pinned by toolchain, and CI builds from a clean checkout with no developer-specific configuration.
- **CI is the authority.** The workflow lints, tests and assembles on every push; a green workflow is
  the definition of "the foundation works".

## 6. Testing strategy

| Layer | Runs | Covers |
| --- | --- | --- |
| JVM unit tests | `:app:testDebugUnitTest`, every push | Design tokens, contrast guarantees, route hygiene, pure logic |
| Android Lint | `:app:lintDebug`, every push | Correctness, API misuse, resource and manifest problems |
| Instrumented tests | not wired up yet | Added with the first phase that renders real content |

The rule for this repository: **test what does not need a device, and test what will silently break.**
Design tokens and navigation identities are hand written and easy to corrupt, so they are asserted
now. Feature behaviour is tested as it lands.

## 7. Deliberately absent

The following are missing on purpose, and each has a phase that introduces it:

- **Playback.** No Media3, no `MediaCodec`, no media permissions. Phase 2.
- **Frame interpolation, inference runtimes, native code.** Phases 7 and 8. No stub interfaces are
  defined for them, because an interface written before the problem is understood is a liability.
- **Density-specific raster icons.** `minSdk` is 26, so adaptive vector icons resolve everywhere; PNG
  mipmaps would be dead weight that drifts from the vector source.
- **A DI framework.** Nothing needs injection yet. Composition and constructor parameters are
  sufficient until the media graph arrives.
- **Formatting plugins (ktlint/Spotless).** Android Lint is the enforced quality gate; a second
  formatter is added when a shared style config is actually needed.
