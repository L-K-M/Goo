# 0015 — Goo Whip

- **Status:** accepted — built (roadmap #27)
- **Date:** 2026-08-07

## The pitch

Flick the photo and the goo keeps flying after the finger leaves the glass.

Goo Whip is a velocity-aware directional brush. A slow drag behaves like a
soft, controllable pull. A fast release launches a short decaying tail in the
last direction of travel, stretching hair into a comet, snapping a smile
sideways, or sending a streetlight streaking across the sky.

The current palette asks where the finger went. Whip also asks how it went
there. It turns speed and release into creative material without requiring a
stylus, sensor permission, or new GPU kernel.

## The interaction

- Select **Whip** and drag normally.
- Move slowly for a restrained feathered pull.
- Flick and lift to launch the virtual brush beyond the release point.
- **Size** controls the width of the taffy being caught.
- **Strength** controls how firmly the image follows both the held stroke and
  its launched tail.
- Release speed controls tail length inside a conservative fixed cap.
- A chrome cursor afterimage shows the computed tail for a fraction of a
  second. One haptic snap marks release; neither affects the saved result.
- A tap or a slow lift creates no tail and, if there was no useful movement,
  no stroke.

The tail is short and ballistic, not a free-running simulation. Whip should
feel like snapping a wet ribbon, not waiting for an animation to finish.

## What it makes

- Hair, fur, flame, clouds, and fabric pulled into fast directional streaks.
- Eyes, ears, and mouths launched into exaggerated cartoon poses.
- Comet trails behind lights and repeated objects.
- Controlled motion blur made from actual image geometry rather than a color
  filter.
- Mirrored releases that fire away from each other like a party favor.
- A one-gesture GOOvie setup: punch, flick, punch, then watch a feature shoot
  between poses.

## Why it belongs in Meltorama

### It makes the touchscreen an instrument

After resampling, two current Smear gestures that follow the same path produce
nearly the same edit even if one was careful and one was thrown. Whip gives
performance a visible consequence. That is native to a touch device and more
playful than adding another static falloff profile.

### It has a built-in skill curve

The first flick is funny. With practice, users learn to aim release direction
and speed. The tool therefore works immediately but still rewards feel, like a
toy with good physics.

### It adds dynamics without adding a dynamics engine

Procreate's Liquify exposes Momentum, which keeps an effect moving after
lift-off and describes the result as overshooting the stroke. That validates
the interaction. Meltorama can implement the idea more conservatively by
materializing the entire decaying path as ordinary stamps at release. There
is no clock in the persisted document and no frame-rate-dependent physics.

### It differentiates the directional family

Smear, Move, Smudge, and Nudge currently differ by falloff and strength scale.
Whip earns a palette position through a different gesture lifecycle: the
release is part of the mark. The user can feel why it exists before reading
its label.

## Engine fit

Whip uses `StampMode.DIRECTIONAL` with a feathered falloff. It needs input and
resampling work, not a shader branch.

At release, capture the pointer velocity in view pixels per second, transform
the vector through the inverse `ViewTransform`, and convert it to the existing
aspect-space source convention. Clamp and quantize the resulting speed, then
generate a fixed-step decaying virtual path:

```text
rawSpeed = length(releaseVelocity)
return no tail when rawSpeed < START_SPEED
clampedSpeed = min(rawSpeed, MAX_WHIP_SPEED)
quantizedSpeed = round(clampedSpeed / SPEED_QUANTUM) * SPEED_QUANTUM
return no tail when quantizedSpeed == 0
v[0] = releaseVelocity * (quantizedSpeed / rawSpeed)
p[0] = releasePoint

# p and v hold MAX_WHIP_STEPS + 1 entries, including index 0.
for i in 0 until MAX_WHIP_STEPS:
    p[i + 1] = p[i] + v[i] * FIXED_DT
    v[i + 1] = v[i] * DECAY
    stop when length(v[i + 1]) < STOP_SPEED
```

Feed those points through the same normalized stroke resampler as real pointer
samples. Append every resulting concrete `Stamp` to the in-flight Whip stroke
before pushing it to `StrokeLog`. Replay sees only centers and deltas; it never
recalculates velocity or decay.

This division is important:

- input timing chooses the mark once;
- fixed constants generate its tail once;
- the stroke log stores the answer;
- GL replay remains ordinary directional stamping;
- preview, recovery, export, and GOOvies consume the same answer.

The complete tail should enter the document atomically on release. The
renderer may reveal its precomputed stamps over a short fixed presentation for
the snap effect, but dropped frames must skip presentation frames rather than
skip document stamps. If the surface disappears mid-reveal, rebuilding from
the committed stroke produces the final state.

Historical pointer samples must contribute to the velocity estimate. Android
delivers batched movement precisely so fast gestures are not reduced to the
last displayed frame. A standard `VelocityTracker` or Compose equivalent can
estimate release velocity, but the engine-facing result must be converted to
source coordinates before any virtual points are made.

Mirror expansion happens after tail generation, exactly as it does for the
physical part of a stroke. One Whip, including both its held and launched
sections, remains one undoable `Stroke` with the current serialization schema.

## Honest costs

- Release velocity differs across hardware and touch sampling rates. That is
  acceptable as input feel; deterministic replay begins after the generated
  stamps are logged. Device testing still needs to tune filtering and caps.
- A raw velocity estimate is noisy at lift-off. Use a short recent window,
  include historical samples, and require a clear threshold before any tail
  appears.
- A long tail can cross the photo in one gesture and add many stamps. Cap both
  source-space distance and generated step count; do not let a high-density
  screen create a larger document.
- The inverse view transform is easy to omit. Without it, flick direction and
  speed would change when the preview is zoomed or rotated, violating the
  source-coordinate rule that protects export parity.
- A staged visual reveal briefly leaves document state ahead of GPU state.
  Export and other edits should be gated for that short interval, or the full
  batch should be applied in one GL command before controls unlock.
- Gesture cancellation is not release. Existing visible real stamps may still
  commit under the editor's cancel policy, but cancellation must not invent a
  momentum tail.
- Stylus pressure is deliberately not part of the MVP. Android exposes it, but
  finger pressure is device-dependent and Whip must be complete for every
  user with speed alone.

## MVP acceptance sketch

- A release below the threshold adds no generated stamps.
- Faster releases monotonically produce longer tails until the fixed cap.
- A fixed recorded input sample sequence generates exactly the same stamp list
  in a pure JVM test.
- Tail distance is invariant under screen density, preview zoom, pan, and
  rotation.
- Generated spacing follows the existing brush-radius resampling rule and has
  no holes.
- Mirror transforms the complete physical and virtual stroke correctly.
- One Undo removes the drag and tail together; Redo restores both.
- Rebuild, project reload, full-resolution export, and GOOvie endpoint replay
  match the completed preview.
- Cancellation commits no virtual tail.
- Maximum speed cannot exceed the tested displacement and stamp-count caps.

## Research

- [Liquify - Procreate Handbook](https://help.procreate.com/procreate/handbook/adjustments/adjustments-liquify)
  documents Momentum as continuing a Liquify effect after lift-off so it
  overshoots the stroke. It is direct evidence that release momentum is a
  useful, understandable warp control.
- [Track touch and pointer movements - Android Developers](https://developer.android.com/develop/ui/views/touch-and-input/gestures/movement)
  documents `VelocityTracker` for measuring pointer velocity. It supports the
  capture-side implementation without any sensor or runtime permission.
- [MotionEvent - Android API reference](https://developer.android.com/reference/android/view/MotionEvent)
  documents historical coordinates and event times for batched pointer input.
  Those samples are essential for estimating a fast flick rather than only
  observing the final delivered point.
- [Animate movement using spring physics - Android Developers](https://developer.android.com/develop/ui/views/animations/spring-animation)
  describes stiffness, damping, and start velocity for spring presentation.
  Whip may borrow that visual language for its cursor, while deliberately
  keeping platform animation state out of the persisted edit.

## Recommendation

Include Goo Whip as a mobile-first expressive brush. It makes the user's
gesture matter in a way the current tools do not, creates immediately legible
results, and can be implemented entirely as precomputed ordinary stamps. It
adds the feel of physics without making simulation state part of Meltorama's
document contract.
