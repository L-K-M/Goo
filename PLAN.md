# Goo — Plan

> Working name: **Goo** (`ch.lkmc.goo`). The display name is preliminary and
> deliberately easy to change — see [Renaming](#renaming). Candidate names are
> collected in [Appendix A](#appendix-a--name-ideas).

## 1. What this is

Goo is a fun, fast photo-warping app for Android in the spirit of **Kai's
Power Goo** (MetaTools, 1996) — the original "Realtime Liquid Image Funware".
You open a photo, smear it around with your finger like wet paint, balloon an
eye, shrink a chin, twirl the whole thing into a spiral, then save or share
the result. Simple and silly on the surface, professional-grade underneath:
the same engine architecture as Photoshop's Liquify (a resolution-independent
backward-mapped displacement field), full-resolution exports that are
pixel-faithful to the preview, unlimited undo, and keyframed warp animation.

It is an image **editor**, not a browser, gallery, or camera app. Photos come
in through the system photo picker, get gooed, and go out through MediaStore
or the share sheet. No network access at all — photos never leave the device.

## 2. The KPT Goo heritage — feature map

KPT Goo organized itself into full-screen "rooms". We keep the metaphor,
adapted to a phone:

| KPT Goo (1996)                          | Goo (this app)                                     |
| --------------------------------------- | -------------------------------------------------- |
| **In room** (CD libraries, TWAIN, disk) | Home screen: system Photo Picker + bundled sample images |
| **Goo room** — brush palette            | Editor: Smear, Move, Grow, Shrink, Smudge, Nudge, Smooth, UnGoo brushes |
| Mirror Toggle                           | Mirror mode: strokes applied symmetrically         |
| Global effects (Bulge, Twirl, Stretch, Squeeze, Spike, Static…) | Global effects palette: parametric whole-image warps driven by levers |
| Two dangerous one-click Reset buttons   | Reset lives behind a confirmation and is undoable (fixing Goo's most-criticized flaw) |
| **Fusion room** (blend two images)      | Fusion: paint a second image's pixels through onto the first |
| Keyframe palette, 64 keyframes, GOOvies | Keyframe strip; tweened playback; MP4 (primary) and GIF (secondary) export |
| **Out room** (print, save, movies)      | Export sheet: JPEG/PNG to MediaStore, share intent, movie export |
| Full-screen candy UI, big juicy 3D buttons, levers, squishy sounds | Full-screen immersive editor, round candy buttons, springy Compose animations, haptics, optional sounds |

Deliberately **not** copied: the CD-ROM image libraries, TWAIN, printing, the
text engine, and SuperGoo's face-part construction kit (out of scope for v1).

## 3. Tech stack

Single source of truth for versions: `gradle/libs.versions.toml` (and the
committed wrapper for Gradle itself) — exact numbers live there and only
there. The toolchain matches sibling repo Blipbird, the family's newest:
current Gradle/AGP with AGP's built-in Kotlin support (K2, no
`kotlin-android` plugin), KSP, JDK 17, Compose BOM + Material 3, Hilt,
kotlinx-serialization, DataStore Preferences, navigation-compose.
`compileSdkVersion("android-37.0")` (string form) paired with
`android.suppressUnsupportedCompileSdk=37` — these move together or not at
all. minSdk 26, targetSdk 37.

No network dependencies (no Retrofit/OkHttp) — the app is fully offline; the
manifest requests no INTERNET permission, which is an enforceable privacy
guarantee, not just a promise.

## 4. Architecture

### 4.1 The warp engine (the heart of the app)

**Model.** All warping is a single persistent **displacement field**: a
texture `D` of UV-space offsets over the source image. Rendering is one
backward-mapping pass: `color(p) = src(p + D(p))`. Brushes never touch
pixels; they stamp small kernel quads into `D`. This is how Photoshop
Liquify works internally, and it buys us everything at once:

- **Real-time**: per frame, one scissored stamp pass + one fullscreen warp
  pass on the GPU — 60fps on mid-range devices.
- **Resolution independence**: `D` lives in normalized UV units. Preview
  edits happen against a ≤2048px decode; export re-runs the identical shader
  against the full-resolution decode. What you saw is what you save.
- **Undo/redo ≈ free**: history is a **stroke log** (tool id + parameters +
  resampled stroke points, kilobytes per stroke), not bitmap snapshots.
  Undo = rebuild `D` by replaying the log minus the last stroke (pure GPU
  stamping, milliseconds). Periodic field snapshots bound worst-case replay.
- **Animation ≈ free**: a keyframe is a saved field state; tweening two
  fields is `mix(D₁, D₂, t)` in the shader.
- **Crash/context-loss safety**: GPU state is a cache. The stroke log is the
  document; after EGL context loss the field is rebuilt by replay.

**GL specifics.** GLES 3.0 (universal on API 26+ hardware), GLSurfaceView
(`RENDERMODE_WHEN_DIRTY`, `setPreserveEGLContextOnPause(true)`), ping-pong
RG16F FBO pair for the field (packed-RGBA8 fallback where renderable
half-float is unavailable). Stamps spaced at ~25% of brush radius along the
interpolated stroke path, consuming MotionEvent historical samples; zero
allocation on the touch path. Brush math: `b(p) = strength · falloff(|p−c|/r)
· direction`, smoothstep falloff; every tool is just a different kernel:

| Tool   | Kernel                                                        |
| ------ | ------------------------------------------------------------- |
| Move/Smear | displace along drag delta                                 |
| Grow   | displace radially inward toward sample point (backward map ⇒ magnifies) |
| Shrink | displace radially outward (backward map ⇒ pinches)            |
| Smudge | Smear with smaller radius/pressure                            |
| Nudge  | Smear with heavy damping — fine adjustments                   |
| Smooth | blur/relax the field toward locally-averaged values           |
| UnGoo  | lerp the field toward zero under the brush (localized eraser) |
| Global effects | parametric analytic fields (bulge, twirl, …) composed over the stroke field |

**Export.** Decode the original (EXIF-rotated) at the export cap — the GL
max texture size or the 4096 memory budget, whichever is tighter
(`ExportSize`; five export-sized allocations coexist at the readback peak
— source bitmap, two GL textures, readback buffer, result bitmap: ~320 MB
at the 4096² worst case, so uncapped 48 MP would OOM mid-range devices). Upload, replay the stroke
log into a fresh field with the identical stamp code, run the same warp
shader into an offscreen FBO, `glReadPixels`, save. Sources beyond the cap
downscale (~12 MP output); true full-resolution tiled export
(displacement-bounded source tiles) is tracked as REVIEW.md G-5. Movie
export renders each tweened frame into a MediaCodec input surface (shared
EGL context) and muxes H.264 into MP4; GIF uses a bounded-size palette
encoder.

### 4.2 Package layout

Single module `:app`, Kotlin packages first (no feature modules until a real
boundary justifies one — family convention):

```
ch.lkmc.goo/
  GooApp.kt, MainActivity.kt
  di/            Hilt modules
  engine/
    core/        pure-JVM math: kernels, falloff, stroke resampling,
                 field composition reference impl, undo stack — heavily unit-tested
    gl/          GlWarpRenderer, shaders, ping-pong FBOs, texture utils
    export/      full-res replay, tiling, MediaStore writer, (later) movie encoder
  data/          ImageLoader (subsampled decode + EXIF), SettingsRepository,
                 ProjectStore (stroke-log persistence via kotlinx-serialization)
  ui/
    home/        In room: picker entry, samples
    editor/      Goo room: GL surface + brush palette + controls
    export/      Out room: save/share sheet
    components/  candy buttons, levers, palette wheel
    theme/       the Goo look (dark dimensional backdrop, candy colors)
```

**MVVM, unidirectional**: one immutable `UiState` per screen from a
ViewModel via StateFlow. Decision logic lives in `engine/core` plain classes
so the JVM test suite covers it — composables and the GL renderer stay thin.

## 5. Key design decisions

1. **Displacement field over pixel pushing** — see 4.1. ADR 0001.
2. **Offline by design** — no INTERNET permission, ever. Adding it later
   would be a product decision recorded by ADR, not a convenience.
3. **Zero-secret signing (Kararead model)** — a checked-in debug keystore
   signs both build types, so CI needs no secrets and any clone builds
   upgrade-compatible APKs. Deliberate consequence: sideload-only
   distribution; switching to a real key later breaks upgrades for every
   installed user. Decided day one, documented loudly. ADR 0002.
4. **Preview-res editing, full-res export** — brush geometry is computed in
   normalized source coordinates so the replayed full-res warp matches the
   preview. A golden test compares preview render vs downscaled export.
5. **The stroke log is the document** — GPU textures are disposable caches;
   everything the user made can be rebuilt from the log at any resolution.
6. **JVM-only test suite** (family convention, Kararead-style): engine math
   is pure Kotlin, tested with plain JUnit; no androidTest/emulator matrix
   until something (e.g. golden GL images) genuinely requires one.
7. **Fun is a feature, guardrails included** — KPT's charm (candy buttons,
   levers, playfulness) with its flaws fixed (no destructive one-click
   Reset, unlimited undo, no modal rooms trapping work).

