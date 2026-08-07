# 0003 — Melt brush

- **Status:** accepted — built as the second brush palette (roadmap #20)
- **Date:** 2026-08-07

Consolidates two independently written proposals for the same tool
(PRs #51 and #55). Where they disagreed the better argument won, and
both disagreements are recorded below rather than quietly resolved.

## The tool

Hold a finger under a chin, an ice-cream cone, a candle — and it starts
to run. Content beneath the brush sags downward, slowly at first and
faster the longer you hold, until it pulls away into a drip with a tail.
Drips wander slightly column to column as they fall, so they separate
into fingers like real wax instead of sliding as one rubber sheet.

You do not drag it, you *hold* it: down is down regardless of how you
swiped, so the gesture is "put the melt here" and time does the work —
the same division of labour as Grow. Dragging while holding paints a
whole row of drips, a skyline melting off the bottom of the frame.

## Why it belongs in Meltorama 2000

**1. The name promises it.** The app was renamed Meltorama 2000 in
PR 14 and the claim is *Goo Your Photos*. There are nine brushes and six
levers and not one of them melts anything. Every first-time user tries;
the name is a promise. This is the rare feature that is simultaneously
the brand, the joke and the demo.

**2. It is the most legible warp there is.** A vortex needs a second of
"what is it doing?" — dripping needs none, because gravity does the
storytelling. The cultural reference is universal (Dalí's clocks, candle
wax, slime, the melted-face genre that sustains whole app-store
categories) and it is funny on everything: portraits, food, cars,
architecture, text.

**3. It is the only tool with a direction of its own.** Everything else
is radially symmetric (Grow, Shrink, Smooth, UnGoo) or takes its
direction from the finger (Smear, Move, Smudge, Nudge). Melt takes it
from the *picture*. That is a genuinely new axis in the palette, and the
one every metaphor about weight and sagging lives on.

**4. It makes the best GOOvie the app can make.** Punch a frame, hold
Melt for two seconds, punch another: the export is a photo liquefying.
GOOvies are the most expensive feature in the app and the hardest to
explain; this is the demo that explains them, produced by holding one
finger down. Combined with Freeze (proposal 0002), "solid head, melting
body" is a two-minute masterpiece.

## How it fits the engine

The notable thing about Melt is what it is **not**: not a new stamp
mode, not a shader change, not a field-format change. This is #51's
design and it is decisively the better one — #55 proposed a dedicated
`StampMode.DRIP` plus a seed uniform to do what the existing directional
path already does.

- Melt emits ordinary `DIRECTIONAL` stamps whose recorded deltas point
  down and grow with the pump count: `delta_k = min(k · DRIP_STEP,
  DRIP_MAX)`. The stroke log already stores every stamp's delta, so
  replay, undo, full-res export and project reload reproduce a melt
  exactly — acceleration included — with no new machinery and no
  accumulator anywhere.
- The column wander reuses `GlobalField.valueNoise` and its integer LCG
  hash, which exist because the Static lever needed bit-identical
  results on both sides (SOL-41 pinned the wire contract). The resampler
  jitters each stamp's downward delta by `noise(column, k, seed)` with
  the seed **recorded on the stroke**, so two replays of one document
  produce the same icicles. Constants reach the shader the way every
  other one does — hand-mirrored as float literals with sync markers,
  never injected; core GLSL ES 3.00 has no implicit `int` → `float`, so
  the literal carries its own `.0`, exactly as `WARP_FRAG` already
  writes `uv.x * 24.0` against an Int `STATIC_CELLS`.
- Everything else composes for free: Mirror (a mirrored melt still falls
  down — the `dx` flip leaves the vertical delta untouched, which is
  correct), Freeze (frozen regions simply do not drip), GOOvie tween,
  Fusion masks.

**Which way is down, and why every sign here depends on it.** Stamp-space
UV has a **top-left origin** — `Stroke.kt`'s coordinate block says so,
and `QUAD_VERT` is deliberately unflipped so that "field texel v ≡ image
UV v". So down is **+v**, `fromCenter.y > 0` is *below* the brush
center, and the recorded delta points to +v. The shader's existing
`b = -u_delta * w` then samples from higher up, which under backward
mapping is what makes content appear lower. Only `WARP_VERT`, which maps
to the y-up window, flips, and it is not in this path.

Because the field is document space and the view transform lives only in
`WARP_VERT`'s `u_view` — with export and movie passes binding identity
there — a melt painted with the canvas rotated 90° still runs toward the
bottom of the *photo* on replay. That is both the correct behaviour and
the free one.

**The falloff is the one genuinely new piece.** A radially symmetric
weight gives a symmetric sag: content above the touch point rises as
much as content below falls, which reads as a lens, not a leak. Melt
wants a lobe that reaches *downward*, so the distance metric is scaled
anisotropically before the existing smoothstep:

```glsl
// Above center: unchanged. Below: distance compressed, so the same
// weight is reached further down — the lobe extends to ~2.2 radii
// below center and the falloff is gentler all the way.
float dy = fromCenter.y < 0.0 ? fromCenter.y : fromCenter.y * 0.45;
float distA = length(vec2(fromCenter.x, dy));
```

The direction of that factor *is* the tool. **Below 1 reaches down;
above 1 reaches up**, giving a brush that pulls harder above the finger
than below it — gravity backwards. Worth stating because it is
counterintuitive: to make a brush act *further* in a direction you
shrink the distance it measures there, since weight decreases with
distance.

That is a new `FalloffProfile.DRIP` — one more row in the table that
FEATHER and PLATEAU already made. It is also the engine's first
non-radial kernel: `BrushFalloff.weight(d, profile)` is currently a
function of one scalar and this needs the vector, so the smallest honest
change is a second entry point, `weightAniso(fromCenter, radius,
profile)`, leaving the scalar function unchanged and still the reference
for everything else.

## Cost, risks, honest trade-offs

- **Long holds are REVIEW G-6's worst case.** Melt's whole point is
  holding: a ten-second melt is ~600 stamps that every undo, export and
  keyframe materialization replays. Melt is the strongest argument yet
  for the field-snapshot checkpoints G-6 describes, and probably should
  not ship before them.
- **`DRIP_MAX` must stay in the small-delta regime.** Warp-of-warp
  assumes stamps are small; the resampler can subdivide large deltas the
  way it already subdivides long drags.
- **It tears at the top.** Pulling content down while the content above
  stays put is a discontinuity by construction. The taper softens it but
  a hard hold at high strength will show a seam where the melt begins —
  arguably correct (wax has an edge where it left), and Smooth cleans it
  up, which is a nice discoverable pairing.
- **The bottom edge.** A run near the frame bottom samples past the
  image and clamps, smearing the last row into a vertical streak. Worth
  confirming `GL_CLAMP_TO_EDGE` is what is bound, and worth deciding
  deliberately whether that reads as melting (it does, a bit) or as a
  bug (it also does, a bit).
- **Not a true teardrop.** This is an elongated lobe; a real drop also
  narrows in x as it descends, which needs the x scale to depend on y.
  Worth trying at implementation time — the noise modulation on run
  length is already doing most of the work on the silhouette.

## Open questions

- **Pooling.** Real liquid pools at obstacles. Simulating containment is
  out of scope; Freeze (proposal 0002) gives the user a manual way to
  stop a drip line.
- **Sideways melt.** A gravity-direction lever is tempting and cheap —
  the delta is just a vector — but a rotated photo already gives you
  sideways. Ship down-only.

## Declined

- **Gravity from the accelerometer.** Enormously fun for ten seconds,
  and it makes the document depend on how the user was holding the
  phone, which no replay can reproduce. The engine's central promise is
  that the stroke log is the document. Declined on the same grounds as
  shake-to-reset.
- **Melt in the drag direction.** That is Smear with extra steps.
- **A whole-frame Melt lever instead of a brush.** It sags everything
  evenly, which just looks like a bad anamorphic squeeze. The
  interesting melt is a face that runs while the wall behind it stays.
