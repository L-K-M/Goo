# 0003 — Strokes that reference revisions

- **Status:** accepted
- **Date:** 2026-08-08

## Context

Proposal 0008 (Chrono) asks for a brush that erases back to a *keyframe*
rather than back to the original photo. It asked for this ADR by name,
because it is the first feature that changes what a stroke **is**.

Every edit in this app has been self-contained since ADR 0001: a tool,
its parameters, and a list of stamps. A revision is that stroke plus a
parent pointer, so the document is a chain of independent edits and
replaying revision R means walking R's parents and running each stroke's
stamps in order. Nothing a stroke contains has ever pointed outside
itself.

A Chrono stroke has to. Its kernel blends the live field toward *another
field* — the one a keyframe pinned — and that field is identified by a
`StrokeRevisionId`. The alternatives that avoid a reference are all
worse:

- **Bake the target field into the stroke** as pixels. This is the
  bitmap-snapshot document model ADR 0001 rejected, reintroduced one
  stroke at a time: it breaks resolution independence (the export
  replays at full res; a baked field is whatever the preview was), and
  it makes a stroke's size depend on the image.
- **Bake the target's stroke list into the stroke.** Correct, but it
  duplicates history — and the duplicate does not share structure with
  the original, so a document with ten Chrono strokes aimed at one
  keyframe carries eleven copies of the same prefix. `StrokeRevision`
  exists precisely to stop that.
- **Reference "N strokes ago" instead of a revision.** Declined in the
  proposal, and rightly: the meaning of the reference changes as history
  moves, so the same document replays differently later.

So the reference is the cheap option, and the question is what it costs.

## Decision

`Stroke` gains a nullable `targetRevision: Long?`. A stroke with a
target is a stroke whose kernel reads a second field, materialized by
replaying that revision. The document is no longer a set of chains; it
is a DAG.

Four things follow, and all four are decisions rather than consequences:

**1. References point backwards, structurally.** A keyframe can only pin
a revision that already existed, and `StrokeRevisionId`s are never
reused, so a target id is always smaller than the id of the revision
containing the stroke. `StrokeLog.restore` already refuses a table whose
*parent* does not appear earlier; it now refuses one whose *target* does
not either. Acyclicity is therefore a property the loader enforces on
every file it accepts, not a property we promise about files we wrote.
Replay termination depends on it.

**2. A reference is a retention root.** `StrokeLog.snapshot` used to walk
`history + pins` and their parent chains. A target is neither a parent
nor a pin, so a naive save would drop the very revision a Chrono stroke
needs and produce a file that loads but cannot replay. The walk now
treats every referenced revision as a root as well, transitively — a
target's own strokes may themselves be Chrono strokes.

This is the sharpest edge in the whole change, and it is invisible in
the common case: while the keyframe still exists it pins the target
anyway, so the bug only appears after the user deletes the keyframe they
painted from. That is exactly the shape of defect that ships.

**3. The pin and the stroke are independent references to one immutable
revision.** Deleting a keyframe does not invalidate a Chrono stroke that
aimed at it. This is not new machinery; it is what the revision model
was built for.

**4. Resolution happens at the boundary, not inside the field.** The
renderer is handed a `(StrokeRevisionId) -> List<Stroke>?` resolver, the
same way it is handed keyframes. `StrokeRevision` stays immutable and
ignorant of the log; the log stays ignorant of GL. A stroke records
*which* revision, never a pointer to one.

## Consequences

- **Replay cost can double.** Rebuilding a revision containing a Chrono
  stroke also rebuilds its target chain. The revision-keyed endpoint
  cache absorbs the common case (Chrono strokes in one session almost
  always aim at the same frame or two). The pathological case — a Chrono
  stroke per frame across a long strip — is bounded by the same field
  checkpoints REVIEW G-6 already wanted, and by nothing else. A target
  that fails to resolve renders as a no-op stroke rather than a crash,
  which is the only safe answer at a GL boundary.
- **Schema growth is additive.** `targetRevision` defaults to null, so
  every existing project loads unchanged. A newer file opened by an older
  build hits the loader's existing unknown-field tolerance and then
  replays the Chrono stroke as an ordinary one — visibly wrong but not
  destructive. That asymmetry is acceptable only because the loader
  never writes back a document it could not fully understand.
- **The strip becomes an editing surface.** Chrono can only target a
  keyframe, so the brush is unavailable until one exists and aims at the
  selected one. That is a real constraint on the UI, deliberately kept:
  it is what makes the tool teach the strip rather than need a second
  target-picker.
- **The invariant "a stroke is self-contained" is gone and cannot come
  back.** Anything that reasons about a stroke in isolation — validation,
  a future merge or import, a diff view — must now ask whether it has a
  target. This ADR is the record that the cost was accepted for one
  feature; a second referencing feature should re-read it rather than
  assume the door is open.
- **Revisit if** the reference graph ever needs to point forward (it must
  not), if replay cost without checkpoints becomes the limiting factor on
  export, or if a second, differently-shaped payload appears on `Stroke`
  — proposal 0016's `PinWarp` is exactly that, and it is a *different*
  decision from this one: it makes a stroke carry something other than
  stamps, where this one makes a stroke point at something else. Neither
  implies the other.

## Note on the proposal's shader id

Proposal 0008 specifies `StampMode.RECALL(6)`. Id 6 is VORTEX — the
proposal was written before 0001, 0010, 0013, 0014 and 0002 landed their
modes. RECALL takes 11. The ids are a wire format read by the shader, so
this is not a detail: reusing 6 would have silently re-pointed every
saved Vortex stroke.
