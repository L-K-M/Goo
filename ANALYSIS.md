# ANALYSIS.md - forward backlog

The living base for future Goo work. It consolidates the k3 deep review
(`k3.md`, 2026-08-05) and the Sol whole-project review (`sol.md`, 2026-08-05).
The point-in-time documents retain full evidence, exact source references, and
idea detail; this file contains only work that remains open or is waiting to
merge.

IDs stay stable. `REVIEW.md` owns G-* findings, `k3.md` owns K3-*, and
`sol.md` owns SOL-*. Do not renumber them.

## In-flight implementation PRs

These changes are implemented in their own branches and under review, but are not
present on `main` until their PRs merge. They are removed from the open backlog
below without being mislabeled as done.

| Finding | Change | PR | Relationship |
| --- | --- | --- | --- |
| SOL-6 | Structurally shared, linear-memory stroke revisions | #24 | Base for #27 |
| SOL-1 | Branch-stable GOOvie keyframes and revision-keyed endpoint caches | #27 | Stacked on #24 |
| SOL-8 | Corner-correct resampler displacement | #28 | Independent |
| SOL-9 | Explicit touch-down semantics; single initial pump stamp; Fusion taps | #29 | Independent |
| SOL-11 | Fusion import readiness, stale-request cleanup, failure fallback | #30 | Independent |
| SOL-4 | Upright MediaCodec movie rectangle | #31 | Independent; overlaps upstream #25 |
| SOL-12 | Throwing MP4 muxer finalization before success | #32 | Independent |
| SOL-18 | MediaStore/legacy export finalization and collision safety | #33 | Independent |
| SOL-19 | Bounded retention of unique FileProvider share files | #34 | Independent |
| SOL-24 | Source-anchored viewport rebase after resize | #35 | Independent |
| SOL-31 | Dismiss onboarding only after a nonempty committed stroke | #36 | Independent |
| SOL-39 | SHA-pinned, two-job release trust boundary | #37 | Independent |
| SOL-41 | CPU/GLSL wire IDs, hash vectors, and shader literal contracts | #38 | Independent |

Upstream PR #25 also includes the SOL-4 movie-orientation fix alongside Crop.
Upstream PR #26 proposes a different keyframe/history interaction model. Merge
one coherent implementation for overlapping behavior; do not blindly combine
both approaches.

## Release blockers and document safety

- **SOL-2: Export is not an atomic snapshot of the visible state.** Still
  export remains available over a scrubbed GOOvie but renders the live final
  document. A visible in-flight stroke is omitted, and globals are read later
  from mutable renderer state. Create one immutable render request containing
  strokes, globals, Fusion identity, and optional tween endpoints. Commit or
  cancel live work before Out. Until full-resolution tween snapshots exist,
  disable still export in GOOvie mode or visibly return to live mode.

- **SOL-3: The 4096 cap does not bound decode memory.** An 8000x6000 image at
  a 4096 target decodes at full 48 MP before scaling; EXIF rotation can briefly
  require a second full bitmap. A power-of-two sampler-only fix is rejected:
  it creates a 4097-to-2048 resolution cliff. The defensible solution is an
  export-specific, EXIF-aware `BitmapRegionDecoder` tile plan, cover-aware
  Fusion decode, explicit unsupported-format fallback, and physical low-memory
  tests. The existing 4096 Fusion GL/readback peak must be measured too.

- **SOL-5: Reset is only partly undoable.** Reset restores strokes on Undo but
  permanently discards global levers; globals-only Reset creates no history
  entry. Model Reset as a document transaction containing strokes and globals,
  or retain an explicit undo token for destructive lever resets. `Zero all`
  needs the same guardrail.

- **SOL-7: Back destroys an unpersisted edit without warning.** Toolbar and
  system Back immediately pop the editor. Until SOL-34 lands, intercept dirty
  Back and offer Keep gooing / Discard. Dirty includes strokes, globals,
  Fusion, and keyframes. Do not mistake this interim dialog for persistence.

- **SOL-34 / G-2 / K3-7 / K3-27: Persist the document and add Recent Goo.**
  This remains the highest-value feature. Write source identities, normalized
  stroke revisions, history cursor, globals, Fusion state, keyframe revision
  IDs, and schema version atomically; recover corrupt projects safely; add Home
  resume/discard. Build it after #24/#27 settle the revision model. Runtime
  `StrokeRevision` graphs must be serialized as a normalized ID table, never
  recursively.