## 6. Screens

1. **Home (In)** — big friendly "Open a photo" button (system Photo Picker;
   no storage permissions), sample images to play with instantly, recent
   project resume (later).
2. **Editor (Goo)** — the app. Full-screen image on a GLSurfaceView; brush
   palette as a candy-button arc; size/strength levers; Mirror toggle;
   undo/redo; Reset (confirmed); global-effects drawer; keyframe strip
   (animation phase); Export button.
3. **Export (Out)** — format (JPEG quality / PNG), save to `Pictures/Goo`
   via MediaStore (`IS_PENDING` flow, API 29+); on 26–28 the legacy branch
   writes to app-owned storage and hands off via the share sheet, because
   shared-collection writes there would need `WRITE_EXTERNAL_STORAGE`,
   which the no-permissions rule forbids. Movie export (animation phase).
4. **Settings/About** — haptics/sound toggles, export defaults, licenses.

## 7. Testing

- `./gradlew testDebugUnitTest` is the suite. Engine math (kernels, falloff,
  resampling, composition, undo, tiler geometry, EXIF orientation mapping)
  is pure JVM code with thorough tests, including property-style cases
  (e.g. UnGoo over anything converges to identity; Smooth is idempotent at
  fixpoint; stamp spacing invariant under resolution).
- A CPU reference implementation of the field composition mirrors the shader
  math and anchors correctness tests; the shaders are kept trivially close
  to it.
