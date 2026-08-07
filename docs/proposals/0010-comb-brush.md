# 0010 — Comb brush

- **Status:** accepted — built as the second brush palette (roadmap #20)
- **Date:** 2026-08-07

**One-liner.** A Smear with teeth: one drag lays down a dozen parallel
strands instead of one smooth push, so a photo can be combed into hair,
fur, fire, feathers or a Van Gogh sky.

## The feel

Pick Comb, set a fat brush, drag once across a head. Where Smear would
have pushed the whole area sideways as a lump, Comb leaves *filaments* —
strands that moved by different amounts, alternating, evenly spaced
across the brush. Drag a curve and the strands fan the way hair does
around a parting. Drag over an evening sky and it turns into paint
strokes. Drag upward out of a candle flame and the flame gets longer and
stringier.

The teeth belong to the brush, not the picture: widen the brush and you
get wider strands, not more of them. So the tool looks the same at every
size, and it looks the same in the export as in the preview.

## Why Meltorama should have it

**1. Everything in the palette moves a mass; nothing makes a texture.**
Smear, Move, Smudge, Nudge push a lump around. Grow and Shrink change a
lump's size. Smooth and UnGoo take lumps away. The result is that every
Meltorama picture, at a distance, has the same visual signature: soft
blobs. Comb introduces *structure* — high-frequency, directional, hand-
placed detail — which is a different kind of picture entirely and one
the engine can already make.

**2. It is what people actually do with a warp tool after five
minutes.** Ballooning an eye is the first thing anyone tries and the
first thing anyone gets bored of. Making a photo look painted, or making
someone's hair enormous, is what keeps them. Comb is the tool for the
second five minutes.

**3. It photographs well.** The README, the store listing and the
onboarding hint all need one image that says what this app does. A
combed portrait says it instantly and does not look like every other
warp app's before-and-after.

**4. Nobody else has it.** Liquify has no comb. KPT Goo had no comb. The
closest relative is Liquify's Turbulence, which scrambles rather than
organizes. This is the proposal in this folder with the best claim to
being genuinely new rather than a gap being filled — and it is still
only a few lines, because the engine's kernels are just weights.

## How it works in this engine

Comb is the DIRECTIONAL branch with the weight modulated along the axis
*across* the drag. The drag direction is already in the shader as
`u_delta`; the cross-axis coordinate of the current texel is its
projection onto the perpendicular:

```glsl
// existing DIRECTIONAL: b = -u_delta * w;
// COMB: same, with teeth cut across the stroke.
vec2 dir = normalize(vec2(u_delta.x * u_aspect, u_delta.y));
vec2 across = vec2(-dir.y, dir.x);
float s = dot(fromCenter, across) / u_radius;   // -1..1 across the brush
float teeth = 0.5 + 0.5 * cos(6.2831853 * s * COMB_TEETH);
b = -u_delta * w * teeth;
```

`fromCenter` is already in aspect space at that point in the shader, so
the projection is round in pixels for free — the same reason brush
circles are round.

`COMB_TEETH` is teeth per brush *radius*, not per image, which is what
makes the tool resolution-independent: the strand pattern is a function
of normalized brush geometry, so the full-resolution export replay
produces the same strands as the preview, which is decision 4 in
PLAN.md §5 ("brush geometry is computed in normalized source
coordinates") applied to a texture rather than a displacement.

`BrushTool` gains one row — `COMB(StampMode.COMB, SMOOTHSTEP, 1f,
pumped = false)` — and `DisplacementField.applyStamp` gains the mirror-
image three lines that the tests pin.

**Why the strands stay continuous along a drag.** The modulation is a
function of position relative to the stroke's own axis, not of stamp
index, so consecutive stamps along a straight drag agree on where the
teeth fall and the strands run unbroken. Where the drag curves, the axis
rotates and the strands fan — which is not a defect to be corrected; it
is what a comb through hair does, and it is free.

## Cost, risks, honest trade-offs

- **The field resolution is the real constraint, and it decides the
  tooth count.** The displacement field is ≤1024² and bilinearly
  sampled. A tooth narrower than about three field texels will alias
  into a shimmer before it reads as a strand. With teeth specified per
  brush radius, the finest legal tooth count is set by the *smallest*
  usable brush, and that number has to be measured, not guessed. It is
  also the honest reason to ship one fixed tooth count rather than a
  third lever: the safe range is narrow.
- **A tap does nothing.** With no drag there is no axis, so Comb has no
  meaning on touch-down and should keep `stampsOnDown = false` (the
  default for non-pumped tools, so this is already right — but SOL-9
  exists because that distinction was got wrong once).
- **Very slow drags stack teeth on teeth.** Stamps are spaced at ~25% of
  brush radius, so a slow drag lays several stamps whose teeth align and
  compound — deepening the strands rather than blurring them, which is
  probably desirable but should be looked at before the constant is
  fixed.
- **It interacts oddly with Mirror.** `mirrorStamp` flips `dx` for
  directional modes, which flips the axis and therefore the teeth phase.
  The mirrored strands will be mirror-symmetric, which is correct, and
  worth a golden test rather than an assumption.

## Declined variants

- **Teeth per image instead of per brush.** Makes the comb a property of
  the photo rather than of the tool; changing brush size would change
  the number of strands, and the preview/export parity argument would
  need a different justification for no benefit.
- **A tooth-count lever.** The usable range is roughly one octave (see
  above), and the rail has two levers already. If it is wanted later it
  is a detented three-position dial, not a continuous control.
- **Randomized tooth positions ("hair, but messier").** Needs a seed in
  the stroke to stay replayable — doable, cf. the Drip proposal's
  `u_dripSeed` — but the regular comb is the legible one, and irregular
  is one line away once someone wants it.
- **A radial comb (teeth around the brush, not across it).** That is a
  sunburst, and it is a different and also good tool. Not this one.
