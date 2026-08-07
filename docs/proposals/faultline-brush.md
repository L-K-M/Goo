# Tool proposal: Faultline

Status: pitch, not an implementation

## The pitch

Draw a fault across the photo. Pixels on one side slide forward along the
stroke while pixels on the other side slide backward, as if the image were
two tectonic plates slipping past each other.

Faultline makes a path behave as a boundary instead of a trail. A stroke
through a face can offset its two halves into a cubist grin. A pass across a
city can kick every window sideways. Short horizontal faults make analog-TV
tracking tears; curved faults make fabric, hair, and horizons buckle in paired
directions.

Nothing is cut and no transparent gap opens. This remains a continuous
displacement warp: the two sides shear past each other through a soft band.

## The interaction

- Select **Faultline** and drag the desired seam.
- A small two-state **Slip** bead reverses which way both sides travel.
- Drawing the same path in reverse produces the same fault. Direction should
  come from an explicit control, not from which endpoint happened to be first.
- **Size** controls the width of the soft shear zone around the path.
- **Strength** controls slip distance.
- A double-chevron cursor previews the two opposed directions.
- A tap does nothing because a fault needs a tangent. The first useful stamp
  appears only after the pointer has moved far enough to define one.
- Optional haptic ticks can mark resampled fault segments, but haptics never
  determine the document.

The path may curve. Each segment uses the local tangent, so a circular stroke
makes the inside and outside counter-rotate without becoming another Vortex
brush.

## What it makes

- Split portraits and offset facial features with one decisive gesture.
- VHS tracking errors and scanline tears that follow a hand-drawn path.
- Sliding horizons, staggered buildings, and bent typography in photographed
  signs.
- Curved seams through fur, hair, clouds, wood grain, and fabric.
- GOOvie cuts where a picture appears to suffer an earthquake and settle into
  a new pose.
- Mirrored faults that turn a face or object into a synchronized mechanical
  hinge.

## Why it belongs in Meltorama

### It gives the path a new job

Every current drag tool treats the sampled path as a row of similar pushes.
Faultline interprets the path itself as a dividing line. That change in
grammar produces an effect that cannot be reached by making Smear softer,
harder, larger, or smaller.

### It creates structure, not only blobs

Meltorama excels at soft masses: cheeks bulge, chins pinch, and regions smear.
An opposed shear produces a crisp directional relationship across an edge.
The result reads as collage, geology, and video damage rather than generic
Liquify.

### It is dramatic without requiring drawing skill

One line through a recognizable feature creates a composed result. The user
does not need to trace both sides or keep two manual smears balanced. The tool
does the bilateral bookkeeping and leaves the user in charge of the seam.

### The physical metaphor is accurate enough to teach the gesture

The USGS defines a strike-slip fault as two blocks sliding past one another.
That is exactly the proposed motion. Procreate's Edge mode demonstrates that a
line-centered local warp is legible, but Edge pulls both halves inward.
Faultline rotates the response: both halves travel along the drawn line in
opposite directions.

## Engine fit

Faultline is one new analytic `StampMode`. The existing `Stamp` carries all
the inputs it needs: center in `cx/cy` and local path direction in `dx/dy`.

For a field texel `p`, stamp center `c`, aspect-space radius `r`, and
aspect-space tangent `t`, let `bBack` be the new **backward sample
displacement**, not the visual forward motion of the content:

```text
q = aspectSpace(p - c)
t = normalize(aspectSpace(stampDelta))
n = perpendicular(t)
side = smoothOdd(dot(q, n) / r)
window = circularFalloff(length(q) / r)
bBack = t * side * window * strength * polarity * FAULT_STEP
D'(p) = bBack(p) + D(p + bBack(p))
```

`smoothOdd` is zero on the seam and approaches opposite signs on its two
sides. Both `q` and `r` are in aspect-space units, matching `Stroke.radius`.
The circular window bounds each stamp; overlapping stamps turn the row
of local responses into one continuous curved fault. The backward-map sign
must be established by CPU reference tests rather than inferred from the
content's visual motion. The `p + bBack` lookup is the engine's existing
`b(p) + D(p + b(p))` composition; a minus would only be correct if the symbol
described a forward warp, which it does not here.

