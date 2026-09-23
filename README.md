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

**Phase 8 — Hardware Validation & Processing Feasibility. Complete — with hardware validation NOT TESTED.**

The application plays a local video end to end, describes it, asks the display to refresh at a rate
that suits the video's cadence, reports how well those rates line up, describes the rendering path, says
whether a processing stage is attached, and now **measures** that path: a controlled 10, 30 or 60 second
session, taken on either pipeline, reported as counts, durations and deltas.

**No interpolation exists**, and the effect-pipeline baseline does not implement any. It puts Media3's own
frame processor in the path with an identity effect that changes nothing about the picture, so what is
measured is the *cost of the pipeline*, not a quality improvement. Nothing in the application reports a
generated frame rate or claims a speed-up.

Phase 8 built the tooling a hardware characterization needs and **did not take any measurement**: the
environment this phase ran in has no device attached, so every number below is absent rather than
estimated. See "Hardware characterization" — the procedure is ready to run and the evidence gate is
deliberately unresolved.

What exists now:

- **Phase 0** — reproducible Gradle build, dark-first Material 3 design system, Compose navigation,
  adaptive launcher icon, CI that lints, tests, assembles and publishes a debug APK.
- **Phase 1** — Media3 ExoPlayer playback: centralized player factory and ownership, media session,
  local media opening through the Storage Access Framework, player state model, player UI, error
  classification, lifecycle and resource management.
- **Phase 2** — video metadata engine: a player-independent reader, a process-scoped cache, frame
  rate measured from sample timing with fractional rates preserved, explicit unknown states
  throughout, and a metadata panel on the player surface.
- **Phase 3** — adaptive display refresh rate: a pure matching policy over the display's reported
  modes, a window-level request through the platform's refresh-rate API, diagnostics on the player
  surface, and lifecycle handling that restores the display when the screen goes away.
- **Phase 4** — cadence-aware frame pacing: a pure classifier for the relationship between the two
  rates, the pattern of frame holds it implies, diagnostics stating whether pacing was actually
  applied, and a seam for the phase that owns a renderer.
- **Phase 5** — rendering foundation: the path documented and described, the ownership contract
  encoded and asserted, surface-type reporting read from the view in use, and a first-frame timing
  baseline.
- **Phase 6** — frame processing architecture: the official Media3 effect API verified against its
  own source, a processing state model with one owner, a session-command request path from the screen
  to the engine, and diagnostics that name the exact reason no stage is attached.

Explicitly **not** implemented: frame interpolation, AI-generated frames, optical flow, motion
estimation, OpenGL or Vulkan rendering, custom shaders, decoder replacement and frame synthesis. **A
display running at 60 Hz is not a video containing 60 frames**: matching a refresh rate changes how
often the panel redraws, not how many frames exist. Nothing in MotionFlow claims that a 24 fps video
becomes a 60 fps one, that pacing removes judder, or that any GPU processing is running.

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
│       ├── pacing/               cadence analysis: how the display's rate relates to the video's
│       ├── player/               player engine: factory, ownership, state and error mapping
│       ├── refresh/              display refresh-rate matching, and its Android implementation
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

## Display refresh rate

**Display refresh rate and video frame rate are different quantities.** The video's frame rate is how
many distinct images a second the file contains; the display's refresh rate is how often the panel
redraws. Showing 24 fps content on a 120 Hz panel does not produce 120 frames a second — it shows
each of the 24 frames five times. MotionFlow changes the first to suit the second, and never claims
otherwise. **No frame interpolation is implemented**: no frames are synthesised, no AI model runs,
and there is no OpenGL, Vulkan or custom GPU path.

### What it does

Given the measured cadence and the modes the display reports, the engine picks a target mode and asks
the platform for it, then shows what happened on the player surface.

| Cadence | Display modes | Choice | Why |
| --- | --- | --- | --- |
| 24 fps | 60, 24 | 24 Hz | Shows each frame once |
| 24 fps | 60, 120 | 120 Hz | 120 = 5 × 24: each frame shown 5 times, evenly |
| 24 fps | 50, 60 | 50 Hz | No whole multiple; the slowest rate that still shows every frame |
| 23.976 fps | 60, 24 | 24 Hz | Same family — 24 Hz panels exist precisely because of 23.976 content |
| 30 fps | 60, 120 | 60 Hz | Both are whole multiples; the slower one costs less |
| 24 fps | 24, 120 | 24 Hz | 1:1 beats showing each frame five times |
| 60 fps | 50 | *none* | A 50 Hz panel cannot show 60 fps without dropping frames |
| unknown | anything | *none* | Nothing to match |

