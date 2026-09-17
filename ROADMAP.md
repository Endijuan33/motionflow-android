# MotionFlow Roadmap

Ten phases from an empty repository to a released product. Each phase is shippable on its own, and
each has an exit criterion that can be checked rather than argued about.

**Status legend:** ✅ complete · 🚧 in progress · ⏳ not started

---

## Phase 0 — Project Foundation ✅

**Goal:** a repository that builds reproducibly and a codebase the later phases can grow into.

**Delivered**

- Gradle build with committed checksum-pinned wrapper, JDK toolchain and a version catalog.
- Dark-first Material 3 design system: colour, type, shape, spacing, elevation, motion tokens, with
  palette contrast asserted by unit tests.
- Single-activity Compose application, edge-to-edge, with a real navigation graph.
- Adaptive launcher icon (vector foreground/background, monochrome layer for themed icons).
- CI that lints, unit tests, assembles a debug APK and uploads it as an artifact.

**Exit criteria — met**

- `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` succeeds from a clean checkout.
- CI is green on the default branch and publishes the APK artifact.
- Palette contrast guarantees and route hygiene are asserted by unit tests.

---

## Phase 1 — Core Video Playback ✅

**Goal:** play a local video file reliably, with playback owned outside the UI.

**Delivered**

- Media3 1.11.1 with the standard hardware-first decode pipeline; no decoder internals overridden.
- A single `ExoPlayer` per process, created by a centralized factory and owned by a
  `MediaSessionService`; the UI drives it through a `MediaController`.
- Local media input through the Storage Access Framework, including `content://` URIs and refusal
  of unsupported sources before they reach the player.
- `StateFlow`-backed player state model and a Compose player surface: video stage, play/pause,
  scrubber, position and duration, buffering indicator, error notice, back navigation, speed and
  repeat controls, fullscreen-ready layout.
- Playback failure classification into user-facing messages, with technical detail kept for logs.
- Lifecycle safety: no player in the composition, deliberate release, playback that survives
  configuration changes, and release of the session resources.
- Unit tests for the state model, error mapping, route round-tripping and readout formatting.

**Exit criteria — met**

- A local file plays through hardware decode, driven entirely from the Compose surface.
- Rotation and screen navigation do not create a second player, and the existing session is reused.
- Failures (unsupported format, missing file, lost permission, decoder fault) show an explanation
  instead of a crash.

**Explicitly out of scope:** rendering pipelines, refresh-rate control, interpolation, inference.

---

## Phase 2 — Video Metadata Detection ⏳

**Goal:** know exactly what is being played.

**Scope**

- Container, track, codec, resolution and bitrate detection.
- Real frame rate detection, including variable frame rate sources.
- Cadence analysis for telecined and pulldown content.

**Exit criteria**

- The reported frame rate matches `ffprobe` for a corpus of CFR and VFR test files.
- VFR content is flagged rather than silently reported as a single frame rate.

---

## Phase 3 — Display Refresh Rate Control ⏳

**Goal:** make the display's refresh rate a first-class, observable input.

**Scope**

- Enumerate supported display modes and their refresh rates.
- Request a refresh rate that matches the content cadence where supported.
- Restore the previous mode when playback ends.
- Surface the active mode in the UI.

**Exit criteria**

- On a variable-refresh-rate device, playing 24/30/60 fps content selects the closest matching mode.
- Unsupported requests degrade to the platform default without errors.

---

## Phase 4 — Frame Pacing Engine ⏳

**Goal:** remove judder caused by source cadence and panel cadence disagreeing.

**Scope**

- Presentation timestamp driven frame release.
- Cadence matching (e.g. 3:2 pulldown selection for 24 fps on 60 Hz panels).
- Drift correction against the audio clock.

**Exit criteria**

- Frame drop and late-frame counters stay within budget over a 10-minute playback soak.
- A/V sync drift stays under one frame over the same soak.

---

## Phase 5 — GPU Rendering Pipeline ⏳

**Goal:** own the path from decoded frames to the display.

**Scope**

- OpenGL ES render path with a Vulkan path behind the same abstraction.
- Surface, texture and colour-space handling, including HDR transfer functions.
- Shader-based scaling and pixel format conversion.
- Replaces the `PlayerView` stage in the player surface and the renderer configuration in the player
  factory — the two seams Phase 1 exists to provide.

**Exit criteria**

- Identical visual output to the platform path on the reference corpus.
- Zero GPU-side stalls attributable to buffer ownership over a playback soak.

---

## Phase 6 — Frame Interpolation Architecture ⏳

**Goal:** the plumbing that interpolation needs, independent of any particular model.

**Scope**

- Interpolator contract: given two frames and a phase, produce an intermediate frame.
- Frame queueing, lookahead, and A/V sync when interpolation adds latency.
- Deterministic fallback to plain playback when the budget is exceeded.

**Exit criteria**

- A trivial (non-AI) interpolator can be enabled end to end without touching player or render code.
- Playback remains correct when interpolation is toggled mid-stream.

---

## Phase 7 — AI-Based Interpolation ⏳

**Goal:** real quality interpolation on device.

**Scope**

- RIFE-class model execution via ONNX Runtime or NCNN.
- Model loading, quantization and hardware delegation (GPU/NPU) where available.
- Per-device throughput measurement and dynamic quality selection.

**Exit criteria**

- Sustained real-time interpolation at the target output rate on reference hardware.
- Graceful fallback to non-interpolated playback when inference cannot keep up.

---

## Phase 8 — Adaptive Performance Management ⏳

**Goal:** stay smooth and cool for the whole film, not the first five minutes.

**Scope**

- Thermal status and battery aware quality scaling.
- Dynamic control of output frame rate, interpolation quality and render resolution.
- Telemetry for frame times, drops, inference latency and thermal state.

**Exit criteria**

- No thermal throttling induced stutter on a 45-minute soak.
- Measured battery drain stays within the agreed budget for the reference profile.

---

## Phase 9 — Production Hardening and Release ⏳

**Goal:** ship it.

**Scope**

- Accessibility audit: content descriptions, focus order, touch targets, captions.
- R8 rules, baseline profiles, startup and frame-time profiling.
- Release signing, versioning and Play Store release track.
- Crash and ANR reporting with opt-out.
- Media library: persistence of played items and URI grants, queue and playback resumption.

**Exit criteria**

- Release build is signed, minified and passes the accessibility audit.
- Crash-free session rate above the agreed threshold on the internal track.

---

## Cross-phase rules

- A phase does not start until the previous phase's exit criteria are verified in CI or on device.
- A capability is queried before it is assumed; every phase ships a fallback path.
- Performance and thermal budgets are measured, not estimated, before a phase is called complete.
- No stub implementations of a future phase are merged into an earlier one.
