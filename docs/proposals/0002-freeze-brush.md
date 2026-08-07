# 0002 — Freeze brush

- **Status:** proposed
- **Date:** 2026-08-07

## The tool

Paint a clear varnish over part of the photo. Whatever is varnished is
pinned: every later stroke, lever, and lens flows around it and leaves it
untouched. Freeze the eyes and the tip of the nose, then stretch the rest
of the face into orbit — the classic caricature workflow. UnGoo (which
already un-warps and un-fuses) thaws.

Frozen regions show a faint frost sheen while the tool is armed, so the
mask is a thing you can see, not a state you have to remember.

## Why it belongs in Meltorama 2000

Control is what separates five minutes of smearing from an hour of craft.
Every serious liquid-warp tool converged on the same pair: Liquify's
Freeze/Thaw mask is the entire professional half of its palette, and it
exists because of one universal experience — you get the left eye
perfect, reach for the right one, and ruin the left. Without Freeze,
every careful edit is one slip away from soup, and the only recovery is
undoing your way backwards.

Freeze also unlocks compositions that are otherwise impossible: frozen
subject over a melted background (the good kind of "Dalí portrait"), one
frozen half of a face while the other half gets the full Vortex, a frozen
logo while the poster around it turns to soup. It multiplies the value of
every tool we already have, because it makes them aimable.

And it makes Undo kinder: the reason people fear big goo gestures is that
they're all-or-nothing. Frozen regions shrink the blast radius of every
stroke.

## How it fits the engine

The field texture already has a channel doing nothing: GL stores
`vec4(next, 0.0)` and `STAMP_FRAG` documents "the texture's w is
unused". Freeze is that channel's destiny:

- `DisplacementField.CHANNELS` 3 → 4 (w = freeze mask in [0,1]);
  RGBA16F was already the allocated format, so GPU memory is unchanged.
- New `StampMode.GUARD` accumulates the freeze mask exactly the way
  `FUSE` accumulates the fusion mask (`clamp(w·FREEZE_STEP)`), and
  every warp-stamp branch multiplies its weight by `(1 − freeze(p))`.
  One line in the shared weight computation, CPU and shader alike.
- Unlike the fusion mask, the freeze mask does **not** ride the
  warp-of-warp lookup: protection is pinned to document space (that's
  what "this eye stays here" means), matching Liquify's image-space mask.
- UnGoo's `ERASE` fades w along with the other channels — it already
  un-fuses, so un-freezing is the same gesture the user expects.
- GOOvie tween: `mix()` the w channel alongside xyz in `WARP_FRAG` (one
  swizzle change), so a frozen eye stays frozen across a tween.
- Undo, export replay, and persistence are free — freeze strokes are
  ordinary log entries; `StrokeLogSnapshot` needs no format change
  because `Stroke` already carries the tool enum.

Cost: one channel, one stamp mode, one weight factor, one palette dome.

## Open questions

- **Thaw gesture.** UnGoo-thaws-everything is the consistent cut
  (ERASE already resets all channels); a dedicated Thaw tool is more
  precise but a tenth dome for a mask you can also UnGoo feels like
  clutter. Proposal: UnGoo thaws; revisit if users complain.
- **Freeze vs. levers.** Should globals respect the mask? Yes — the mask
  scales `globalDisp` too, or "frozen" stops meaning anything the moment
  someone pulls the Twirl lever. (One multiply in `WARP_FRAG`.)
- **Sheen rendering.** A subtle blue-white overlay where w > 0 while the
  Freeze dome is selected; never rendered into export (it's UI chrome,
  not document).
