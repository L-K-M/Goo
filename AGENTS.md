# AGENTS.md — operating manual

The operational source of truth for agents (and humans) working on Goo.
When you learn something durable about how this repo behaves — a quirk, a
footgun, a changed convention — **update this document** in the same PR.

## What this app is

A KPT-Goo-style real-time photo-warping app. Read [PLAN.md](PLAN.md) first;
it is the constitution (product framing, engine architecture, roadmap).
Deviations from PLAN.md get recorded here as they happen.

## Build, test, lint

```sh
./gradlew testDebugUnitTest    # the whole test suite (JVM-only, by design)
./gradlew lintDebug            # hard CI gate — keep it clean
./gradlew assembleDebug        # debug APK
scripts/build.sh               # release APK staged into dist/
scripts/install.sh             # build + install + launch on a device
```

- JDK 17. Android SDK path via `local.properties` (`sdk.dir=…`) or
  `ANDROID_HOME`. Agent sessions: `.claude/setup-android.sh` bootstraps the
  SDK idempotently (wired as a SessionStart hook).
- Versions are pinned ONLY in `gradle/libs.versions.toml`. Never add an
  ad-hoc version to a build file; never restate catalog versions in docs.

## Toolchain quirks — don't "fix" these

- `compileSdkVersion("android-37.0")` (string form) is deliberately paired
  with `android.suppressUnsupportedCompileSdk=37` in `gradle.properties`.
  The two move together or not at all.
- There is NO `kotlin-android` plugin: AGP 9 provides built-in Kotlin
  support. Only android-application, kotlin-compose, kotlin-serialization,
  ksp, and hilt are applied.
- `app/debug.keystore` is checked in ON PURPOSE and signs BOTH build types
  (`.gitignore` whitelists it). Zero-secret CI, reproducible builds,
  sideload-only distribution — see `docs/decisions/0002`. Do not "rotate"
  it, do not add signing secrets without a recorded product decision.

## Architecture in one paragraph

Single module `:app`, packages first. MVVM with one immutable UiState per
screen (StateFlow from a ViewModel), Hilt DI, single activity, Compose +
Material 3 with a custom always-dark "goo table" theme. The warp engine is
a GLES 3.0 backward-mapped displacement field; brushes stamp kernels into
the field, the **stroke log is the document** (GPU state is a rebuildable
cache), exports replay the log at full resolution. Engine decision logic
lives in `engine/core` as pure JVM classes.

## Conventions and footguns

- **Tests are JVM-only** (`testDebugUnitTest`); keep decision logic out of
  composables and the GL renderer so it stays testable. No androidTest
  directory exists; adding one means adding the emulator CI job too.
- **"Works in debug, breaks in release"** is almost always a missing R8
  keep rule for a new reflection/serialization entry point — check
  `app/proguard-rules.pro` first.
- The shader math and `engine/core` reference implementations must stay
  trivially close; when one changes, change both, and let the unit tests
  pin the semantics.
- Brush geometry is computed in normalized source coordinates, never screen
  pixels — preview/export parity depends on it (PLAN.md §5.4).
- **A GOOvie keyframe is a pin, not a canvas.** It stores
  `(revision, globals)` — the immutable `StrokeRevision` it was punched
  from — so there is no "editing keyframe 2" in place: you goo the photo
  and re-punch (`repunchSelectedKeyframe`). Two deviations from the
  original PLAN.md §4.1 wording, both recorded there:
  - Editing is NOT paused while the strip is open. The one real
    constraint is that stamps only ever reach the *live* field, so any
    edit inside the strip first flips `UiState.goovieLive` and clears the
    tween. Don't reintroduce an edit lock to "protect" the pins.
  - Pins are revisions, NOT prefix counts (and never a history cursor).
    A count indexes into `StrokeLog.strokes`, which shrinks on undo —
    that is precisely how undo used to flatten a whole strip. Revisions
    are shared, immutable, and outlive a truncated redo branch, which is
    why `rebuild` no longer invalidates the renderer's endpoint cache
    (it is keyed by `StrokeRevisionId`, which is never reused, so it
    can't lie). Don't "optimize" it back to a count.
- Runtime revision graphs are intentionally non-serializable. Project
  persistence must store a normalized revision table plus revision IDs
  rather than recursively serializing shared parent nodes.
- The app has **no INTERNET permission**. Keep it that way; adding any
  network dependency is a product decision requiring an ADR.
- App display name lives ONLY in `strings.xml` — `app_name` (short, for
  the launcher) plus `app_name_full`/`app_model` for the Wordmark lockup.
  Never hardcode "Meltorama" in a composable (rename checklist: PLAN.md
  "Renaming"). The applicationId stays `ch.lkmc.goo`; "goo" remains the
  verb and the material ("Goo Your Photos", GOOvies, UnGoo).
- The design language is a retro-future console: gunmetal panels with
  milled bevels (`Modifier.chromePanel`), neon domes in swept chrome rims
  (`ChromeButton`/`ChromeIconButton`), one light source for every bevel
  (above, slightly left). Colors come from `ui/theme/Color.kt` — no ad-hoc
  Color(0x…) in screens.
- Scripts follow the family house style: header comment doubles as
  `--help` via the awk one-liner; `==>` / `--` / `!!` log prefixes;
  `set -euo pipefail`.
- Sample images must be public domain / CC0 with provenance recorded in
  this file when added. Current samples (`app/src/main/assets/samples/`):
  `goo-guy.png` and `candy-blobs.png` are generated procedurally by
  `scripts/generate_samples.py` from the app's own palette — provenance is
  this repo, license is the project's (Unlicense). Regenerate with
  `python3 scripts/generate_samples.py`.

## CI/CD

Three workflows (details: [CICD.md](CICD.md)): `ci.yml` (tests + lint +
debug APK on every PR/main push), `release.yml` (v* tags → verified,
published APK), `zai-code-review.yml` (GLM 5.2 reviews every PR; respond
per [CLAUDE.md](CLAUDE.md)). Family contract on every workflow:
least-privilege permissions, explicit concurrency, timeouts, wrapper
validation.

## Releasing

`scripts/release.sh X.Y.Z --push` (shared lkm-release engine) bumps
versionName, auto-increments versionCode by exactly 1, rewrites the README
version marker, commits, tags `vX.Y.Z`, pushes. **Never hand-edit
versionCode. Never create a `v*` tag by hand.** Bump the most-minor version
component + versionCode on every non-trivial change set.

## Review process

PRs are reviewed by GLM 5.2 automatically. Findings are triaged
apply/decline/refute per [CLAUDE.md](CLAUDE.md); declined findings and
their reasons accumulate in [REVIEW.md](REVIEW.md) so later rounds (and
later agents) don't flip-flop. Point-in-time review snapshots archive under
`docs/reviews/`.
