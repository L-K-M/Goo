# 0001 — Displacement-field warp engine on GLES 3.0

- **Status:** accepted
- **Date:** 2026-08-04

## Context

The core of the app is real-time "liquid" warping of photos with a finger,
at 60fps on mid-range devices, with full-resolution export that matches the
preview. Candidate engines: CPU `Canvas.drawBitmapMesh` (simple, but
per-vertex falloff, fold-over artifacts, CPU-bound at dense grids), AGSL
`RuntimeShader` (API 33+ only — excludes minSdk 26, and multi-pass
accumulation is awkward), Vulkan (no benefit for one fullscreen pass,
highest driver risk), or an OpenGL ES fragment-shader displacement field.

## Decision

A single persistent backward-mapped displacement field (`color(p) = src(p +
D(p))`) on GLES 3.0: brushes stamp falloff-weighted kernels into a
ping-pong RG16F field texture; rendering is one scissored stamp pass plus
one fullscreen warp pass. History is a stroke log, not bitmaps; exports and
undo replay the log. This is the architecture Photoshop Liquify uses
(edit-small, replay-on-full-res), and GLES 3.0 is effectively universal on
API 26+ hardware.

## Consequences

- Preview/export parity is structural (same shader, same field) rather than
  approximated — but demands brush geometry in normalized source
  coordinates everywhere.
- GPU state is disposable; the stroke log must be the single source of
  truth (context loss, undo, animation all depend on it).
- A packed-RGBA8 fallback path is needed for the rare device without
  renderable half-float.
- Keyframe animation and movie export come almost free (field lerp +
  encoder surface), which is why they are on the roadmap at all.
- Revisit if: minSdk ever rises past 33 (AGSL becomes viable for display
  paths) or profiling shows the two-pass loop can't hold 60fps on target
  hardware (unlikely per research).