## Engine, lifecycle, and platform

- **SOL-10: Two-finger navigation can stamp the image.** The first pointer
  mutates immediately, then its stroke is committed when a second pointer
  turns the gesture into navigation. Stage one-pointer intent until slop, or
  discard/rebuild staged work when pointer two arrives. Navigation must be
  document-neutral.

- **SOL-13: Movie dimensions ignore encoder capabilities.** Common 4:3 and
  square inputs request 1920x1440 or 1920x1920 from API 26-era AVC hardware.
  Query `VideoCapabilities`, honor alignment/size/rate/bitrate ranges, and step
  down through conservative 1080p-area and 720p fallbacks. Device tests are
  required.

- **SOL-14: Pumped brushes can create an unbounded UI-to-GL queue.** A runnable
  is queued every 16 ms even when a low-end GPU processes slower than cadence.
  Use one scheduled drain and a bounded/coalesced pending-stamp buffer; stop
  admission while paused. First prove the backlog with a long Smooth trace.

- **SOL-15: Still export can block lifecycle pause like movie export.** Replay,
  render, readback, and bitmap creation are one GL runnable; coroutine cancel
  cannot interrupt it. Use a chunked session or dedicated offscreen EGL worker
  and expose honest editor-wide busy/cancel state.

- **SOL-16: GL state resynchronization around pause/context recreation is
  incomplete.** Commands can queue without a current surface, a recreated
  context loses the visible tween endpoints, and live batches can no-op before
  commit. Add a context-generation signal, replay desired logical state per
  generation, gate resumed readiness, and track whether a live stroke fully
  reached that generation.

- **SOL-17: Still and movie exports can overlap while editing remains live.**
  Replace independent booleans with one editor-wide export state carrying kind
  and destination. Block competing exports and document mutation. Real cancel
  depends on SOL-15/G-7 chunking.

- **SOL-20: Imported source bytes are purgeable and unbounded.** The source of
  truth lives under `cacheDir`, while an unrestricted provider stream can fill
  storage or stall IO. Durable drafts belong in deliberate private storage
  with explicit cleanup. Add compressed-byte/free-space limits and cancellation
  before copying. Decide backup behavior with SOL-34.

- **SOL-21: GLES capability failure is not consistently fail-soft.** Declare
  GLES 3.0 in the manifest, wrap config/shader/FBO initialization in one error
  boundary, runtime-probe the field format, and validate critical GL uploads.
  The packed-field fallback remains separately deferred as G-W1.

- **SOL-22: Alpha, wide gamut, and HDR have no output policy.** JPEG/MP4 need
  an explicit opaque matte; otherwise transparent inputs differ from preview.
  Choose intentional sRGB normalization or end-to-end color management and
  publish a support matrix for P3, HEIC/AVIF, animation, and Ultra HDR.

## Performance work with measurement gates

- **G-6 / K3-9 / SOL-44: Replay growth and GOOvie segment hitches.** Pumped
  holds create about 60 passes/s; undo/export replay them, and endpoint
  materialization can hitch at segment boundaries. Field checkpoints keyed by
  revision ID plus field specification are the shared fix. Implement when a
  low-end trace establishes latency.

- **G-7 / K3-10: Monolithic movie rendering.** Long GOOvies can block
  `GLSurfaceView.onPause()` into ANR territory. Use a renderer-held MovieSession
  that renders bounded frame chunks and self-requeues. Do before promoting
  long strips.

- **G-3 / SOL-45: Every stamp is a full field pass.** Profile fill rate first.
  A naive scissor is incorrect with ping-pong textures because untouched
  destination pixels are stale; use a bounded quad plus preservation/copy if
  evidence justifies it.

- **G-4: Touch-batch allocation.** A pooled primitive ring buffer is the real
  fix, not cosmetic list reuse. Retain the current ownership-safe batches until
  a low-end frame trace shows GC pressure.

- **K3-11 / SOL-46: Fixed 2048 preview and broad high-frequency state.** A
  device/window heuristic could reduce low-end fill, while playback/cursor
  state, touch Pairs/lists, and lever drag coroutines are profiling targets.
  Do not optimize from static suspicion alone.