The rule is that a mode counts as a match when its rate is an **integer multiple** of the cadence,
because that is what makes motion even: each frame is held for the same number of refreshes. A mode
at 2.5 × the cadence (24 fps on a 60 Hz panel, the familiar 2:3 pulldown) is judder, however high the
number looks, so it is never chosen merely for being fast.

**Tolerance.** A mode counts as a multiple when `modeRate / cadence` is within **0.2%** of a whole
number. That figure comes from the numbers themselves: the fractional cadence families sit exactly
0.1% from their whole siblings (23.976 vs 24, 29.97 vs 30, 59.94 vs 60), so same-family modes are
always recognised, while a false multiple is much further away — 120.000 Hz is 0.5% from five times
23.976, and 120.000 genuinely cannot present 23.976 evenly. In absolute terms the tolerance is
0.048 Hz at 24 fps and 0.12 Hz at 60 fps: wider than measurement noise, far narrower than the gap
between real cadences.

**When nothing is requested.** No request is made when the frame rate is unknown, when the source was
observed varying its cadence (switching would chase a moving target), when the display will not
report its modes, when nothing on offer can show every frame, or when the display is already at
least as suitable as anything available. A container header rate — which rounds 23.976 to 24 — is
treated as a hint: it may justify an integer match but never a non-integral guess.

### How it is applied, and what that cannot promise

The request goes through `WindowManager.LayoutParams.preferredRefreshRate` on the player window,
which names a rate and leaves every other window property — resolution included — to the framework.
AOSP documents this as the recommended replacement for pinning a display mode, and as equivalent to
`Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT)`; below API 34 the platform requires it
to be one of the rates the display reports, which is exactly what the policy selects.

It is a request, not a command. **The platform may ignore it**, and MotionFlow cannot detect or
enforce otherwise: in multi-window or picture-in-picture the window does not own the display; some
devices switch modes only when the panel is idle; some OEM implementations resolve the request to
whatever mode they prefer; and a device with a single fixed mode has nothing to switch to. That is
why the diagnostics report the rate the display *reports*, separately from the rate that was asked
for, and why the engine never pauses playback or reports a playback error when a request is refused.

**No particular refresh rate is assumed to exist.** There is no code path that asks for 24, 90 or
120 Hz as such: every candidate comes from `Display.getSupportedModes()` on the device in hand, and a
device that reports only 60 Hz simply keeps it.

Supported API range: **26–36**, using only public API that exists from API 23 (`Display.getMode`,
`Display.getSupportedModes`, `DisplayManager.registerDisplayListener`) for the discovery side. There
are no version branches, no reflection and no hidden APIs. Exact physical mode switching is therefore
best-effort by design on every Android version.

## Frame pacing

**Frame pacing is not frame interpolation.** Pacing can only decide *when* frames that already exist
are presented; interpolation invents frames that were never decoded. MotionFlow does not interpolate:
there is no AI model, no optical flow, no motion estimation, no custom shader and no OpenGL or Vulkan
renderer in this project. When the diagnostics say a display is running at 120 Hz for 24 fps content,
the video still contains twenty-four frames a second — each shown five times.

### What it analyses

The relationship between the display's rate and the video's rate, and whether every frame can be held
for the same number of refreshes:

| Video | Display | Ratio | Classification |
| --- | --- | --- | --- |
| 24 fps | 24 Hz | 1.0 | 1:1 — every frame once |
| 24 fps | 48 Hz | 2.0 | Integer multiple — every frame twice |
| 30 fps | 60 Hz | 2.0 | Integer multiple |
| 60 fps | 120 Hz | 2.0 | Integer multiple |
| 29.97 fps | 59.94 Hz | 2.0 | Integer multiple |
| 24 fps | 60 Hz | 2.5 | 3:2 pattern — uneven: 3 refreshes, then 2 |
| 23.976 fps | 59.94 Hz | 2.5 | The same 3:2 pattern |
| 25 fps | 60 Hz | 2.4 | A longer uneven pattern, repeating every 5 frames |
| 24 fps | 59.94 Hz | 2.4975 | No short pattern: the display drifts against the video |
| 60 fps | 50 Hz | 0.83 | Display slower than the source |
| unknown | anything | — | Not analysed |

