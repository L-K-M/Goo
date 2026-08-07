# 0004 — Echo brush (clone)

- **Status:** proposed
- **Date:** 2026-08-07

## The tool

Touch and hold for a beat to plant an **anchor** — a soft ring shows
exactly what is being sampled. Now drag anywhere else, and the anchored
content flows out under your finger. Paint a second left eye onto a
forehead. Copy the dog's ear onto the cat. Give the baby a beard made of
grandpa's beard. The anchor holds for the stroke; the next stroke can
plant a new one.

The graft is goo, not paste: once echoed, the copy is part of the warp
field, so Smear can stretch it, Smooth can blend its seams away, and
UnGoo can shave it back.

## Why it belongs in Meltorama 2000

The clone stamp is the highest-ceiling creative tool in photo editing —
the one that turns an editor from "filters" into "I made this." In a goo
app it is pure comedy with a craft core: perfect duplicates (a third eye,
a twin nose, a crowd of one) that then take every liquid tool we have.
Nothing else in the palette *adds content*; Echo is the only brush that
can put something where nothing was.

It is also the missing half of Fusion's story. Fusion paints a *second
photo* through; Echo paints *this photo* through, offset. Together they
answer the two questions every user asks within a week: "can I blend in
another picture?" (yes) and "can I copy part of this one?" (currently no).

And it has KPT blood: SuperGoo's construction kit was built on
recombining face parts. Echo is the recombination primitive, without
needing any face detection to be fun.

## How it fits the engine

Copying region S to point P *is* what a displacement field expresses
natively: `D(P) = S − P`. Which leads to the proposal's central surprise —
**Echo needs no new stamp mode and no shader change.**

- An echo stroke is an ordinary `DIRECTIONAL` stroke whose stamp deltas
  are computed by the resampler as `delta = anchor − stampCenter`
  (constant per stroke, up to falloff weighting). The stamp shader
  already stamps arbitrary per-stamp deltas; warp-of-warp composes them;
  `CLAMP_TO_EDGE` sampling handles anchors near frame edges.
- The stroke log already stores stamps with explicit deltas, so replay,
  undo, full-res export, and persistence are exact by construction.
  `Stroke` gains one serializable field — the anchor UV — so export
  replay can recompute the deltas without input.
- Strength and falloff do clone-UI duty for free: strength scales blend,
  FEATHER falloff feathers the graft edges.
- The only genuinely new code is input UX (long-press to plant, ring
  overlay while armed) — and the overlay is a sibling of the brush
  preview ring that already exists.
- Range check: echo deltas are large next to smear deltas, but half-float
  displacement range (±4 UV against a 16F field) is three orders of
  magnitude beyond the worst legal anchor distance of √2.

Cost: one resampler branch, one `Stroke` field, one anchor overlay, one
dome. No shader diff at all — the cheapest high-ceiling tool this
architecture will ever offer.

## Open questions

- **Anchor gesture.** Long-press-to-plant keeps one-finger painting
  sacrosanct; a dedicated "set anchor" toggle mode is more discoverable.
  Proposal: long-press while the Echo dome is selected, with the ring
  preview teaching the gesture.
- **Live vs. fixed sampling.** Sampling through the live field (echo of
  already-gooed content) is the warp-of-warp default and the funny one;
  sampling the pristine original is the Liquify default. Start with live;
  a "pure source" toggle is a follow-up if users ask.
- **Echo from photo B.** Tempting (graft the other Fusion photo at an
  offset), but it tangles the mask channel semantics. Explicitly out of
  scope for v1 of the tool.
