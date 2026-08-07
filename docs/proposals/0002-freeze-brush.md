# 0002 — Freeze brush

- **Status:** proposed
- **Date:** 2026-08-07

Consolidates two independently written proposals for the same tool
(PRs #50 and #56). Where they disagreed the better argument won, and
both disagreements are recorded below rather than quietly resolved.

## The tool

Paint a clear varnish over the part of the photo you want to keep.
Whatever is varnished is pinned: every later stroke, lever and lens
flows around it and leaves it alone. Freeze the eyes and the tip of the
nose, then stretch the rest of the face into orbit — the classic
caricature workflow. Goo piles up against the varnish, streams past it,
and where it is half-set the content drags a little, so edges feather
rather than snapping.

Frozen regions show a faint frost sheen while the tool is armed, so the
mask is something you can see rather than something you must remember.
UnGoo — which already un-warps and un-fuses — thaws.

The console voice would probably rather call it **Aspic**, after the
jelly a 1950s cookbook sets a whole ham in: edible, wobbly, slightly
disgusting, and the exact physical metaphor. "Freeze" is what it does;
"Aspic" is what the bead should say.

## Why it belongs in Meltorama 2000

**1. The palette is nine accelerators and no brake.** Every tool adds
displacement. The only way to protect anything today is a steady hand
and a small brush, and not needing either is the app's whole promise.
There is one universal experience behind this: you get the left eye
perfect, reach for the right one, and ruin the left. Without Freeze
every careful edit is one slip from soup and the only recovery is
undoing backwards through good work.

**2. It is the one Liquify brush with no counterpart here.** Forward
Warp is Smear, Reconstruct is UnGoo, Pucker and Bloat are Shrink and
Grow; Smooth and Mirror exist. Freeze/Thaw is what is missing, and it is
the one that turns a toy warp into a retouch.

**3. It multiplies every other tool without touching any of them.** A
protect mask is a multiplier on stamp weight: nine existing brushes and
every future one become *aimable* for one new mode. It also unlocks
compositions that are otherwise impossible — frozen subject over a
melted background, one frozen half of a face while the other takes the
full Vortex, a frozen logo in a poster turning to soup.

**4. The engine has been holding a channel open for it.** The field is
RGBA16F: `xy` is displacement, `z` is the Fusion mask, and `w` is unused
— `DisplacementField.CHANNELS` is 3 and `STAMP_FRAG` writes
`vec4(next, 0.0)`. The memory is already allocated on every device, so
Freeze costs zero bytes.

## How it fits the engine

This is the trick Fusion already pulled, run a second time. PLAN.md §3's
list of what the `z` mask gets free — undoes, replays, exports via the
stroke log, tweens through the same field mix — is true word for word of
a protect mask in `w`.

- `DisplacementField.CHANNELS` 3 → 4 (`w` = freeze mask in [0,1]).
- A new `StampMode.GUARD` accumulates it exactly as `FUSE` accumulates
  the fusion mask: `next = vec4(cur.xyz, clamp(cur.w + w * FREEZE_STEP,
  0.0, 1.0))`.
- Every warp-stamp branch multiplies its weight by `(1 − freeze(p))` —
  one line in the shared weight computation, CPU and shader alike.
- The GOOvie tween mixes `w` alongside `xyz` (one swizzle change in
  `WARP_FRAG`), so a frozen eye stays frozen across a tween.
- Undo, export replay and persistence are free: freeze strokes are
  ordinary log entries, and `StrokeLogSnapshot` needs no format change
  because `Stroke` already carries the tool enum.

**The mask is pinned to document space** — it does *not* ride the
warp-of-warp lookup the way the fusion mask does. This was the real
disagreement between the two source proposals, and #50 is right: "this
eye stays here" is a statement about the document, and a protection that
travels with content it is simultaneously preventing from travelling is
incoherent. It also matches Liquify's image-space mask, which is the
behaviour anyone arriving from there expects.

**ERASE and GUARD are exempt from the mask.** This is the one decision
that has to be written down before it is written in GLSL: without the
exemption, painting varnish at full strength makes that region
permanently unreachable by anything except a global Reset. The brake
must not brake itself.

**Globals respect the mask too.** The mask scales `globalDisp` in
`WARP_FRAG` — one multiply — or "frozen" stops meaning anything the
moment someone pulls the Twirl lever. The same multiply covers proposal
0006's lenses.

**The sheen is chrome, not document.** A subtle blue-white overlay where
`w > 0` while the Freeze dome is selected, gated on a `u_showFreeze`
uniform, and never rendered into an export.

## Cost, risks, honest trade-offs

- **`CHANNELS` 3 → 4 is the real work.** The CPU reference grows a
  channel, its golden tests grow with it, and every `* CHANNELS +
  channel` index has to be checked. Mechanical, but this is the type the
  whole test suite is anchored on.
- **Half-set regions shear.** Content that is partly dragged and partly
  pinned stretches. That is exactly how Liquify behaves and exactly what
  you want at a hairline, but at a hard edge — a horizon, a door frame —
  it will smear where a user expects a clean stop. There is no fix
  inside a continuous displacement field; the honest answer is a
  strength lever on the varnish and documentation.
- **Reset must not eat it.** SOL-5 already records that Reset is only
  partly undoable — it restores strokes but discards levers. Freeze must
  not become the third thing Reset silently eats, which argues for SOL-5
  landing first and for the mask being part of whatever document
  transaction it concludes with.
- **This spends the last free channel.** After Freeze the field is full;
  any future per-texel quantity needs a second texture and the ping-pong
  pair doubles. That is the argument for spending it on the tool that
  multiplies every other brush rather than on something narrower.

## Open questions

- **Thaw gesture.** UnGoo-thaws-everything is the consistent cut, since
  ERASE already resets all channels. A dedicated Thaw dome is more
  precise but a tenth dome for a mask you can also UnGoo is clutter.
  Proposal: UnGoo thaws; revisit if users complain.
- **Freeze strength.** A partially-set varnish is the feathering
  mechanism; whether that rides the existing strength lever or gets its
  own detent is a UI question, not an engine one.

## Declined

- **A freeze *selection* (lasso, magic wand, subject detect).** Better
  UX for some jobs, an entire interaction model and probably an ML
  dependency for others. This app has one interaction: a finger on the
  glass. Painting the mask is on-brand and is what Liquify does.
- **Inverting the mask into a "goo only here" stencil.** A one-character
  change that doubles the mental model. If wanted, it is a toggle on the
  bead, later, once the plain version has been lived with.
- **A separate mask texture instead of the `w` channel.** More
  orthogonal; doubles field memory and ping-pong bandwidth for a feature
  that fits in a channel already paid for and already inheriting all the
  warp/tween/undo/export behaviour.