**Integer multiples are what you want**, because every frame is then held equally long. A fractional
relationship is never even: 24 fps on a 60 Hz display alternates holds of 3 and 2 refreshes, which is
the judder this phase exists to identify.

**Tolerance: 0.2% on the ratio**, the same figure the refresh engine uses, so the two cannot disagree
about a pair. That is a display-rate margin of 0.048 Hz at 24 fps — wide enough for measurement
noise, narrow enough that 59.94 Hz is not mistaken for a multiple of 24.000 fps (0.06 Hz away, and
genuinely drifting).

**One coincidence worth stating plainly.** 24 fps on 60 Hz and 23.976 fps on 59.94 Hz are *the same
ratio* — exactly five refreshes to two frames. A classifier that is a function of the two rates must
give them the same answer, so both are reported as the 3:2 pattern. Only a policy that penalised
whole-rate pairings could separate them, and that would be a judgement about the rates rather than a
fact about the cadence.

### What it does about it: nothing, on purpose

**Pacing is diagnostic-only, and the UI says so.** That is a deliberate limitation, not unfinished
work:

- Changing *when* a frame is presented means controlling frame release inside Media3's video renderer.
  `MediaCodecVideoRenderer` releases output buffers itself, and the only app-facing hook,
  `VideoFrameMetadataListener`, is *told* the release time rather than being able to change it.
  Intervening means supplying a custom renderer, which replaces the rendering path this project has
  deliberately left to Media3.
- What can be done without one is already being done: **Media3** calls `Surface.setFrameRate(...)` on
  API 30+ from its own `VideoFrameReleaseHelper`, with `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` for a
  steady source, and **Phase 3** asks the platform for a display mode that suits the cadence. A third
  engine doing either would duplicate it.

So the engine classifies, explains and stops. Every decision reports `isApplied = false` through the
`FramePacingController` seam, which exists for the phase that owns a renderer. **Judder is not
removed**: a 3:2 mismatch is identified, described, and left as it is, because nothing in the current
architecture can present it more evenly.

Device-specific validation remains necessary for all of it. Whether a display honours a mode request,
whether it switches seamlessly, and whether the resulting motion looks better are hardware
questions, and CI has no display.

## Rendering path

```
MediaCodec decoder  →  Media3 video renderer  →  SurfaceView  →  display
   (Media3)              (Media3)                 (Media3, inside the screen's PlayerView)
```

**This application owns none of that.** It composes a `PlayerView`, hands it a `Player`, and Media3
decides everything about how frames reach the screen. The foundation in `core/media/rendering`
*describes* that path and never joins it — it imports nothing from Android, Media3 or the player, and
if it were deleted, playback would be byte-for-byte the same.

### Ownership contract

Expressed as data (`RenderingOwnership`) so it can be asserted by tests rather than only promised:

| Resource | Owner |
| --- | --- |
| ExoPlayer | The player process (one, via the session service) |
| MediaSession | The media session service |
| PlayerView | The player screen |
| Video Surface | Media3 |
| Processing surface | Nobody — it does not exist |
| Display preference | The Phase 3 refresh-rate engine |

The point is that attaching a processing stage later must not move ownership of anything that already
has an owner, and must not give this application a surface of its own. Two tests check exactly that.

### SurfaceView, and why it is not a TextureView

The surface type is *read from the view Media3 actually built* (`getVideoSurfaceView()`), not assumed.

| | SurfaceView (in use) | TextureView |
| --- | --- | --- |
| Composition | Composited by the system, separate layer | Composited as part of the view hierarchy |
| Power and latency | Lower: no extra copy into the view hierarchy | Higher: an extra GPU copy per frame |
| HDR and protected content | Supported, including secure surfaces | Secure surfaces and some HDR paths unsupported |
| Transforms and animation | Cannot be transformed or alpha-blended by the view system | Can, which is its only real advantage |
| API range | 1+ | 14+ |

