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
| `core/media/pacing` | Classifying the relationship between the video's cadence and the display's, and its diagnostics | Android, Media3, the player, Compose |
| `core/media/refresh` | Deciding and applying display refresh-rate preferences: the policy, the coordinator, the seams | Compose, Android display APIs |
| `core/media/rendering` | Describing the rendering path: the surface in use, the ownership contract, baseline metrics | Android, Media3, the player, Compose, processing state |
| `core/media/processing` | Whether a processing stage is attached, why not when it is not, and the seam a screen asks through | Android, Media3, the player, a `Surface`, a coroutine scope |
| `core/media/processing/android` | The transport: carrying a processing request over the media session and reading the answer | Ownership of the player or the controller, decision logic |
| `core/media/refresh/android` | The only code that reads a display or sets a window attribute | Decision logic, Compose |
| `core/media/session` | Publishing playback to Android through a media session service, and the commands it declares | UI, feature state |
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
| `interpolator` | Phase 7 | The interpolator contract: given two frames and a phase, produce one intermediate frame |
| `inference` | Phase 7 | ONNX Runtime / NCNN model loading and execution |
| `performance` | Phase 8 | Thermal and battery adaptation |

**There is deliberately no `rendering` module and no GPU pipeline of this application's own.** Phase 6
established where a processing stage actually attaches: inside Media3's video renderer, through
`ExoPlayer.setVideoEffects`, with the frames travelling to the display on Media3's own surface. A
surface and shader pipeline written here would duplicate that path and own a surface the ownership
contract forbids. It is reserved for the case where a custom renderer becomes genuinely necessary, and
until then naming it would invite exactly the parallel pipeline this project avoids.

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

## 6. Refresh-rate engine

```
PlayerScreen ──┬── AndroidRefreshRateController     (window attributes)
               │   AndroidDisplayCapabilityProvider  (Display.getSupportedModes)
               │        ▲ implemented in refresh/android
               ▼        │
        RefreshRateCoordinator ── RefreshRatePolicy   (pure decision)
               ▲
               └── PlayerViewModel ── FrameRateInfo    (from the metadata engine)
```

### What each part owns

| Part | Owns | Must not |
| --- | --- | --- |
| `RefreshRatePolicy` | The decision. Pure: takes a cadence and a capability snapshot, returns a decision | Touch Android, or apply anything |
| `RefreshRateCoordinator` | When to decide, applying through the seams, remembering what was applied, restoring on detach | Know what a `Window` or a `Display` is |
| `RefreshRateController`, `DisplayCapabilityProvider` | The seams | Contain decision logic |
| `refresh/android/*` | Every Android display and window call | Contain decision logic |

The split exists so the whole decision path — including refusals, unknown capabilities and the
lifecycle rules — is testable with fakes, and so that the "keep Android-specific display code
isolated" rule is structural rather than a matter of discipline.

### The matching rule

A mode fits a cadence when its rate is an **integer multiple** of it, because that is what makes
motion even: every frame is held for the same number of refreshes. Candidates rank as exact (1:1),
whole multiple (2×, 5×, …) and, last, the slowest mode still fast enough to show every frame. Within
a tier the choice is deterministic and never "the highest number": exact takes the mode closest to
the cadence, multiples and fallbacks take the slowest. A change is only requested when it strictly
improves on the mode the display is already in, so two equally suitable modes never cause a switch.

The tolerance is 0.2% on the ratio, chosen from the cadences: the fractional families are 0.1% from
their whole siblings, a false multiple such as 120.000 Hz for 23.976 fps is 0.5% away. See
`RefreshRatePolicy` for the derivation and `README.md` for the resulting table.

### What it refuses to do

No request is made for an unknown cadence, an observed variable cadence, unknown capabilities, a
cadence no mode can present, or a display already at least as suitable as anything on offer. A header
rate is a hint: it can justify an integer match, never a non-integral guess. Nothing here changes how
many frames exist — a display refreshing five times per frame shows the same frame five times.
Interpolation is not part of this, and the diagnostics word the two quantities apart.

### Applying, and the limits of applying

