# Brush Lab

Tune how a Joy Brush brush **looks** on your PC with your pen, then take the numbers to the phone.
It runs the **same shader files the phone runs** — `joybrush/shaders/jb_tip.glsl` and
`jb_grain.glsl`, fetched at runtime from the repo. There is no copy of the GLSL in the HTML, and
there never should be: if the tip shape changes for the phone it changes here too, for free.

Feel and speed are judged on the phone. This is for the look.

## To run it

**From the repo root:**

```bash
python -m http.server 8777
```

Then open <http://127.0.0.1:8777/tools/brushlab/BrushLab.html>.

It must be served over HTTP. It will not work as a double-click — `fetch()` is blocked on
`file://`, so the shader load fails and the page says so instead of quietly carrying a private copy
of the GLSL. That is deliberate: the one thing this tool must not do is drift away from the phone.

Screenshots (Playwright — installed separately, this task installs nothing):

```bash
python -m playwright screenshot --viewport-size 1620,1120 \
  --wait-for-timeout 1500 http://127.0.0.1:8777/tools/brushlab/BrushLab.html shot-1.png
```

To drive real strokes rather than a blank page, load the page in Playwright yourself and
dispatch pointer events, or just draw with the pen — the canvas keeps its contents between frames
(`preserveDrawingBuffer`), so a screenshot of the window shows the mark.

## What the panel does

Every number is **scrubbable**: drag the value sideways, `Shift` ×10, `Alt` ×0.1, double-click to
type an exact number. The slider beside it does the same job coarsely. Settings are remembered in
this browser; `Clear` wipes the paper but not the settings.

| Group | What it sets |
|---|---|
| **Preset** | name (the contract requires it), size-by-pressure `linear`/`off` |
| **Brush** | size, flow, opacity, spacing %, hardness, corner exponent (0.5–16), taper, aspect (−1…1), angle, follow-direction, min px |
| **Tip texture** | on/off, scale, depth, edge, tilt gradient (−2…2), radial |
| **Paper grain** | on/off, scale, depth, edge |
| **Stroke** | wash or buildup; fake rotation on/off + angle |
| **Colour** | brush colour, paper colour (lab-only — see *brush.json*) |
| **File** | Clear, Save `brush.json`, Load `brush.json` |

The defaults are **`BrushPreset`'s defaults**, not the lab's taste, so an untouched lab saves the
brush the phone builds from a fresh `BrushPreset()`. One consequence: grain **depth 1.0** is nearly
solid, so turn a grain on and drag depth down to about 0.5 before judging it.

The line under the canvas shows the last `PenSample` the lab built from the pointer — x, y,
timeMs, pressure, tilt, azimuth, barrel, tool, predicted — so you can see exactly what the browser
gave it and what was mapped onto the contract.

The meter top-right is the **phone budget**: dabs per second and total dab pixels per second over
the last second (the average dab area is in its tooltip). It turns amber past 20,000 dabs/s or
60 M dab-px/s and says *"may be slow on a Note 9"*. It is a warning, never a limit — it will not
stop you drawing a 2 %-spacing monster to see what it looks like.

## How the pieces map

- **Shared shaders.** `jb_tip.glsl` + `jb_grain.glsl` are pasted into the dab fragment shader right
  after the page's own `#version 300 es` and precision block, and are called as
  `jb_tipCoverage()`, `jb_grainLevel()`, `jb_heightCoverage()`, `jb_grainedCoverage()`. Not
  reimplemented, not re-tuned — the phone's numbers.
- **Grain.** `joybrush/assets/grain/cloud_256.png` (JB-1.03) if it is there; a tileable
  value-noise fBm generated in JS if it is not. Either way it is only an input to the shared
  functions.
- **Dabs.** Instanced quads, one per dab, `diameter + 2px` so the tip's antialiasing band has
  somewhere to live. Spacing is `spacing% × diameter` with the leftover distance carried between
  events, so spacing is exact no matter how fast the pointer reports.
- **Stroke buffer.** A texture the same size as the canvas. RGBA16F when
  `EXT_color_buffer_float` exists, RGBA8 when it does not. `wash` blends alpha with `MAX`
  (alpha darken — the stroke only gets darker); `buildup` blends normally and is capped at the
  stroke opacity when the pointer comes up. On pointer-up the buffer is composited onto the
  painting and cleared.