**TextureView is not used.** Its advantage is being transformable like any other view — useful for
video in a scrolling list, irrelevant here — and it costs a per-frame copy plus secure-content
support. There is no measured benefit to switching, and this project does not change the surface type
on a hunch; if a future phase finds a reason, the change is one line in `VideoStage` and is reported
automatically, because the type is read rather than assumed.

### Frame metadata: what can be observed, and what cannot

| API | What it gives | Limitation |
| --- | --- | --- |
| `Player.Listener.onRenderedFirstFrame()` | That a frame reached the screen | Notification only, once per item |
| `Player.Listener.onVideoSizeChanged` / `onSurfaceSizeChanged` | Frame and surface dimensions | Notification only |
| `VideoFrameMetadataListener` | Presentation timestamp, **release time**, format for a frame about to be rendered | **Notification only.** The callback is `void`; the release time is handed to the application and cannot be changed by returning anything |
| `AnalyticsListener` | Dropped frames, frame-processing offsets, rendered-frame timing, decoder initialisation | ExoPlayer-level; **not forwarded to a `MediaController`**, so a session-based UI cannot see them without a custom session command |
| `ExoPlayer.setVideoEffects` | Attaching a processing stage | Activates a GPU pipeline — see below |

So: frame *timings* can be observed, but frame *release* cannot be controlled. Any claim otherwise
would be wrong, and no reflection or hidden API is used anywhere in this project.

### Where a processing stage attaches

Inside Media3, not beside it. Media3's own video renderer hosts a `VideoFrameProcessor`, driven by
`ExoPlayer.setVideoEffects(List<Effect>)`, so a stage needs no custom `RenderersFactory`, no custom
`MediaCodec` handling and no second surface. `Effect` is a marker interface in `media3-common`, so
even naming one costs no graphics dependency.

What that API does *not* allow is the subject of the next section. The short version: this build
attaches nothing, and could not attach anything, so `core/media/rendering` describes the path and the
processing report says why it is empty.

### Baseline metrics

| Metric | Source | Status |
| --- | --- | --- |
| First-frame latency | Measured across `prepare()` to `onRenderedFirstFrame` | Available |
| Surface attach/detach counts | The player screen's view lifecycle | Available |
| Video size | `Player.Listener.onVideoSizeChanged` | Available |
| Rendered and dropped frame counts, frame offsets | `AnalyticsListener` | **Not available**: not forwarded through a session |
| Per-frame timestamp intervals | `VideoFrameMetadataListener` | **Not available**: ExoPlayer-level only |

This is a baseline, not telemetry: no polling, no per-frame work, no logging of frames, and nothing
privacy-relevant — no file path, no location, no identifier is recorded. Numbers only change when an
event arrives, and an identical measurement is not re-published.

### Fallback

Playback never depends on any of this. If the surface is never reported, or if the entire foundation
were removed, Media3 plays the video. Each of those paths is covered by a test.

## Frame processing

```
player screen  →  view model  →  session command  →  session service  →  MotionFlowPlayer
                                                                              │
                                                              PlayerProcessingEndpoint
                                                          (attaches nothing, and says why)
```

**No processing stage is attached in this version**, and there is no graphics dependency in the build.
What exists is the route a stage would take and an honest account of why the route ends where it does.

### The verified facts

Four things were established from Media3 1.11.1's own source rather than from documentation or
assumption, and together they decide the outcome:

| Fact | Evidence | Consequence |
| --- | --- | --- |
| `setVideoEffects` needs `androidx.media3:media3-effect` at runtime | `ExoPlayerImpl.setVideoEffects` runs `Class.forName("androidx.media3.effect.SingleInputVideoGraph$Factory")` and throws `IllegalStateException("Could not find required lib-effect dependencies.")` when it fails — on every call, including an empty list | The API cannot be called at all from this build, so the seam stays unbound rather than pretending to try |
| The effects pipeline must exist before `prepare()` | The method's contract: "must be called at least once before calling `prepare()` in order to set up the effects pipeline" | Attaching on request cannot be the call that creates the pipeline; supporting on-demand attachment means installing a pass-through pipeline for every session, copying every frame for people who never ask for processing |
| The pipeline is created from a non-null effects list, not a non-empty one | `MediaCodecVideoRenderer.onEnabled` builds the video sink while `videoEffects != null`, and `onReset` clears the flag so a later enable can build it then | Even `setVideoEffects(emptyList())` is not a no-op: it arms the pipeline for the next renderer enable. Calling it to "clear" effects would switch playback onto the GL path |
| `setVideoEffects` is on `ExoPlayer`, not on `Player` | `Player` declares no such method, so a `MediaController` cannot reach it | The request must travel as a session command to the process-owned player, which is what the architecture now provides |

