# Tool idea 0001 — Vortex (and Unwind)

> **Status: proposal.** No engine code ships with this document. If the
> idea is taken, it becomes its own implementation PR with a roadmap row
> in PLAN.md and the constants pinned in `BrushDynamics`. Ideas live in
> `docs/ideas/`; decisions live in `docs/decisions/`.

**One-liner.** Hold a finger on the picture and everything under it
turns — a whirlpool you can put anywhere, the way Grow is a magnifier
you can put anywhere.

## The feel

Vortex is a pumped tool, like Grow and Shrink: it applies while the
finger is down, at the touch point, on the 16 ms pump clock. A tap gives
a quarter turn of a small area. A one-second hold winds a face into a
liquorice swirl. Because pumped stamps compound warp-of-warp, the spiral
tightens the longer you stay — the same "keep pressing and it keeps
going" feel the 1996 original had, applied to rotation.

Unwind is the same tool with the sign flipped, exactly as Shrink is
Grow flipped. The name is not invented: the KPT Goo global-effects
palette shipped **Twirl, Rotate and Unwind** side by side.

## Why Meltorama should have it

**1. It is the one classic gesture the palette is missing.** Meltorama
has a global Twirl *lever* — it spins the whole frame around the
image's center, and only around the image's center. It has Grow and
Shrink, which are radial and local. It has nothing that is rotational
and local. Every ancestor of this app had one: KPT Goo put Twirl in the
global palette and SuperGoo's marketing led with "distort, bulge, smear
or **twirl** portions of the image"; Photoshop's Liquify has shipped a
Twirl Clockwise brush for two decades. A user who has twirled a photo in
anything else will look for it here, find a lever that only spins the
middle, and conclude the brush rail is incomplete. It is.

**2. Rotation is the warp that reads best on a face.** Bulge and pinch
are grotesque in a generic way; a spiral is *legible* — the eye follows
it. Put a small vortex on an iris and you get a hypnotist's spiral; a
large slow one on a whole head gives the melted-photograph look that
sells the app in a screenshot. Meltorama needs screenshots.

**3. It composes with everything already there.** Mirror gives you twin
counter-rotating whirlpools in one gesture. Smooth relaxes an
over-wound spiral instead of forcing an undo. UnGoo erases one. Punch
two keyframes around a hold and the GOOvie is a photo going down a
drain. None of that needs new code — it falls out of the field model.

**4. It is a one-row tool.** `BrushTool` is documented as "(mode,
falloff, strength-scale, cadence) rows; the engine has no per-tool code
paths beyond these parameters". Vortex is two rows and one shader
branch. The cheapest interesting thing left on the table.

## How it works in this engine

A new `StampMode.SWIRL` (shaderId 6) beside INFLATE/DEFLATE. The radial
branch in `STAMP_FRAG` already builds everything needed:

```glsl
// existing, INFLATE/DEFLATE:
float m = w * centerRamp(d) * 0.004;
vec2 outward = vec2((fromCenter.x / distA) / u_aspect, fromCenter.y / distA);

// SWIRL: the same unit vector, turned a right angle in ASPECT space
// (rotate first, divide the aspect out second — rotating the UV-space
// vector would shear on non-square images).
vec2 t = vec2(-fromCenter.y / distA, fromCenter.x / distA);
vec2 tangent = vec2(t.x / u_aspect, t.y);
b = sign * tangent * w * centerRamp(d) * SWIRL_STEP_UV;
```

`centerRamp` exists for precisely this class of singularity — the
tangent is undefined at the exact center — so the ramp that was written
for Grow/Shrink is reused unchanged. `BrushFalloff` needs nothing new.
The CPU reference (`DisplacementField.applyStamp`) gets the mirror-image
five lines, which is where the tests go.

`BrushTool` gains:

```kotlin
VORTEX(StampMode.SWIRL, FalloffProfile.SMOOTHSTEP,  1f, pumped = true),
UNWIND(StampMode.SWIRL, FalloffProfile.SMOOTHSTEP, -1f, pumped = true),
```

…if `strengthScale` is allowed to go negative. Today every scale is
positive and the sign lives in the mode (INFLATE vs DEFLATE), so the
honest options are a negative scale (one-line change, slightly sneaky)
or `SWIRL_CW`/`SWIRL_CCW` as two shader ids (dumber, matches how
Grow/Shrink already do it). Prefer the second: it keeps "the sign is a
mode" as an invariant rather than making it true in two different ways.

One new constant beside `RADIAL_STEP_UV`:

```kotlin
/** Tangential UV displacement per pumped swirl stamp at strength 1. */
const val SWIRL_STEP_UV = 0.0035f
```

Sized so a one-second hold at full strength is roughly a half turn at
the rim of the brush — fast enough to be fun on the first try, slow
enough that the wind-up is visible.

## Cost, risks, honest trade-offs

- **Shear, not stretch.** Rotation puts far more gradient into the
  field than translation does, and the field is sampled bilinearly at
  ≤1024². Wound hard enough, the innermost ring aliases into visible
  stair-stepping before the picture looks wrong. Cap `SWIRL_STEP_UV` and
  say so; a "wind it until it breaks" tool is a bug report generator.
- **It feeds REVIEW G-6.** Another pumped tool means more 60-stamps-a-
  second holds inflating the stroke log and every replay that reads it.
  Vortex does not make G-6 worse per stamp, but it is one more reason
  the field-snapshot checkpoint work eventually has to happen.
- **Test surface.** Two golden properties are worth pinning: two stamps
  of angle θ compose to approximately one stamp of 2θ near the center
  (warp-of-warp is not exactly additive, so "approximately" needs a
  tolerance chosen honestly), and Vortex followed by Unwind of equal
  hold returns the field to within ε of identity. The second is the one
  users will try first.

## Declined variants

- **Vortex that also sucks (spin + inward pull, one dome).** Real
  whirlpools do both, and a blend parameter would be lovely — but there
  is nowhere to put a third continuous control on the rail, and the
  palette already composes: hold Vortex, then hold Shrink in the same
  spot. Two gestures for a compound effect is the KPT bargain.
- **Direction from the drag.** Circling clockwise winds clockwise is a
  charming idea and an unpredictable one: pumped tools stamp at a point,
  so the app would be inferring intent from jitter. Two beads on the
  rail is boring and always right.

## Open question

Rotation about the *touch point* is what Liquify does and what this
proposal describes. KPT's Rotate was about the image center. If the
Mirror-axis work (ANALYSIS K3-23) ever puts a visible axis marker on
screen, a vortex anchored to that axis instead of the finger becomes
possible and is a different, more architectural tool. Not this one.
