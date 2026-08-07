# 0001 — Vortex brush

- **Status:** proposed
- **Date:** 2026-08-07

Consolidates two independently written proposals for the same tool
(PRs #49 and #54). Where they disagreed the better argument won, and
both disagreements are recorded below rather than quietly resolved.

## The tool

Hold a finger on the photo and everything under it starts orbiting, like
stirring coffee: cheeks curl into cheeks, hair curls into commas, a
straight smile winds itself into a cinnamon roll. It is a pumped tool on
the same clock as Grow and Shrink, so a still finger keeps winding the
spiral tighter — the KPT pump feel applied to rotation.

**Unwind** is the same tool the other way. The name is not invented: it
sat beside Twirl and Rotate in KPT Goo's own global-effects palette.

## Why it belongs in Meltorama 2000

**1. It is the one classic gesture the palette is missing.** There is a
magnifier you can put anywhere (Grow) and a pinch you can put anywhere
(Shrink), and no way at all to rotate *a place* — Twirl is a global
lever that spins the whole frame about the image center. KPT Goo's 1996
pitch listed twirl between two brushes we already have; SuperGoo's copy
led with "twirl portions of the image"; Liquify has shipped a Twirl
brush for two decades. A user who has twirled a photo anywhere else will
look for it here and conclude the rail is incomplete. It is.

**2. A spiral is the highest-signal warp there is.** Bulge and pinch are
grotesque in a generic way; a spiral is *legible* — the eye follows it.
Small on an iris it is a hypnotist's spiral, large and slow on a head it
is the melted-photograph look. This is a screenshot tool, and the app
needs screenshots.

**3. It composes with everything already there.** Smooth relaxes an
over-wound spiral instead of forcing an undo. UnGoo erases one. Punch
either side of a hold and the GOOvie is a photo stirring itself — the
demo clip the app does not have.

**4. It is a one-row tool.** `BrushTool` is documented as "(mode,
falloff, strength-scale, cadence) rows; the engine has no per-tool code
paths beyond these parameters". The cheapest interesting thing left on
the table.

## How it fits the engine

One new `StampMode.VORTEX` (shaderId 6) beside INFLATE/DEFLATE. The
radial branch in `STAMP_FRAG` already builds nearly everything:

```glsl
// existing, INFLATE/DEFLATE:
float m = w * centerRamp(d) * 0.004;
vec2 outward = vec2((fromCenter.x / distA) / u_aspect, fromCenter.y / distA);

// VORTEX: the same unit vector, turned a right angle in ASPECT space
// (rotate first, divide the aspect out second — rotating the UV-space
// vector would shear on non-square images).
vec2 t = vec2(-fromCenter.y / distA, fromCenter.x / distA);
// The divide converts whichever component is x IN ASPECT SPACE back to
// UV; it is a basis conversion, not a tag following the value that was
// there before the rotation. Same conversion as `outward` above.
vec2 tangent = vec2(t.x / u_aspect, t.y);
b = chirality * tangent * w * centerRamp(d) * SWIRL_STEP_UV;
```

`centerRamp` exists for precisely this class of singularity — the
tangent is undefined at the exact center — so the ramp written for
Grow/Shrink is reused unchanged, and `BrushFalloff` needs nothing new.
The CPU reference (`DisplacementField.applyStamp`) gets the mirror-image
lines, which is where the tests go.

**Chirality rides in the stamp's `dx` sign** — one mode, not two, and
not a negative `strengthScale`. This is #49's design and it is the
better one, because it makes a claim #54 got wrong come true:
`BrushTool.mirrorStamp` flips `dx` for directional modes and moves only
the center for radial ones, so under a two-mode design a mirrored vortex
would spin the *same* way as its twin. With the sign in the stamp, the
existing mirror flip *is* the chirality reversal, and Mirror produces
genuinely counter-rotating whirlpools for free. The `Stamp` wire format
is untouched.

One new constant beside `RADIAL_STEP_UV`:

```kotlin
/** Tangential UV displacement per pumped swirl stamp at strength 1. */
const val SWIRL_STEP_UV = 0.0035f
```

**This is a displacement, not an angle** — same units and same order as
the shipped `RADIAL_STEP_UV = 0.004f`, which is the check that it is
sized right. A fixed tangential step is an *arc length*, so the angle it
sweeps falls off with distance from the center: `Δθ ≈ m / r`. Rotation
is therefore differential rather than rigid, which is what makes it a
whirlpool and not a turntable. Worked at mid-radius of a 0.1 brush
(`r = 0.05`, `falloff(0.5) = 0.5`, `centerRamp = 1`): `Δθ ≈ 0.035` rad
per stamp, so a one-second hold at the 16 ms pump cadence is ~2.1 rad
≈ 120° before warp-of-warp compounding. Tighter near the center, looser
toward the rim, and exactly zero *at* the rim where the falloff is 0.

Undo, redo, full-res export replay, project persistence and GOOvie
endpoint caching are all free — a vortex stroke is ordinary stamps in
the log.

## Cost, risks, honest trade-offs

- **Shear, not stretch.** Rotation puts far more gradient into the field
  than translation does, and the field is bilinearly sampled at ≤1024².
  Wound hard enough the innermost ring aliases into stair-stepping
  before the picture looks wrong. Cap `SWIRL_STEP_UV` and say so; a
  "wind it until it breaks" tool is a bug-report generator.
- **It feeds REVIEW G-6.** One more pumped tool means more
  60-stamps-a-second holds inflating the log and every replay that reads
  it. Not worse per stamp than Grow, but one more reason the
  field-snapshot checkpoints eventually have to happen.
- **Two golden properties worth pinning.** Two stamps of angle θ compose
  to approximately one stamp of 2θ near the center — warp-of-warp is not
  exactly additive, so the tolerance has to be chosen honestly — and
  Vortex followed by Unwind of equal hold returns to within ε of
  identity. Users will try the second one first.

## Open questions

- **Chirality UX.** Two rail domes (Liquify-style) is the honest cut;
  one dome with a long-press flip keeps the rail calmer, and K3-1
  records the rail overflowing at 360 dp once already. Inferring
  direction from the drag is declined: pumped tools stamp at a point, so
  that is reading jitter as intent.
- **Angular ramp.** Should a long hold accelerate (delta growing with
  pump count, the way proposal 0003's Melt does) or stay linear? Linear
  first; acceleration is a follow-up if users ask for tighter cores.
- **Anchored vortex.** Rotation about the touch point is what this
  describes and what Liquify does; KPT's Rotate was about the image
  center. If the mirror-axis work (ANALYSIS K3-23, and proposal 0005)
  ever puts a visible axis on screen, a vortex anchored to it is a
  different and more architectural tool. Not this one.

## Declined

- **Vortex that also sucks (spin plus inward pull in one dome).** Real
  whirlpools do both and a blend parameter would be lovely, but there is
  nowhere to put a third continuous control, and the palette already
  composes: hold Vortex, then hold Shrink in the same spot.
- **Chirality via a negative `strengthScale`.** Today every scale is
  positive and sign lives in the mode or the stamp; a negative scale
  would make "where the sign lives" true in three different ways.
