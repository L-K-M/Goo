# Tool proposal: Pond Drop

Status: pitch, not an implementation

## The pitch

Tap the photo and three concentric ripples bend it like the surface of a pond.
Drag to leave a wake. Drop several ripples close together and their rings
cross into knots, lenses, and accidental interference patterns.

Pond Drop is a local, paintable wave warp. It is not a water animation or a
post-processing overlay: the rings become ordinary displacement in the same
field as every other goo. Smear can pull them, Smooth can calm them, UnGoo can
erase them, and GOOvies can raise them from a flat photo.

## The interaction

- A tap creates a complete ripple centered under the finger.
- A drag lays overlapping ripples along the path, like a skipping stone or a
  wake viewed from above.
- **Size** controls the outside diameter. The MVP always fits three alternating
  push/pull bands inside it, so the pattern stays legible.
- **Strength** controls wave amplitude, not ring count.
- Mirror drops a second disturbance at the reflected point.
- The cursor previews the actual rings, including their alternating direction,
  instead of showing only a plain brush circle.

The deformation is static after the gesture. A GOOvie between the untouched
and rippled states animates the wave amplitude, not an outward-moving water
simulation. That distinction keeps the tool honest and deterministic.

## What it makes

- Eyes behind bottle glass, soap bubbles, portholes, and watery portraits.
- Concentric halos around lights and faces without growing or shrinking the
  entire region in one direction.
- Overlapping lens patterns that look planned only after they happen.
- Wakes through skies, hair, fabric, and architectural grids.
- A useful transition motif for GOOvies: flat image, rising rings, then a
  Smear that carries the rings away.

The result has a visual frequency the current palette lacks. Smear, Move,
Grow, and Shrink make broad low-frequency bends; Comb proposals add parallel
texture; Pond Drop adds closed rhythmic bands that can cross and accumulate.

## Why it belongs in Meltorama

### It makes tapping expressive

Most directional brushes need a drag. The pumped tools need a hold. Pond Drop
rewards the smallest possible gesture with a finished-looking event. That is
valuable on a phone, where one clean tap is easier than drawing a controlled
spiral around a small eye.

### It combines control with productive accidents

One drop is predictable. Two nearby drops make a pattern that is easy to
influence but hard to pre-visualize. This is the funware sweet spot: the user
chooses where energy enters the picture and the tool supplies a surprising
composition.

### It is not another synonym for Liquify

The standard brush vocabularies surveyed in Procreate and GIMP center on push,
twirl, pinch, expand, smooth, and reconstruct. Meltorama already covers those
families. A local annular wave gives the palette a new topology rather than a
different strength scale for an existing push.

### The metaphor explains the controls

People already know what a pebble does to a pond. Size as wavelength/extent
and Strength as amplitude require almost no tutorial. The cursor can teach the
alternating bands before the first tap lands.

## Engine fit

Pond Drop is one new radial `StampMode`, not a simulation. For texel position
`p`, stamp center `c`, and aspect-space radius `r`:

```text
q = aspectSpace(p - c)
d = length(q) / r
ring = alternatingBand(d) * envelope(d)
b = radialDirection(q) * ring * strength * RIPPLE_STEP
D'(p) = b(p) + D(p + b(p))
```

`alternatingBand` has three signed lobes over `d` in `[0, 1]`. A piecewise
smooth polynomial is preferable to an unbounded sine: it gives fixed zeroes,
keeps CPU and GLSL translations close, and makes the outer boundary reach zero
with a zero slope. The existing center ramp removes the undefined radial
direction at the exact center.

The sign alternation is the feature. One band samples inward, the next samples
outward, and the last returns smoothly to identity. The kernel still composes
with the field through the existing warp-of-warp rule, so painted Fusion mask
content rides the deformation as it does for Grow and Shrink.

`BrushTool.POND_DROP` would be non-pumped with `stampsOnDown = true`. A tap
stores one ordinary normalized `Stamp`; a drag uses the existing resampler and
stores its concrete stamps. The current `Stroke` schema is sufficient.

CPU reference and GLSL must remain line-for-line close. Tests should pin band
zeroes, sign alternation, aspect-space circularity, bounded amplitude, center
behavior, and replay equivalence.

Everything downstream remains the existing path:

- stroke-log undo and redo;
- context-loss rebuild;
- project snapshot and restore;
- full-resolution replay;
- Mirror expansion;
- GOOvie endpoint materialization and field interpolation.

## Honest costs

- Fine rings alias against a displacement field of at most about 1024 texels.
  The MVP should keep a fixed three-band kernel and enforce a minimum radius
  that leaves several field texels per half-wave. A ring-count slider would
  mostly be a shimmer slider.
- Excess amplitude makes a backward map fold over itself. `RIPPLE_STEP` must
  be capped relative to band width rather than allowing Strength to produce
  arbitrary displacement.
- Overlapping stamps use ordered warp-of-warp composition. They can look like
  interference, but they are not a physically linear wave superposition and
  should not be advertised as a simulator.
- A dense wake can add many fullscreen field passes. It has the same replay
  scaling as Smear, not the pumped-tool worst case, but profiling should set
  its resampler spacing.
- GOOvie interpolation raises and lowers an already placed pattern. Making
  rings travel outward over time would require phase/radius as document state
  and a time-aware renderer; that is intentionally outside the proposal.
- The alternating cursor needs sufficient contrast over any photo without
  becoming visual noise under a finger.

## MVP acceptance sketch

- One tap produces exactly three alternating radial lobes and returns to zero
  at the brush boundary.
- The center is finite and stationary; no NaN or direction singularity enters
  CPU or GPU state.
- A ripple is circular in image pixels on portrait, square, and landscape
  photos.
- Size changes diameter without changing ring count; Strength changes
  amplitude without changing zero crossings.
- A drag creates a continuous wake with no gaps at the supported size range.
- Mirror produces two geometrically mirrored drops.
- Undo, redo, rebuild, project reload, and export reproduce the preview.
- GOOvie tweening changes amplitude smoothly without a special movie path.
- Tests bound the kernel tightly enough that full Strength cannot exceed the
  chosen foldover safety limit.

## Research

- [16.5 Interference of Waves - OpenStax University Physics](https://openstax.org/books/university-physics-volume-1/pages/16-5-interference-of-waves)
  uses stones in a pond to explain circular ripples, superposition, and the
  visually rich patterns created where waves overlap. It also notes that
  linear behavior assumes amplitude small relative to wavelength, which is a
  useful basis for the proposed amplitude cap.
- [Liquify - Procreate Handbook](https://help.procreate.com/procreate/handbook/adjustments/adjustments-liquify)
  documents the established local warp set: Push, Twirl, Pinch, Expand,
  Crystals, Edge, and Reconstruct. The list helps identify an annular wave as
  a genuinely different local brush shape.
- [Warp Transform - GIMP 3.0 Documentation](https://docs.gimp.org/3.0/en/gimp-tool-warp.html)
  similarly documents move, grow, shrink, swirl, erase, and smooth modes, plus
  periodic application and animation. It supports the value of brush-local,
  replayable warps while showing the same conventional kernel vocabulary.

## Recommendation

Include Pond Drop as a focused experimental brush. It asks the engine for one
bounded analytic kernel and asks the user for only a tap, yet it opens a large
space of rhythmic, layered results that no current tool can approximate. That
is an unusually favorable exchange of complexity for delight.