`AndroidRefreshRateController` sets `WindowManager.LayoutParams.preferredRefreshRate` on the player
window: a rate, with resolution and every other window property left to the framework, which AOSP
documents as the recommended replacement for pinning a display mode. Below API 34 that attribute must
be a rate the display reports, and the policy only ever selects one from `Display.getSupportedModes()`,
so requests are always well-formed without a version branch.

It is advisory. Multi-window, OEM mode policies, a panel that only switches when idle, or a device
with one mode can all leave the display where it was — so the state keeps the requested rate and the
reported rate apart, and a refusal is recorded as a diagnostic. It never becomes a playback error and
never pauses the video.

### Recompute triggers

A decision is reconsidered only when an input changes: a cadence from the metadata engine (twice per
video — the container read, then Media3's refinement), a display change from the platform listener,
the automatic preference, or the screen attaching. There is no timer, no per-frame work and no
capability polling; recomputation is signalled through a flag, so a burst of inputs collapses into one
decision for the latest state rather than a queue of stale requests.

## 7. Frame pacing engine

```
PlayerViewModel ──┬── FrameRateInfo            (metadata engine)
                  │
                  └── RefreshRateState          (refresh engine: what the display reports)
                             │
                             ▼
                  FramePacingCoordinator ── FramePacingPolicy   (pure cadence analysis)
                             │
                             └── FramePacingController            (seam, unbound today)
```

### What each part owns

| Part | Owns | Must not |
| --- | --- | --- |
| `FramePacingPolicy` | The classification: what the two rates are to each other, and what pattern of holds that implies | Touch Android, or apply anything |
| `FramePacingCoordinator` | When to analyse, and handing a decision to a mechanism when one is bound | Know what a player or a display is |
| `FramePacingController` | The seam a future renderer implements | Contain classification |

The package imports nothing from Android, Media3 or the player: its only dependencies are the two
cadence models, `kotlin.math` and coroutines. That is what keeps the arithmetic testable, and it is
also a structural statement — this engine cannot reach into rendering even by accident.

### The classification

A mode fits when its rate is an integer multiple of the cadence, because that is what makes every
frame equally long. Everything else is uneven, and the question is how uneven:

| Relationship | Classification | What it means |
| --- | --- | --- |
| 1:1 | `NATIVE_CADENCE` | Every frame once |
| Whole multiple | `INTEGER_MULTIPLE` | Every frame n times, evenly |
| Short fraction (≤ 2 frames) | `CADENCE_MISMATCH` / `SHORT_REPEATING_PATTERN` | Holds of 3 and 2, repeating exactly (the 3:2 pattern) |
| Longer fraction (≤ 8 frames) | `CADENCE_MISMATCH` / `LONG_REPEATING_PATTERN` | A repeating unit too long to read as a pulse |
| No short fraction | `CADENCE_MISMATCH` / `UNRESOLVED_PATTERN` | The pairing drifts; 24 fps on 59.94 Hz slips a refresh every few seconds |
| Ratio below 1 | `UNSUPPORTED` | The display cannot deliver every frame |

There is no "fractionally compatible" outcome. A fractional relationship is never even, and the two
cases such an outcome would separate — 24 fps on 60 Hz and 23.976 fps on 59.94 Hz — are the same
ratio, exactly five refreshes to two frames. How regular the pattern is carried by the reason instead.

The tolerance is `RefreshRatePolicy.RATIO_TOLERANCE`, referenced rather than restated, so a pair the
refresh engine calls a whole multiple cannot be called a mismatch here. The boundary that matters is
in the tests: 24.000 fps on a 59.94 Hz display is 2.4975, which is 0.1% from five-to-two and must not
be described as the 3:2 pattern.

### Why nothing is applied

`FramePacingController` has no implementation, and that is the finding rather than an omission.

Frame release happens inside `MediaCodecVideoRenderer`, which calls `releaseOutputBuffer` itself. The
only app-facing hook, `VideoFrameMetadataListener`, receives the release time and cannot change it.
Influencing *when* a frame is presented therefore requires a custom video renderer — replacing the
rendering path this project deliberately left to Media3 — which is out of scope for this phase and
for Phase 4's stated purpose of a *foundation* for later rendering work.

The two things that can be done without a renderer are already done elsewhere: Media3's own
`VideoFrameReleaseHelper` calls `Surface.setFrameRate` on API 30+ with `FIXED_SOURCE` semantics, and
Phase 3 asks the platform for a suitable display mode. Repeating either here would be duplication, so
the engine diagnoses and stops, and every decision reports `isApplied = false`.

### Work happens only on a change

Two inputs, each fed once per event: a cadence from the metadata engine, and the refresh engine's
state. The coordinator conflates a burst into one analysis of the latest state, and identical inputs
produce an identical value that the state flow does not re-emit. There is no timer, no per-frame
work, and nothing in the UI layer participates in the analysis. A mechanism is only consulted for a
cadence that actually needs pacing — a mismatch or an unsupported pairing.

## 8. Rendering foundation

```
MediaCodec decoder  →  Media3 video renderer  →  SurfaceView  →  display
      Media3                Media3                  Media3 (inside the screen's PlayerView)

        RenderingCoordinator  ←  surface created/released, first frame, video size
                │
                └── RenderingDiagnostics  (surface + metrics: a description, nothing more)

        ProcessingCoordinator ←  surface bound/released, the controller, the two rates
                │
                └── MediaSessionProcessingController  →  session command  →  PlayerProcessingEndpoint
```

### What the foundation is, and is not

It *describes* the rendering path; it does not join it. The package imports nothing from Android,
Media3 or the player — only `kotlinx.coroutines` — which is checked mechanically, and which is the
structural reason a second player, a second renderer or a second surface cannot appear by accident.
Remove the whole package and playback is unchanged.

There is no coroutine scope either: every input is an event that arrives rarely, so a description is
published synchronously from the event that caused it. Nothing can delay a frame, and there is no
interval in which a stale description could be published.

Whether a processing stage is attached is deliberately **not** answered here. Phase 5 tried to answer
it with a surface-lifecycle seam, and Phase 6 established that was the wrong shape: the effects
pipeline has to exist before `prepare()`, so a stage is never attached by a surface appearing. That
question now has one owner, `core/media/processing`, and this package describes only what frames
arrive at.

### Ownership, as data

`RenderingOwnership` holds the contract as a list rather than only as prose, so it can be asserted:

| Resource | Owner |
| --- | --- |
| ExoPlayer | The player process |
| MediaSession | The media session service |
| PlayerView | The player screen |
| Video Surface | Media3 |
| Processing surface | Nobody — it does not exist |
| Display preference | The Phase 3 refresh-rate engine |

The property being protected is that adding a processing stage later must not move ownership of
anything that already has an owner, and must not give the application a surface of its own. Phase 6 did
not move a single row: the request path added no player, no session, no surface and no display
preference, and tests still hold the contract to that.

### What can be observed, and what cannot

| API | Gives | Limit |
| --- | --- | --- |
| `Player.Listener.onRenderedFirstFrame`, `onVideoSizeChanged`, `onSurfaceSizeChanged` | That a frame reached the screen; frame and surface dimensions | Notification only. All three are forwarded through a `MediaController`, so a session-based UI can use them — verified in `MediaSessionImpl` |
| `VideoFrameMetadataListener` | Presentation timestamp, release time, format | **Notification only.** The callback is `void`; the release time cannot be changed by returning anything |
| `AnalyticsListener` | Dropped frames, frame-processing offsets, rendered-frame timing, decoder initialisation | ExoPlayer-level, **not forwarded through a session**, so consuming them needs a custom session command |
| `ExoPlayer.setVideoEffects` | Attaching a processing stage | Unreachable from a screen, and unusable without the effects module — see section 9 |

So frame timings can be observed and frame release cannot be controlled. The metrics model reflects
exactly that split rather than listing fields it cannot fill.

### Why the foundation owns no scope, no context and no surface

It reads no platform fact at all any more: the surface type and the metrics arrive as events from the
things that observe them, and it never holds a `Window`, a `Surface` or an `Activity`. Only an enum and
four numbers cross the boundary, and the GPU is deliberately not probed — whether a stage can run is
decided by Media3's renderer, and a second opinion computed elsewhere could only disagree with the
renderer that has to do the work.

## 9. Frame processing

### Where a stage attaches, and where it does not

A processing stage goes *inside* Media3's video renderer, not beside it. `ExoPlayer.setVideoEffects`
supplies effects to the renderer's own `VideoFrameProcessor`, and `Effect` is a marker interface in
`media3-common`. There is no custom `RenderersFactory`, no `MediaCodec` handling, no EGL context and no
surface owned by this application — and after Phase 6 there is no seam of ours for a stage to sit in
either, because a second one could only disagree with the renderer doing the work.

### The two facts that keep the seam empty

Both were verified against Media3 1.11.1's source, and both are stronger than "it would cost
something":

1. **The API cannot be called from this build at all.** `ExoPlayerImpl.setVideoEffects` opens with
   `Class.forName("androidx.media3.effect.SingleInputVideoGraph$Factory")` and throws
   `IllegalStateException("Could not find required lib-effect dependencies.")` when that lookup fails.
   The guard runs on **every** call, so `setVideoEffects(emptyList())` throws too: there is no
   defensive "clear the effects" call available either. Using the API means linking
   `androidx.media3:media3-effect`, a graphics module whose shaders build the frame-copy pipeline.
2. **The pipeline is armed by the first call, not by a non-empty list.** The method's contract requires
   a call before `prepare()`; `MediaCodecVideoRenderer.onEnabled` builds the video sink whenever its
   effects field is non-null, and `onReset()` clears the flag so a *later* enable can build it then.
   Attaching on request therefore cannot be the call that creates the pipeline, and calling it early to
   "have it ready" would put every session — including sessions where nobody asks for processing — on a
   pipeline that copies every frame.

Neither can be worked around without violating the phase's own constraints, so the endpoint refuses and
names the reason. `EFFECTS_MODULE_ABSENT` is the honest answer to "why is processing not active", and
it is the first thing Phase 7 changes.

### The request path

`ProcessingSessionContract` defines two custom commands, one per request, and `ProcessingSessionCallback`
declares them to a trusted controller and answers them. That route exists because the API is on
`ExoPlayer`, which a screen cannot reach: `Player` declares no effects method, so a `MediaController`
has no way to ask. The command travels

```
player screen → view model → MediaSessionProcessingController → MediaSession.sendCustomCommand
              → ProcessingSessionCallback (service, application thread) → MotionFlowPlayer
              → PlayerProcessingEndpoint
```

and comes back as a `SessionResult` carrying an outcome and a reason.

Three details are worth keeping:

- **Declaration is the gate.** `ConnectionResult.DEFAULT_SESSION_COMMANDS` holds the predefined session
  commands and no custom command, so a controller that is not offered this one is refused before any
  callback runs. Only a trusted controller is offered it; the system's media controls keep the read-only
  set they are entitled to, and this application never gains a way to change the rendering path from
  outside.
- **The service answers, because the service owns the player.** `onCustomCommand` runs on the
  application thread, which is the thread the `ExoPlayer` requires, so the request reaches the engine
  without any thread hand-off to get wrong. The callback reads the engine through a lambda rather than
  capturing it, so it can never keep a released player alive.
- **The client refuses early where it can.** With no surface there is nothing to attach to, and a
  command the session does not offer cannot be answered — both are recorded as structured answers
  rather than sent, so no caller waits for a reply that will not come, and no request is retried.

### The state model

`ProcessingMode` has five values, and only one of them means a stage is in the path:

| Mode | Meaning | Reachable how |
| --- | --- | --- |
| `NATIVE` | Nothing reported yet; the path is Media3's own | The initial value |
| `PROCESSING_UNAVAILABLE` | No surface, so nothing to render through | A surface release |
| `PROCESSING_INACTIVE` | A surface is bound, a stage could attach, none does | A surface binding |
| `PROCESSING_ACTIVE` | A stage is attached | Only from a player's report |
| `PROCESSING_FAILED` | The last request could not be honoured | A refusal or an unreachable request |

`ProcessingReason` carries the specific cause — `NO_SURFACE`, `EFFECTS_MODULE_ABSENT`,
`NO_STAGE_IMPLEMENTED`, `REQUEST_REFUSED`, `COMMAND_UNAVAILABLE`, `TRANSPORT_FAILED` — so an inactive
state is never a shrug.

The invariant the tests hold is that `effectAttached` follows a **report** and never a request: no
number of enable requests, no phrasing, and no failure can produce a stage that no player confirmed.
The counters count attachments a player confirmed, and the model has no field for an output frame rate.

### What it owns, and what it must not

| Thing | Owns? |
| --- | --- |
| An `ExoPlayer` | No. It borrows the session connection the screen already has |
| A `Surface` | No. The ownership contract's `PROCESSING_SURFACE` row still says `NOBODY` |
| A rendering pipeline | No. Nothing is attached, and no graphics dependency is linked |
| A coroutine scope | `ProcessingCoordinator` has none; `MediaSessionProcessingController` suspends on its caller's scope, confined to the application thread |
| An `Activity`, `Window`, `View` or `Context` | No. Plain values cross every boundary |

The processing package in `main` imports only its own package and `kotlinx.coroutines`; the Media3
types live in `core/media/session` (the contract and the callback) and
`core/media/processing/android` (the transport). Both are enforced by the pre-push check.

## 10. Design system

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

## 11. Performance characterization

### Two baselines, one engine

```
        Native:           MediaCodec -> Media3 video renderer -> SurfaceView -> display
        Effect pipeline:  MediaCodec -> Media3 video renderer (frame processor armed) -> SurfaceView
```

The engine is built *for* a pipeline, because Media3 requires the effects pipeline to exist before
`prepare()`. `PlayerFactory` therefore takes a `PlaybackConfiguration` and arms
`ProcessingBaselines.effectsFor(mode)` -- the only place in the project that names an effect -- before it
returns the engine, which is necessarily before anything can prepare it. `null` from that function means
*do not call* `setVideoEffects` at all: an empty list would still make `MediaCodecVideoRenderer` build its
frame processor, which would put the control condition on the very pipeline it is the control for.

The effect itself is Media3's `AlphaScale(1f)`: documented as "no change is applied", reported as a no-op
by `isNoOp`, identity matrices in its shader, and the same `configure()` size. `isNoOp` is never consulted
by the playback path -- verified in `PlaybackVideoGraphWrapper` and `DefaultVideoFrameProcessor` -- so the
pipeline genuinely runs, which is the point of measuring it.

Changing the pipeline is an explicit restart rather than a switch: the session service observes the
application's requested configuration, releases the session and the engine, and stops itself, so the next
engine is built for the new pipeline. Rebuilding in place would mean a second place that creates a player,
and "the engine is created in `onCreate` and nowhere else" is worth more than the convenience.

### Where the numbers come from

| Layer | Role |
| --- | --- |
| `FramePerformanceAccumulator` | All the arithmetic: deltas, the thermal peak, what was not measured. Pure, fed by events, no clock |
| `PerformanceRecorder` | The adapter: one Media3 callback -> one accumulator call. Holds the counters, makes no decisions |
| `PerformanceSessionCoordinator` | The session's lifecycle: one at a time, a controlled window, refused when nothing is loaded |
| `PerformanceSessionContract` | The wire: three commands, one flat answer, and absent fields that stay absent |
| `PerformanceCommandHandler` | Answers START, STOP and READ where the player lives, and finalizes a session — exactly once — when its window has elapsed or a stop arrives |

### How a session completes and is persisted

A session ends the first moment either its window has elapsed or the client stops it. Every command
routes through a single finalization point: `stop()` finishes the recorder only while the session is
still running, and `start()`/`read()` finalize an expired session before doing their own work. A stop
that arrives after the window has already been finalized reports the completed run rather than a refusal,
so the measurement cannot be lost to the order an expiry and a stop happened to fire in.

The client persists before it publishes: a completed run is written to the store *before* the UI reports
the measurement as finished, so a reader who sees "complete" and opens the export always finds the run. A
refused or unreachable result persists nothing. This ordering — and the single finalization point —
replaced a Phase 8 defect in which an expired window was finalized twice: the second finalization found
nothing running, answered with an empty `NOT_RUNNING` refusal, and the client stored no run at all, so a
completed 60-second session on real hardware exported as "no runs recorded".

The handler's collaborators are the `MeasurementRecorder` and `MeasurementProbe` seams, so this
completion path is exercised on the JVM against a controllable clock; the production recorder and probe
implement them unchanged.

Counters are read at the two ends of a session rather than accumulated per event, so the overhead does not
scale with frame count. Because the renderer's counter object is held between those reads, there is no
per-drop callback to accumulate and no chance of counting the same drop twice.

### What a null means, and what it does not

A null field is *not measured*; a zero is a measurement of zero. The two are kept apart end to end --
through the accumulator, the wire codec (which checks for a key rather than reading a default) and the
panel, which prints "not measured". Metrics no Android version publishes at all are a third category: a
property of the platform, named in `PerformanceMeasurementSupport`, and kept out of the per-session list so
a device is never blamed for a gap in Android.

### First-frame latency, measured against the session

Media3's `onRenderedFirstFrame` reports `renderTimeMs` as a `SystemClock.elapsedRealtime()` *timestamp*,
not a duration. First-frame latency is therefore defined as that timestamp minus the measurement
session's start, on the same clock — a duration relative to the window. A frame that arrived before the
session began is reported as *not measured* rather than as a negative, a zero, or a value carried over
from a previous window. `System.nanoTime()` and `elapsedRealtime()` are never mixed: both are monotonic
and have unrelated epochs, so subtracting one from the other is a confident nonsense.

### The pipeline the panel reports is the one the player was built with

The diagnostics row describes the *running* player, not the Home selection. The service answers a read
with the configuration it constructed the engine from, so `Playback pipeline: Effect Pipeline` means the
identity effect is in the renderer, not that someone asked for it. Changing the selection rebuilds the
engine in place — the old session and engine are released first, one builder serves both `onCreate` and
the configuration observer — so the requested pipeline is always the pipeline that is running, and this
does not depend on the service being torn down by the platform.

### Measurement hygiene

No per-frame logging, no disk, no network, no bitmaps, no screen capture, no polling. A snapshot is
published when a session starts, when a command asks for it, and when a session ends. Thermal status
arrives through a listener registered on the playback thread, so a device is asked once per change rather
than sixty times a minute. The one timer in the feature is the client's single wait for the window it
chose, and it is not a poll.

## 12. Hardware evidence

### What the characterization is made of

| Layer | Role |
| --- | --- |
| `PerformanceRun` + `RunCondition` | One measurement with everything held constant around it: the pipeline, the video, the display, the window |
| `PerformanceIntegrity` | The rules that decide whether a run may be compared with another one, and whether it claimed something the platform cannot provide |
| `PerformanceSeriesStatistics` | Mean, minimum, maximum and range over repeat runs of one condition, over the metrics each run actually measured |
| `PerformanceOverheadPolicy` | Absolute differences always, ratios only against a non-zero baseline |
| `PerformanceFeasibilityPolicy` | The evidence gate, and the statement categories it files its reasoning under |
| `PerformanceReport` | The deterministic local text export |

Nothing in this package reaches for another engine: it does not read a display, classify a cadence or
decode anything. The engines that own those facts are quoted into a record by the layer that has them —
the view model — which is what keeps the performance system from being able to reinterpret cadence, and is
enforced mechanically by the package-purity check.

### The boundary between observation and interpretation

The gate files every statement under one of five categories, and the categories are the point:

- **Observed** — a value a device reported.
- **Calculated** — arithmetic over observed values, and nothing else.
- **Unknown** — a platform that exposes no reading for a metric. Named, never estimated.
- **Not tested** — something this phase did not exercise. With no device reachable, this is most of the
  catalogue, and it says so.
- **Hypothesis** — what remains open, phrased as a question rather than a finding.

### Why a run is kept when it is unusable

A short run, a failed run and an empty run are all recorded and labelled. Discarding them would leave a
series that looks complete, and the missing third run would be indistinguishable from a run that never
happened — which is the difference between an experiment and a story. Statistics count only the usable
runs; the record keeps all of them.

## 13. Build architecture

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
- **Media3 is pinned to `media3-common`/`exoplayer`/`session`/`ui`/`effect` at one version.** The artifacts are
  released together and are not independently versioned in practice, so they move as a set. Only
  `media3-ui` is used for `PlayerView`; no ExoPlayer extensions (network stacks, decoders, cast) are
  declared, because local playback does not need them.
- **CI is the authority.** The workflow lints, tests and assembles on every push; a green workflow is
  the definition of "the foundation works".

## 14. Testing strategy

| Layer | Runs | Covers |
| --- | --- | --- |
| JVM unit tests | `:app:testDebugUnitTest`, every push | Design tokens, contrast guarantees, route round-tripping, player state and error mapping, frame-rate arithmetic, metadata formatting and classification, repository caching, the refresh-rate matching policy and its coordinator, the cadence classification and its coordinator, the rendering foundation's state machine and ownership contract, and the processing state machine, endpoint and wire contract |
| Android Lint | `:app:lintDebug`, every push | Correctness, API misuse, resource and manifest problems |
| Instrumented tests | not wired up | Would cover surfaces, codecs, session binding, whether a display actually changes mode, and whether a session command reaches a real player — CI has no emulator |

The rule for this repository: **test what does not need a device, and test what will silently break.**
Design tokens, hand-written mappings, the media-URI route round trip, the frame-rate arithmetic and
the matching policy all fail quietly at runtime, so they are asserted. Playback policy is deliberately
*not* covered by fake player tests: a mock `Player` would assert that the code calls the methods it
visibly calls, while the behaviour that matters — hardware decode selection, surface lifetime, session
binding, and whether a refresh-rate request is honoured — is device-dependent and is verified on
hardware instead.

Both media engines are split so that their *decisions* are testable and their *I/O* is not. Metadata:
interval analysis, rate naming, unit scaling, error classification and the repository's caching policy
are pure or fake-driven. Refresh rate: the matching policy is a pure function, and the coordinator is
driven through fake controller and capability providers, so refusals, unknown capabilities and the
lifecycle rules are all covered without a display. That is what the two interfaces are for. Pacing
needs no fakes for its arithmetic at all — it has no I/O — and its coordinator is driven by feeding it
cadences and refresh states; a bound controller is faked to prove the seam.

Processing follows the same pattern with one addition: its *transport* is an interface
(`ProcessingController`), so the coordinator can be driven through a fake that refuses, attaches,
answers nothing, or throws — which is how the failure ladder is covered without a session. The
transport's own implementation is not unit tested, because a `MediaController` cannot exist off-device;
what is tested instead is the wire codec (`ProcessingSessionContract.decode`) and the rule that makes
the client refuse locally, so the only untested lines are the three that read a `Bundle`. The two
cross-engine tests exist for a different reason: they assert that a processing request cannot move the
display decision or the cadence classification, which is a property of the boundary rather than of
either engine.

The rendering foundation has no I/O left to fake: the surface type and the metrics are events handed to
it, so its state machine is tested directly, and the GPU is not probed anywhere.

## 15. Deliberately absent

The following are missing on purpose, and each has a phase that introduces it:

- **Frame interpolation, inference runtimes, native code.** Phase 7. No stub interfaces are defined
  for them, because an interface written before the problem is understood is a liability. Nothing in
  the pacing engine implies they exist: it decides when existing frames are shown, not how many frames
  there are.
- **Frame release control.** Pacing classifies cadences but cannot change presentation timing, because
  release happens inside Media3's video renderer and the app-facing hook only reports it. The
  classification and the `FramePacingController` seam are the foundation for whichever phase owns a
  renderer; until then a 3:2 mismatch is identified, explained and left alone.
- **An attached processing stage.** Phase 6 built and verified the route to one — the request path, the
  endpoint, the state model — and attaches nothing, because attaching is not a small step: Media3's
  `setVideoEffects` refuses to run without `androidx.media3:media3-effect` on the classpath, and the
  effects pipeline has to be armed before `prepare()`, which puts every session on a frame-copying
  pipeline whether or not anyone asks for processing. The refusal names both facts at runtime.
- **A custom rendering pipeline, and any graphics dependency.** `PlayerView` and Media3's default
  renderers are used deliberately: they are the correct, low-risk path, and the reference behaviour any
  future pipeline is judged against. Phase 5 established that a processing stage attaches *without*
  replacing them, so a custom renderer is less likely to be needed at all — and Phase 6 established
  that a seam beside the renderer would only be able to disagree with it.
- **Calling `Surface.setFrameRate` ourselves.** Not needed, and deliberately not done: Media3's
  `VideoFrameReleaseHelper` already calls it on API 30+, with `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE`
  for a steady source and a change-frame-rate-only-if-seamless strategy. A second caller would
  duplicate it.
- **A settings store, and with it a persisted refresh-rate preference.** The player surface has an
  Auto/System-default toggle, but it lives for the session. Persisting one setting would mean
  inventing a settings architecture here; that arrives with the phase that needs it.
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