- **SOL-47: Low-priority micro-optimizations.** Context VAO, invariant sampler
  uniforms, reusable globals array, cached fit, fewer encoder `eglMakeCurrent`
  calls, and larger movie-copy buffers are valid only after correctness work.

- **G-5: Native resolution above the export cap.** True tiling needs
  displacement-bounded source margins. First disclose actual output dimensions
  honestly; implement native 48 MP output only when users require it.

## Layout, interaction, and accessibility

- **SOL-23: Compact-height, landscape, and large-font layouts lose controls.**
  Home and dialogs need vertical scroll fallback; levers need a compact grid or
  landscape side console; the crowded GOOvie action row needs an adaptive
  arrangement. Verify 320/360/600 dp, landscape, fold posture, and 200% font
  scale with screenshots.

- **SOL-25: Critical controls are hidden in silent horizontal rails.** Pin Back
  and Export, preserve/hoist brush scroll state, auto-reveal the selected tool,
  and add an edge cue or grouped palette. Use a bounded side console on larger
  windows.

- **SOL-26: Custom control accessibility is incomplete.** Attach semantic
  labels to sliders, describe bipolar lever direction/Off, distinguish action
  chips from selectable tools, name keyframes, add keyboard focus halos and
  lever keys, and provide a non-drag creative path. The full canvas needs a
  cursor/action model for TalkBack, Switch Access, and keyboard users.

- **SOL-27: Safe insets, hinges, and Snackbar placement are incomplete.** Use
  `WindowInsets.safeDrawing` for interactive chrome, place transient messages
  above the active panel, and branch on WindowManager only for separating
  hinges.

- **SOL-28: Export feedback is hidden or misleading.** Show failures inside
  Out rather than behind its scrim, freeze options while busy, display actual
  capped dimensions/downscaling, explain API 26-28 app-private Save before the
  operation, and distinguish `Pictures/Goo` from `Movies/Goo` in success UI.

- **SOL-29: Export events can be consumed while stopped.** Collect one-shot UI
  effects only while STARTED/RESUMED, retain pending effects, and do not lose a
  share result when chooser launch fails.

- **SOL-30: GOOvie interaction integrity needs a pass.** Suppress the fake
  brush cursor in non-editing mode, preview a single keyframe with identical
  endpoints, offer delete Undo, restore scrub endpoints before disk copy, and
  use the editor-wide busy state. Coordinate with upstream PR #26's larger
  proposal before editing this area.

- **SOL-32: Visual selection sometimes communicates content, not mode.** Global
  Effects should be selected only while its panel is open; active lever values
  need a separate badge/glow. The table texture and candy-slider work below
  complete visual cohesion.

- **SOL-33: Home and error recovery undersell the primary action.** Keep the
  visible playful "GOO!", but expose "Open a photo" to accessibility and in
  supporting copy. Add Choose another photo and friendly format guidance.
  Privacy copy should say Goo never uploads; user-selected Share can leave the
  device.

## Deferred product features and polish

- **K3-15 / SOL-35: Settings and notices.** Haptics opt-out, future sound
  toggle, export defaults, licenses, support limits, and diagnostics. Settings
  must precede optional sound/stroke-haptic delight.

- **K3-16: Procedural squishy sounds.** Offline synthesized PCM for stroke
  start, pump, lever detent, and keyframe punch. Depends on Settings.

- **K3-17: GIF export.** MP4 covers the primary share case; a bounded palette
  encoder remains lower priority unless users ask.

- **K3-5 / SOL-36: API 26-27 HEIC and support disclosure.** The current failure
  is graceful. Publish actual format/output limits first; implement old-API
  HEIC only if device stats or reports justify the matrix.

- **K3-19: Textured goo table.** A subtle felt/vignette or two-stop gradient
  adds KPT depth for little runtime cost.

- **K3-20: Keyframe thumbnails.** Cache tiny GL renders per revision pin;
  retain the numbered candy badge for character.

- **K3-21: Candy sliders and Out room.** Replace stock M3 sliders with grooved
  candy rails and give Out a distinctive output-slot/card treatment.

- **K3-23: Horizontal plus quad mirror.** Generalize the mode, show the mirror
  axis, and preview ghost cursor rings. Small and photogenic.

