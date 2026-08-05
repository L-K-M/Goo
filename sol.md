# sol.md - deep review (2026-08-05)

Independent review of Goo at `29d1d81`, after reading the product plan,
living backlogs, application sources, JVM tests, Android resources, build and
release configuration, and recent history. This is a point-in-time review;
`ANALYSIS.md` remains the living backlog after this document is triaged.

## Review baseline

- `git pull`: already up to date on `main`.
- `./gradlew testDebugUnitTest lintDebug assembleDebug`: successful.
- JVM tests: 117 passed, 0 failed.
- Lint: 0 errors, 2 `UseKtx` style warnings.
- Debug APK: assembled successfully.
- No Android device or emulator was attached. Findings involving actual GPU
  output, codec quirks, frame timing, foldables, and screenshots are therefore
  static findings or profiling recommendations, not claimed device measurements.
- The current implementation is substantial. The picker and bundled samples,
  nine brushes including Fusion, Mirror, six global effects, undo/redo,
  pan/zoom/rotate, keyframed GOOvies, MP4 export, JPEG/PNG save/share, custom
  candy controls, haptics, and context rebuild machinery are all real rather
  than scaffolding.

Severity: P0 release blocker or data corruption; P1 serious user-visible bug;
P2 meaningful issue; P3 polish, hardening, or measured optimization.

Confidence: high means the source establishes the failure; medium means the
mechanism is clear but device/platform behavior affects incidence; measure
means profile or user-test before changing it.

## P0 - document and export correctness

### SOL-1 - Punched keyframes are not stable saved states

**Confidence: high. New.** `Keyframe` stores only `strokeCount`
(`engine/core/Goovie.kt:11-16`). `StrokeLog.push` truncates a redo branch and
reuses the same list length (`engine/core/StrokeLog.kt:30-35,60-62`). Capture
`[A,B]`, undo `B`, then paint `C`: the old count-2 frame silently changes from
`[A,B]` to `[A,C]`. Reset clamps every nonzero pin to identity. This directly
contradicts PLAN's saved-field wording and can change an already-authored or
exported animation without touching its keyframes.

Fix the root model, not just the clamp. Keyframes need stable immutable
document revisions, or independent endpoint stroke snapshots. Add integrated
capture/undo/branch/reset tests. Explicit invalidation is safer than silent
rebinding but is only a fallback, not the ideal UX.

### SOL-2 - Still export is not a snapshot of what the user sees

**Confidence: high. New.** The top Export control remains available while a
GOOvie tween is visible (`ui/editor/EditorScreen.kt:233-241,312-336`), but
`renderExport` deliberately renders the live field and globals, never the
scrubbed endpoints (`engine/gl/GlWarpRenderer.kt:814-840`). Export also
snapshots only committed strokes (`EditorViewModel.kt:697-721`), so a stroke
already visible under another finger is omitted. Globals are read later from
mutable renderer state rather than included in the request.

Use one immutable render request containing strokes, globals, Fusion source,
and optional tween endpoints. Commit or deliberately cancel a live stroke
before opening Out. Until full-resolution tween snapshots exist, disable still
export in GOOvie mode or leave GOOvie mode visibly before showing Out.

### SOL-3 - The 4096 output cap does not bound decode memory

**Confidence: high. New.** `SampleSizeCalculator` keeps the sampled long side
at or above the target (`data/SampleSizeCalculator.kt:14-22`). For an
8000x6000 source and a 4096 target, halving would produce 4000, so the app
decodes all 48 MP, roughly 183 MiB in ARGB_8888, and only then scales. EXIF
rotation can briefly require another full-size bitmap (`ImageLoader.kt:131-159`).
Fusion and export GL allocations raise the same operation's peak further.
`Exception` catches do not catch `OutOfMemoryError`.

The decode must be allocation-bounded, even when that means a small target
undershoot before final scaling. Cover decode for Fusion needs its own
cover-aware sample calculation. Test common 48 MP and 100 MP dimensions,
rotated orientations, panoramas, and Fusion combinations.

### SOL-4 - MP4 rendering is vertically inverted

