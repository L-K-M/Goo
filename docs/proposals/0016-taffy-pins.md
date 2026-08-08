# 0016 — Taffy Pins

- **Status:** accepted — built (roadmap #30, ADR 0004 + ADR 0005). The
  60 fps gate remains unmet: no GPU in the build environment, so it
  wants profiling on real low-end GLES 3 hardware.
- **Date:** 2026-08-07

## The pitch

Pin down the parts that must stay put, then grab another part and pull the
whole photo like a rubber sheet.

Taffy Pins is a constraint-based posing tool. Silver pins hold selected points
in place. A magenta suction cup grabs one point and drags it somewhere new.
The image between them bends coherently instead of being smeared along the
finger path.

Pin the shoulders and pull a hand into the air. Pin both eyes and stretch the
nose without drifting the face. Pin the corners of a building and make its
middle bow. On the sample blobs, plant four pins and turn the whole picture
into a rubber puppet.

## The interaction

Taffy Pins is a temporary canvas mode, like Crop, because the user must place
constraints before making the edit.

1. Enter **Pins**. The current goo becomes the fixed base for the preview.
2. Tap up to five silver hold pins onto points that should not move.
3. Switch to **Pull**, touch the feature to grab, and drag the magenta puck.
4. The candidate deformation updates live from the unchanged base. Pointer
   event density does not accumulate extra deformation.
5. Release to commit one atomic pin pull. Hold pins may remain for another
   pull; Back leaves the mode.

Four implicit frame-corner anchors keep an unconstrained pull from translating
the entire photograph. The solver gives those controls a lower fixed weight
multiplier than explicit hold pins, so they stabilize the frame without
dominating the interior. Explicit hold pins remain exact constraints where the
user cares about exact points.

The mode uses **Reach** and **Rubber** controls rather than pretending ordinary
brush Size and Strength explain it. Reach controls how local the influence is.
Rubber trades rigid local shape against a softer, more elastic bend inside a
safe range.

The MVP supports one moving puck and at most five explicit hold pins per
commit. Multiple simultaneous moving pins, subject cutouts, and automatic face
landmarks are not required.

## What it makes

- Coherent caricature: lengthen a nose while the eyes and mouth remain fixed.
- Puppet posing: bend arms, tails, branches, signs, and silhouettes without
  painting dozens of corrective strokes.
- Rubber architecture: bow a tower between pinned corners or make a horizon
  sag around fixed landmarks.
- Deliberate impossible perspective rather than a trail of blurred texture.
- GOOvies that move from one posed field to another like a paper puppet coming
  alive.
- A game in its own right: "How far can this face stretch before the pins pop
  red?"

## Why it belongs in Meltorama

### It adds relationships, not another force

Every current brush answers "what should happen near this finger?" Pins answer
"what must remain true while something else moves?" That is a different class
of creative control. A fixed eye and a pulled cheek are a relationship the
current circular kernels cannot express exactly.

### It preserves recognizable features

Smear is intentionally wet and Move drags a plateau-shaped region, but both
accumulate local sampling along a path. A constraint deformation can carry a
feature as a coherent patch while distributing the bend around it. That makes
larger, more ambitious edits possible before Smooth and UnGoo cleanup.

### It raises both the craft ceiling and the toy ceiling

Puppet-style deformation is useful for posing and controlled correction. It
is also inherently funny when the puppet is a family photo. Meltorama is at
its best when the professional reason and the ridiculous reason are the same
mechanism.

### It stays private and general

No face detector, segmentation model, cloud API, or new permission is needed.
The user identifies the meaningful points directly. The same interaction
works on people, pets, drawings, landscapes, and objects.

### Research supports real-time point-handle deformation

Moving Least Squares image deformation was designed around point handles and
offers affine, similarity, and rigid variants. Adobe's Puppet tools establish
the pin-and-move interaction in production software, while Blender's Elastic
Deform brush demonstrates that elastic grab behavior can be interactive. The
proposal combines that proven grammar with Meltorama's persistent field and
GOOvie endpoint interpolation.

## Engine fit

This tool does not honestly fit inside an ordinary circular stamp. It should
not be approximated by hundreds of Move stamps just to avoid acknowledging a
new field operation.

### Deformation

Rigid Moving Least Squares is a pragmatic first solver. Each hold pin supplies
an identical source and target control point. The pull puck supplies the one
control pair that differs. All coordinates are normalized source coordinates,
with distance and regularization evaluated in aspect space. Rubber can be a
curated blend between the rigid and similarity forms rather than a free-form
solver coefficient.

Meltorama renders by backward mapping, so preview and commit evaluate the
inverse control relation: target controls map back to source controls. For
output point `x`:

```text
source = rigidMls(x, targetControls, sourceControls, reach, rubber)
w(x) = source - x
D'(x) = w(x) + D(x + w(x))
```

The last line is the existing warp-of-warp composition rule. It applies the
new pin deformation first and then looks up the prior displacement and Fusion
mask at the prewarped position.

The MLS weight function needs two explicit guards. A point at a target control
returns that control's source point directly instead of evaluating an infinite
inverse-distance weight. Every other squared distance is bounded by a small
fixed epsilon before division. Those branches prevent a control-point
singularity from putting a NaN into the field; CPU and GLSL use the same
epsilon and exact-hit rule. Per-control weight multipliers are also part of the
payload semantics so implicit corner anchors can be weaker than user pins.

At most ten controls (five explicit holds, four implicit corners, one pull)
keep the shader loop bounded. The candidate pass can run full-field while the
drag is active. Commit writes the composed result into the normal ping-pong
field once; it does not accumulate every pointer move.

### Document model

The pin pull still belongs in the revision log, but it needs more data than
`Stamp(cx, cy, dx, dy)` can carry. The smallest compatible extension is a
`PinWarp` payload on `Stroke`:

```text
PinWarp(
    sourceControls,
    targetControls,
    controlWeights,
    reach,
    rubber,
    solverVersion,
)
```

`BrushTool.TAFFY_PINS` acts as the wire discriminator. A Pins stroke contains
one validated `PinWarp` and no ordinary stamps; every other stroke contains
ordinary stamps and no pin payload. `StrokeLog.push` and restore enforce that
exclusive invariant instead of accepting an empty edit.

This avoids replacing the normalized revision graph, changing keyframe pin
identity, or adding bitmap snapshots. Old saved projects load because the new
payload has a default. A project containing Taffy Pins needs a schema bump.
On downgrade, the old app's unknown-enum decode takes its existing generic
project-open failure path: decoding returns no project, the editor stays
closed, and no save can overwrite the folder. It cannot name a future tool it
does not know, but it fails safely instead of opening a flattened field or
crashing. That behavior must be pinned before shipping because persistence is
a shipped contract.

Replay branches once per log entry:

- ordinary stroke: run its stamp passes;
- pin warp: run one full-field analytic composition pass.

The pure JVM reference mirrors rigid MLS and the composition. The GL renderer,
export replay, context rebuild, and keyframe materializer all consume the same
ordered list. GOOvie code remains unchanged because it only sees materialized
endpoint fields.

This is a meaningful expansion of "the stroke log is the document" from
"every edit is a circular stamp" to "every edit is a deterministic field
operation." It deserves an ADR when implemented, not a quiet special case in
the renderer.

## Honest costs

- The document and replay types become more general. That touches persistence,
  validation, CPU reference code, renderer replay, export, tests, and project
  schema handling. This is not a one-row `BrushTool` addition.
- Rigid MLS can fold under extreme pulls or crowded controls. The live preview
  needs a conservative displacement limit and a red invalid state instead of
  committing an unreplayable knot.
- A rectangular photo has no subject boundary. Implicit corner anchors and a
  Reach limit keep the outer frame stable, but they cannot infer where a body
  ends. The proposal deliberately avoids claiming content awareness.
- Full-field MLS evaluation costs more per preview frame than a scissored
  brush stamp. Each 2D rigid-MLS sample needs O(pin count) weighted
  accumulations plus a vector normalization. The paper's closed form avoids a
  general matrix inverse or polar decomposition, but doing even that bounded
  work at every field texel is still substantial. The small fixed control cap
  and a preview-quality field are the performance guardrails; also prototype a
  coarser control grid with interpolation, as the paper recommends, and
  profile on low-end GLES 3 hardware before polishing the UI.
- Pins under a transformed view must be mapped through the inverse view exactly
  like brush input. Their overlay then maps forward with the view so handles
  stay attached while zooming or rotating.
- Hold pins are setup, not persistent editor furniture. A completed `PinWarp`
  is saved; an unfinished arrangement may be discarded when leaving the mode.
- "Rubber" cannot expose arbitrary solver coefficients. A narrow curated
  range is more testable and more understandable than a scientific panel.

## MVP acceptance sketch

- Every explicit hold pin remains fixed within a documented field-texel
  tolerance while the pull puck reaches its target.
- Releasing one puck adds one undoable revision; Undo restores the exact base
  field and Redo restores the pull.
- Preview is recomputed from the mode-entry base, so identical final controls
  produce identical output regardless of pointer event count.
- Portrait and landscape photos agree in pixel-space geometry.
- The maximum control count, displacement, reach, and solver iterations are
  fixed and validated before a command enters the log.
- CPU and GLSL reference cases agree within the project's established float
  tolerance.
- Exact control-point hits and near-zero distances produce finite values in
  both implementations.
- Context rebuild, project reload, still export, and GOOvie endpoint
  materialization reproduce the committed preview.
- Old schema-1 projects continue to load unchanged; malformed and unsupported
  pin payloads are rejected rather than half-restored.
- The feature uses no network, permission, face model, or bitmap snapshot.

## Research

- [Image deformation using Moving Least Squares](https://doi.org/10.1145/1179352.1141920)
  by Schaefer, McPhail, and Warren, SIGGRAPH 2006, presents point-handle image
  deformation with affine, similarity, and rigid transformations suitable for
  interactive manipulation. It is the primary mathematical basis for the
  proposed solver.
- [Animating with Puppet tools in After Effects](https://helpx.adobe.com/after-effects/using/animating-puppet-tools.html)
  documents deform pins that move image regions and starch pins that keep
  regions more rigid. It supports the hold-and-pull interaction and shows why
  constraints add capability beyond a freehand warp brush.
- [Elastic Deform - Blender 4.5 LTS Manual](https://docs.blender.org/manual/en/4.5/sculpt_paint/sculpting/brushes/elastic_deform.html)
  documents production elastic modes including Grab, Scale, Twist, and
  volume-preserving variants. It is evidence that elastic direct manipulation
  can remain responsive and understandable as a tool.
- [Regularized Kelvinlets: Sculpting Brushes Based on Fundamental Solutions of Elasticity](https://doi.org/10.1145/3072959.3073595)
  presents closed-form real-time elastic grab, scale, twist, and pinch brushes,
  including image-editing applications. Kelvinlets are a credible fallback if
  a constrained MLS prototype is too expensive, though they do not satisfy
  arbitrary hold pins as directly.

## Recommendation

Include Taffy Pins on the long-range roadmap and prototype it before committing
the document-model expansion. It is the highest-cost idea in this set, but it
also adds the most capability: coherent, constrained posing that no collection
of circular push kernels can replace. If the bounded rigid MLS prototype
holds 60 fps and survives replay round trips, the product case is strong
enough to justify the ADR.
