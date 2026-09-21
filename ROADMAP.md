# MotionFlow Roadmap

Eleven phases from an empty repository to a released product. Each phase is shippable on its own, and
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

## Phase 5 — Rendering Pipeline Foundation ✅

**Goal:** establish a safe, measurable rendering-path foundation for future frame processing.

**Delivered**

- The rendering path documented, and the observable parts described at runtime: mode, the surface type
  read from the view actually in use, capabilities, and a baseline of first-frame latency, surface
  attach/detach counts and video size.
- The ownership contract expressed as data and asserted, so a future stage cannot move ownership of
  the player, the session, the view, the surface or the display preference.
- A frame-processing seam (`RenderingController`) that is deliberately unbound, with the reason
  recorded: Media3's own renderer hosts the stage, attaching it costs a per-frame GL copy, and
  attaching it belongs where the player lives.
- Frame-metadata API evaluation: what can be observed (first frame, surface and video size, release
  times) versus what cannot be controlled (release timing) — with no reflection or hidden API anywhere.
- SurfaceView retained over TextureView on documented trade-offs, and the type read rather than assumed.
- 24 new unit tests (185 in total).

**Exit criteria — met, with an explicit limit**

- Everything about *describing* the path is satisfied and tested. The foundation owns no player, no
  renderer, no surface, no `Context` and no coroutine scope, and that is checked mechanically: the
  package imports nothing from Android, Media3 or the player.
- *Limit:* **no GPU processing is active**, and none could be without violating this phase's own
  constraints — attaching Media3's processing stage builds a GL pipeline that copies every frame and
  adds latency, which the phase forbids. The seam, the vocabulary and the diagnostics are what it
  contributes instead.
- *Deviation:* the brief's `FutureVideoProcessingStage` abstraction was realised as a
  surface-lifecycle seam, because the stage it anticipated is hosted *inside* Media3 rather than
  sitting between the decoder and a surface this application owns. Phase 6 verified that shape was
  wrong — the effects pipeline has to be armed before `prepare()`, not per surface — and replaced it
  with a request-driven endpoint.

**Explicitly out of scope:** interpolation, generated frames, optical flow, shaders, GPU code, and any
claim that acceleration or processing is active. Attaching a real stage moves to Phase 7.

---

## Phase 6 — Frame Processing Architecture ✅

**Goal:** prove that a processing stage has a safe route into the video path, and that asking for one
cannot break playback.

**Delivered**

- Verification, against Media3 1.11.1 source, of what the official effect API actually permits:
  `Effect` is a marker interface in `media3-common`; `ExoPlayer.setVideoEffects` is on `ExoPlayer` and
  not on `Player`, so it is unreachable from a screen; and it throws
  `IllegalStateException("Could not find required lib-effect dependencies.")` unless
  `androidx.media3:media3-effect` is on the classpath — including for an empty list.
- A processing architecture with one vocabulary: mode, reason, request, outcome, capabilities and
  diagnostics, in an Android-free package that owns no player, no surface and no scope.
- A verified request path — screen → view model → declared session command → session service → the
  process-owned player — with the command offered only to a trusted controller and answered where the
  player lives.
- A player-side endpoint that refuses every enable with the reason that applies, and confirms the
  native path for every disable, so the phase's fallback guarantee is literal rather than aspirational.
- The Phase 5 rendering foundation reduced to what it can honestly describe — the surface and its
  metrics — so exactly one component decides processing state.
- 30 new unit tests (215 in total), including the two cross-engine checks that a processing request
  cannot move the display or the cadence classification.

**Exit criteria — met, with an explicit limit**

- The transport is complete, tested at both ends, and covered by tests for a refusal, an unreachable
  session, an unavailable command, a missing surface, a repeated request and a player that throws. In
  every one of those cases playback continues on Media3's path with position, speed, repeat mode,
  audio and surface untouched.
- *Limit:* **no effect is attached, and none can be from this build.** Attaching one requires
  `androidx.media3:media3-effect`, which the effect API's own guard demands on every call, and it
  requires the pipeline to be armed before `prepare()` — so an on-demand attach would mean installing
  a pass-through graphics pipeline for every session, copying every frame for people who never ask for
  processing. Neither is compatible with this phase's constraint that nothing be attached that is not
  needed, so the endpoint refuses, names the reason, and the seam stays empty.
- *Deviation:* the roadmap itself said this phase would attach a stage. It could not, for the two
  reasons above, so what it delivers instead is the verified route, the diagnostics that say which
  fact blocks it, and a refusal that is impossible to mistake for a failure of playback.

**Explicitly out of scope:** interpolation, generated frames, optical flow, shaders, GPU code,
graphics dependencies, and any claim that processing or acceleration is active.

---

