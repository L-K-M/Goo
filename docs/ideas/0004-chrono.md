# Tool idea 0004 — Chrono (paint back in time, but not all the way)

> **Status: proposal.** No engine code ships with this document. If the
> idea is taken, it becomes its own implementation PR with a roadmap row
> in PLAN.md — and, given what it does to the stroke log's shape, quite
> possibly an ADR. Ideas live in `docs/ideas/`; decisions live in
> `docs/decisions/`.

**One-liner.** UnGoo erases back to the original photo. Chrono erases
back to a **keyframe** — pick a frame off the strip and paint that
version of the picture into this one.

## The feel

You have gooed a face through six punches and gone too far. The chin is
wrong now; it was perfect at frame 2. Today the options are undo (which
takes the eyes back too) or fixing it by hand. With Chrono you tap
frame 2 on the strip, take a soft brush, and wipe the chin. The chin
comes back. Everything else stays where it is.

The same gesture used deliberately is where it gets interesting: paint
frame 5 *into* frame 1 and half the face is melted while the other half
is not — in one document, with no layers, no masks, and no compositing
step. The strip stops being an output format and becomes a set of
alternate takes you can brush between.

## Why Meltorama should have it

**1. The app has exactly one "before", and it is the wrong one.**
UnGoo's target is identity — the untouched photo. That is the only prior
state any tool can reach. But the moment a user punches a keyframe they
have *told the app* that this state matters. The document is full of
meaningful earlier versions and no brush can see any of them.

**2. It turns the GOOvie strip into an editing surface.** The keyframe
strip is expensive machinery — revision pins, endpoint materialization,
revision-keyed caches, the tween shader path — and today it earns its
keep only at export time. Users who never make a movie get nothing from
it. Chrono gives the strip a job in still work, which roughly doubles
the return on the most complex subsystem in the app.

**3. It answers the actual reported confusion.** PLAN.md §4.1 records
the user report that produced two revisions of the keyframe design:
*"how do I edit the second step?"* The answers were "editing stays live"
and "re-punch to update". Both are correct and both are about writing to
a keyframe. Nobody added the other direction — reading *from* one. That
is what the question was really asking.

**4. It is one shader line.** RELAX blends the field toward a blurred
copy of itself. Chrono blends it toward a different field:
`mix(cur, texture(u_target, v_uv), w * BLEND_STEP)`. The endpoint fields
it needs are already materialized and cached by revision id, because
that is precisely what scrubbing a tween does.

## How it works in this engine

**The kernel.** A new `StampMode.RECALL` (shaderId 6). Its branch is
RELAX's with the four-tap blur replaced by a single read from a second
field sampler:

```glsl
} else if (u_mode == 6) {          // RECALL
    vec3 target = texture(u_targetField, v_uv).xyz;
    next = mix(cur, target, w * 0.22);
}
```

Note what comes along for free: because the whole `vec3` is mixed, a
Chrono stroke also restores the *Fusion mask* as it was at that frame.
Paint back to before the fusion was applied and the second photo
recedes. Nobody has to write that.

**Binding the target.** `STAMP_FRAG` currently takes one field sampler;
this adds a second. `WARP_FRAG` already carries exactly this pair
(`u_field` / `u_fieldB`) for tween rendering, and `GlWarpRenderer`
already knows how to materialize a pin's field by replay and cache it by
revision id (PLAN.md §4.1: "only the active segment's two endpoint
fields are materialized… cached by stable revision ID, slot-swapped for
adjacent segments"). Chrono asks that machinery for one more slot rather
than inventing any.

**The document change, which is the whole risk.** Every stroke in this
app is self-contained: a tool, its parameters, its stamps. A Chrono
stroke is not — it refers to another revision. `Stroke` gains a nullable
`targetRevisionId`, and with it four consequences that have to be
accepted deliberately:

1. **Replay ordering.** Rebuilding the field for revision R may require
   first rebuilding the field for target T. `StrokeLogSnapshot` already
   writes "every reachable revision once, parents before children"
   (PR 17), so the serialized order is available; the replayer needs to
   follow references, not just the parent chain.
2. **No cycles, by construction.** A keyframe can only pin a revision
   that existed when it was punched, and revision ids are never reused,
   so references always point backwards. The reference graph is a DAG.
   Worth a unit test that says so, because it is the property the
   replayer's termination depends on.
3. **Retention.** A Chrono stroke keeps its target revision alive for as
   long as the stroke exists, exactly as a keyframe pin does. Deleting
   the keyframe must therefore *not* invalidate the stroke — the pin and
   the stroke are two independent references to one immutable revision,
   which is precisely the model SOL-6 built.
4. **Replay cost doubles in the worst case.** An export whose log
   contains a Chrono stroke replays the target chain as well as the main
   one. The revision-keyed cache absorbs the common case (all Chrono
   strokes in a session usually aim at the same one or two frames); the
   pathological case is a stroke per frame across a 64-frame strip, and
   the answer to that is REVIEW G-6's field checkpoints, again.

## Cost, risks, honest trade-offs

- **This is the most invasive idea in `docs/ideas/`.** Every other
  proposal here is a new kernel; this one changes what a stroke *is*.
  The stroke log is the document, and the document stops being a linear
  chain of self-contained edits. That deserves the ADR.
- **Schema bump.** `project.json` grows an optional field per stroke;
  old projects load with it null, which is the whole compatibility
  story, but it is a schema change on a store whose loader deliberately
  "refuses rather than half-restores".
- **A UI problem that is not solved here.** The tool needs a *target*,
  and the strip is where targets live — so arming Chrono has to open or
  highlight the strip, and the brush rail and the strip currently do not
  talk. The smallest version: Chrono is only selectable when the strip
  has at least one keyframe, and it targets the currently selected one,
  reusing the selection that already exists. That is a real constraint,
  not a workaround: it makes the tool teach the strip.
- **Naming.** "Chrono" is a placeholder that sounds like a watch. The
  console voice would probably prefer **Rewind**, and the bead would be
  a neon ◄◄. Compare with ANALYSIS's "Rewind Gum" idea, which is a
  different thing (a preview of one undo) and should not end up with a
  colliding name.

## Declined variants

- **Paint back to "N strokes ago" instead of to a keyframe.** No anchor
  the user chose, no stable identity to record in the stroke, and the
  meaning of the reference changes as history moves. Keyframes exist
  because they are the states a user deliberately marked; use those.
- **A global "morph between frames 2 and 5" slider.** That is the tween
  scrub, which already exists. Chrono's value is that it is *local*.
- **Copying the target field into the live field wholesale on tap.**
  Cheap, and it is just an undo with extra steps.
