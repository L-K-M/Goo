# Tool proposal: Goo Portals

Status: pitch, not an implementation

## The pitch

Plant two linked chrome rings anywhere on the photo. Goo through one and the
same deformation comes out of the other.

Goo Portals turns the existing Mirror idea from "the picture has a vertical
axis" into "these two places belong together." A stroke over one unevenly
placed eye can grow the other eye too. A nudge at one corner of a mouth can
lift the other corner. Two people in the same photo can melt in sync. Fusion
can open two windows onto photo B with one gesture.

The portals copy deformation, not pixels. They are therefore not a clone
stamp and they do not paste one feature over another. Each ring receives the
same push, puff, pinch, smoothing, erasure, or Fusion reveal in its own
location.

## The interaction

Portals is a brush modifier, like Mirror, rather than a tenth deformation
kernel.

1. Select a brush, then tap **Portals**.
2. Tap once to plant cyan ring A and again to plant magenta ring B.
3. Drag either ring to refine the pairing. The rings live in source-image
   space, so view pan, zoom, and rotation do not move the relationship.
4. Start a stroke inside either ring. The real cursor stays under the finger;
   a ghost cursor performs the translated stroke through the other ring.
5. Tap Portals again to dismiss the rings. A long press can re-enter placement
   without turning the link off.

The MVP is translation-only. Both rings have the current brush diameter and
the copied stroke keeps the same orientation and scale. A later version could
give each ring an orientation notch, but rotated, reflected, and scaled links
are not required to prove the tool.

A stroke that begins outside both rings is ordinary. This makes the modifier
selective: the user can keep the pair on screen while gooing unrelated parts
of the photo normally.

## What it makes

- Matched caricature on a face whose eyes are not centered on Meltorama's
  fixed Mirror axis.
- Synchronized expressions across two faces.
- Repeated bubbles, windows, or dents on product and architecture photos.
- Paired cleanup: Smooth or UnGoo both sides of a composition together.
- Twin Fusion reveals whose masks continue to smear and tween with the goo.
- GOOvies in which two distant regions move as if connected by hidden taffy.

The rings also make a good spectator interaction. The second cursor explains
the trick while it happens, and moving the rings invites experimentation in a
way a settings panel does not.

## Why it belongs in Meltorama

### It generalizes a proven control

Mirror already demonstrates that duplicating a normalized stamp is useful,
understandable, and compatible with every brush. Portals removes the three
assumptions that limit Mirror: the relation need not be centered, vertical,
or reflective. Real portraits are tilted, asymmetrical, and often contain
more than one person.

### It multiplies the whole palette

This is not one more way to smear. It gives every current and future brush a
new spatial grammar. Grow through a portal is a paired inflator; Faultline
would become two linked slips; Fusion becomes a two-window reveal. One modest
input feature creates many distinct toys.

### It is precise and ridiculous at the same time

Linked editing is genuinely useful for eyes, mouth corners, repeated objects,
and cleanup. Arbitrarily linking a nostril to the moon is also exactly the
kind of bad idea a funware app should make irresistible. The tool raises both
the craft ceiling and the joke ceiling.

### It has a recognizable setup but a novel result

Procreate's Clone tool uses a visible movable source disc, and Krita's
Multibrush can place translated brush copies. Those interfaces establish that
people can understand a visible source relationship. Goo Portals changes the
payload: it transports a displacement gesture bidirectionally instead of
copying paint or pixels.

## Engine fit

The implementation should follow the existing Mirror boundary exactly:
resample the physical gesture once, expand each emitted stamp, and store the
concrete expanded stamps in the stroke log.

For a translation-only pair in aspect space:

```text
offset = portalB - portalA

stroke beginning in A: copiedCenter = center + offset
stroke beginning in B: copiedCenter = center - offset
copiedDelta = delta
```

Portal centers and the offset must be computed in aspect space before the x
component is converted back to UV. That keeps a circular relationship round
on portrait and landscape photos.

No portal metadata is needed for replay. Each generated `Stamp` already holds
its final normalized center and delta, just as mirrored stamps do today. The
pair is an input aid; the resulting stamps are the document. This preserves:

- one undo step for the leader and its twin;
- context-loss rebuild from the existing log;
- full-resolution export with no new path;
- project persistence with no schema change;
- GOOvie keyframes and endpoint caches with no special case;
- all current `StampMode` semantics, including Fusion and UnGoo.

Directional tools copy both center and delta. Radial and field tools copy the
center; their unused delta remains unchanged. Expansion order is fixed as
leader first, portal twin second because warp-of-warp composition is ordered.

Mirror and Portals may compose, producing at most four heads. The transform
order must be canonical and tested. Recursive copying is forbidden: generated
stamps never re-enter a portal.

## Honest costs

- An active pair doubles stamp passes and log size. Combining it with Mirror
  quadruples them, so one portal pair is the deliberate cap for the MVP.
- Nearly coincident rings would apply almost the same stamp twice and silently
  increase strength. Placement should reject centers closer than one small
  fraction of the active radius, or explicitly preview the overlap.
- Copied centers can fall outside the photo. Keep copies whose brush disc can
  still intersect the image; drop only copies that cannot affect a texel.
- Two movable rings compete with the canvas for touch. Placement must be an
  explicit state, while ordinary painting only treats the initial down point
  as a portal hit test.
- Ring placement itself is ephemeral. Losing an uncommitted pair on process
  death is acceptable because all completed portal strokes are already fully
  materialized in the document.
- Rotation and scaling sound small but are not. Rotation requires an oriented
  frame; scaling requires per-copy radius, which the current `Stroke` model
  does not carry. They should be separate follow-ups after translation proves
  useful.

## MVP acceptance sketch

- A stroke beginning in either ring emits one leader and one translated copy.
- Reversing A and B produces the inverse translation.
- Smear deltas remain parallel; Mirror plus Portals uses a stable documented
  order.
- Grow, Smooth, UnGoo, and Fusion behave at both centers.
- One Undo removes both paths and one Redo restores both.
- Rebuild, project reload, still export, and GOOvie export match the preview.
- The relation is invariant under view pan, zoom, and rotation and under image
  aspect ratio.
- A stroke outside both rings is not copied.
- The feature requests no permission and adds no network or image-analysis
  dependency.

## Research

- [Clone - Procreate Handbook](https://help.procreate.com/procreate/handbook/adjustments/adjustments-clone)
  uses a visible movable source disc, supports locking it, and lets any brush
  paint the selected source elsewhere. It supports the proposed visible-ring
  grammar, while Goo Portals deliberately transfers deformation rather than
  source pixels.
- [Multibrush Tool - Krita Manual](https://docs.krita.org/en/reference_manual/tools/multibrush.html)
  documents simultaneous brush instances, including translated copies placed
  relative to the real cursor. It is precedent for one gesture driving
  multiple visible heads.
- [Symmetry Guide - Procreate Handbook](https://help.procreate.com/procreate/handbook/guides/guides-symmetry)
  shows that transformed strokes can update in real time and that movable
  guides make the relationship legible. Portals extends that idea from a
  global symmetry axis to an arbitrary pair of regions.

## Recommendation

Include Goo Portals as a post-v1 experimental modifier. It creates a result
that neither conventional Liquify tools nor Meltorama's current Mirror can
make, yet its MVP compiles to the exact stamp representation the engine
already trusts. It is rare for an idea this strange to be this architecturally
conservative.
