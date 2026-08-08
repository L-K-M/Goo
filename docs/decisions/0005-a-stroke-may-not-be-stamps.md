# 0005 — A stroke may not be stamps

- **Status:** accepted
- **Date:** 2026-08-08

## Context

ADR 0004 prototyped the Taffy Pins solver and then deliberately stopped:

> At that point the decision to expand `Stroke` is still outstanding and
> still separate — this document deliberately does not make it.

This is that decision. The prototype answered what it could: the solver
interpolates its pins, stays finite at the singularities, agrees across
photo shapes, and runs at ~4.9 Mtexel/s on the CPU reference. It did not
answer the 60 fps question, and still has not — there is no GPU in the
build environment. Building on an unmet gate is a real cost and is
recorded in the consequences below rather than argued away.

ADR 0003 established that a stroke may **point at** something else. This
one establishes that a stroke may **be** something other than stamps.
They are independent: neither implied the other, and 0003 said so.

## Decision

`Stroke` gains a nullable `pinWarp`. A stroke carries **stamps XOR a pin
warp**, and the log enforces it in both directions.

**Two failures, opposite handling.** "No payload at all" is ordinary — a
drag too short to clear the resampler's spacing produces one every time —
so it is dropped in silence, exactly as an empty stamp list always was.
"Both payloads at once" is a contradiction with no defined replay order
for its two halves, so it is refused loudly. Collapsing these into one
predicate was the first version of this change and it silently swallowed
the second case; `hasContent` and `isCoherent` exist as separate
properties because they answer separate questions.

**A pin warp is one analytic full-field pass.** Not hundreds of Move
stamps approximating a constraint, which the proposal explicitly refuses,
and not a new pass primitive either: it goes through
`PingPongField.renderPassIn` with `TexelRect.full`, because that is
deliberately the only pass primitive and a parallel unscissored path
would drift out of step with its ping-pong invariant.

**The composition is the engine's existing rule.** `D'(x) = w(x) +
D(x + w(x))`, with `w(x) = rigidMls(x) − x`. Reading the old field at the
prewarped position is what makes a pull act on the picture as it
currently looks rather than on the original photo, and it carries the
Fusion mask and the varnish along for free because they ride the same
lookup.

**Replay branches in exactly one place.** `GlWarpRenderer.replayInto` is
the only code that tells the two edit shapes apart, and every replay path
— live commit, rebuild, keyframe materialization, still export, movie
export — goes through it. Five call sites each branching for themselves
is five chances for one to forget.

**The preview recomputes from a base, never from itself.** Entering Pins
snapshots the field; each drag event restores that snapshot and applies
the candidate. Accumulating instead would make a slow drag deform harder
than a fast one along the same path, and no tuning fixes that because it
is a property of the event stream rather than of the gesture.

## Consequences

- **The 60 fps gate is still unmet, and is now load-bearing.** ADR 0004
  could leave it open because nothing depended on it. Now the preview
  runs a full-field MLS pass per drag event on top of the warp pass. The
  bounded ten-control loop is the kind of work fragment shaders are good
  at, and the CPU measurement says the arithmetic is cheap, but neither
  is a frame time on a phone. **This wants profiling on real low-end GLES
  3 hardware before it is called finished.** If it does not hold, the
  proposal's own answer — a coarser control grid with interpolation, as
  the paper recommends — is unblocked and unchanged by this work.
- **The varnish does not brake a pin pull, and cannot.** Every other mode
  scales its weight by the Freeze mask. A pin pull has no weight to
  scale, and a half-frozen texel cannot be half-constrained by a solver
  that only knows about points. Freezing a region and pulling through it
  moves it. Stated here because a silent approximation would be worse
  than a documented limitation, and because someone will report it.
- **`BrushTool.PINS` carries four meaningless fields.** Radius, strength,
  falloff and cadence mean nothing for a pin pull. They are filled with
  inert values rather than made nullable, because fifteen other rows need
  them and a nullable column would push a `?:` onto all of them to
  accommodate one.
- **Schema growth is additive.** `pinWarp` defaults to null, so every
  existing project loads unchanged. An older build opening a newer file
  takes its existing unknown-enum failure path: the project does not
  open, and nothing overwrites it. That is the behaviour the proposal
  asks to have pinned before shipping, and it is pinned.
- **A pin warp is validated at the log boundary, not trusted.** It
  arrives from a file as readily as from a finger, and the loader refuses
  rather than half-restores. Non-finite values are *replaced* rather than
  clamped, because `NaN.coerceIn(a, b)` is NaN — a clamp that looks like
  a guard and is not one, the lesson `Lens.sanitized` already records.
- **`solverVersion` is written and never read.** Deliberate: a future
  improvement to `RigidMls` should apply to new pulls without silently
  redrawing documents authored against today's. A field the writer fills
  and the reader ignores is cheap; retrofitting one is impossible.
- **Revisit if** a third payload shape appears. Two is a branch; three is
  a sealed hierarchy, and at that point `Stroke` should become one rather
  than growing a third nullable column.
