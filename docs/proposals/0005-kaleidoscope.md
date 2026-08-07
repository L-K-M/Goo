# 0005 — Kaleidoscope symmetry

- **Status:** proposed
- **Date:** 2026-08-07

## The tool

The Mirror toggle grows a dial: **×2** (today's mirror), **×3, ×4, ×6,
×8, ×12**. Every stroke fans out into N transformed copies around the
image center — smear once and six mirrored tendrils race each other;
pump Grow and a mandala blooms; run Melt at ×8 and the photo drips like a
rotating spice rack. ×1 is off, ×2 is exactly the Mirror users have
today, so the feature is a superset of a control people already
understand.

## Why it belongs in Meltorama 2000

Symmetry is the oldest cheap-thrill multiplier in image toys — the
kaleidoscope predates photography by half a century, and Apple Photo
Booth's Kaleidoscope has eaten more afternoons than most video games.
But every existing kaleidoscope is a *static filter*: it tiles what the
camera sees. Nobody ships a *liquid* kaleidoscope, because liquid warping
and N-way symmetry almost never live in the same app.

We are the exception, and the combination is the point: symmetry turns
every brush we have into a pattern engine. One Smear stroke becomes a
six-armed starfish; one Vortex (proposal 0001) becomes a pinwheel;
portraits become Rorschach tests; a GOOvie of kaleidoscope smears is the
hypnotic demo clip the app's store listing doesn't have yet. It is also
the rare feature that makes *bad* strokes look deliberate — wobble a
finger at ×8 and the result still reads as design.

## How it fits the engine

The entire feature already has its precedent in the codebase:
`BrushTool.mirrorStamp` implements today's Mirror by *transforming
stamps* — reflect the center, flip the delta's x, done. Kaleidoscope is
the same call site with a bigger family:

- The resampler emits N copies of each stamp under the dihedral group of
  order N (rotations of 2π/N, alternating reflections), computed in
  aspect space so sectors are true wedges on a non-square image.
  Directional deltas rotate with the copy; radial and field modes only
  move their centers — exactly the rule `mirrorStamp` already encodes.
- Each copy is an ordinary stamp. **No shader change, no field change,
  no log-format change**: undo, redo, full-res export replay, project
  persistence, and GOOvie endpoint caches all see plain stamps.
- `Stroke` records the sector count (default 1 = today's behavior), so a
  stroke replays with the symmetry it was painted with even after the
  dial moves — the log stays the single source of truth.
- Cost is bounded: stamps per stroke multiply by N ≤ 12, and stamps are
  kilobyte-scale; the stamp pass already scissored per stamp, so worst
  case is a few more scissored quads on the GPU.

Cost: one resampler fan-out, one serializable int, one dial in the rail.
No GL diff. No new tests infrastructure — the existing
resampler/mirror tests are the template.

## Open questions

- **Fixed center.** All sectors pivot on the image center for v1;
  drag-to-place centers are a follow-up (they compose with Freeze in
  delightful ways, but the dial is already enough UI).
- **Which N.** ×2/3/4/6/8/12 covers the satisfying set; odd counts are
  the sleeper hits (×5 starfish). A cycling dome keeps the rail calm.
- **Sector guides.** A faint wedge outline while the dial is ≠ ×1 helps
  aim; it's preview chrome only, never exported.
