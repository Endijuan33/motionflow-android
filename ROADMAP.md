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

## Phase 2 — Video Metadata Detection ✅

**Goal:** know exactly what is being played.

**Delivered**

- A player-independent metadata engine: `VideoMetadataReader` (storage provider, `MediaExtractor`,
  timestamp probe, decoder lookup) behind a process-scoped `VideoMetadataRepository`.
- Container and track description: duration, resolution, rotation, video and audio codec, codec
  string, preferred decoder, bitrate, pixel aspect ratio, colour space, transfer and bit depth,
  audio channel count, sample rate, plus the document's label, size and MIME type.
- Frame rate measured from sample timestamps, preserving fractional rates (23.976 stays 23.976),
  with a named-rate vocabulary and an explicit unknown state.
- Explicit `MetadataResult` states and actionable `MetadataError` classifications.
- A metadata panel on the player surface, showing a summary without interaction and the full
  description on expansion, with "Unknown" for anything not measured.
- Metadata feeding `PlayerUiState` asynchronously, refined once by Media3's parsed track formats,
  with stale reads cancelled when a new source is selected.

**Exit criteria — met, with one deviation and one deferral**

- Frame rate arithmetic is asserted against uniform, millisecond-dithered and variable synthetic
  timelines, and fractional rates survive the round trip. *Deviation:* the criteria named an
  `ffprobe` corpus, which CI cannot provide; the arithmetic is unit tested instead, and a real
  corpus comparison moves to on-device verification.
- Unsupported containers, permissions, missing files and malformed sources all produce a
  classification rather than a crash.
- *Deferral:* "VFR content is flagged rather than silently reported as a single frame rate" is
  satisfied in one direction only. A varying window is flagged; a uniform window is reported as
  *not determined*, because proving a constant rate requires reading the whole timing table and this
  phase rules out full-file scans. Full-file confirmation is deferred to Phase 4, where pacing needs
  the answer badly enough to pay for it.
- *Moved:* cadence analysis for telecined content now belongs to Phase 4 as well — it is only
  actionable once something paces frames.

**Explicitly out of scope:** acting on the measurements. Metadata detection does not change playback
frame rate, refresh rate or pacing.

---

## Phase 3 — Adaptive Display Refresh Rate ✅

**Goal:** make the display's refresh rate a first-class, observable input.

**Delivered**

- A pure matching policy: given a cadence and the display's own modes, it ranks exact matches, whole
  multiples and a last-resort fallback, with a documented 0.2% tolerance on the ratio.
- Display capability discovery through `Display.getSupportedModes()` and `Display.getMode()`, with a
  platform listener for display changes and no version branches above minSdk.
- Applying a preference through `WindowManager.LayoutParams.preferredRefreshRate` on the player
  window, and restoring the previous value when the screen goes away.
- A coordinator that decides only when an input changes, never repeats an identical request, and
  treats a refusal as a diagnostic rather than a playback error.
- Diagnostics on the player surface: the video's cadence and the display's rate labelled apart, the
  matching status, the reason when the outcome needs explaining, and an Auto/System-default toggle.
- 48 new unit tests (127 in total), covering the policy and the coordinator through fakes.

**Exit criteria — met in code, unverified on hardware**

- 24/30/60 fps content selects the appropriate mode from the display's own list, and fractional
  cadences keep their precision. *Unverified:* CI has no display, so no mode has actually changed on
  a device; this needs a hardware pass.
- Unsupported or refused requests leave playback untouched: the request is advisory, a refusal is
  recorded, and nothing pauses or errors. Covered by unit tests; behaviour on a device that ignores
  the request is still to be observed.

**Deferred:** the automatic preference is not persisted (no settings store yet), and
`Surface.setFrameRate` is not used — the window attribute is the documented equivalent, and the
surface-level hint belongs with Phase 5, which owns a surface.

**Explicitly out of scope:** interpolating frames, synthesising frames, or claiming that a higher
display refresh rate means more frames. The engine changes how often a frame is shown, never how many
frames there are.

---

## Phase 4 — Frame Pacing Engine ✅

**Goal:** remove judder caused by source cadence and panel cadence disagreeing.

**Delivered**

- A pure cadence classifier: 1:1, whole multiples, short repeating patterns (the 3:2 case), longer
  repeating patterns, unresolved pairings that drift, and displays too slow for the source — with the
  pattern of frame holds named ("3:2") rather than left implicit.
- A documented tolerance, shared with the refresh engine so the two cannot disagree about a pair, with
  its boundaries tested — including the pairing that matters most, 24.000 fps on a 59.94 Hz display,
  which sits 0.1% from five-to-two and drifts.
- A coordinator that re-analyses on a changed cadence or display state, conflates bursts, and does not
  re-emit identical state. No timer, no per-frame work, no polling.
- A `FramePacingController` seam and an explicit applied-or-not fact, so the diagnostics can say
  "diagnostic only" as a statement about what happened rather than as a hedge.
- Diagnostics on the player surface: the cadence, its pattern, whether the frame rate was reliable, an
  explanation when the cadence needs one, and whether the display could not be moved to suit it.
- 33 new unit tests (160 in total).

**Exit criteria — met, with an explicit limit**

- Every cadence relationship the phase brief lists is classified and covered by a test, including the
  fractional pairings and the unknown, variable and low-confidence inputs.
- *Limit:* **judder is not removed.** Frame release happens inside Media3's video renderer, the only
  app-facing hook reports release times rather than accepting changes, and influencing presentation
  timing would mean supplying a custom renderer — outside this phase, and outside its stated purpose as
  a foundation for later rendering work. The phase therefore classifies and explains, applies nothing,
  and says so. The two mechanisms that *can* affect presentation are already in place: Media3's own
  `Surface.setFrameRate` call on API 30+, and Phase 3's display-mode request.
- *Deviation from the brief:* there is no "fractionally compatible" outcome. A fractional cadence is
  never even, and the brief's 24/60 and 23.976/59.94 rows are the *same ratio* — exactly 5 refreshes to
  2 frames — so a classifier that is a function of the two rates must answer both the same way. What
  those rows were reaching for, how long the repeating unit is, is carried by the reason instead.

**Explicitly out of scope:** interpolating or synthesising frames, optical flow, motion estimation,
custom shaders, and any claim that judder has been eliminated or that a display rate creates frames.

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
