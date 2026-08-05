# ANALYSIS.md — forward backlog

The living base for future work on Goo. Born from the k3 deep review
(2026-08-05, see k3.md for the full original findings and the PR mapping);
everything that review fixed has been cleared out of the backlog below.
IDs are the original k3.md ones (stable; don't renumber — REVIEW.md owns
the G-* series, this file owns K3-*). Upstream PRs #11 (brush size
preview) and #12 (pan/zoom/rotate) landed mid-review and cleared K3-13's
slider half and all of K3-22.

Legend: 🐞 bug · ⚠️ risk · 🔧 improvement · ✨ idea

## Open bugs / risks

- **K3-5** ⚠️ **HEIC photos fail on API 26–27.** BitmapFactory only decodes
  HEIC on 28+; the system picker hands out HEIC from modern galleries, so
  26/27 users hit the "couldn't goo" pane. Graceful today. A fix means an
  ImageDecoder-based decode path with an API-split matrix — small win, two
  dying API levels. Do only if device stats say 26/27 matter.

- **K3-7** ⚠️🔧 **The document dies with the process** (= REVIEW.md G-2).
  Stroke log and keyframes live in the ViewModel; a phone call can erase
  20 minutes of goo. The types are already `@Serializable` and the
  session-file restore path exists — the log just isn't written down.
  Highest-value open item. Shape: ProjectStore (JSON in filesDir),
  write-through on commit, restore on editor entry, plus a Home "resume"
  affordance (that also clears K3-27).

- **K3-9** ⚠️ **Replay cost grows with pumped-tool stamps** (= REVIEW.md
  G-6) — and GOOvie playback hitches at segment boundaries: a segment
  crossing mid-playback replays a whole stroke prefix on the GL thread
  (`materializeInto`) inside the frame loop, so heavy logs stutter exactly
  at each keyframe. REVIEW.md's fix (field snapshot checkpoints every N
  strokes) cures both. Do when replay latency is user-visible; measure on
  a low-end device first.

- **K3-10** ⚠️ **`renderMovie` is one monolithic GL runnable** (= REVIEW.md
  G-7): backgrounding mid-export freezes the app for the remainder;
  64-keyframe strips over heavy logs are ANR territory. Fix = chunked
  rendering (renderer-held MovieSession, ~30 frames per runnable,
  self-requeue). Do before raising movie length.

- **K3-11** 🔧 **Preview decode is always 2048** regardless of view size or
  GPU class. On small/low-end devices a ~1024 preview would halve texture
  upload and warp-pass fill with no visible difference. Needs a
  device-class heuristic that's hard to get wrong — measure first.

## Deferred features (designed, not scheduled)

- **K3-15** ✨ **Settings screen** (PLAN.md §6.4): haptics on/off (always
  on today), sound on/off (when K3-16 lands), export defaults (format,
  JPEG quality). Plumbing: prefs repository + a CompositionLocal threading
  haptics through every candy control. Entry point: gear bead on Home next
  to About.

- **K3-16** ✨ **Squishy sounds** (PLAN.md §2 promised them; roadmap #6
  said "optional"). Delightful version: procedurally synthesized PCM
  squishes — no assets, no network, no permissions (AudioTrack).
  Stroke-start pop, pump squelch keyed to stamp rate, lever detent click,
  keyframe punch. Depends on K3-15 for the off switch.

- **K3-17** ✨ **GIF export** — explicitly deferred by PLAN.md §4.1
  ("GIF secondary… deferred to the polish pass"). MP4 covers the share
  case; a palette encoder is a chunk of work. Revisit if users ask.

## Visual polish

- **K3-19** ✨ **Textured goo table.** The letterbox is a flat clear color;
  KPT's desk had felt. A subtle two-stop vertical gradient (or faint
  vignette) behind the image quad adds depth for ~free — one extra
  fullscreen quad in the warp pass. Taste-level, near-zero risk.

- **K3-20** ✨ **Keyframe thumbnails.** The strip's numbered beads were an
  acknowledged stand-in; real thumbnails = tiny GL renders cached per pin
  (the tween machinery already materializes endpoint fields — read back a
  96px thumb when a pin is punched or the log prefix changes). Lovely,
  medium-heavy.

- **K3-21** ✨ **Candy-fy the stock M3 sliders** (brush rail, export
  quality): ball thumb + grooved track in the CandyLever idiom, so nothing
  stock remains on screen. Pure polish.

## Delight ideas (novel, quirky)

- **K3-23** ✨ **Mirror modes: horizontal + quad (kaleidoscope).** The
  mirror twin is one parametric stamp today; a quad mode is three twins
  (mirrorStamp generalizes to a mode enum). Cheap, very KPT, hugely
  photogenic for faces/patterns.

- **K3-24** ✨ **Ping-pong GOOvie playback** — bounce instead of hard loop:
  one branch in `GoovieTimeline.advance` plus direction state. Loops look
  twice as polished for zero new machinery. Small, lovely.

- **K3-25** ✨ **"Goo me" dice button** — random lever/brush preset for
  instant fun; doubles as an accessibility ramp for users who can't do the
  stroke gesture (the canvas is otherwise gesture-only — currently the
  app's biggest a11y hole).

- **K3-26** ✨ **Stroke haptics** — a soft tick per emitted stamp batch
  makes smearing feel like texture under the finger. VM already batches
  stamps per event; the screen can tick on non-empty batches. Small.

- **K3-27** ✨ **Home "recent goo" resume** (PLAN.md §6.1's "later" item) —
  blocked on K3-7; lands naturally with it.

## Declined (with reasons — don't re-raise without new evidence)

- **Shake-to-reset** — sensor gimmick with accidental-loss risk; Reset
  already has the confirmed-undoable treatment that fixed KPT's
  most-criticized flaw.
- **Keeping dead deps "for future tests"** (GLM on #17) — the classpath
  stays lean on purpose; re-adding turbine/coroutines-test with the first
  coroutine test is one catalog edit.
- **Timber/Log for best-effort file deletes** (GLM on #19) — no logging
  convention exists in the app; session sweeps are the safety net.

## Done (cleared from the backlog; kept for archaeology)

| Entry | What | PR |
| ----- | ---- | -- |
| K3-1 | Top rail overflow on 360dp screens → scrollable rail | #13 |
| K3-2 | History ops orphaning live-stroke pixels → discard-guard rebuild | #14 |
| K3-3 | Pumped tools dead on tap → stamp at touch-down | #15 |
| K3-4 | Movie work-file IO on main thread → suspend IO | #16 |
| K3-6 | Dead dependencies removed | #17 |
| — | lint SuspiciousIndentation false positive restructured | #18 |
| K3-8 | Fusion photo B removable | #19 |
| K3-14 | Keyframe punch bead on the brush rail | #20 |
| K3-13 | Brush cursor ring while painting | #21 |
| K3-12 | Bundled sample images + generator | #22 |
| K3-22 | Pinch/pan/zoom canvas | upstream #12 |