**Confidence: high static analysis; device golden still required. New.**
`WARP_VERT` already maps positive rectangle height to an upright window
surface (`engine/gl/GlShaders.kt:137-153`). Movie rendering passes a negative
height (`GlWarpRenderer.kt:382-399`), the same flip used specifically to
compensate for bottom-up `glReadPixels` in still export. A MediaCodec EGL
window surface has no CPU readback row inversion to compensate, so the movie
geometry is upside down relative to preview.

Use a positive full-frame rectangle for MediaCodec and retain the negative
height only in the readback path. An asymmetric quadrant/text fixture decoded
from the first MP4 frame should pin this permanently.

### SOL-5 - Reset is only partly undoable

**Confidence: high. New.** Reset first zeros `GlobalParams` and then records
only an empty stroke snapshot (`EditorViewModel.kt:431-439`). Undo restores
brush strokes but cannot restore the six levers. A globals-only Reset creates
no history entry at all. `Zero all` also discards tuned values immediately
(`ui/editor/LeversPanel.kt:64-71`). This breaks the product-level promise that
Reset is undoable.

Represent Reset as a document transaction containing strokes and globals, or
give destructive lever resets an immediate undo token. Ordinary lever motion
can remain outside stroke history; the destructive aggregate operation is the
special case.

### SOL-6 - Stroke history has quadratic memory and commit cost

**Confidence: high. New.** Every push stores `strokes + stroke`, copying the
entire active list, and every copy remains in `history`
(`engine/core/StrokeLog.kt:19,30-35`). After N strokes, roughly N(N+1)/2
references remain. The comment claiming one list cell per entry is false. Long
sessions can develop growing commit latency, heap pressure, and eventual OOM,
despite the unlimited-undo promise.

Use a structurally shared persistent revision or a linear action log with a
cursor. Solving this together with stable revision identity provides the right
foundation for SOL-1 and project persistence.

### SOL-7 - Leaving the editor can destroy all work without warning

**Confidence: high. New, extends G-2/K3-7.** Toolbar Back and system Back pop
the editor immediately (`ui/navigation/GooNavHost.kt:22-32`). There is no
project store or recent draft, so the navigation-scoped ViewModel and its
strokes/keyframes disappear. Reset receives a confirmation; accidental Back
can destroy much more work without one.

The durable fix is write-through project persistence plus Home resume. Until
that lands, intercept dirty Back and offer Keep gooing / Discard. Dirty must
include strokes, globals, Fusion state, and keyframes.

## P1 - engine, lifecycle, and platform bugs

### SOL-8 - Resampled displacement is wrong across path corners

**Confidence: high. New.** Positional spacing correctly carries between input
segments, but every emitted stamp receives one full spacing of displacement in
the current segment's direction (`engine/core/StrokeResampler.kt:64-82`). If
the next spacing interval began on the previous segment, that overstates the
new direction and drops the prior direction. Tight turns and event jitter can
produce hooks or overshoot not present in the finger path.

Accumulate the UV movement since the previous emitted stamp, including every
crossed segment. Add L-shaped and zigzag tests that check both centers and
vector sums, not only straight-line centers.

### SOL-9 - Touch-down behavior conflates pumping with whether a tap is useful

**Confidence: high. New follow-up to fixed K3-3.** Fusion is non-pumped and
therefore waits for one quarter-radius of travel before its first stamp; a tap
or short drag paints nothing even though a zero-delta mask stamp is meaningful.
Pumped tools get a manual touch-down stamp and `startPump` emits another before
its first delay (`EditorViewModel.kt:301-353`), so a quick tap gets two shots.

Model `stampOnDown` separately from `pumped`. Fusion and field/radial tools
should stamp once on down; the repeating pump should wait one interval before
its next stamp. Directional brushes should continue requiring movement.

### SOL-10 - Pinch navigation can permanently modify the image

**Confidence: high. New.** The first pointer starts a stroke immediately. If a
second pointer arrives, the gesture switches to navigation but commits that
stroke (`EditorScreen.kt:343-400`). A pumped tool has already stamped at touch
down; a directional tool may have moved before the second pointer. A normal
pinch can therefore light Undo and alter pixels.

Stage the one-finger gesture until slop/intent is known, or discard its staged
stamps and rebuild when a second pointer appears. Navigation must be document
neutral.

### SOL-11 - Fusion can author an invisible mask while B is unavailable

