<div align="center">

# Meltorama 2000

**Goo Your Photos.**

[![CI](https://github.com/L-K-M/Goo/actions/workflows/ci.yml/badge.svg)](https://github.com/L-K-M/Goo/actions/workflows/ci.yml)

Latest release: v<!-- version -->1.0.0<!-- /version -->

</div>

Meltorama 2000 is a fun, fast photo-warping app for Android in the spirit
of Kai's Power Goo, the 1996 "Realtime Liquid Image Funware" — dressed in
the chrome-and-neon retro-future that era promised. Open a photo, drag a
finger through it like wet paint, balloon an eye, shrink a chin, twirl the
whole thing into a spiral — then save or share the result.

Playful on the surface, serious underneath: a Liquify-grade displacement
field engine, full-resolution exports that match the preview pixel for
pixel, unlimited undo, keyframed warp animation you can export as video,
and a Fusion brush that paints a second photo through the first.

> [!NOTE]
> **Offline by design.** Meltorama requests no permissions — not even
> network access. Photos come in through the system photo picker, get
> gooed, and go out through your gallery. Nothing ever leaves the device.

> [!IMPORTANT]
> **LLM disclosure:** this app is developed almost entirely by LLM agents,
> including its reviews. See [AGENTS.md](AGENTS.md) for the operational
> conventions and [PLAN.md](PLAN.md) for the design.

## What's in v1

- **Eight goo brushes** — Smear, Move, Smudge, Nudge, Grow, Shrink,
  Smooth, UnGoo — with per-tool falloffs, a strength lever, and a Mirror
  toggle for instant symmetry. Hold-tools pump like the 1996 original.
- **Global effects** — Bulge, Twirl, Squeeze, Stretch, Spike and Static
  levers that warp the whole photo live and compose with hand-painted goo.
- **Fusion** — pick a second photo and brush it through the first with
  soft edges; fused regions smear, tween and export like any other paint.
- **GOOvies** — punch up to 64 keyframes, scrub the tweens, play the
  loop, and export it at ½×–4× speed as a 1080p MP4 straight from the GPU
  or as a looping GIF.
- **Retro-future console UI** — neon domes in milled chrome bezels, a
  detent lever rig, haptic ticks, and a horizon grid running to the
  vanishing point, in the spirit of KPT funware and 1999 box art.
- **Serious exports** — full-resolution stills (JPEG/PNG) that replay
  the stroke log at export size for pixel parity with the preview;
  MediaStore on Android 10+, share sheet everywhere.
- **Unlimited undo/redo**, confirmed Reset, and an engine that treats
  GPU state as a cache — the stroke log is the document, and everything
  rebuilds by replay after context loss.
- **Saved projects, with nothing to remember** — your goo is always kept:
  a checkpoint when you pause, again when the app goes to the background,
  and once more on the way out. The whole document goes with it (strokes
  and their undo history, keyframes, levers, crop, Fusion photo), shelved
  on the In screen as a preview tile to pick back up. No save prompts, no
  "unsaved changes", and nothing deleted behind your back: the In screen
  shows how much space your goo takes and how much is free, and you throw
  away the ones you don't want — one at a time or all at once. The shelf is a recents
  list — the newest 20 sessions, oldest out, as the screen says. Projects
  live in app-private storage and stay out of cloud backup: the photos are
  yours, and they stay on the device.
- **English and Simplified Chinese**, switchable per app on Android 13+
  without changing the phone's language.

The full design and the PR-by-PR build history live in
[PLAN.md](PLAN.md); known trade-offs and deferred work in
[REVIEW.md](REVIEW.md).

## Building

```sh
./gradlew assembleDebug        # or: scripts/build.sh --debug
scripts/install.sh             # build + install + launch on a device
```

Requirements: JDK 17, Android SDK (set `sdk.dir` in `local.properties` —
see `local.properties.example`). Both build types are signed with the
checked-in debug keystore so any clone produces installable,
upgrade-compatible APKs (a deliberate sideload-only decision — see
`docs/decisions/0002-zero-secret-signing.md`).

## Releasing

`scripts/release.sh X.Y.Z --push` — never hand-edit `versionCode`, never
create a `v*` tag by hand. CI publishes the APK to GitHub Releases.

## Name

**Meltorama 2000** — shortened to **Meltorama** where length matters (the
launcher label, where Android ellipsizes past ~12 characters). "Goo"
survives as the verb, the material, and the name of the animations
(GOOvies), which is why the claim is *Goo Your Photos*.

The applicationId stays `ch.lkmc.goo`, and so do the package and the
repository: changing an applicationId breaks upgrades for everyone who
sideloaded a build, and no user ever sees it. PLAN.md's *Renaming* section
lists every file a future rename would touch.

## License

[Unlicense](LICENSE) — public domain. "Kai's Power Goo" and "KPT" are
referenced as historical inspiration only; this project is unaffiliated
with their past or present rights holders.
