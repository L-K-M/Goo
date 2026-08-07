# Tool idea 0003 — Aspic (paint what must not move)

> **Status: proposal.** No engine code ships with this document. If the
> idea is taken, it becomes its own implementation PR with a roadmap row
> in PLAN.md. Ideas live in `docs/ideas/`; decisions live in
> `docs/decisions/`.

**One-liner.** Paint jelly over the parts of the photo you want to keep;
every other brush then flows around them instead of through them.

## The feel

Arm Aspic and the picture takes on a faint cold sheen where you paint —
a set jelly, visible while the tool is armed and invisible the moment
you pick up a goo brush again. Now smear. The frozen region does not
move. Goo piles up against it, streams past it, and where the jelly is
half-set the content drags a little, so edges feather rather than
snapping.

UnGoo thaws it, because UnGoo already erases everything under the brush.
Nothing else needs to.

## Why Meltorama should have it

**1. The palette is nine accelerators and no brake.** Every tool in the
app adds displacement. The only way to protect anything today is a
steady hand and a small brush, and the app's whole promise is that you
should not need either. This is the difference between "goo the wall
behind her and keep her face" and giving up on the idea.

**2. It is the one Liquify tool with no counterpart here.** Meltorama
already covers Forward Warp (Smear), Reconstruct (UnGoo), Pucker and
Bloat (Shrink, Grow), Smooth, and Mirror. Liquify's Freeze/Thaw mask is
the only one of its brushes that this app has nothing like — and it is
the one professionals reach for first, because it is what turns a toy
warp into a retouch.

**3. It changes every other tool without touching any of them.** A
protect mask is a multiplier on the stamp weight. Nine existing brushes
and every future one gain precision from one new mode. Very few features
in this codebase have that leverage.

**4. The engine has been holding a channel open for it since day one.**
The displacement field is RGBA16F. `xy` is displacement, `z` is the
Fusion mask, and `w` is unused — `DisplacementField.CHANNELS` is 3 and
`STAMP_FRAG` literally writes `o_field = vec4(next, 0.0)`. The memory is
already allocated, on every device, in every session. Aspic costs zero
bytes.

## How it works in this engine

This is the trick Fusion already pulled, run a second time. PLAN.md §3
on Fusion: "the through-paint mask is the field's z channel… so it warps
with the goo, blurs under Smooth, erases under UnGoo, undoes/replays/
exports via the stroke log, and tweens in GOOvies through the same field
mix, all with zero new machinery". Every word of that is true of a
protect mask in `w`.

**Writing it.** One new `StampMode.SET` (shaderId 6), the FUSE branch
with a different channel:

```glsl
next = vec4(cur.xyz, clamp(cur.w + w * ASPIC_STEP, 0.0, 1.0));
```

**Obeying it.** One line, above the branch, in the stamp shader:

```glsl
float w = falloff(d) * u_strength;
// A texel that is set refuses the stamp. Aspic and UnGoo are exempt:
// the brake must not brake itself, or a fully set region could never
// be thawed.
if (u_mode != 4 && u_mode != 6) w *= (1.0 - texture(u_field, v_uv).w);
```

That exemption is the one real design decision in the whole tool, and it
has to be written down somewhere before it is written in GLSL: **ERASE
and SET are not attenuated by the mask.** Without it the tool is a trap —
paint jelly at full strength and that region is permanently unreachable
by anything except a global Reset.

**Seeing it.** A mask you cannot see is a haunting: three sessions later
a user wonders why one cheek refuses to move. `WARP_FRAG` gains a
`u_showAspic` uniform, 1 while the Aspic tool is armed and 0 otherwise,
and tints frozen texels toward a cold blue with a slight specular lift —
set jelly, in keeping with the console. One uniform, three lines, and it
composes with the existing `f.z` fusion mix.

**Everything else is free.** The mask rides the same warp-of-warp lookup
as the displacement, so jelly painted over an eye *follows that eye*
when the surrounding goo moves it. It tweens through the existing
`mix(fa, fb, u_tween)` because that mix is already a vector. It
serializes with the stroke log because it *is* the stroke log. It
replays at export resolution because everything does.

## Cost, risks, honest trade-offs

- **`CHANNELS` goes 3 → 4.** The CPU reference `DisplacementField` grows
  a channel, its golden tests grow with it, and every place that indexes
  `* CHANNELS + channel` has to be checked. Mechanical, but not nothing:
  this is the type the whole test suite is anchored on.
- **Half-set regions shear.** A feathered mask boundary means content
  is partly dragged and partly pinned, which stretches it. That is
  exactly how Liquify's mask behaves and exactly what you want at a
  hairline, but at a hard edge (a horizon, a door frame) it will smear
  where users expect a clean stop. There is no fix inside a continuous
  displacement field; the honest answer is a strength lever on the
  jelly and documentation.
- **Reset and the mask.** Does Reset clear jelly? SOL-5 already flags
  that Reset is only partly undoable (it restores strokes but discards
  levers). Aspic must not become the third thing Reset silently eats.
  It should be part of whatever document transaction SOL-5 concludes
  with, and that argues for landing SOL-5 first.
- **Two masks, one field, four channels, no room left.** After Aspic
  the field is full. Any future per-texel quantity needs a second
  texture and the ping-pong pair doubles. Worth knowing that this
  proposal spends the last free channel, and worth spending it on the
  tool with the widest leverage — which is the argument for Aspic
  getting it rather than something narrower.

## Declined variants

- **A freeze *selection* (lasso, magic wand, subject detect).** Better
  UX for some jobs, an entire interaction model and probably an ML
  dependency for others. This app has one interaction: a finger on the
  glass. Painting the mask is on-brand and is what Liquify does.
- **Inverting the mask into a "goo only here" stencil.** Tempting (it
  is a one-character change) but it doubles the mental model. If it is
  wanted, it is a toggle on the Aspic bead, later, once the plain
  version has been lived with.
- **A separate mask texture instead of the `w` channel.** More
  orthogonal, and it would double the field memory and the ping-pong
  bandwidth for a feature that fits in a channel that is already paid
  for and already gets all the warp/tween/undo/export behaviour free.

## Name

"Aspic" — the savory jelly a 1950s cookbook sets a whole ham in — is a
better fit for a console called Meltorama than "Freeze". It is edible,
wobbly, slightly disgusting, and it is the exact physical metaphor:
things suspended in it do not move. The bead reads **Aspic**; the tint
is cold; the string for the empty state is "nothing is set".