**Confidence: high. New.** Selecting Fusion launches a picker, but after a URI
is returned the tool remains active throughout copy/decode. A failed initial
import emits an error but leaves Fusion selected (`EditorViewModel.kt:222-251`).
`beginStroke` does not require `bitmapB`. Touches can enter invisible mask
strokes that appear unexpectedly after a future successful image swap.

Expose an importing-B state, block Fusion strokes until B is ready, and return
to Smear when the initial B import fails. Preserve a working old B if a swap
fails.

### SOL-12 - Movie finalization errors are reported as success

**Confidence: high. New.** `MovieEncoder.finish()` only drains EOS
(`engine/media/MovieEncoder.kt:117-121`). `renderMovie` marks success before
`MediaMuxer.stop()`, which runs later in best-effort `release()` and swallows
all failures (`MovieEncoder.kt:123-130`, `GlWarpRenderer.kt:349-362`). Disk-full
or muxer-index failure can therefore yield a success event for an unplayable
MP4.

Make successful muxer stop part of a throwing `finishAndClose` path. Keep a
separate best-effort abort path for teardown after failures.

### SOL-13 - Movie dimensions are not negotiated with the AVC encoder

**Confidence: high mechanism, medium device incidence. New.** `MovieSpec`
limits only the longest side, producing 1920x1440 for 4:3 and 1920x1920 for a
square (`engine/core/MovieSpec.kt:14-31`). `MovieEncoder` configures the default
AVC encoder without checking size/rate support, alignment, frame-area, or
bitrate (`engine/media/MovieEncoder.kt:32-53`). Many older API 26-era hardware
encoders are 1920x1088-class and will reject common photos.

Select/query a codec and use `VideoCapabilities`; align dimensions and step
down to a conservative 1080p-area or 720p fallback. Do not simply change the
constant without a compatibility test matrix.

### SOL-14 - Pumped brushes can build an unbounded GL event queue

**Confidence: high mechanism, measure incidence. New, related to G-3/G-6.**
Every 16 ms pump tick queues another GL runnable (`EditorViewModel.kt:344-353`,
`engine/gl/WarpSurfaceView.kt:42-45`). Each stamp is a field-sized pass, and
Smooth has multiple texture reads. If processing exceeds cadence, input grows
without bound; redraw, release, and lifecycle pause wait behind stale stamps.

Use one scheduled drain with a bounded/coalesced pending buffer and stop
admission while paused. Profile a long Smooth hold on a low-end device and
record queue latency, jank, and pause time.

### SOL-15 - Still export has the same pause/ANR shape as movie export

**Confidence: high mechanism, medium incidence. New sibling of G-7.** Replay,
4096 render, readback, and bitmap creation run as one GL queue command
(`GlWarpRenderer.kt:704-853`). `GLSurfaceView.onPause()` can wait for the queue,
and cancelling the waiting coroutine cannot interrupt work already executing.

Use a chunked/cancellable export session or a dedicated offscreen EGL worker.
At minimum, make editor-wide busy state honest and prevent navigation from
appearing responsive while the GL thread is monopolized.

### SOL-16 - Paused/context-recreated GL synchronization is incomplete

**Confidence: medium-high. New.** The bridge keeps accepting `queueEvent`
commands around pause; `contextReady` means a context was once initialized,
not that a surface is current. Fusion picker results can race resume. A fresh
context discards GOOvie endpoint fields while Compose has no context-generation
key to resend the same scrub (`GlWarpRenderer.kt:530-585`,
`EditorScreen.kt:233-241`). Live batches silently return when no field exists,
yet commit assumes they were applied (`GlWarpRenderer.kt:176-220`).

Introduce a surface/context generation signal and replay desired logical
state on each generation. Gate commands on actual resumed readiness, retain
pending logical requests, and mark whether a live stroke was completely
applied in the current generation.

### SOL-17 - Export kinds can overlap and the rest of the editor stays live

**Confidence: high. New.** Still export guards only `exporting`; movie export
guards only `exportingMovie` (`EditorViewModel.kt:563-571,689-700`). The top
rail remains active during movie export, while only controls inside the
GOOvie panel show busy state. Users can queue a still behind a movie, close the
strip, mutate levers, or navigate away while the GL thread is occupied.

Use one editor-wide export state carrying media kind/destination. Freeze
document mutation and competing export entry points. Pair it with real
cancellation after chunking SOL-15/G-7.

