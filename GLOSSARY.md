# Glossary

Terms of art in this repo — KPT heritage and engine internals.

- **Goo / gooing** — dragging pixels around like wet paint; the app's whole
  point. From Kai's Power Goo (MetaTools, 1996).
- **Room** — KPT Goo's full-screen single-task spaces (In, Goo, Fusion,
  Out). We keep the metaphor: Home = In, Editor = Goo, Export = Out.
- **Displacement field** — texture `D` of UV offsets; rendering samples the
  source at `p + D(p)` (backward mapping). The only place warp state lives
  on the GPU.
- **Backward mapping** — looking up *where a destination pixel comes from*
  rather than pushing source pixels forward; hole-free by construction.
- **Kernel** — a brush's contribution to the field per stamp (e.g. Smear =
  drag delta × falloff; Grow = radial inward pull).
- **Falloff** — smoothstep weight from brush center (1) to rim (0);
  `engine/core/BrushFalloff` is the pinned reference the shader mirrors.
- **Stamp** — one kernel application; strokes are resampled into stamps
  spaced at ~25% of brush radius.
- **Stroke log** — the document: every stroke's tool + parameters + stamps
  in normalized source coordinates. Undo, export, and crash recovery all
  replay it.
- **UnGoo** — KPT's localized distortion eraser: a stamp that lerps the
  field toward zero under the brush.
- **GOOvie** — KPT Goo's exported keyframe animation; ours are MP4/GIF.
- **Punch** — capturing a keyframe: pinning `(strokes, globals)` as they
  stand right now, where `strokes` is the whole immutable stroke-log
  snapshot. The authoring loop is goo → punch → goo → punch; the movie is
  what happens *between* punches. Because a pin owns its snapshot, undo
  in the editor never moves it.
- **Re-punch (Update)** — re-pinning an existing keyframe to the current
  document. The only way goo made after a punch reaches that keyframe,
  since a pin is a bookmark rather than an editable canvas.
- **Fusion** — painting a second image's pixels through onto the first
  (KPT's proto face-swap room); roadmap step 9.
- **Candy button** — the family of big, round, glossy, springy controls
  that make the UI read as funware (`ui/components/CandyButton`).
- **The table** — the dark backdrop everything floats on
  (`ui/theme` GooTable colors).
