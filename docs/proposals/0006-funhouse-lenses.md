# 0006 — Funhouse lenses

- **Status:** accepted — built (roadmap #24)
- **Date:** 2026-08-07

## The tool

Drop persistent warp **lenses** onto the photo. Tap the canvas with the
Funhouse dome armed: a lens lands — a bulge, a pinch, a fisheye, a slow
vortex — and *stays*. Drag it onto the nose. Pinch it smaller. Tap it to
cycle its type, long-press to fling it away. Up to four at a time, each
rendered with a slim chrome ring so you can see your apparatus.

Lenses are not strokes: they don't paint history, they *stand* on the
photo — furniture, not graffiti. Reset clears them; undo doesn't walk
through them (same precedent as the levers).

## Why it belongs in Meltorama 2000

There is a hole in the creative space between our two existing families.
Brushes are one-shot and local; levers are persistent but glued to the
frame's center. **Placed, persistent effects** — the funhouse mirror you
can position — is the missing cell, and it changes how people compose:
bulge on the nose *and* pinch on the ears *and* a vortex in the sky,
each nudged until the timing of the face is right. That's a composition
workflow, not a gesture workflow.

The heritage is KPT's own: the Projector filter's whole identity was
draggable distortion handles in a live preview, three decades ago.
Funhouse mirrors are older than that by a century. And the animation
payoff is unique: keyframe pins already store and lerp the app's
"globals", so a lens that *moves* between two punched frames tweens into
a bulge that travels across the face — a goo dance that no brush can
record, because brush strokes are instant and lenses are positions.

Finally, lenses are the gentlest possible on-ramp: a user intimidated by
painting can still drag a bulge onto their friend's forehead and laugh.
Zero motor skill required.

## How it fits the engine

Lenses are analytic warps — the lever family, positioned:

- `GlobalParams` (the document's lever pack, already serialized and
  already stored in every keyframe pin) gains a fixed-size lens list:
  `Lens(u, v, radius, type, strength)`, capacity 4. Fixed capacity keeps
  the uniform pack bounded and the warp-pass cost O(1).
- `GlobalField.displacement` gains a bounded loop over the list, reusing
  the existing math vocabulary: bulge/pinch are the radial forms already
  written; fisheye is bulge with a flat core; lens-vortex is the twirl
  rotation with a local falloff window (the `shape` term, evaluated
  around the lens center instead of the frame center). The GLSL
  transliteration rule applies as always.
- Document-state semantics follow the lever precedent exactly: Reset
  clears, undo stays stroke-only, pulling a lens's strength to zero
  removes it. No history format changes.
- GOOvie pins store `(revision, globals)` — lenses ride the existing
  pack, and the tween's lever-lerp machinery interpolates positions and
  radii, which is where the traveling-bulge animation comes from. Free.
- Full-res export evaluates the same analytic terms (levers already work
  this way), so preview/export parity is structural.

Cost: one serializable data class, one loop in `GlobalField` + its GLSL
twin, placement/drag gestures on the canvas, four chrome rings. No new
shaders, no field changes, no log changes.

## Open questions

- **Capacity 4.** Enough to compose, few enough to keep the uniform pack
  and the user's head uncluttered. KPT would say 4.
- **Type set.** Bulge / Pinch / Fisheye / Vortex. (Swirl direction and
  strength get the dome's lever while a lens is selected.)
- **Lens + Freeze.** Frozen regions (proposal 0002) should resist lenses
  the same way they resist levers — one shared mask multiply.
- **Undo of lens placement.** Lever precedent says document state isn't
  undoable; flagging because lens placement *feels* more like an action
  than a slider. Proposal: hold the precedent; Reset and delete cover
  the regret cases.
