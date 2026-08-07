# 0001 — Vortex brush

- **Status:** proposed
- **Date:** 2026-08-07

## The tool

Hold a finger on the photo and the pixels start orbiting the brush, like
stirring coffee: cheeks curl into cheeks, hair curls into commas, a
straight smile winds itself into a cinnamon roll. Chirality is part of the
gesture — start the hold and it spins one way; the palette offers the
other. It is a pumped tool (the KPT pump feel, same clock as Grow and
Shrink), so a still finger keeps winding the spiral tighter.

## Why it belongs in Meltorama 2000

It is the most famous warp tool ever shipped, and the one hole in our
palette. KPT Goo's own 1996 pitch — "smeared, smudged, twirled, and
pinched" — lists twirl between the two brushes we already have. Photoshop
Liquify put Twirl Clockwise / Counter-Clockwise at the top of its toolbar
and kept it there for thirty years. Every goo clone rediscovers it within
a release or two, because a spiral is the highest-signal warp there is:
one gesture, unmistakable result, instantly funny on a face and genuinely
beautiful on skies, hair, and fabric.

We have the *global* Twirl lever, but that spins the whole frame around
its center — you cannot swirl one nostril. The brush is the point.

Spirals are also the best GOOvie material we could add: punch a frame,
pump the Vortex for a second, punch another — the tween makes a photo
stir itself. That is the demo clip the app does not currently have.

## How it fits the engine

One new `StampMode.VORTEX` (`shaderId 6`) — the same shape of change the
Fusion PR landed:

- Kernel: `b(p) = t̂ · w · RADIAL_STEP_UV`, where `t̂` is the unit tangent
  (the outward radial direction rotated 90°). The `CENTER_RAMP_END` ramp
  that already tames the radial singularity for Grow/Shrink tames the
  tangent singularity identically. Chirality rides in the stamp's `dx`
  sign, so the existing `Stamp` wire format is untouched.
- CPU reference: one more branch in `DisplacementField.applyStamp`; one
  more branch in `STAMP_FRAG`; the two stay transliteration-close per the
  house rule, and `GlShaderContractTest` / `BrushToolTest` grow by one row.
- Mirror is free: `BrushTool.mirrorStamp` flips `dx` for directional
  modes; for Vortex that same flip is exactly the chirality reversal a
  mirrored swirl needs.
- Undo, redo, full-res export replay, project persistence, and GOOvie
  endpoint caching are all free — a vortex stroke is ordinary stamps in
  the log.
- `BrushTool` gains `VORTEX(StampMode.VORTEX, FalloffProfile.SMOOTHSTEP,
  1f, pumped = true)`; the engine keeps its "no per-tool code paths
  beyond the table" property.

Cost estimate: one stamp mode, one palette dome, one icon. The smallest
possible PR with the largest possible fun delta.

## Open questions

- **Chirality UX.** Two palette domes (CW/CCW, Liquify-style) is the
  honest cut; detecting chirality from the drag's initial tangent is
  fancier but misreads. Proposal: two domes, or one dome whose dome-flip
  is a long-press.
- **Angular ramp.** Should a long hold accelerate (stamp delta grows with
  pump count, Melt-style) or stay linear (Liquify-style)? Linear first;
  acceleration is a follow-up if users ask for tighter cores.