## Phase 7 — Processing Performance & Hardware Characterization ✅

**Goal:** find out, by measuring rather than assuming, whether Media3's effect pipeline is a viable
foundation for a future interpolation stage.

**Delivered**

- The official identity effect, verified rather than assumed: `AlphaScale(1f)` is documented as "no change
  is applied", reports `isNoOp`, uses identity matrices and the same output size — and Media3's playback
  path never consults `isNoOp`, so the pipeline genuinely runs when it is armed.
- Two measured baselines. The engine is built *for* a pipeline, with the effect armed before `prepare()`,
  because Media3 requires it to exist that early. The control condition never calls `setVideoEffects` at
  all: an empty list would still build the frame processor.
- A measurement architecture with the arithmetic in a pure, event-fed accumulator and a thin Media3
  adapter, so session lifecycle, frame-count aggregation, unavailable metrics and the comparison are all
  testable on the JVM with no device.
- A controlled session — 10, 30 or 60 seconds — started and stopped over the session command path, with
  metrics that no Android version publishes named as unmeasurable instead of estimated.
- CPU time, resident memory, managed heap and thermal status through public APIs only, sampled at the
  session's two ends, with the thermal status arriving through a listener rather than a poll.
- A comparison policy that reports differences and refuses to rank: no score, no verdict, no best mode.
- 45 new unit tests (264 in total), including the refusal rules, the deltas, the absent-versus-zero
  distinction, and the wire codec.

**Exit criteria — met, pending hardware**

- Both baselines exist, are selected deliberately, and cannot be confused with each other: the mode a
  measurement was taken under travels with it, and the panel names it.
- *Pending:* **no measurement has been taken on a device.** CI has no display, no decoder and no session,
  so the architecture is unit-tested and the hardware run is documented as a procedure (`README.md`) with
  its result to be recorded by hand. That is the honest state: this phase produced the instrument, and the
  instrument has not yet been pointed at a device.
- *Deviation:* switching pipelines stops playback, because Media3 requires the effects pipeline before
  `prepare()`. Documented in the README, in `ARCHITECTURE.md`, and on the screen that offers the switch.
- *Deviation:* "an identity effect if safely supported" was answered from the effect's own source rather
  than by trying it and hoping — and it was supported, so baseline B is real rather than modelled.

**Explicitly out of scope:** interpolation, generated frames, optical flow, motion estimation, motion
vectors, frame synthesis, custom shaders, a custom renderer or `VideoFrameProcessor`, decoder replacement,
frame retiming, frame duplication, dropped-frame algorithms, release-time manipulation, vendor-specific
hacks, and any claim of a generated frame rate.

---

## Phase 8 — Interpolation Stage and AI Interpolation ⏳

**Goal:** a stage that genuinely changes pictures, and then real quality interpolation behind it.

**Depends on Phase 7's evidence.** The effect pipeline's cost — first-frame latency, dropped frames, CPU
time, resident memory and thermal behaviour against the native baseline — is what says whether a stage can
be afforded at all. Phase 7 deliberately produced the instrument rather than the conclusion; the
conclusion is a hardware run whose numbers decide how much budget a stage has.

**Scope**

- The interpolator contract: given two frames and a phase, produce one intermediate frame.
- Frame queueing, lookahead, and A/V sync when a stage adds latency.
- Buffer ownership for the stage: colour-space and HDR handling, and shader-based scaling and conversion,
  with Vulkan behind the same abstraction.
- RIFE-class model execution via ONNX Runtime or NCNN, with model loading, quantization and hardware
  delegation (GPU/NPU) where available.
- Per-device throughput measurement and dynamic quality selection, using Phase 7's measurement paths.
- Deterministic fallback to plain playback when the budget is exceeded, and when a stage cannot attach.

**Exit criteria**

- A trivial (non-AI) interpolator can be enabled end to end without touching player or screen code, and the
  diagnostics report processing as active only while a stage is genuinely attached.
- Playback remains correct when interpolation is toggled mid-stream, with position, speed, repeat mode and
  audio preserved.
- Sustained real-time interpolation at the target output rate on reference hardware, with graceful fallback
  when inference cannot keep up.
- Zero GPU-side stalls attributable to buffer ownership over a playback soak.

---

## Phase 9 — Adaptive Performance Management ⏳

**Goal:** stay smooth and cool for the whole film, not the first five minutes.

**Scope**

- Thermal status and battery aware quality scaling.
- Dynamic control of output frame rate, interpolation quality and render resolution.
- Telemetry for frame times, drops, inference latency and thermal state.

**Exit criteria**

- No thermal throttling induced stutter on a 45-minute soak.
- Measured battery drain stays within the agreed budget for the reference profile.

---

## Phase 10 — Production Hardening and Release ⏳

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
