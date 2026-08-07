# Tool idea 0007 — Mandala (Mirror, turned up)

> **Status: proposal.** No engine code ships with this document. If the
> idea is taken, it becomes its own implementation PR with a roadmap row
> in PLAN.md, and it should be merged into ANALYSIS K3-23's scope rather
> than competing with it. Ideas live in `docs/ideas/`; decisions live in
> `docs/decisions/`.

**One-liner.** The Mirror toggle becomes a dial: off, ↔, ✛, then 3, 6, 8
or 12-fold rotational symmetry — so one careless squiggle becomes a
snowflake and any photo becomes a kaleidoscope.

## The feel

Set the dial to 6 and drag once. Six identical strokes appear at once,
rotated around the middle of the picture, and they meet in the center in
a way you did not plan and could not have drawn. Keep going and the
thing assembles itself: a rosette, a snowflake, a mandala, a spirograph
made of someone's face.

The pleasure is specifically that it is *out of your control in a good
way*. You supply one gesture; the tool supplies the composition.

## Why Meltorama should have it

**1. Highest fun-per-gesture ratio available.** The entire promise of
funware is that a person who cannot draw produces something they are
pleased with. Symmetry is the oldest trick for that and still the best:
it converts a mess into a pattern with no skill and no undo. Nothing
else on the roadmap turns one drag into a finished-looking image.

**2. The hook already exists and is already the right shape.**
`BrushTool.mirrorStamp` is a pure function that takes a stamp and
returns its twin, with the correct distinction already written into it
("Directional deltas flip their x; radial/field modes carry no
direction, so only the center moves"). N-fold symmetry is the same
function with a rotation instead of a reflection, applied k−1 times.
Pure Kotlin, in `engine/core`, unit-testable, **no shader work at all**.

**3. It is the app's best animation, for free.** A GOOvie of a mandala
assembling itself — six arms growing together, punch by punch — is the
single most shareable thing this app could export, and it needs no new
export machinery. The Wobbulator proposal and this one together would
produce a breathing kaleidoscope from about four gestures.

**4. It generalizes a control the user already understands.** Mirror is
a toggle on the rail today. K3-23 already wants it generalized
(horizontal plus quad mirror, with the axis shown). This proposal is
that item with a bigger ceiling — same UI, same axis overlay, same ghost
cursor rings, one more detent range on the dial. It should be built as
one piece of work, not two.

## How it works in this engine

Today's twin generation is one call producing one extra stamp. It
becomes a small pure function producing k−1 extra stamps:

```kotlin
/**
 * The stamp's twins under [symmetry]-fold rotation about the image
 * center. Rotation happens in ASPECT space (the same space brush
 * circles are round in, and the space GlobalField's twirl rotates in);
 * the x conversion is applied on the way in and divided out on the way
 * back, or a non-square photo would shear.
 */
fun rotatedStamps(s: Stamp, aspect: Float, symmetry: Int): List<Stamp>
```

For each j in 1 until k, rotate the stamp's aspect-space offset from the
center by 2πj/k, and — for DIRECTIONAL modes only — rotate the delta by
the same angle. Radial and field modes (Grow, Shrink, Smooth, UnGoo,
Fuse) carry no direction, so only the center moves; that is exactly the
rule `mirrorStamp` already encodes, and it survives the generalization
unchanged.

Everything downstream is untouched. The stamps join the same stroke, go
into the same log, replay the same way, export at full resolution the
same way, and tween in GOOvies the same way. There is no new stamp mode,
no new uniform, no new field channel, no new serialization.

**Twins that land outside the frame.** For k other than 2, rotating a
stamp near an edge can put a twin's center outside the image. That is
fine and should be kept: an off-canvas center still contributes to
in-canvas texels within its radius, which is exactly the behaviour that
makes strokes near the border blend rather than stop dead at it.

## Cost, risks, honest trade-offs

- **The stroke log grows k×, and so does every replay that reads it.**
  This is the whole cost of the feature and it is not small. REVIEW G-6
  already records that a five-second pumped hold is ~300 field passes;
  at k=12 that is 3600. Undo, export, keyframe materialization and
  project load all pay it. The honest conclusion: **cap k**, and treat
  G-6's field-snapshot checkpoints as a prerequisite for the high end of
  the dial rather than a follow-up. Shipping 3 and 6 before 8 and 12 is
  a reasonable compromise; shipping 12 with no checkpoints is not.
- **Pumped tools multiply worst.** A held Grow at k=12 emits 720
  stamps/second into the log. Either the pump cadence backs off with k,
  or the dial's high end is restricted to non-pumped tools — the first
  is less surprising, the second is less code. Decide deliberately.
- **The center is a singularity.** All k arms meet at the image center,
  where every rotation lands on top of every other. Strokes through the
  middle will pile displacement up k-fold and can fold the field. A
  center ramp like the one radial modes already use
  (`BrushFalloff.centerRamp`, written for exactly this class of problem)
  is the obvious mitigation and should be tested for, not assumed.
- **Non-square photos look wrong at high k if the math is careless.**
  Rotation must happen in aspect space. Getting this wrong produces
  ellipse-shaped mandalas on landscape photos and is the single most
  likely bug in the implementation; the golden test writes itself
  (k arms on a 16:9 image must be congruent).

## Declined variants

- **Symmetry about the touch point instead of the image center.**
  Kaleidoscopes have a center and it is the middle of the picture. A
  movable origin is a second control for a less legible result.
- **Reflective N-fold (mirror *and* rotate, true kaleidoscope).** The
  prettiest version, and it doubles the stamp count again (2k twins).
  Worth revisiting after the checkpoint work, as a further detent.
- **Symmetry as a post-effect on the whole field rather than per
  stamp.** Cheaper at paint time and wrong at document time: it would
  not be in the stroke log, so it could not be undone stroke-by-stroke,
  could not vary between keyframes, and would need its own export path.
  Per-stamp keeps the invariant that the stroke log is the document.