### SOL-18 - Save/share finalization has false-success and partial-file paths

**Confidence: high. New.** Image and movie savers ignore the row count when
clearing MediaStore `IS_PENDING` (`data/ImageSaver.kt:79-85`,
`data/MovieSaver.kt:67-73`). A zero-row update is still announced as Gallery
success. The API 26-28 image path writes directly to its final file without
deleting a partial on failure, and second-precision names can overwrite.

Require exactly one successful finalization update. Use a unique temporary
file followed by finalization/rename on legacy storage and clean failures.

### SOL-19 - A new share can delete a file another app still needs

**Confidence: high mechanism, medium incidence. New.** Both savers delete all
files in the shared cache before creating the next URI (`ImageSaver.kt:51-60`,
`MovieSaver.kt:47-53`). URI grants do not pin files; receiving apps can open or
upload them later. A subsequent share can cause `FileNotFoundException` in the
first recipient.

Keep collision-proof files and sweep by age and a bounded size/count policy,
never delete every predecessor synchronously.

### SOL-20 - Imported source bytes are neither durable nor bounded

**Confidence: high. New extension of G-2.** The source of truth is copied to
`cacheDir/sessions` (`ImageLoader.kt:38-50`), which Android may evict while an
edit is open. The copy uses unrestricted `InputStream.copyTo`, so a huge,
stalled, or malicious provider stream can fill cache or occupy IO indefinitely.

Durable projects should store live source bytes under private non-cache
storage, with explicit cleanup. Enforce a compressed-byte limit, free-space
check, cancellation, and a friendly rejection. Decide backup behavior before
adding project files.

### SOL-21 - GLES capability failures are not consistently fail-soft

**Confidence: high. New.** The manifest does not declare GLES 3, so
incompatible devices are not filtered. The config chooser can throw on the GL
thread before the renderer's unsupported callback; shader/FBO initialization
and GL uploads also lack a common error boundary (`WarpSurfaceView.kt:59-67`,
`GlWarpRenderer.kt:485-541,661-681`). Extension strings are trusted without an
actual FBO probe.

Declare required GLES 3.0, wrap initialization in a renderer failure boundary,
probe the chosen field format, and validate critical allocations/uploads.

### SOL-22 - Transparent and wide-gamut inputs have undefined export behavior

**Confidence: high for metadata loss, medium for visible severity. New.**
Preview composites premultiplied alpha over the green table. JPEG and MP4 do
not choose an explicit opaque matte, so transparency can turn black and differ
from preview. Decode/upload/readback also has no explicit sRGB/P3/HDR policy;
wide-gamut tagging and Ultra HDR gain maps are lost, and Fusion may combine
sources in different spaces numerically.

Choose and document an opaque matte for JPEG/MP4. Either normalize all inputs
to sRGB intentionally or implement end-to-end color management. Publish a
format matrix for HEIC, AVIF, animated inputs, P3, and Ultra HDR.

## P2 - UX, visual, layout, and accessibility

### SOL-23 - Compact-height, landscape, and large-font layouts lose controls

**Confidence: high static layout analysis. New.** Home uses fixed spacers and
a 128 dp hero without vertical scrolling (`ui/home/HomeScreen.kt:83-150`). The
six lever rows consume about 336 dp before insets (`LeversPanel.kt:39-73`). The
GOOvie action row can leave almost no width for its scrubber at 320-360 dp
(`GooviePanel.kt:170-234`). Fixed 64 dp labels do not tolerate larger fonts.
Export and About also lack explicit scroll fallback.

Provide compact-height and expanded-window arrangements. Scroll Home and modal
content, use a compact/two-column lever rig or landscape side console, and let
labels size naturally. Verify portrait/landscape at 320/360/600 dp and 200%
font scale with screenshot tests.

### SOL-24 - Window resize preserves stale pixel-space pan

**Confidence: high. New.** The Activity handles orientation and size changes
itself (`AndroidManifest.xml:22-26`), so the composition's `remember` survives.
`ViewTransform.tx/ty` are old-canvas pixels and `onSizeChanged` updates only
`canvasSize` (`EditorScreen.kt:161-175,338-343`). A rotated, folded, or
multi-window editor can move the photo off-screen.

Reset or mathematically rebase the viewport whenever canvas dimensions change
materially. Preserve scale/rotation only if the new center mapping is defined.

