# 0009 — The Wobbulator

- **Status:** proposed
- **Date:** 2026-08-07

**One-liner.** A second row of small knobs under the six levers that
makes any lever *oscillate* — the photo breathes, throbs and shimmers on
its own, and a GOOvie of it loops perfectly with zero keyframes punched.

## The feel

Pull Bulge halfway up. Now turn the little knob under it. The picture
starts to pulse — in, out, in, out, around the position you set. Add a
slow wobble on Twirl and the whole frame breathes and rocks at the same
time. Nothing has been recorded, nothing has been punched, no strip has
been opened: the console is simply alive, and the Out tray will hand you
a seamless four-second loop of it.

Each lever gets a *rate* in cycles-per-loop (1, 2, 3, 4, 6, 8 — detented,
integers only) and a *depth*. Integers are the whole trick: a loop that
contains a whole number of cycles is seamless by construction, so every
Wobbulator movie loops without a stitch, forever, in both MP4 and GIF.

## Why Meltorama should have it

**1. The best output the app has is the hardest to reach.** A GOOvie
needs at least two keyframes, and keyframes need the punch–goo–punch
loop, an open strip, and a mental model ("a pin is a bookmark, not a
canvas" — PLAN.md §4.1 spends two revisions explaining it). The
Wobbulator produces a finished, loopable, exportable animation from one
knob. It is the on-ramp the GOOvie never had, and everything it teaches —
that motion lives between states, that the strip is optional — makes the
real strip easier to arrive at afterwards.

**2. It is the most 1999 object that could possibly be added.** A
modulation panel with a row of small knobs under the main levers is
*exactly* the industrial design language this app has committed to: milled
bezels, detent levers, neon domes, a horizon grid. Kai's funware was
about consoles that felt alive before you touched them. A wobble row is
that idea in one control.

**3. It needs no shader work at all.** This is the strongest technical
argument and it deserves to be stated plainly: the levers already ride
the warp pass as `u_g[6]` uniforms, uploaded per frame. The Wobbulator
evaluates six sines on the CPU and uploads the *resulting* lever values.
`WARP_FRAG` does not change by one character. `GlobalField` does not
change. The entire feature is a pure function and a frame clock.

**4. It rescues the levers from being one-shot.** Today you set Twirl
and it sits there. Six analytic warps, each capable of continuous
motion, all frozen at a constant. The machinery for animated globals
exists (movie export already lerps levers CPU-side between pins); the
Wobbulator is that capability exposed directly instead of only as a
by-product of keyframing.

## How it works in this engine

**The state.** `GlobalParams` keeps its six floats and its `u_g[6]` wire
contract untouched. A sibling type carries the modulation:

```kotlin
@Serializable
data class GlobalWobble(
    /** Cycles per GOOvie loop, 0 = still. Integers keep loops seamless. */
    val rate: List<Int> = List(6) { 0 },
    /** Excursion either side of the lever's set position, [0,1]. */
    val depth: List<Float> = List(6) { 0f },
)
```

Lists rather than `IntArray`/`FloatArray`, deliberately: a `data class`
holding arrays gets `equals`/`hashCode` that compare by *reference*, so
two identical wobble rigs would not be equal. That is not academic here —
this is document state, and "unsaved" in this app means `savedSignature`
differs from disk (PR 17). A wobble rig that never compares equal to
itself would report unwritten changes forever and defeat the autosave
checkpoint policy. `GlobalParams` avoids the same trap by holding six
named floats and only becoming an array at the uniform boundary
(`toArray()`); if the six-of-each shape reads better as named fields
here too, that is the closer parallel and the safer one.

**The evaluation.** One pure, testable function — the natural home is
beside `MovieSpec`, which already owns frame counts and timing and is
already pure:

```kotlin
fun leversAt(base: GlobalParams, w: GlobalWobble, phase: Float): GlobalParams
// value_i = clamp(base_i + depth_i * sin(2π · rate_i · phase), -1, 1)
```

`phase` is `frameIndex / frameCount` during export and a preview clock
in the editor. Never a wall clock in the document; the phase is derived
from the frame index, so **export reproduces preview exactly** and two
renders of the same document are identical — the same discipline
`GlobalField`'s integer-hash noise was written under.

**Where it composes.** Movie export already interpolates levers between
keyframe pins on the CPU. The wobble is evaluated on top of that
interpolated base, so a strip and a wobble together give an authored
motion with a shimmer on it, rather than one overriding the other. That
ordering is a decision, and the reason for it is that a lever's *set*
value is what the user aimed at; the wobble is what happens around it.

**Document, not history.** Levers are already document state rather than
history entries (PLAN.md §4.1: "center = identity, pulling back undoes
exactly, Reset zeroes them; undo/redo stay stroke-only"). The wobble
rig follows the same rule, serializes into `project.json` beside the
levers, and Zero-all zeroes it.

## Cost, risks, honest trade-offs

- **The preview needs a clock.** The editor renders
  `RENDERMODE_WHEN_DIRTY`, which is a deliberate battery decision. A
  wobbling lever means continuous rendering while any wobble is
  non-zero. That is the real cost of this feature and it should be
  scoped honestly: render continuously only while at least one depth is
  non-zero, and stop on `ON_PAUSE` like everything else.
- **Cycles-per-loop hides a frequency, and the frequency is the safety
  problem.** This is the most serious flaw in the proposal as first
  drafted, and it is arithmetic rather than opinion. A segment is
  `GoovieTimeline.SECONDS_PER_SEGMENT` = 1.2 s, the shortest strip is
  two keyframes (one segment), and the export speed control goes to 4×
  — so the shortest loop this app can produce is **0.3 s**. Eight cycles
  in 0.3 s is **26.7 Hz**, which is not a stylistic choice; it is the
  middle of the photosensitive-seizure risk band, and it would be baked
  into a file the author then shares with people who never chose it.

  So the detents cannot be a fixed list. **Cap the frequency, not the
  cycle count**: `rate / loopSeconds ≤ 3 Hz`, the WCAG 2.3.1 "three
  flashes or below threshold" line, evaluated against the loop the
  document will actually export (`MovieSpec.durationSeconds` already
  computes it from keyframe count, speed and fps). Rates above the cap
  are simply not offered for that document, and shortening the loop or
  raising the export speed takes the high detents away again. The
  guarantee is then a property of the file, on every device, for every
  viewer.

  For the same reason the *export* must **not** consult the exporting
  device's reduced-motion setting. That would make one document produce
  different files on different phones — the reproducibility this whole
  proposal rests on — and would silently hand back a still image to a
  user who deliberately authored an animation. Bounding the hazard at
  the source is strictly stronger: it protects the audience even when
  the author has no accessibility settings enabled at all.
- **Vestibular comfort in the editor is a separate question** with a
  separate answer. Full-frame oscillation makes some people ill well
  below any seizure threshold, and it would start the moment a knob is
  turned. The *editor preview* should respect the system reduced-motion
  setting — with animations disabled it holds at phase 0 and the knob
  shows a small "preview paused" state. That is about the person
  authoring; the frequency cap above is about everyone downstream, and
  neither substitutes for the other. Settings work (ANALYSIS
  K3-15/SOL-35) should probably land first, since that is where such a
  control belongs.
- **Six knobs is a lot of console.** The levers panel is already six
  full-height levers on a phone. A knob row under them is plausible at
  360 dp only if it is genuinely small — and K3-1 records that the top
  rail already overflowed at that width once. Worth prototyping the
  layout before the logic; the logic is the easy half.
- **Discoverability cuts both ways.** A knob that makes the picture move
  by itself is either the most delightful thing in the app or an
  accidental seizure of a control the user did not mean to touch. It
  needs a detented zero and a visible at-rest state.

## Declined variants

- **Free-running rates (2.7 cycles per loop).** More expressive, and it
  breaks the seamless loop that is half the point. Detented integers.
- **Waveforms other than sine (square, ramp, sample-and-hold).** Not
  because they fail to loop — a sawtooth at an integer rate returns to
  its starting value exactly as a sine does; the seam is fine. The
  problem is inside the loop: both square and ramp are *discontinuous
  once per cycle*, so the picture jump-cuts `rate` times per loop rather
  than moving. On a full-frame warp that is a flicker, and a flicker is
  the thing the frequency cap above exists to keep out. Sine and — 
  arguably — triangle are the two that are both periodic and
  continuous. Ship one.
- **Wobbling the brush size or strength instead.** That modulates
  authoring rather than the document, so it cannot be replayed from the
  stroke log deterministically. The levers are wobble-able precisely
  because they are document state read at render time.
- **An LFO per stroke.** See above, and see also that this app's strokes
  are already stamps on a field with no time axis of their own.
