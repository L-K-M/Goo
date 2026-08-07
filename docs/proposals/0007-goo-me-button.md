# 0007 — The Goo-Me button

- **Status:** proposed
- **Date:** 2026-08-07

## The tool

One more dome on the rail, labeled **Goo Me**. Tap it and the app deals a
small, seeded recipe onto the canvas: two or three strokes from curated
tools at curated strengths, plus a lever pull or two — played in live, so
you watch the goo happen. Don't like it? Tap again. It's a slot machine
whose jackpot is your friend's face with a spiral cheek and one
magnified eyebrow.

Every deal is a real edit: real strokes in the log, real lever positions
in the document. Undo it, tune it, punch it into a GOOvie, export it —
there is nothing fake or sandboxed about the result.

## Why it belongs in Meltorama 2000

Kai Krause's design philosophy was the **reward-based interface**: KPT's
panels famously revealed bonus functions as you explored, and the
delight was the surprise. A chance button is that philosophy compressed
into a single control — and it solves three real problems at once:

1. **Blank-canvas paralysis.** New users open a photo and freeze:
   nine brushes, six levers, no idea what "good" looks like. One tap
   shows them three tools used well, in combination, on their own photo.
   It is the onboarding tutorial that doesn't feel like one.
2. **Discovery.** Nobody finds Smudge-or-Nudge nuance on their own; a
   recipe that uses it, visibly, teaches the palette by example.
3. **The loop.** Tap → laugh → tap → laugh → *keep that one* → tune it.
   That is the exact loop the whole app exists to create, shortened to
   one button.

It also showcases the architecture: everything Goo Me can deal is
something the user could have painted, which means every deal silently
demonstrates undo, levers, and the stroke log.

## How it fits the engine

The only feature here is a **recipe generator**; the engine needs
nothing new at all.

- A deal synthesizes ordinary `Stroke`s (tool, radius, strength, a
  resampled stamp path) and applies ordinary `GlobalParams` deltas.
  Because they *are* strokes, they log, undo, replay at full export
  resolution, persist in projects, and tween in GOOvies with zero
  special cases — the "no per-tool code paths" property working as
  designed.
- The generator is pure-JVM `engine/core` code (a `GooMeRecipe(seed)`
  function returning strokes + lever targets), property-tested like the
  rest of core: radii in range, stamps inside the frame, strengths
  within curated bands, placement biased toward the image's salient
  center region.
- Determinism: the seed is recorded with the batch, so a reloaded
  project replays the exact accident the user fell in love with — a
  rerun of the RNG would be a different face. (Same rule the Static
  lever follows: fixed seed, no per-driver drift.)
- One undo step per deal: the batch enters the log as a single history
  entry, because "undo the joke" is the second most common interaction
  after "deal again".
- The play-in animation is just the strokes being stamped on the pump
  clock the editor already runs — the user literally watches the recipe
  apply.

Cost: one pure-JVM generator with tests, one dome, one "deal" path in
the ViewModel that reuses the existing stroke-application entry point.
No GL, no serialization format, no engine changes.

## Open questions

- **Recipe curation.** The generator's taste *is* the feature: strength
  bands must stay on the funny side of horrifying, and combos should be
  authored pairs (Grow+Vortex, Melt+Squeeze) rather than uniform random
  picks. Ships with a small hand-tuned deck.
- **Themed decks later.** Portrait / landscape / food decks are a
  follow-up once the salient-region heuristic proves itself.
- **Lever restore.** A deal that moves levers should remember their
  prior positions in the same history entry, so undo restores the whole
  table, not just the strokes.