### SOL-25 - Critical controls are reachable but undiscoverable in silent rails

**Confidence: high. New distinction from resolved K3-1.** Scrolling prevents
hard clipping, but Export starts at the far end of a top rail with no edge
fade, indicator, or pinned position (`EditorScreen.kt:732-789`). Fusion,
Mirror, Punch, and later tools are similarly hidden. Returning from another
panel recreates BrushRail's scroll state at the start, potentially hiding the
selected tool (`EditorScreen.kt:596-662,822-877`).

Pin Back and Export, preserve/hoist brush scroll state, auto-reveal the selected
tool, and add an edge cue or grouped palette. On larger screens, use a bounded
side console rather than simply stretching the rail.

### SOL-26 - Custom control accessibility is incomplete

**Confidence: high. New, related to K3-25.** Size, Strength, Quality, and scrub
sliders have visible adjacent labels but no attached semantic label. Bipolar
levers announce a midpoint percentage rather than Off/direction. Candy
controls suppress indication without a keyboard focus halo. Action chips such
as Punch/Swap/Remove expose selectable semantics. Keyframes are essentially
announced as "1, button". The Home hero's spoken label is just "GOO!". The
canvas itself has no non-drag semantic actions.

Attach labels/state descriptions, distinguish actions from selectable tools,
name keyframes, draw a candy focus halo, support keyboard arrows for levers,
and expose at least one non-gesture creative action. A cursor/action model for
TalkBack, Switch Access, and keyboard users is the full solution.

### SOL-27 - Insets, foldables, and transient messages are not safely placed

**Confidence: medium-high. New.** Home lacks safe-drawing padding; editor
chrome handles top/bottom bars but not side bars/cutouts; no separating-hinge
branch exists. SnackbarHost is aligned to the physical bottom without
navigation inset or bottom-panel clearance (`EditorScreen.kt:664-667`).

Use `WindowInsets.safeDrawing` for interactive chrome, position snackbars above
the active panel, and add a WindowManager posture branch only for separating
hinges.

### SOL-28 - Export feedback is hidden or misleading

**Confidence: high. New.** Still-export failure leaves the modal sheet open,
while the Snackbar is behind its scrim. Format/quality remain editable during
an in-flight request even though changes cannot affect it. The sheet does not
show actual capped output dimensions. API 26-28 Save looks like gallery Save
until after completion. Movie saves use `Movies/Goo`, but the shared success
event always says `Pictures/Goo` (`strings.xml:73-74`).

Show failures inside Out, freeze its options while busy, expose Cancel once
rendering is cancellable, display output dimensions/downscaling before work,
explain legacy Save before work, and distinguish Pictures from Movies in the
success event.

### SOL-29 - Event collection can consume actions while the editor is stopped

**Confidence: medium-high. New.** UI state collection is lifecycle-aware, but
the export event collector is a plain composition-scoped `LaunchedEffect`
(`EditorScreen.kt:256-284`). A background completion can expire a Snackbar
unseen or try to launch a chooser over another app after consuming the event.

Collect one-shot UI effects only while STARTED/RESUMED, retain pending events,
and handle chooser launch failure without losing the share result.

### SOL-30 - GOOvie mode has several interaction integrity gaps

**Confidence: high. New.** The canvas still draws a live brush cursor although
`beginStroke` rejects painting in GOOvie mode. Delete has no undo. A single
captured keyframe cannot be previewed because `tweenRequest` requires two.
After movie rendering, endpoint textures remain on the final movie segment
until a later IO completion resends the user's scrub. The whole editor is not
visibly busy during export.

Use navigation-only canvas feedback in GOOvie mode, allow one-frame preview
with identical endpoints, offer undo for deletion, restore scrub endpoints
before disk copy, and apply the editor-wide busy state from SOL-17.

### SOL-31 - Onboarding can disappear before any edit succeeds

**Confidence: high. New.** The one-time hint is marked seen on touch-down
(`EditorViewModel.kt:281-289`), before a stamp exists. A tiny directional tap,
letterbox edge touch, or first finger of a pinch can permanently dismiss the
only editing instruction.

Mark it complete only after committing a nonempty stroke. Expand contextual,
nonmodal coaching for hold tools, two-finger navigation, Fusion, Mirror, and
the goo-punch-goo animation loop.

