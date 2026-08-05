<div align="center">

# Goo

**Squish, smear and stretch your photos like wet paint.**

[![CI](https://github.com/L-K-M/Goo/actions/workflows/ci.yml/badge.svg)](https://github.com/L-K-M/Goo/actions/workflows/ci.yml)

Latest release: v<!-- version -->0.1.0<!-- /version -->

</div>

Goo is a fun, fast photo-warping app for Android in the spirit of Kai's
Power Goo, the 1996 "Realtime Liquid Image Funware". Open a photo, drag a
finger through it like wet paint, balloon an eye, shrink a chin, twirl the
whole thing into a spiral — then save or share the result.

Playful on the surface, serious underneath: a Liquify-grade displacement
field engine, full-resolution exports that match the preview pixel for
pixel, unlimited undo, keyframed warp animation you can export as video,
and a Fusion brush that paints a second photo through the first.

> [!NOTE]
> **Offline by design.** Goo requests no permissions — not even network
> access. Photos come in through the system photo picker, get gooed, and go
> out through your gallery. Nothing ever leaves the device.

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
  loop, and export it as a 1080p MP4 straight from the GPU.
- **Candy UI** — springy glossy beads, a detent lever rig, haptic ticks,
  and one dark table for everything, in the spirit of KPT funware.
- **Serious exports** — full-resolution stills (JPEG/PNG) that replay
  the stroke log at export size for pixel parity with the preview;
  MediaStore on Android 10+, share sheet everywhere.
- **Unlimited undo/redo**, confirmed Reset, and an engine that treats
  GPU state as a cache — the stroke log is the document, and everything
  rebuilds by replay after context loss.

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

"Goo" is a working title (the applicationId `ch.lkmc.goo` is final either
way). Candidate names are collected in PLAN.md Appendix A.

## License

[Unlicense](LICENSE) — public domain. "Kai's Power Goo" and "KPT" are
referenced as historical inspiration only; this project is unaffiliated
with their past or present rights holders.