Reversing the path flips both `t` and `n`. That also flips `side`, so the two
sign changes cancel in `t * side`: the same geometric path makes the same
fault in either drawing direction. The Slip bead multiplies `bBack` by an
explicit polarity when the user wants the opposite result.

Aspect-space conversion is load-bearing. Normalizing the raw UV delta would
make the shear angle wrong on non-square photos. Mirror must transform both
the center and tangent, just as it transforms directional deltas today.

`BrushTool.FAULTLINE` is non-pumped and does not stamp on down. Resampling
stores the concrete tangent-bearing stamps, so replay never has to estimate a
direction from neighboring points. The two Slip states can be two tool rows
sharing one shader mode, with polarity baked into the stroke's internal signed
strength. The user-facing Strength control remains positive. The current
`Stroke` schema remains valid.

The rest of the architecture stays ordinary:

- the new warp composes through the existing warp-of-warp lookup;
- the Fusion mask rides that lookup;
- Undo and Redo operate on one complete fault gesture;
- project persistence stores ordinary stamps;
- preview and full-resolution export run the same kernel;
- keyframes pin the resulting revision and GOOvies mix its field normally.

CPU and shader implementations need matching `smoothOdd`, tangent conversion,
falloff, and step constants. Unit tests should include reversed paths,
aspect-ratio cases, mirrored diagonals, center continuity, and bounded output.

## Honest costs

- A continuous displacement field cannot create a literal crack, reveal a
  background, or leave empty space. Product copy must say "slip" or "shear,"
  not "cut" or "tear open."
- A noisy local tangent makes alternating kinks. Tangents should come from the
  resampled segment direction, with the same minimum-distance protection that
  prevents zero-length directional stamps.
- Strong opposing motion can fold the map near the seam. `FAULT_STEP` needs a
  conservative cap relative to radius and stamp spacing.
- Repeated passes can intentionally create discontinuous-looking bands, but a
  single slow stroke should not depend strongly on event density. The
  resampler contract needs a golden case for this mode.
- The signed kernel is less intuitive to show in the existing circular brush
  overlay. Without arrows or chevrons the user cannot predict which way either
  side will move.
- Fusion content near the fault will shear with photo A because both sources
  share one displacement. That is consistent with current Fusion semantics,
  but worth including in visual tests.

## MVP acceptance sketch

- A straight horizontal stroke moves the two sides in opposite horizontal
  directions through a bounded soft zone.
- Drawing the same stroke backward preserves the result; changing Slip
  polarity reverses both motions.
- Displacement is continuous and zero on the seam and at the outside of the
  brush footprint.
- A stationary touch is a no-op and does not enable Undo.
- Curved paths follow their local tangent without visible gaps or direction
  flips.
- Portrait and landscape images produce the same pixel-space angle and width.
- Mirror produces a geometrically correct reflected fault.
- Undo, redo, context rebuild, project reload, still export, and movie export
  agree with the live result.
- Maximum Strength remains inside a tested displacement bound.

## Research

- [What is a fault and what are the different types? - U.S. Geological Survey](https://www.usgs.gov/faqs/what-fault-and-what-are-different-types)
  defines faults as fractures between relatively moving blocks and
  strike-slip faults as blocks sliding past one another. It provides the
  physical model and the directional vocabulary for the tool.
- [Liquify - Procreate Handbook](https://help.procreate.com/procreate/handbook/adjustments/adjustments-liquify)
  documents Edge as pulling surrounding pixels inward to a line rather than a
  point. That is useful precedent for a line-based local warp; Faultline uses
  the line as an opposed tangential shear instead of an inward attractor.
- [Warp Transform - GIMP 3.0 Documentation](https://docs.gimp.org/3.0/en/gimp-tool-warp.html)
  documents the conventional brush-warp families and their size, strength,
  spacing, periodic-stroke, and animation controls. Faultline keeps that
  understandable brush contract while adding a different kernel geometry.

## Recommendation

Include Faultline as a signature experimental brush. It adds only one bounded
kernel and no new document representation, but it gives Meltorama a visual
language closer to collage, seismic maps, and broken video than to another
Liquify clone. It is simple to perform, easy to demo, and difficult to imitate
with the existing palette.