### SOL-32 - Visual state sometimes communicates the wrong mode

**Confidence: high. New.** Global Effects is marked selected whenever any
lever is active, even when the Brush panel is open (`EditorScreen.kt:326-333,
765-771`). The selected visual and TalkBack state therefore identify document
content rather than the visible panel. The stock sliders and bottom sheet also
break the otherwise custom candy language, and the letterbox is a flat clear.

Reserve selection for the open panel and show active effects with a separate
badge/glow. K3-19/K3-21 already track the table texture and candy sliders; an
Out-room chute/card treatment would complete the visual language.

### SOL-33 - Home and error recovery undersell the primary action

**Confidence: high. New.** PLAN calls for a friendly Open a photo action, but
the visible/spoken hero is only "GOO!" (`strings.xml:8`,
`HomeScreen.kt:118-128`). Decode errors expose raw messages and only Back. The
About copy's "nothing leaves your device" is too absolute because Share
intentionally hands bytes to another app.

Keep the playful GOO! display, but give it an Open a photo accessibility label
and supporting copy. Add Choose another photo to error recovery and friendly
format guidance. Say "Goo never uploads your photos; sharing is your choice."

## P2 - missing product work and promise gaps

### SOL-34 - Project persistence and Recent Goo are still the largest feature gap

**Confidence: high. Already G-2/K3-7/K3-27.** Strokes and keyframes live only
in the ViewModel; process death restores a source and globals but silently
drops the actual goo (`EditorViewModel.kt:137-187,453-458`). The planned
serializable project store and Home resume affordance have not landed.

Persist source identities, strokes, globals, Fusion source/state, keyframes,
and schema version atomically. Recover corrupt/partial projects safely. This
must be designed after SOL-1/SOL-6 so the persisted history model is not born
obsolete.

### SOL-35 - Settings, sounds, notices, and release presentation remain absent

**Confidence: high. Tracked in part as K3-15/K3-16.** There is no Settings
screen, haptics opt-out, sound control, export defaults, open-source notices,
or squishy sound layer. README has no screenshots/demo or direct release
download/verification path. The current Home About dialog is the only support
surface.

Ship Settings before sounds or stroke haptics so delight is optional. Add
licenses/notices, supported Android/GPU/export limits, screenshots or a short
demo, and a direct verified APK path.

### SOL-36 - Some advertised export/support claims need qualification

**Confidence: high. New disclosure gap, with G-5/K3-5/K3-17 tracked.** README
says full-resolution and pixel-for-pixel output, while sources above the
texture/4096 cap are downscaled. GIF is absent by explicit deferral. HEIC fails
on API 26-27. API 26-28 Save is app-private, not gallery-visible. P3/HDR/Ultra
HDR behavior is undefined.

Truthful output dimensions and a support matrix should land before expensive
tiled export/GIF work. Native tiled export remains demand-driven G-5. HEIC on
two old API levels and GIF remain lower priority unless users ask.

### SOL-37 - Fusion lacks source registration and inspection

**Confidence: high as a usability opportunity. New.** Photo B is center-cover
cropped once. There is no way to pan/zoom/rotate it to align faces, nor to peek
at B while painting. Differing source aspect ratios also soften B because
`decodeCover` first fit-decodes inside the target and then crops/upscales
(`ImageLoader.kt:89-108`).

Fix cover-aware decode first. A later Fusion registration mode and hold-to-peek
gesture would make the feature much more useful without changing mask
semantics.

### SOL-38 - The current release/version story is inconsistent

**Confidence: high. New process issue.** `main` is many nontrivial commits past
tag `v1.0.0`, but still declares version 1.0.0/versionCode 2. This conflicts
with AGENTS' bump policy. The app also calls Goo a working title after shipping
v1.

Use the mandated release engine for the next release rather than hand-editing
versionCode or creating a tag. Decide whether Goo is now the product name and
align PLAN/README/About accordingly.

## P2 - security, build, and verification

### SOL-39 - Write-capable release automation uses mutable action tags

**Confidence: high trust-boundary issue. New.** `release.yml` grants
`contents: write` and executes mutable major-version tags, including the
release publisher (`.github/workflows/release.yml:19-41,69-78`). The review
workflow already demonstrates immutable-SHA pinning. A moved/compromised tag
could replace release artifacts.

