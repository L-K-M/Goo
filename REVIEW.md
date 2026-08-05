# REVIEW.md — living review backlog

Findings from AI review rounds (GLM 5.2 on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

- **G-1** 🔧 🟢 Session files under `cacheDir/sessions` are never pruned.
  Resolved in roadmap #3: `ImageLoader.sweepSessions` deletes everything
  but the live session on each import.
- **G-2** 🔧 ⬜ Strokes are lost on process death (the log lives only in
  the ViewModel). Fine for the MVP; project persistence (serialized stroke
  log — the types are already `@Serializable`) is planned alongside
  animation (roadmap #7).
- **G-3** ✨ ⬜ The stamp pass renders a fullscreen quad per stamp at field
  resolution. Fine at ≤1024 fields (~5 Mpx per dozen stamps); a scissored
  sub-quad is the known optimization if device profiling ever disagrees.

- **G-4** ✨ ⏸️ Pool the per-batch stamp lists in the touch path (GLM 5.2
  round 1, PR #2, info-level). Each batch crosses the UI→GL thread
  boundary, so per-batch ownership is inherent; eliminating allocation for
  real means a pooled ring buffer of primitive arrays. Revisit if frame
  traces on a low-end device show GC pressure during fast drags.

- **G-5** ✨ ⬜ True full-resolution export above the 4096 budget cap
  (`ExportSize.EXPORT_MAX_DIM`). Needs tiled rendering with
  displacement-bounded source tiles — the output tile must sample source
  up to max|D| beyond its edges, so the tiler needs a field-magnitude
  bound first. Today >12 MP sources export at ~12 MP; revisit when a real
  user asks for native 48 MP output.

- **G-6** ⚠️ ⬜ Pumped tools (Grow/Shrink/Smooth/UnGoo) emit ~60
  stamps/s while held, so long holds inflate the stroke log and the
  full-replay cost undo/redo/export pay (a 5 s hold ≈ 300 field passes).
  Stamps can't be naively merged — warp-of-warp compounding is the pump
  feel — so the real fix is field snapshot checkpoints every N strokes
  (replay from nearest checkpoint instead of identity). Do when replay
  latency becomes user-visible.

- **G-7** ⚠️ ⬜ `renderMovie` runs as one GL runnable, and
  `GLSurfaceView`'s `onPause()`/`surfaceDestroyed()` block the MAIN
  thread until the GL event queue drains (AOSP-verified), so
  backgrounding mid-export freezes the app for the export remainder.
  Typical 2–8-keyframe GOOvies ≈ 0.5–3 s — fine; a 64-keyframe strip
  over a heavy stroke log ≈ 15–45 s — ANR/kill territory. Fix is
  chunked rendering: per-export state in a renderer-held MovieSession,
  ~30 frames per runnable, self-requeue via a view-provided queueEvent
  hook, end-of-chunk restore tolerating dead preview surfaces. Do
  before raising movie length or promoting long strips.

## Won't do (for now)

- **G-W1** ⏸️ Packed-RGBA8 displacement-field fallback for GLES3 devices
  without renderable float formats (`EXT_color_buffer_half_float` /
  `EXT_color_buffer_float`). Such hardware is essentially nonexistent in
  practice; the engine surfaces a clear error instead. Reopen if a real
  device report shows the error string.

## Sol PR review dispositions

- **PR #24: per-revision lazy materialized lists — declined.** The repeated
  active-state read concern was valid and fixed with one active-revision cache.
  Caching every historical prefix would restore the quadratic retained-reference
  behavior SOL-6 removes.
- **PR #32: make `MovieEncoder.finish()` idempotent — declined.** The encoder
  lifecycle requires exactly one finish. Marking a failed muxer stop as complete
  and returning from a retry can turn a prior finalization failure into apparent
  success; the render already fails and release remains best-effort.
- **PR #37: release files missing from the publisher — refuted.** The original
  `dist/*.apk` and `dist/*.sha256` inputs remain in the release step. A named
  `actions/download-artifact` download extracts contents directly into its
  requested path; an artifact-name subdirectory is the multi-artifact behavior.