- **Input.** Pointer Events, `getCoalescedEvents()` for every sample, mapped onto every field of
  `PenSample`. `altitudeAngle`/`azimuthAngle` when the browser has them, `tiltX`/`tiltY` otherwise,
  and `NaN` — never a made-up number — when the pen measures neither.
- **Sizes are CSS pixels.** The lab multiplies by the display scale so a 40px brush looks the same
  on a laptop and on a 2× monitor. On a 3× display the scale is capped at 2 rather than allocating
  4800×3000 twice over.

## brush.json

`Save brush.json` writes the **`BrushPreset`** from
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushPreset.kt` — field for
field, in declaration order, every default written out, which is exactly the shape `BrushJson.encode()`
produces and `BrushJson.decode()` accepts. Not a subset, not a reinterpretation: the lab holds the
whole preset object and only overwrites the fields it actually drives.

That means a load → save round trip through the lab is lossless. `scatter`, `sizeJitter`,
`angleJitter`, `color`, `smoothing`, `blend`, `license`, `author`, `sourceFormat`, `engine`, any
`Param.inputs` curve, and any key the lab has never heard of all come back out unchanged.

Things worth knowing:

- **Size / flow / opacity / hardness / angle / grain depth are `Param` objects**, not plain numbers:
  `{ "base": 40, "inputs": [], "combine": "multiply" }`. Only `base` is what the lab edits.
- **`spacing` is a fraction of the diameter** (0.04), so the panel's "Spacing 4 %" is the same
  number. The panel speaks percent because that is easier to drag.
- **`angle` is in degrees** in the contract too, so no conversion happens anywhere.
- **Wash / buildup is `accumulate: "wash" | "buildup"`**, not a boolean.
- **`id` and `name` are required** and have no Kotlin default, so the lab asks for a **Name** and
  derives `id` from it as a slug.
- **`Size by pressure`** writes `size.inputs = [{ "input": "pressure", "curve": [[0,0],[1,1]] }]`
  when `linear`, and `[]` when `off`. It changes the drawing too, so the file never lies about
  what you saw on the paper.
- **Brush colour, paper colour and fake rotation are not in the contract** and go into
  `extensions`, which `BrushPreset` defines as `Map<String,String>` for exactly this purpose.
  (`BrushPreset.color` is `ColorJitter` — a hue/sat/val jitter, not an ink colour.)

Specs: `tasks/joybrush/specs/JB-1.20_pc_brush_lab.md`. Contract: `BrushPreset.kt`, `BrushJson.kt`.

## Known limits

- **WebGL2 only.** No WebGL1 fallback, by choice: the shared shaders are GLSL ES 3.00 and so is
  the phone. Without WebGL2 the page explains itself and the panel still works — nothing draws.
- **RGBA8 fallback.** Without `EXT_color_buffer_float` (or on a driver that advertises it and then
  refuses `RGBA16F`) the stroke buffer is 8-bit and buildup saturates at 1.0 instead of piling up.
  The top-right badge says which buffer you got.
- **No curve evaluator.** `Param.inputs` curves are read, reported on load and written back
  untouched — but the lab draws every param as its flat `base`, and expresses pressure-on-size only
  as the `linear` curve. A phone brush with a shaped curve will preview differently here. Nothing is
  lost on a round trip; it is just not shown.
- **`blend: "erase"` previews as normal.** An eraser brush round-trips intact but the lab paints it
  like any other brush.
- **No smoothing.** Raw input, as the spec says — this is for judging the mark, not the feel.
- **Resizing the window between displays** (a different `devicePixelRatio`) reallocates the
  buffers and carries the painting across, but a resize requested *during* a stroke waits for
  pointer-up so a dab is never dropped mid-mark.
- **One stroke at a time.** The second finger down does not draw; the lab models a single stylus,
  not two.
- **A tap leaves a dot** even with no pointer movement — the pointer-down sample is always one dab.
- **The visual check is still outstanding.** This page has been read over and its `<script>` parses
  (`node --check`, run by the orchestrator), but nobody has yet drawn on it in a browser or seen a
  single frame of it. See Question 9 in the spec.

Specs: `tasks/joybrush/specs/JB-1.20_pc_brush_lab.md`. Shaders: `joybrush/shaders/jb_tip.glsl`,
`joybrush/shaders/jb_grain.glsl`. Contract: `PenSample.kt`.
