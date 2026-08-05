# REVIEW.md — living review backlog

Findings from AI review rounds (GLM 5.2 on PRs, periodic deep reviews) and
their dispositions. Stable IDs; nothing is deleted, only resolved or
declined with reasons. Point-in-time review snapshots archive under
`docs/reviews/`.

Legend: 🐞 bug · 🔧 improvement · ✨ idea · ⬜ open · 🟢 done · ⏸️ declined/deferred

## Open

- **G-1** 🔧 ⬜ Session files under `cacheDir/sessions` are never pruned.
  Android may clear cache under pressure, but a tidy LRU sweep (keep last
  N) belongs in the export/persistence step (roadmap #3).
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

## Won't do (for now)

- **G-W1** ⏸️ Packed-RGBA8 displacement-field fallback for GLES3 devices
  without renderable float formats (`EXT_color_buffer_half_float` /
  `EXT_color_buffer_float`). Such hardware is essentially nonexistent in
  practice; the engine surfaces a clear error instead. Reopen if a real
  device report shows the error string.