### The request path

`core/media/session/ProcessingSessionContract` defines two custom commands — one to enable, one to
disable — and `ProcessingSessionCallback` declares them to a **trusted** controller and handles them.
Nothing else in the application is offered them: the system's media controls keep the read-only
command set they are entitled to.

A request travels: player screen → view model → `MediaSessionProcessingController` (which borrows the
session connection, never the player) → `MediaSession.sendCustomCommand` → the service's callback →
`MotionFlowPlayer` → `PlayerProcessingEndpoint`, which answers. A disable request is answered by
confirming the state that already holds, which is what makes the fallback guarantee literal: after any
refusal, a disable request leaves the player on Media3's own path.

### What the diagnostics say

| Mode | Meaning |
| --- | --- |
| `native` | Nothing has been reported yet; the path is Media3's own |
| `unavailable` | There is no video surface, so there is nothing for a stage to render through |
| `inactive` | A surface is bound, a stage could be attached, and none is — the steady state |
| `active` | A player reported that a stage attached. Reachable only from such a report |
| `failed` | The last request could not be honoured, with the reason it could not |

Reasons are specific rather than generic: `no_surface`, `effects_module_absent` (this build links no
effect module), `no_stage_implemented`, `request_refused`, `command_unavailable` (the session does not
offer the command), `transport_failed` (the request never arrived). The panel shows the line
`Rendering: Native Media3 · Processing: inactive · First frame 412 ms` and one explanatory note, so a
reader never has to guess which of several causes applies.

The counters count attachment: how often a player confirmed a stage, how often one stopped being
attached, and how many requests were made. They cannot report a stage nobody confirmed, and there is no
field anywhere for an output frame rate — an effect that copies a frame is not a rate.

### Fallback

Processing is optional by construction. A refusal, an unreachable session, a command the session does
not offer, a missing surface, a repeated request, a player that throws — each is a recorded answer, and
playback continues with position, play/pause state, repeat mode, playback speed, audio and the video
surface untouched. No request is retried, nothing is polled, and no failure can make Media3's path
unavailable.

### Known limitations

- **Nothing is attached**, so `active` is not reachable in this version — by design, and documented
  above rather than papered over.
- **No interface control sends a request.** A button that could never succeed would imply a capability
  that does not exist. The path is exercised by tests and is what Phase 7 drives.
- **The transport is verified by unit tests and by compilation, not on a device.** CI has no display
  and no session, so the command's round trip through a real `MediaSession` on real hardware is still
  to be observed.


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
| Checksum | APK size and SHA-256 recorded in the log and the job summary |
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
| 3 | **Adaptive Display Refresh Rate** | Match the display's own modes to the video's cadence. **✅ complete** |
| 4 | **Frame Pacing Engine** | Cadence analysis and pacing diagnostics. **✅ complete** |
| 5 | **Rendering Pipeline Foundation** | Path described, ownership asserted. **✅ complete** |
| 6 | **Frame Processing Architecture** | Effect API verified, request path proven. **✅ complete** |
| 7 | **Processing Performance & Hardware Characterization** | Two measured baselines, metric availability named, hardware procedure documented. **✅ complete** |
| 8 | **Hardware Validation & Processing Feasibility** | Characterization tooling, integrity rules, evidence gate. **✅ complete, hardware NOT TESTED** |
| 9 | Interpolation Stage and AI Interpolation | A stage that attaches for real, then RIFE-class models — scope to be set from Phase 8's evidence |
| 10 | Adaptive Performance Management | Battery, thermal and load-aware quality scaling |
| 11 | Production Hardening and Release | Accessibility, profiling, signing, Play release |

