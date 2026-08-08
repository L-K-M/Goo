# 0004 — Taffy Pins: what the solver prototype found

- **Status:** accepted
- **Date:** 2026-08-08

## Context

Proposal 0016 (Taffy Pins) is the most expensive idea in
`docs/proposals/`. It asks for constraint-based posing: silver pins hold
points still, a magenta puck drags one somewhere new, and the image
between them bends coherently instead of smearing along a finger path.

It also asks, explicitly, not to be built yet:

> Include Taffy Pins on the long-range roadmap and **prototype it before
> committing the document-model expansion**. […] If the bounded rigid MLS
> prototype holds 60 fps and survives replay round trips, the product
> case is strong enough to justify the ADR.

That gate is the right one. The document-model expansion it guards —
`Stroke` carrying a `PinWarp` payload instead of stamps — is a *different*
and larger change than ADR 0003's, and paying for it before knowing
whether the solver works would be the wrong order.

So this is not an ADR accepting Taffy Pins. It is the record of what the
prototype established, what it did not, and what changed as a result.
`RigidMls` and `RigidMlsTest` are the artefact; nothing in the document
model, the renderer, the schema or the UI was touched.

## Decision

**Build the solver, keep it out of the document.** `RigidMls` ships as a
pure, tested function with no caller. It costs nothing at runtime, it is
covered by the JVM suite, and it converts the proposal's riskiest claim
from an argument into a measurement.

Three things changed on contact with the arithmetic.

**1. "Rigid refuses to scale" is false as stated, including in my own
first draft of the test.** Rigid MLS names what each *local* transform
is — a rotation, with no scale of its own — not what the map is. Between
the controls the weighted blend still stretches. Scaling every target ×2
about the frame centre and measuring a short segment near the middle:
similarity grows it by exactly ×2.00, rigid by ×1.79. Less, but not an
isometry.

This matters because the whole product argument for choosing rigid over
the cheaper similarity variant was "it carries a feature as a coherent
patch instead of inflating it". That argument survives, but only in its
comparative form. A test asserting a ratio of 1 asserts something MLS
does not do, and would have had to be quietly loosened later — which is
how a wrong claim becomes a permanent comment.

**2. Similarity reproducing a pure similarity transform *exactly* is the
strongest correctness check available**, and it is what caught two real
bugs in the accumulation: a dropped per-control weight, and an inverted
sign on the cross term. Neither is visible in the interpolation tests —
at a control point one weight dominates so completely that the pins still
hold with both wrong — so "the pins stay put and the puck lands" would
have signed off on a solver that was wrong everywhere in between. Any
future rewrite should keep the ×2 test.

**3. `pow()` in the inner loop is the only transcendental, and the
default does not need it.** The weight is `1 / d^2α`; α = 1 is a plain
divide. Branching on it is worth **≈2.7×** on the CPU reference, and
the default reach is exactly 1, so the common case now pays nothing for
a knob most people will never move.

## Measurements

**How, before what.** The first attempt was a stopwatch around a
full-field sweep at three sizes, before and after the change. It produced
a tidy table and the table was not real: re-running it moved 512×288
between 39 ms and 190 ms, and 1920×1080 between 393 ms and 613 ms. This
is a shared container, and a between-runs comparison on one measures the
container.

Replaced with an interleaved A/B in a single process — α = 1 against
α = 1.0001, which is numerically the same deformation and forces the
`pow` path — nine pairs, medians reported. Noise now hits both arms
equally.

At 1024×576 with 10 controls (4 corner anchors, 5 holds, 1 puck),
single-threaded JVM, two independent runs:

| | Run 1 | Run 2 |
| --- | --- | --- |
| α = 1 (divide) | 120.1 ms | 120.6 ms |
| α ≠ 1 (`pow`) | 321.4 ms | 320.7 ms |
| ratio | **2.68×** | **2.66×** |

So the fast path is worth **≈2.7×**, and the CPU reference runs at
**≈4.9 Mtexel/s**.

**What this does and does not establish.** It bounds the *CPU reference*,
which is what the test suite and any golden comparison run against, and
at ~5 Mtexel/s that reference sweeps a preview-sized field fast enough
not to slow the suite. It also settles, with a controlled experiment
rather than an assertion, that the `pow` branch earns its keep.

It says **nothing directly about 60 fps in GLES**, and I cannot measure
that here: this environment has no GPU, and the project has no
instrumented tests by design. The shape of the work is a bounded loop of
at most 10 iterations per texel, each a handful of multiply-adds — which
is the kind of thing fragment shaders are good at — but "should be fine"
is an argument, not a number, and the proposal's 60 fps gate is a number.
**That gate remains unmet.** Its remaining unknowns are the per-texel
`pow` when reach ≠ 1 (now known to cost 2.7× on a CPU, and worth
re-measuring on a GPU where transcendentals are cheaper relative to
memory), and whether a full-field pass per preview frame competes with
the warp pass for bandwidth on low-end GLES 3 hardware. The proposal's
own suggestion — a coarser control grid with interpolation, as the paper
recommends — is the thing to try next, and it is unblocked by this work
rather than replaced by it.

## Consequences

- **The solver is dead code until something calls it.** Deliberate, and
  the cost is one file plus its tests. The alternative — building the
  document-model expansion first and discovering the math is wrong — is
  the failure mode the proposal's gate exists to prevent.
- **The singularity guards are two, not one.** A bounded epsilon stops
  the division from producing an infinity; a separate exact-hit branch
  returns the control's own image, because a texel whose weight is 1e18
  while its neighbours are 1e3 is finite and still a hard singular dot. A
  full-field sweep over an awkward arrangement (controls on the frame
  edge, one on a texel centre, a hard pull) is in the suite, because a
  NaN reaching a displacement field is permanent and replays faithfully.
- **`rubber` is a blend between two results, not a solver coefficient**,
  and it is clamped rather than extrapolated. Interpolation at the
  controls holds across the whole range — a "softer" setting that let a
  hold pin drift would break the tool's only promise.
- **Everything is measured in aspect space**, so the falloff is round in
  pixels; a portrait and a landscape photo with the same pixel
  arrangement agree to 2e-3. The control offsets are *not* converted, and
  the difference is invisible on a square test image, which is why that
  test uses three shapes.
- **Revisit when** someone is ready to answer the 60 fps question on real
  hardware. At that point the decision to expand `Stroke` is still
  outstanding and still separate — this document deliberately does not
  make it.

  **Superseded on that last point by ADR 0005**, which makes the
  expansion. It was made with the 60 fps gate still unmet, which that
  ADR records as a consequence rather than as an answer.