- Lint (`lintDebug`) is a hard CI gate from day one.

## 8. CI/CD

Family contract on every workflow: least-privilege `permissions:`, explicit
`concurrency:`, `timeout-minutes:` on every job, wrapper-validation before
any Gradle execution. Details in [CICD.md](CICD.md).

- **ci.yml** — push to main + PRs: `testDebugUnitTest lintDebug
  assembleDebug`, rolling debug APK artifact.
- **release.yml** — `v*` tags: tag↔versionName gate, re-prove tests+lint at
  the tagged commit, `assembleRelease`, sha256 sidecar, GitHub Release.
  No signing secrets (decision 3).
- **zai-code-review.yml** — GLM 5.2 reviews every PR (hardened
  `pull_request_target`: same-repo + non-draft guard, commit-pinned action,
  PR-number concurrency). Review responses follow [CLAUDE.md](CLAUDE.md).
- Releases are cut only with `scripts/release.sh X.Y.Z --push` (shared
  lkm-release engine): bumps versionName, auto-increments versionCode,
  rewrites the README version marker, commits, tags. Never hand-edit
  versionCode; never create a `v*` tag by hand.

## 9. Privacy & licensing red lines

- No INTERNET permission; no analytics, no telemetry, no accounts.
- Bundled sample images must be public-domain or CC0, provenance recorded.
- License: Unlicense (public domain), matching the repo's existing LICENSE.
- No trademark implications: "Kai's Power Goo", "KPT" are historical
  references in docs only, never in app branding or store copy.

## 10. Roadmap — PR-sized steps

Each step lands as one reviewed PR on `main`; CI green and GLM review
steady-state before merge (policy: [CLAUDE.md](CLAUDE.md)).

| #  | PR                     | Contents | Acceptance |
| -- | ---------------------- | -------- | ---------- |
| 1  | Scaffold               | Gradle/Compose skeleton, CI + release + review workflows, scripts, docs, theme stub, hello screen | CI green; app launches |
| 2  | Warp engine + editor MVP | GLES3 displacement engine, stroke log, Smear brush, photo open (picker + samples), undo/redo | smear a photo at 60fps; undo works |
| 3  | Export pipeline        | full-res replay export, MediaStore save (29+ & legacy), share sheet, Out UI | saved JPEG matches preview at full res |
| 4  | Full brush palette     | Grow/Shrink/Move/Smudge/Nudge/Smooth/UnGoo, Mirror, size/strength controls, confirmed Reset | all brushes behave per §4.1 table |
| 5  | Global effects         | Bulge/Twirl/Squeeze/Stretch/Spike/Static + lever UI, composed with brush field | levers warp whole image live |
| 6  | Candy UI               | full KPT-style theme, springy animations, haptics, optional sounds, app icon, samples, onboarding hint | it feels like funware |
| 7  | Keyframes (GOOvies)    | keyframe strip: capture/reorder/delete, tween scrubbing, live playback | record and replay a warp dance |
| 8  | Movie export           | MP4 via MediaCodec/MediaMuxer (EGL encoder surface); GIF secondary | shareable MP4 of the animation |
| 9  | Fusion                 | second image through-paint brush | brush one face onto another |
| 10 | v1 polish + release    | settings, about, README screenshots, release v1.0.0 | tagged release with APK |

Order may adapt (e.g. Fusion before keyframes) if review findings suggest it;
the roadmap is a plan, not a contract.

## Renaming

The name "Goo" is preliminary. To rename later: `app_name` in
`strings.xml` (single user-visible source), README title/badges, and
`RELEASE_APP_NAME` in `scripts/release.sh`. The applicationId
(`ch.lkmc.goo`) and `rootProject.name` stay fixed — changing the appId
breaks upgrades and the root name only affects local artifact paths.

## Appendix A — name ideas

Collected for the final decision (appId stays `ch.lkmc.goo` regardless):
**Taffy** (stretchy, cute), **Squidge** (soft squeeze), **Gloop**,
**Smoosh**, **Putty** (silly-putty for photos), **Melty**, **Warple**,
**Gooify**, **Blorb**, **PicPutty**. Current favorite: **Taffy** or
**Squidge** — both read as playful without leaning on the KPT trademark
history.
