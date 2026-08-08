# Meltorama 2000 — Plan

> Name: **Meltorama 2000** (short form **Meltorama** where length matters —
> see [Renaming](#renaming)). The applicationId stays `ch.lkmc.goo`, and
> "goo" stays the verb and the material: the claim is *Goo Your Photos*.
> The names considered before this one are kept in
> [Appendix A](#appendix-a--name-ideas).

## 1. What this is

Meltorama 2000 is a fun, fast photo-warping app for Android in the spirit
of **Kai's Power Goo** (MetaTools, 1996) — the original "Realtime Liquid
Image Funware" — wearing the chrome-and-neon retro-future that 1996
imagined for the year 2000.
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

| KPT Goo (1996)                          | Meltorama 2000 (this app)                          |
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
  fields is `mix(D₁, D₂, t)` in the shader. Refined at build time (#7):
  keyframes are stored as document pins `(revision, globals)` — the
  immutable, structurally shared `StrokeRevision` the punch was taken
  from, so 64 of them cost 64 references — and only the active
  segment's two endpoint fields are materialized (by replay, cached by
  stable revision ID, slot-swapped for adjacent segments). Naive
  per-keyframe *field* snapshots would cost hundreds of MB; revision
  pins cost nothing. Levers lerp on the CPU into the same warp
  uniforms.

  Two revisions after user testing, both from the same report ("how do I
  edit the second step?"):

  1. **Editing stays live while the strip is open.** "The editor isn't
     active in movie mode" read as a broken feature. The strip genuinely
     can't paint *into* a tween — stamps land in the live field — so any
     edit first drops the preview from the tween to live. A keyframe's
     content is edited by re-punching it (Update), never by drawing
     "inside" it.
  2. **Each keyframe is its own thing.** Pins were prefix *counts* into
     `StrokeLog.strokes`, which shrinks on undo — so rewinding the editor
     toward the original photo collapsed the whole strip, and punching
     the untouched photo as a closing frame was impossible. Pins hold an
     immutable revision instead (ids never reused), and the log's cursor
     no longer means anything to them: undo, redo, Reset, even a
     truncated redo branch leave every keyframe exactly where it was
     punched.

  Keyframes live in session memory like the log.
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
| Fusion | photo B cover-cropped into A's UV space (CoverCrop, one shared warp); the through-paint mask is the field's z channel, written by a FUSE stamp mode — so it warps with the goo (warp-of-warp lookup carries z), blurs under Smooth, erases under UnGoo, undoes/replays/exports via the stroke log, and tweens in GOOvies through the same field mix, all with zero new machinery. Field format RG16F→RGBA16F (same extension gates) |
| Global effects | parametric analytic fields (bulge, twirl, …) composed additively over the stroke field, live in the warp pass as uniforms. Levers are document state, not history entries: center = identity, pulling back undoes exactly, Reset zeroes them; undo/redo stay stroke-only. Static uses integer-hash value noise (fixed seed) so CPU and GPU agree bit-for-bit — a sin-hash would drift per driver |

**Export.** Decode the original (EXIF-rotated) at the export cap — the GL
max texture size or the 4096 memory budget, whichever is tighter
(`ExportSize`; five export-sized allocations coexist at the readback peak
— source bitmap, two GL textures, readback buffer, result bitmap: ~320 MB
at the 4096² worst case, ~450 MB with a Fusion photo B loaded (B bitmap +
B texture), so uncapped 48 MP would OOM mid-range devices). Upload, replay the stroke
log into a fresh field with the identical stamp code, run the same warp
shader into an offscreen FBO, `glReadPixels`, save. Sources beyond the cap
downscale (~12 MP output); true full-resolution tiled export
(displacement-bounded source tiles) is tracked as REVIEW.md G-5. Movie
export renders each tweened frame into a MediaCodec input surface (shared
EGL context) and muxes H.264 into MP4; GIF uses a bounded-size palette
encoder. As built (#8): no share group at all — the GLSurfaceView thread
makes an EGL window surface over the codec input surface current on its
OWN context (config chosen with EGL_RECORDABLE_ANDROID at surface setup),
so source and endpoint textures are directly usable; offline pacing via
eglPresentationTimeANDROID; sync-mode codec + muxer driven entirely on
the GL thread; 1080p-cap even-dimension sizing and frame timing in
MovieSpec (pure, tested). GIF (post-v1 #16) shares that tween walk but
renders into an offscreen FBO, reads back with glReadPixels, and streams
frames through a median-cut palette and GIF's LZW straight to disk
(`engine/media/GifPalette`, `GifEncoder` — pure JVM, decoded back in the
tests); looping is the Netscape 2.0 repeat-forever block. Export speed
(½×–4×) scales the FRAME COUNT, never the frame rate, so the pts ladder
and the GIF centisecond delay stay on their nominal clock; an over-long
GIF drops down a frame-rate ladder rather than losing strip.

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
| 7  | Keyframes (GOOvies)    | keyframe strip: capture/re-punch/reorder/delete, tween scrubbing, live playback | record and replay a warp dance |
| 8  | Movie export           | MP4 via MediaCodec/MediaMuxer (EGL encoder surface); GIF secondary | shareable MP4 of the animation |
| 9  | Fusion                 | second image through-paint brush | brush one face onto another |
| 10 | v1 polish + release    | settings, about, README screenshots, release v1.0.0 | tagged release with APK |

Order may adapt (e.g. Fusion before keyframes) if review findings suggest it;
the roadmap is a plan, not a contract.

### Post-v1 feedback features (as built)

User-feedback PRs after the v1.0.0 tag, same one-PR-one-feature policy:

| #  | PR                     | Contents |
| -- | ---------------------- | -------- |
| 11 | Brush preview overlay  | transparent size/strength circle while a slider is in hand, plus a live cursor ring during strokes |
| 12 | View navigation        | two-finger pan/zoom/rotate of the preview (paint stays 1-finger), spring-back Reset View button |
| 13 | Crop                   | freeform reframe from the editor rail. Document-space change, not an edit: strokes/keyframes record UVs of the frame they were painted on, so applying a crop restarts the goo (confirmed when there's goo to lose) and clears history hard ([StrokeLog.clearHistory]). The rect lives in normalized upright ORIGINAL-image space — session bytes stay original, decode applies the rect (preview and export alike), so quality never pays for a re-encode and "back to the full picture" always exists. Re-crops compose inward; the decode target scales up to a 4096 cap so cropped previews stay sharp. |
| 14 | Meltorama 2000         | rename (short form "Meltorama" for the launcher; claim "Goo Your Photos"; appId unchanged) plus the retro-future console re-skin: gunmetal panels with milled bevels, neon domes in swept-chrome rims, horizon-grid In room, chrome wordmark that measures itself to fit. |
| 15 | Unsaved-work exit guard | Back (rail bead and system gesture alike) asks before ending a session that holds work — strokes, levers, keyframes, a Fusion photo or a crop ([UiState.hasUnsavedWork]). Back also leaves crop mode instead of the room. Interim: the real fix is project persistence (ANALYSIS SOL-34), and the dialog says so rather than pretending a save exists. |
| 16 | GOOvie speed + GIF     | the strip's Save/Share icons become one out-tray sheet (`MovieExportSheet`): format (MP4/GIF), speed (½×/1×/2×/4×), loop-forever for GIF, and the length the choice produces. Speed changes the frame count only. GIF is a full pure-JVM encoder — median-cut palette per frame, GIF89a LZW, Netscape loop block — fed by an offscreen FBO readback of the same tween walk the MP4 path uses. GIFs are images: they land in `Pictures/Meltorama`, MP4s in `Movies/Meltorama`. |
| 17 | Project saving + i18n  | **Projects persist** (ANALYSIS SOL-34): `ProjectStore` writes one folder per project under `filesDir/projects` — the source bytes, Fusion's photo B, a preview rendered through the export replay, and `project.json` holding the document. The stroke log travels as a NORMALIZED revision table (`StrokeLogSnapshot`): every reachable revision once, parents before children, history and keyframe pins referring to them by id — so structural sharing survives the round trip and a pin on a truncated redo branch comes back with it. Writes are per-file tmp+rename with the document renamed last; loads validate (ids, parent order, local file names) and refuse rather than half-restore. "Unsaved" finally means *differs from disk* (`UiState.savedSignature`). The rail's Save bead writes without leaving; the editor autosaves on `ON_STOP` — the last callback before a backgrounded process can be reclaimed — and checkpoints as it goes (`AutosavePolicy`: 10 s after the last change, and never more than 90 s unwritten while editing continues), so neither an OS kill nor a foreground crash takes the session. The In room shelves saved projects as chrome-bezel tiles: tap to resume, hold to throw away. Saved projects are deliberately excluded from cloud backup and device transfer — the photos stay on the device. Also: full internationalization (failure wording moved from the ViewModel into resources; the ViewModel has no locale) with a Simplified Chinese translation and a `locales_config` for Android 13+ per-app language. |
| 18 | Always saved           | The exit prompt is gone (user-reported). Leaving the editor writes the document and goes, so every path out — Back, the rail bead, `ON_STOP`, the checkpoint loop — saves. That retires PR 15's guard, which only ever existed because the session lived in memory alone, and with it the deliberate-vs-autosave flag the dialog needed (`projectSaved`, `discardProject`): one property, `hasUnwrittenChanges`, is now the whole saved-state question. A goo you did not want is thrown away from the In room, where the rest of the shelf lives. |
| 21 | Echo (clone)           | Paint this photo through itself, offset (proposal 0004). No stamp mode and no shader change: copying a region IS what a displacement field expresses, so an echo stroke is an ordinary DIRECTIONAL stroke carrying one constant delta. Two corrections to the proposal, both of which would have produced something plausible on screen: the delta is a fixed OFFSET rather than `anchor − stampCenter`, which is not constant and would sample the anchor's single pixel for every texel in the stroke; and its sign is inverted, because the kernel is `b = −delta·w`, so writing the proposal's expression literally grafts from `2P − S` — the point reflected through the brush. The long-press-to-plant gesture became touch-to-plant-when-unanchored, since gesture plumbing is the part a JVM-only suite cannot exercise. |
| 20 | Second brush palette   | Six new rows over five accepted proposals: **Vortex/Unwind** (0001) — the local rotation the rail never had, chirality carried in the stamp's `dx` sign so a mirrored twin counter-rotates for free; **Melt** (0003) — the tool the app's name promises, pumped, with the run recorded per stamp so acceleration lives in the log rather than a clock, and the engine's first anisotropic profile (`FalloffProfile.DRIP` reshapes the measured DISTANCE, leaving `BrushFalloff` scalar); **Comb** (0010) — teeth cut across the drag axis, counted per brush radius so preview and export agree; **Pond** (0013) — a tap that means something, alternating radial bands returning to zero at the rim; **Fault** (0014) — the drawn path as a boundary rather than a trail, where reversing the seam cancels its own sign flips. `StampMode` gained a `mirrorsDelta` flag so Mirror stopped being a hard-coded list of one. No document, schema or field-format change: every one of them is stamps in the log. |
| 19 | The shelf is yours     | The 20-project / 256 MiB cap is gone (user-reported). An app that always saves and then quietly deletes the oldest thing it saved is worse than one that never saved — the user cannot even predict which loss they are getting. In its place the In room prints what a decision needs: how many goos, what they cost (`Formatter.formatFileSize`, so the units follow the locale) and how much space is free, beside per-goo delete and a throw-them-all-away with a counted confirmation. Growth is unbounded by design; the readout is the guardrail. |
| 27 | Goo Whip               | Flick and lift, and the goo keeps going (proposal 0015). Every other brush stops when the finger stops; Whip launches a virtual brush at release that carries past the lift and decays to a point, the way snapping a wet ribbon does. No shader branch — it is `DIRECTIONAL` with a feathered falloff plus input work. The design decision that matters is that the tail is computed ONCE at release and appended as ordinary stamps: input timing chooses the mark, fixed constants generate its path, the resampler turns that into stamps, and the log stores the answer. A tail regenerated at replay time would depend on input timing that no longer exists and would be a different mark on every export. Release speed is quantized because pointer timestamps jitter — two flicks a human cannot tell apart otherwise measure differently — so the same flick makes the same mark and a mark you liked is repeatable; the quantization scales the direction vector rather than rebuilding it, so the aim survives exactly. Speed is measured in aspect space (a horizontal flick cannot out-throw an identical vertical one on a 16:9 photo) and capped, because a whip is a mark and not a physics toy. `ViewTransform` gained `invertVector`, since a velocity is not a point and translating a difference would add the pan twice. |
| 25 | Aspic (Freeze)         | The palette's first brake (proposal 0002). Every tool before this one ADDED displacement; the only way to protect anything was a steady hand and a small brush, and not needing either is the app's whole promise. Paint clear varnish over what should survive and every later stroke, lever and lens flows around it. It is the trick Fusion already pulled, run a second time on the field's last free channel: `DisplacementField.CHANNELS` 3 → 4, a `StampMode.GUARD` that accumulates `w` exactly as `FUSE` accumulates `z`, and one multiply on stamp weight — which is what makes ONE new mode aim every brush in the palette, present and future. Three decisions the GLSL could have got plausibly wrong and does not: the mask is read in DOCUMENT space and never through the warp-of-warp lookup (a protection that travelled with the content it prevents from travelling would be incoherent, and Liquify's mask is image-space too); `ERASE` and `GUARD` are exempt, or full varnish would be reachable by nothing but a global Reset — the brake must not brake itself; and the mask scales `globalDisp` as well, or "frozen" would stop meaning anything the moment someone pulled the Twirl lever. The frost sheen is chrome, not document: gated on `u_showFreeze`, which defaults off in `drawWarpQuad` so an export path that never mentions it structurally cannot render varnish into a saved picture. This spends the last channel — the next per-texel quantity needs a second texture and doubles the ping-pong pair. |

### Renaming

Done once already (Goo → Meltorama 2000, PR 14). The complete list of
places a display name lives, for the next time:

- `strings.xml`: `app_name` (launcher label — keep it short, Android
  ellipsizes past ~12 characters), `app_name_full` + `app_model` (the
  Wordmark lockup on the In screen), `about_title`, and the
  gallery-folder text in `export_saved_gallery` and `export_saved_movies`;
- `res/values/themes.xml` style name + the `android:theme` reference in
  `AndroidManifest.xml`;
- the MediaStore folders in `ImageSaver`/`MovieSaver` (`Pictures/…`,
  `Movies/…`);
- README title and prose, PLAN/AGENTS/GLOSSARY headers;
- `RELEASE_APP_NAME` in `scripts/release.sh` and the APK artifact names
  in `.github/workflows/release.yml`;
- the launcher icon, if the mark is tied to the name.

The applicationId (`ch.lkmc.goo`), the package, `rootProject.name` and
the repository name stay fixed — changing the appId breaks upgrades for
every sideloaded install, and none of them is user-visible.

## Appendix A — name ideas

Collected before the name was settled; kept for archaeology. The winner
was **Meltorama 2000** — retro-future, and it leaves "goo" free to stay
the verb (appId stays `ch.lkmc.goo` regardless):
**Taffy** (stretchy, cute), **Squidge** (soft squeeze), **Gloop**,
**Smoosh**, **Putty** (silly-putty for photos), **Melty**, **Warple**,
**Gooify**, **Blorb**, **PicPutty**. Current favorite: **Taffy** or
**Squidge** — both read as playful without leaning on the KPT trademark
history.