[`ROADMAP.md`](ROADMAP.md) tracks scope and exit criteria per phase.

## Performance characterization

A measurement is a controlled window — 10, 30 or 60 seconds — taken on one of two pipelines, reported as
counts and durations. Nothing is measured outside a session the user started, nothing is uploaded, and
nothing is written to disk.

```
Native            Baseline A:  MediaCodec → Media3 video renderer → SurfaceView → display
Effect pipeline   Baseline B:  MediaCodec → Media3 video renderer (with Media3's frame processor) → SurfaceView → display
```

### What each baseline is

| | Measured by | The effect in the path |
| --- | --- | --- |
| Native | The control condition, and the default | None — `setVideoEffects` is not called at all |
| Effect pipeline | The same player, built for a different pipeline | `AlphaScale(1f)`, Media3's own identity effect |

`AlphaScale(1f)` is the safest pass-through Media3 1.11.1 publishes, and it is documented as one: *"An
alphaScale value of 1 means no change is applied"*, `isNoOp()` returns true for it, its shader sets the
identity transformation and texture matrices, and its `configure()` returns the input size unchanged. It
carries no timing behaviour, no rate change, no audio path and nothing that could influence the display
mode. Media3's *playback* path never consults `isNoOp` — verified by reading `PlaybackVideoGraphWrapper`
and `DefaultVideoFrameProcessor` — so the effect is not skipped: the pipeline really runs, which is what
makes it worth measuring.

### Why switching pipelines restarts the player

Media3 requires the effects pipeline to exist before `prepare()`, so the pipeline is chosen while the
engine is being built, never during playback. The choice is made on the Home screen, before a video is
opened; changing it releases the session and the engine so the next one is built for the new pipeline.
**That stops playback and drops the current video**, which is why the screen says so before the button is
pressed. This is the phase's stated measurement limitation, not a hidden behaviour.

### What is measured, and what is not

| Metric | Source | Availability |
| --- | --- | --- |
| Rendered frames | `DecoderCounters.renderedOutputBufferCount`, read at both ends of the session | Available, both pipelines |
| Dropped frames | `DecoderCounters.droppedBufferCount`, same read | Available, both pipelines |
| First-frame latency | `AnalyticsListener.onRenderedFirstFrame`'s `renderTimeMs` | Available, both pipelines |
| Decoder initialisation | `AnalyticsListener.onVideoDecoderInitialized` | Available, both pipelines |
| Frame-processing offset | `AnalyticsListener.onVideoFrameProcessingOffset` | **Effect pipeline only**: the native path has no frame processor, so a zero would be a false claim |
| Playback position, video size | `Player.currentPosition`, `onVideoSizeChanged` | Available, both pipelines |
| Process CPU time | `android.os.Process.getElapsedCpuTime()`, as a delta | Available |
| Resident memory (PSS) | `android.os.Debug.getPss()`, in kB | Available |
| Managed heap | `Runtime.totalMemory() - freeMemory()`, in bytes | Available, and only the managed part |
| Thermal status | `PowerManager.getCurrentThermalStatus()`, reported by a listener | API 29+, and no polling |
| Thermal headroom | — | **Not measurable**: `getThermalHeadroom` is not public API |
| GPU utilisation | — | **Not measurable**: Android publishes no such reading on any version, and inferring it from CPU time would be a fabrication |
| Battery drain | — | **Not measurable**: no API attributes consumption to one session, and deriving it from elapsed time is arithmetic dressed up as measurement |

A rendered-frame count is never divided by the session duration to produce a frame rate. The count is a
count, the source's rate comes from the metadata engine, and the display's rate comes from the refresh
engine — five separate facts, never conflated.

### Running a hardware test

The numbers this phase produces exist only on a device: CI has no display, no decoder and no session, so
it tests the architecture and nothing more. The procedure is:

1. **Install the debug APK** from the CI artifact, or build `:app:assembleDebug` yourself.
2. **Open the app** and choose **Playback pipeline → Native** on Home.
3. **Open a video** with *Open video*, pick a local file, and let it play for a moment to confirm it
   behaves normally.
4. **Start a measurement** from the player's *Measurement* row — `Measure 30 s` for the standard window.
   Leave playback running; the session ends by itself and reports its numbers.