- **K3-24: Ping-pong GOOvie playback.** Bounce instead of snapping at loop
  boundaries. Small once timeline direction is explicit.

- **K3-25: Goo me recipes/dice.** Deterministic random presets with one-tap
  Undo; fun and a useful non-gesture accessibility ramp.

- **K3-26: Textured stroke haptics.** Tick emitted batches softly. Gate behind
  Settings and system preferences.

- **SOL-37: Fusion registration and inspection.** First make B decode
  cover-aware so it does not crop a fit-inside bitmap and upscale. Later add
  pan/zoom/rotate registration and hold-to-peek X-ray.

- **SOL-38: Release/version story.** `main` is materially beyond v1.0.0 while
  still declaring it. Use `scripts/release.sh` for the next release; never
  hand-edit versionCode or create a tag. Decide whether Goo is still a working
  name and align product copy.

## Testing, build, and documentation

- **SOL-40: Product-defining platform contracts need integration tests.** Add
  the smallest deliberate device matrix: asymmetric preview/still/movie
  golden, EXIF orientations, context recreation, codec capability cases,
  MediaStore API 29, legacy API 26 save, and compact-layout screenshots. This
  is targeted boundary proof, not a blanket emulator suite.

- **SOL-42: Wrapper/dependency supply-chain verification.** Add the official
  Gradle distribution checksum and evaluate reviewed dependency-verification
  metadata. Do not commit generated trust metadata without auditing it.

- **SOL-43: Architecture and policy docs drift.** Reconcile PLAN/AGENTS/ADRs
  with actual scissoring, snapshots, packed fallback, stores, GIF status, and
  layer boundaries. Resolve the in-app KPT-name contradiction as a product
  policy decision.

## Novel directions

- **Goo Spells:** share source-free normalized stroke/global/timing recipes that
  friends apply to their own photos offline.
- **UnGoo Monocle:** hold to reveal the original through a wobbling lens, after
  SOL-10 makes second-finger input document-neutral.
- **Rewind Gum:** hold Undo to preview the last stroke peeling backward; release
  commits one ordinary undo.
- **Making-of GOOvie:** derive a creation replay from the stroke log, compressing
  idle time and pump holds.
- **Fusion Slot and X-ray:** playful source swaps that preserve the mask, plus a
  hold-to-peek view of B.
- **Out Chute:** reduced-motion-aware glossy output card that drops onto the
  table after export.
- **Mirror Ghosts:** reflected pre-contact rings and a shimmering symmetry axis.
- **Offline diagnostics card:** copy API, GPU, max texture size, field format,
  and encoder capabilities for voluntary bug reports without telemetry.

## Declined or gated approaches

- **Shake-to-reset:** accidental-loss sensor gimmick; confirmed Reset is safer.
- **Per-revision full-list caches:** would reintroduce quadratic retained stroke
  references. PR #24 caches only the active materialization.
- **Sampler-only SOL-3 fix:** a strict upper-bound power-of-two sample causes a
  severe near-cap quality cliff. Use tiled decode with device tests.
- **Idempotent `MovieEncoder.finish()`:** lifecycle is create/render/finish once/
  release. Marking stop attempted in `finally` and returning on retry can make a
  retry appear successful after `MediaMuxer.stop()` threw. The failed render is
  already reported false and teardown is best-effort.
- **Keeping dead dependencies for hypothetical tests:** add them with the first
  real consumer, not before.
- **Timber for best-effort deletes:** no logging convention exists; bounded
  sweeps are the safety net.

## Done on main

| Entry | What | PR |
| --- | --- | --- |
| K3-1 | Top rail overflow on 360 dp screens -> scrollable rail | #13 |
| K3-2 | History ops orphaning live-stroke pixels -> discard-guard rebuild | #14 |
| K3-3 | Pumped tools dead on tap -> touch-down application | #15 |
| K3-4 | Movie work-file IO on main -> suspend IO | #16 |
| K3-6 | Dead dependencies removed | #17 |
| - | Lint SuspiciousIndentation false positive restructured | #18 |
| K3-8 | Fusion photo B removable | #19 |
| K3-14 | Keyframe punch bead on brush rail | #20 |
| K3-13 | Brush cursor ring while painting | #21 |
| K3-12 | Bundled generated sample images | #22 |
| K3-22 | Pinch/pan/zoom/rotate canvas | upstream #12 |