Pin every action in the privileged workflow to a verified commit SHA, disable
checkout credential persistence for build steps, and preferably separate the
read-only build from the minimal write-capable publish job. Apply SHA pinning
consistently to CI afterward as defense in depth.

### SOL-40 - Product-defining platform contracts are not executable

**Confidence: high. New systemic test gap.** PLAN promises a preview/export
golden and EXIF mapping coverage, but no GL golden, shader compile test,
ImageLoader/EXIF test, UI test, MediaStore/MediaCodec test, `androidTest`, or
Compose screenshot exists. The 117 JVM tests are useful but cannot catch
SOL-4, context loss, codec size support, orientation, channel order, alpha,
storage-provider behavior, or compact layouts. `GlobalFieldTest` claims a
stable hash vector but compares the function to itself.

Add the smallest platform matrix justified by these boundaries: an asymmetric
preview/still/movie golden, context recreation, EXIF fixtures, codec capability
cases, MediaStore 29 behavior, API 26 legacy save, and compact-layout
screenshots. Keep decision logic JVM-only; this is targeted integration proof,
not an indiscriminate emulator suite.

### SOL-41 - Shader/core wire contracts have incomplete drift guards

**Confidence: high. New.** Shader IDs and duplicated numerical constants are
manually synchronized. Tests do not pin all enum wire values, FUSE's ID, exact
falloff IDs, or expected hash vectors. JVM tests can stay green while GLSL
behavior silently changes.

Pin every wire ID and expected deterministic hash vector. Add a lightweight
shader source/compile contract and centralize generated literals only if that
stays simpler than explicit drift tests.

### SOL-42 - Dependency/wrapper supply-chain verification can be stronger

**Confidence: medium. New hardening.** The Gradle wrapper lacks a
`distributionSha256Sum`, URL validation is disabled, and dependency
verification metadata is absent. Wrapper-validation protects the JAR but not
every fetched artifact.

Add the official distribution checksum and evaluate Gradle dependency
verification. Do not add generated metadata blindly; review and maintain it.

### SOL-43 - Documentation and architecture descriptions have drifted

**Confidence: high. New.** PLAN/AGENTS/ADR text still describes some intended
rather than actual behavior: scissored stamps, periodic snapshots, packed
fallback, ProjectStore/SettingsRepository, GIF, and thin/pure ViewModel/GL
layers. In-app About also names KPT despite PLAN saying those names remain in
docs only.

Reconcile durable docs with implementation and record intentional deviations
in AGENTS/ADRs. Treat the trademark wording contradiction as a product-policy
decision, not a code nit.

## P3 - performance work to measure before changing

### SOL-44 - Replay cost and segment-boundary hitches remain open

**Confidence: high mechanism; profile threshold. Already G-6/K3-9.** Pumped
tools generate about 60 stamps/s; undo/export replay all stamps. GOOvie endpoint
materialization occurs synchronously on the GL thread at segment boundaries.
Field checkpoints every N strokes remain the correct shared optimization once
low-end traces show user-visible latency.

### SOL-45 - Every stamp is a full field pass

**Confidence: high mechanism; measure before optimization. Already G-3.** A
naive `glScissor` is not sufficient with ping-pong textures because pixels
outside the scissor would come from an older destination. Profile first; a
bounded quad plus preservation/copy strategy is required if fill rate is a
problem.

### SOL-46 - Preview resolution is fixed and high-frequency UI state is broad

**Confidence: measure. K3-11 plus new profiling targets.** Preview always
decodes to 2048 regardless of window/device. Playback updates the whole
`UiState` every frame; cursor state sits high in `WarpEditor`; touch mapping
builds lists/Pairs; `CandyLever` launches work on drag deltas. These are
plausible sources of battery use or Compose churn, not established regressions.

Profile on a low-end phone before adding heuristics or pooling. Isolate
high-frequency state only where traces show recomposition or allocation jank.

### SOL-47 - Small GL and IO costs can be removed after correctness work

**Confidence: high but low value. New.** Candidate micro-optimizations include
a context VAO, invariant sampler uniforms, a reusable globals array, cached
`FitTransform`, avoiding redundant encoder `eglMakeCurrent`, and a larger/file
channel movie copy buffer. None outranks document/export correctness.