5. **Record the result**: first frame, rendered, dropped, frame-processing offset, decoder init, CPU,
   PSS, thermal status, and the session duration. A metric the session could not read shows
   `not measured`; the metrics Android cannot measure are listed under the panel.
6. **Repeat** the run two or three times per pipeline. A single run is an anecdote, and a fixed-cadence
   video on a fixed display should be repeatable within a few frames.
7. **Switch to the effect-pipeline baseline**: go back to Home, choose **Playback pipeline → Effect
   pipeline** (this stops playback, as the screen warns), then repeat steps 3 to 6 on the same file.
8. **Compare**: the player shows the native-versus-effect differences for the metrics both runs measured.
   It reports deltas, never a winner.

Suggested matrix, where the device and the files allow it: 23.976, 24, 25, 29.97, 30, 50, 59.94 and 60
fps sources against a 60, 90 or 120 Hz display. The refresh-rate engine chooses the display mode; a
measurement never does. If a file is unavailable, no file is fabricated and no measurement is invented.

## Hardware characterization

Phase 8 turns a measurement into evidence: a record with its conditions, the rules that decide whether it
may be compared with another one, statistics over repeat runs, and an evidence gate that says what the
collected numbers do and do not license.

### Hardware validation: NOT TESTED

**No measurement has been taken on hardware.** The environment this phase ran in has no device:

