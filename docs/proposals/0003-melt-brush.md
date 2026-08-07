# 0003 — Melt brush

- **Status:** proposed
- **Date:** 2026-08-07

## The tool

Hold a finger under a chin, an ice-cream cone, a candle — and it starts
to run. Content beneath the brush sags downward, slowly at first, faster
the longer you hold, until it pulls away into a drip with a tail. Drips
wander slightly column-to-column as they fall, so they separate into
fingers like real wax instead of sliding as one rubber sheet.

It is a pumped tool: touch and hold, gravity does the rest. Dragging
while holding paints a whole row of drips — a skyline melting off the
bottom of the frame.

## Why it belongs in Meltorama 2000

The app is called **Meltorama**, and you currently cannot melt anything.
Every first-time user will try; the name is a promise. This is the rare
feature that is simultaneously the brand, the joke, and the demo.

Melt is also the most legible warp there is. A vortex needs a second of
"what is it doing?" — dripping needs none, because gravity does the
storytelling. The cultural reference is universal (Dalí's clocks,
candle wax, slime, the "melted face" genre that sustains entire
app-store apps by itself), and the result is funny on everything:
portraits, food, cars, architecture, text.

For GOOvies it is the perfect animation primitive: punch a frame, hold
Melt under something for two seconds, punch another — the export is a
photo that liquefies. Combined with Freeze (proposal 0002), "solid head,
melting body" is a two-minute masterpiece.

## How it fits the engine

The notable thing about Melt is what it is *not*: not a new stamp mode,
not a shader change, not a field-format change.

- Melt emits ordinary `DIRECTIONAL` stamps whose recorded deltas point
  down (+v) and grow with the pump count: `delta_k = min(k · DRIP_STEP,
  DRIP_MAX)`. The stroke log already stores every stamp's delta, so
  replay, undo, full-res export, and project reload reproduce a melt
  exactly — acceleration included — with zero new machinery.
- The column wander reuses the integer-hash value noise the Static lever
  already keeps bit-identical between CPU and GPU: the resampler jitters
  each stamp's downward delta by `noise(column, k, seed)` with the seed
  recorded on the stroke. Deterministic by construction, no driver drift,
  no transcendental hashes.
- A downward-tapered falloff (new `FalloffProfile.DRIP`: full weight
  below center, quick fade above) makes drips pull tails instead of
  moving as discs. This is one more row in the falloff table — the same
  shape of addition FEATHER and PLATEAU already made.
- Everything else — Mirror (a mirrored melt still falls down; `dx` flip
  leaves +v untouched, correct), Freeze (frozen regions simply don't
  drip), GOOvie tween, Fusion masks — composes for free.

Cost: one `BrushTool` row, one falloff profile, one resampler tweak,
one dome. The engine's "every tool is just a different kernel" promise,
kept.

## Open questions

- **How fast is too fast.** `DRIP_MAX` must stay inside the warp-of-warp
  small-delta regime (stamps are small by design); the resampler can
  subdivide large deltas the way it already subdivides long drags.
- **Sideways melt?** A gravity direction lever (down / left / right /
  up-as-anti-gravity) is tempting and cheap — the delta is just a vector.
  Proposal: ship down-only; a rotated photo already gives you sideways.
- **Pooling.** Real liquid pools at obstacles. Simulating containment is
  out of scope; Freeze gives the user a manual way to stop a drip line.