## Existing deferred ideas still worth keeping

The following remain valid and should survive backlog consolidation:

| Existing ID | Item | Current recommendation |
| --- | --- | --- |
| G-5 | Native tiled export above the cap | Demand-driven after honest dimensions |
| G-7/K3-10 | Chunk monolithic movie rendering | Required before longer movies |
| K3-5 | HEIC on API 26-27 | Disclose first; implement if device stats justify |
| K3-17 | GIF export | Lower priority than MP4 reliability |
| K3-19 | Textured/vignetted goo table | Cheap visual depth |
| K3-20 | Keyframe thumbnails | Lovely, medium-heavy |
| K3-21 | Candy sliders | Strong visual-cohesion win |
| K3-23 | Horizontal plus quad mirror | Small, photogenic, very KPT |
| K3-24 | Ping-pong playback | Small and delightful |
| K3-25 | Goo me recipes/dice | Fun and an accessibility ramp |
| K3-26 | Stroke haptics | Gate behind Settings |
| G-W1 | Packed field fallback | Keep deferred until a device report |

## Novel, quirky directions

These are ideas, not bug-fix commitments.

### Goo Spells

Share a tiny source-free recipe containing normalized strokes, globals, and
timing, but no photo pixels. A friend applies the same spell to their own image
entirely offline. This exploits the resolution-independent document rather
than bolting on a social network.

### UnGoo Monocle

Hold a second finger to reveal the original through a wobbling circular lens.
It is useful before/after inspection and a toy in its own right. Gesture
arbitration must first guarantee that the second finger cannot stamp (SOL-10).

### Rewind Gum

Press and hold Undo to preview the last stroke peeling backward stamp by stamp.
Release commits one normal undo; cancel lets it spring back. It makes history
legible without changing document semantics.

### Making-of GOOvie

Generate a short creation replay directly from the stroke log, compressing
idle time and long pump holds. This complements authored keyframe animation
rather than replacing it.

### Fusion Slot and X-ray

Make B swapping intentionally playful: the painted mask remains while sources
spin through like a slot machine. Hold for an x-ray peek of B, and add source
registration for actual face alignment.

### Out Chute

Render export progress as a glossy photo card moving through a candy output
slot, respecting reduced-motion settings. On completion, the card drops onto
the table; tap to inspect or flick to Share.

### Mirror Ghosts

Before painting, show faint reflected brush rings and a shimmering mirror axis.
Quad mirror can split the cursor into four ghosts. This teaches the mode while
making it feel magical.

### Goo Diagnostics Card

Because the app has no telemetry, offer an offline Copy diagnostics action
with API level, GPU renderer, max texture size, selected field format, and
encoder capabilities. Users can attach it voluntarily to device-specific bug
reports without sharing photos.

## Recommended sequencing

1. Fix history/revision semantics (SOL-1, SOL-6) before defining project
   persistence.
2. Fix release blockers in independent slices: export decode memory, MP4
   orientation/finalization, resampler corners, touch-down behavior, Fusion
   loading, and privileged workflow pinning.
3. Make export atomic and mutually exclusive, then address chunking and codec
   negotiation.
4. Land durable project persistence/resume and Back safety on the stable
   document model.
5. Serialize heavily overlapping `EditorScreen.kt` work: gesture arbitration,
   adaptive layout, rail discoverability, semantics, and onboarding.
6. Add targeted device/platform tests for the exact boundaries JVM tests
   cannot prove.
7. Take cheap delight wins only after data safety and export truthfulness are
   solid.

## Review conclusion

Goo is not a weak prototype. The core displacement approach is coherent, CPU
and GLSL brush semantics are mostly close, normalized geometry is used
consistently, the no-network boundary is real, and the app already has an
unusually distinctive visual identity. The largest risks are concentrated at
state boundaries: a keyframe is not yet a stable document revision, export is
not always an atomic visible-state snapshot, the output cap does not cap peak
decode memory, lifecycle/platform paths are barely executable in tests, and
compact/non-touch UX has not received the same rigor as the engine math.

Those are fixable without replacing the architecture. The highest-leverage
move is to make the document model truly persistent and revision-based; that
single foundation strengthens keyframes, undo memory, process recovery,
resume, export snapshots, and future Goo Spells at once.