| Probe | Result |
| --- | --- |
| `adb devices` | adb starts, no device attached |
| `adb connect localhost:5555` (the host's own adbd, over wireless debugging) | connection refused |
| `pm list packages` / `pm install` | the package manager is present; the running uid is an application uid and cannot install |
| The APK | built by CI only; no Android SDK is present locally to build or install one |

So the evidence gate is **unresolved by construction**, and Phase 8's honest result is a reproducible
procedure plus tooling that has been unit-tested, not a set of numbers. Nothing in this repository claims
otherwise, and no reading has been invented to fill the gap.

### What a run records

| Record | Holds |
| --- | --- |
| `RunCondition` | The pipeline, the video's characteristics, the display's, and the window — everything held constant |
| `VideoCharacteristics` | The metadata engine's measurement, the label it chose, VFR and confidence, resolution, container, duration, and a fingerprint |
| `DisplayCharacteristics` | The panel's modes, what was requested, what the display reports, and how the request ended |
| `PerformanceRun` | The condition, the measurement, the metrics that were *not* measured, the cadence engine's own classification, and how the run ended |
| `CadenceObservation` | The cadence and pacing engines' conclusions, quoted by name |

The fingerprint is derived from the video's *shape* — byte size, duration, resolution — and never from its
name. A fingerprint has to survive an export so two runs can be recognised as the same file, and a hash of
a local filename is one guess away from the filename.

### How a request that was ignored is recorded

Android reports an error when a refresh-rate request fails, and says nothing at all when one is ignored.
So the record distinguishes `HONOURED`, `REFUSED`, `NOT_APPLIED` ("a rate was asked for, no error came
back, and the display reports another one") and `UNKNOWN`. Calling an ignored request refused would be
asserting a refusal nobody reported.

### The rules that stop a comparison being invalid

- Only the pipeline may differ: same video fingerprint, resolution, source rate, display rate and window.
  Anything else is named as a blocker rather than presented as a difference.
- A run that did not finish its window is `INCOMPLETE` and is kept — "the third run was short" is a finding,
  and a missing third run is a hole.
- A run that reported a metric the platform cannot measure is flagged, and the finding names the metric.
- A zero is never a stand-in for absent: a frame-processing offset of zero with no frame count is reported
  as a zero where nothing was measured.

### Statistics, overhead and the gate

Repeat runs are summarised with mean, minimum, maximum and range — never with a score. Overhead is
reported as an absolute difference always, and as a ratio only when the baseline is not zero, because
dividing by nothing is undefined rather than large. The evidence gate reports factual states such as
`MEASUREMENT_INCOMPLETE`, `PIPELINE_OPERATIONAL`, `PIPELINE_OVERHEAD_CHARACTERIZED`, `THERMAL_IMPACT_OBSERVED`
and `ADDITIONAL_DEVICE_DATA_REQUIRED`, several of which can apply at once, and files every statement under
**observed**, **calculated**, **unknown**, **not tested** or **hypothesis**.

### Running the test

1. Install the debug APK from a CI artifact — the artifact's SHA-256 identifies the build.
2. Home → **Playback pipeline → Native**. Open a local video whose rate you know.
3. On the player, run **Measure 30 s**. Repeat at least three times on the same video and display rate.
4. Go back, switch to **Effect pipeline** — the session restarts, which the screen warns about — and repeat
   the same three runs on the same file.
5. Read the panel: it names the pipeline, the measured video, what the display did with the rate request,
   the run counts and the native-versus-effect differences.
   Native shows `Playback pipeline: Native` and `Processing: inactive`; the effect pipeline
   shows `Playback pipeline: Effect Pipeline` and `Processing: active · identity effect`, both before
   any measurement starts, because the row describes the player that was built rather than the
   selection that was made.
6. **Export characterization** and keep the text. It is plain text, produced only when you ask, and shared
   through the system share sheet — MotionFlow has no network permission and no uploader.

The export carries a coarse hardware class (manufacturer, model, ABI, API level, display modes) and a video
fingerprint. It carries no IMEI, serial, Android ID, account, location, or file path.

## Known Limitations

- **No measurement has been taken on a device**, and Phase 8 did not change that: it produced the
  instrument and the rules for reading it. CI has no display, no decoder and no media session, so the
  aggregation, the session rules, the wire codec, the integrity rules, the statistics, the gate and the
  report are unit-tested, and every measured number remains absent. See "Hardware characterization" above.
- **The evidence gate is unresolved**, which is a result rather than a gap: no state beyond
  `MEASUREMENT_INCOMPLETE` and `ADDITIONAL_DEVICE_DATA_REQUIRED` has been earned. Nothing in the project
  claims the pipeline is viable, and nothing claims it is not.
- **Switching pipelines stops playback**, by Media3's own rule rather than by choice: the effects pipeline
  has to exist before `prepare()`. Documented, and stated on the screen that offers the switch.
- **A processing offset is only comparable within the effect pipeline.** The native path has no frame
  processor, so the comparison leaves that delta empty rather than subtracting zero.
- **GPU utilisation, thermal headroom and battery drain are not measured on any device**, because no
  public Android API provides them. They are named in the panel rather than silently omitted.
- **Nothing is GPU-processed, and nothing is interpolated.** `core/media/rendering` describes Media3's
  own path, and `core/media/processing` says whether a stage is attached. The effect-pipeline baseline
  attaches an identity effect and measures the pipeline's cost; it changes nothing about the picture and
  generates no frame.
- **The effect-pipeline baseline exists only as a measurement.** AndroidX Media3's effects module is
  linked, and the engine arms Media3's own identity effect when that pipeline is selected — which is what
  makes the baseline real. The *interactive* processing request path from Phase 6 still refuses every
  enable, because a stage that changes pictures does not exist. See "Frame processing" above.
- **Frame release cannot be controlled.** `VideoFrameMetadataListener` reports a frame's release time
  and cannot change it; influencing presentation timing needs a custom renderer. See "Rendering path"
  above.
- **The baseline is thin on purpose.** Dropped and rendered frame counts are only available from
  `AnalyticsListener`, which a media session does not forward; consuming them needs a custom session
  command.
- **Frame pacing is diagnostic-only.** A 3:2 mismatch is identified and explained, not corrected.
  Nothing in the current architecture can change when a decoded frame is presented without replacing
  Media3's video renderer, and no claim of judder removal is made anywhere.
- **Frame pacing is not interpolation.** It can only decide when existing frames are shown. No frames
  are ever synthesised, and a display refresh rate is never presented as a video frame rate.
- **A refresh-rate request is advisory.** The platform may ignore it — in multi-window, on a device
  that switches modes on its own terms, or where only one mode exists. The diagnostics show the rate
  the display reports so the difference is visible. See "Display refresh rate" above.
- **The automatic preference is not persisted.** The player surface has an Auto/System-default
  toggle, but it lives for the session: there is no settings store yet, and adding one is a later
  phase's work rather than something to bolt on here.
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
