# 0005 — Kaleidoscope symmetry

- **Status:** proposed
- **Date:** 2026-08-07

Consolidates two independently written proposals for the same feature
(PRs #53 and #61). Where they disagreed the better argument won, and
both disagreements are recorded below rather than quietly resolved —
including one factual error in a cost argument.

## The tool

The Mirror toggle grows a dial: **×1** (off), **×2** (today's mirror),
then **×3, ×4, ×6, ×8, ×12**. Every stroke fans out into N transformed
copies around the image center — smear once and six mirrored tendrils
race each other; pump Grow and a mandala blooms; run Melt at ×8 and the
photo drips like a rotating spice rack. ×2 is exactly the Mirror people
have today, so the feature is a superset of a control they already
understand.

The pleasure is that it is out of your control in a good way: you supply
one gesture and the tool supplies the composition.

## Why it belongs in Meltorama 2000

**1. Highest fun-per-gesture ratio available.** The whole promise of
funware is that someone who cannot draw makes something they are pleased
with, and symmetry is the oldest and best trick for it — it converts a
mess into a pattern with no skill and no undo. It is the rare feature
that makes *bad* strokes look deliberate: wobble a finger at ×8 and the
result still reads as design. Nothing else in the folder turns a single
drag into a finished-looking image.

**2. Nobody ships a *liquid* kaleidoscope.** The kaleidoscope predates
photography by half a century and Photo Booth's version has eaten more
afternoons than most video games — but every existing one is a static
filter tiling what the camera sees. Liquid warping and N-way symmetry
almost never live in the same app. We are the exception, and the
combination is the point: symmetry turns every brush we have into a
pattern engine. One Smear becomes a six-armed starfish; one Vortex
(proposal 0001) becomes a pinwheel; portraits become Rorschach tests.

**3. The hook already exists and is already the right shape.**
`BrushTool.mirrorStamp` implements today's Mirror by *transforming
stamps* — reflect the center, flip the delta's x, done — and it already
encodes the distinction that matters. Kaleidoscope is the same call site
with a bigger family: pure Kotlin in `engine/core`, unit-testable, **no
shader work at all**.

**4. It is the app's best animation, free.** A GOOvie of a mandala
assembling itself, or of kaleidoscope smears, is the hypnotic demo clip
the store listing does not have — and it needs no new export machinery.

## How it fits the engine

- The resampler emits N copies of each stamp under the **dihedral group
  of order N** — rotations of 2π/N with alternating reflections, not
  bare rotations, which is what makes it a kaleidoscope rather than a
  pinwheel. Computed in **aspect space** so sectors are true wedges on a
  non-square image.
- Directional deltas rotate (and reflect) with the copy; radial and
  field modes only move their centers — exactly the rule `mirrorStamp`
  already encodes, surviving the generalization unchanged.
- Each copy is an ordinary stamp. **No shader change, no field change,
  no log-format change**: undo, redo, full-res export replay, project
  persistence and GOOvie endpoint caches all see plain stamps.
- `Stroke` records the sector count (default 1 = today's behaviour), so
  a stroke replays with the symmetry it was painted with even after the
  dial moves. The log stays the single source of truth.
- Twins that land outside the frame are fine and should be kept: an
  off-canvas center still contributes to in-canvas texels within its
  radius, which is what makes strokes near the border blend rather than
  stop dead.

## Cost, risks, honest trade-offs

- **The stroke log grows N-fold, and so does every replay that reads
  it.** This is the whole cost and it is not small. REVIEW G-6 records a
  five-second pumped hold as ~300 field passes; at ×12 that is 3600,
  paid by undo, export, keyframe materialization and project load alike.

  PR #53 argued the cost was bounded because "the stamp pass already
  scissored per stamp". **It is not.** REVIEW G-3 says the opposite —
  the stamp pass "renders a fullscreen quad per stamp at field
  resolution", and the scissored sub-quad is the known optimization
  that has *not* been done (status: open). So the per-copy cost is a
  full field-resolution pass, and the honest conclusion is the
  cautious one: cap N, treat G-6's field-snapshot checkpoints as a
  **prerequisite for the high end of the dial** rather than a
  follow-up, and ship ×3 and ×6 before ×8 and ×12. Shipping ×12 with
  neither G-3 nor G-6 done is not reasonable.

  G-3 is also worth reconsidering on its own merits here: symmetry is
  exactly the workload that would make scissoring pay, since N small
  stamps scattered around a frame is the case where a fullscreen quad
  per stamp is most wasteful.
- **Pumped tools multiply worst.** A held Grow at ×12 emits 720
  stamps/second into the log. Either the pump cadence backs off with N,
  or the high detents are restricted to non-pumped tools — the first is
  less surprising, the second is less code. Decide deliberately.
- **The center is an N-fold singularity.** All arms meet at the image
  center, where every rotation lands on top of every other, so strokes
  through the middle pile displacement up N-fold and can fold the field.
  `BrushFalloff.centerRamp` — written for exactly this class of problem
  — is the obvious mitigation, and should be tested for rather than
  assumed.
- **Aspect space is the likeliest bug.** Rotating in UV space instead
  produces elliptical mandalas on every landscape photo. The golden test
  writes itself: N arms on a 16:9 image must be congruent.

## Open questions

- **Fixed center.** All sectors pivot on the image center for v1;
  drag-to-place centers are a follow-up (they compose with Freeze in
  delightful ways, but the dial is already enough UI).
- **Which N.** ×2/3/4/6/8/12 covers the satisfying set, and odd counts
  are the sleeper hits (×5 starfish). A cycling dome keeps the rail
  calm — see K3-1 on rail overflow at 360 dp.
- **Sector guides.** A faint wedge outline while the dial is ≠ ×1 helps
  aim. Preview chrome only, never exported.

## Declined

- **Symmetry as a post-effect on the whole field rather than per
  stamp.** Cheaper at paint time and wrong at document time: it would
  not be in the stroke log, so it could not be undone stroke by stroke,
  could not vary between keyframes, and would need its own export path.
  Per-stamp keeps the invariant that the stroke log is the document.

## Relationship to the backlog

This supersedes ANALYSIS **K3-23** ("Horizontal plus quad mirror:
generalize the mode, show the mirror axis, and preview ghost cursor
rings"). K3-23's ×2-and-×4 scope is the first two detents of this dial,
and its axis overlay and ghost rings are the same UI work. They should
be built as one piece rather than two.
