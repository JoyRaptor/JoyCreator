# R5: Concepts (TopHatch) and Infinite Painter (Infinite Studio). Brushes, UI and gestures for Joy Paint

*Research date: 2026-09-28. Researcher: Claude (research agent). Scope: Topics 1 to 3 of the Joy Paint brief.*

**Evidence labels used throughout**

- **[C]**: Confirmed. Stated in the vendor's manual, help centre or release notes, or quoted from a named review. The URL is in Sources.
- **[R]**: Reported by users (store reviews or forums). The claim is real user sentiment, but not verified behaviour.
- **[I]**: Inferred. My engineering reading of the documented behaviour. Treat it as a hypothesis, not a fact.

**Source limitations.** Reddit (r/ConceptsApp, r/infinitepainter) returned HTTP 403 and 302 through this environment's proxy, so I could not quote Reddit threads directly. User sentiment comes from App Store reviews, the justuseapp.com review aggregations, the Infinite Painter Wikidot forum, and independent reviews (Parka Blogs, Kamila Stankiewicz, Draw Your Weapon). Public complaints about Infinite Painter's selection tools are thin in the sources I could reach. Section 2.5 therefore rebuilds the friction from the documented interaction model, and marks each point [I] where no user quote backs it.

---

## 0. Executive summary

- Both apps keep the UI out of the way in the same basic way. There is one small movable cluster for brush, size, opacity and colour. Navigation happens with multi-finger gestures, not buttons:
  - 2-finger pan, zoom and rotate
  - 2-finger tap to undo, 3-finger tap to redo
  - 4-finger tap to hide the UI

  Everything else appears only in context: mini-toolbars, pop-ups, and a cog that shows up only when a tool has settings. [C]
- **Concepts' distinctive ideas:**
  - A **tool wheel** that turns into a bar at a screen edge and into a compact dock in a corner.
  - **Four size, opacity and smoothing presets per tool.**
  - **Tap+hold+slide spring-loaded sliders.**
  - **Vector strokes you can edit after drawing them**, with Nudge (push or pull a stroke), Slice (cut) and masks.
  - **Selection that stays live.** A lasso or item pick shows a popup above the selection box, transforms by touch, and has no confirm step.
  - Configurable **2-, 3- and 4-finger taps** and a configurable **Finger Action** for stylus users. [C]
- **Infinite Painter's distinctive ideas:**
  - **Every brush works in Paint, Blend or Erase mode**, with a separate brush remembered for each mode.
  - **Virtual sliders**: drag on the size or opacity buttons.
  - **3-finger vertical drag changes size; 3-finger horizontal drag changes opacity.**
  - **The colour dot doubles as a controller**: tap for the panel, a short drag changes brightness, a long drag-out picks up the eyedropper.
  - **Long-press on the canvas is the eyedropper.**
  - **Touch-Alt**: a finger resting on the screen while the stylus draws changes what the stylus does.
  - A roughly 100-parameter brush engine in 5 tabs, with curve graphs for pressure, velocity and tilt, and 3-segment stroke-taper profiles.
  - 64-bit colour and Google's low-latency front-buffer rendering. [C]
- **Infinite Painter's selection tools are the main anti-pattern.**
  - Selection sits 2 to 3 levels deep, in the Editing Tools menu (Tools menu, then the Selection row).
  - It runs in a **modal workspace**. While that workspace is open, all editing tools act on the mask, not on pixels.
  - Every step needs an explicit ✓ or ✗. Transform is a **second** modal workspace with its own ✓.
  - The selection is **cleared after any content operation**.
  - A selection can be "active but invisible", and the docs admit this confuses people.
  - A user review calls the ✓-everywhere pattern "extremely slow and annoying". [C]/[R]
- **Concepts' weak points:**
  - Raster textures on vector strokes pixelate at deep zoom.
  - SVG export flattens variable width to "a single line weight per stroke".
  - Automatic layering by tool type surprises users.
  - Masks behave unlike an eraser.
  - The Android version lags iPad. Custom brush creation, palettes and the colour mixer are still iOS-only.
  - There is no smudge, no blend modes and no bucket fill. [C]/[R]
- **Neither app has frame-based animation.** Infinite Painter's **Trace** layer-visibility mode, which progressively dims lower layers, is the nearest thing to onion skinning. [C] Joy Paint's animation UX needs its own research track.

---

## 1. Concepts (TopHatch)

### 1.1 How vector strokes work

| Aspect | What is known | Label |
|---|---|---|
| Data model | Marketed as a "vector-raster hybrid". Every stroke stays selectable and editable: move, stretch, scale, rotate, recolour, change tool type, Nudge, Slice. | [C] |
| Pressure, tilt, velocity | Pen and Fountain Pen vary width with **velocity**, in opposite directions: Pen gets thicker when slow, Fountain Pen when fast. Dynamic Pen varies with **pressure**. Soft and Hard Pencil respond to **tilt, pressure and velocity**. Marker "chisel follows the rotation" of supported styluses (barrel roll on Apple Pencil Pro). Fixed Width Pen has constant width. Wire keeps the same on-screen width at every zoom. | [C] |
| Rendering of textured brushes | Market brushes use **raster images** laid along the vector path. They "may pixelate if you zoom in too far" and "their size may be limited". "Using many pixel-based brushes can increase file size and require more performance." Dynamic Pen "renders a raster on top of the vector line", which can make files bigger. Dotted is "a single vector stroke with raster dots overlay". | [C] |
| Custom brush model (iOS only) | Two brush types. A **Stamp** brush places 1 to 9 JPG/PNG stamps at intervals along the path. A **Reveal** brush uses the stroke to reveal a tiled **grain** texture underneath, "similar to how the watercolor brush reveals the texture of the underlying paper". Parameters: Spacing, Scatter and Rotation, each a split-handle jitter range; "Rotate Along Stroke Path" or stylus azimuth; grain scale, rotation and "line up the grain position for every stroke"; variance graphs for pressure, tilt and velocity whose Y axis is the percent change in size and opacity. | [C] |
| Watercolour | Watercolour strokes on the **same layer blend "as though they are wet"**. Another stroke type in between breaks the blend. Using separate layers gives a "dry" look. | [C] |
| Likely internal representation | Each stroke stores the smoothed input samples: x, y, pressure, tilt, azimuth and velocity per point, plus the tool id, size preset, opacity and colour. At render time the engine turns this into a ribbon of triangles for solid pens, or into stamps and a grain mask for textured brushes. SVG export keeps "point data and colours" but only "a single line weight per stroke", which suggests the width lives as a per-point attribute that the SVG writer does not carry (it does not emit a variable-width outline polygon). PDF (vector paths) "loses brush textures", which fits the idea that textures are only a render-time effect. | [I] |
| Resolution independence | "Your work is resolution independent… change the size… without losing quality." Raster textures are the exception. | [C] |
| Infinite canvas | Unbounded. The manual gives no zoom limit. An optional **Artboard** (presets or custom size, moved by tap+hold on a corner) sets the export frame and a real-world scale. Double-tapping the zoom value in the Status Bar returns you to the canvas centre. On iOS, edge pointers lead to off-screen strokes. | [C] |
| File size | Vector files are small, but raster-textured and Dynamic Pen strokes add weight. The native format is **.concepts** on Android and Windows and **.concept** on iOS, and the two do not open on each other's platform. | [C] |
| Rendering rate | Android "Live Rendering up to 120Hz" shipped in December 2020. | [C] |

### 1.2 Built-in brushes and tools

- **Brushes** [C]: Pen, Fountain Pen, Dynamic Pen, Fixed Width Pen, Wire, Soft Pencil, Hard Pencil, Marker, Airbrush, Watercolor, Fill, Dotted.
  - Fill draws a closed shape and fills its inside. It needs smoothing below 100%.
  - Concepts has **no bucket fill**. Reviewers flag this as a missing feature. [R]
- **Tools** [C]:
  - **Selection**: Lasso, Item Picker, Color Picker.
  - **Nudge**: "push your lines like a piece of string, or touch your stroke directly to pull it". Has a size setting, plus filters for locked items, masks and layer focus.
  - **Slice**: a destructive vector cut. At width 0 it splits strokes without changing them.
  - **Hard Mask** and **Soft Mask**: non-destructive hiding.
  - **Quick Clear**: double-tap the mask tool, then clear by layer or by type.
  - **Text**.
  - **Pan, Rotate and Zoom** as tools, with the keyboard shortcuts Space, R and S. Holding the key gives a spring-loaded mode.
- **Brush settings visible to users on every platform** [C]:
  - Size, opacity and smoothing, each with **4 presets per tool**.
  - Smoothing: 0% is raw input; 50% removes most bumps; 100% turns the stroke into a straight line from start to end point.
  - In Settings → Stylus: a pressure-response trim slider, pressure on/off, tilt on/off, eraser-end action, shortcut buttons (including Apple Pencil Pro squeeze) and hover brush previews.
- **Changing a finished stroke**: select the strokes, then switch tool, colour or size. The selected strokes take on the new properties. The Color Picker in the colour wheel can copy a stroke's colour **and brush properties** into the active slot. [C]
- **Brush Market and brush packs**: bought individually or included in the subscription. Brush-pack import, export and live sharing need Essentials or the subscription, and **sharing is iOS-only**. [C]

### 1.3 Precision tools (Precision panel, toggled from the Status Bar)

- **Grids** [C]:
  - Dot, Graph, Lined, Isometric, Triangle, and 1-, 2- and 3-point perspective.
  - Each grid is its own layer, with spacing, opacity, line weight and "Confine to Artboard".
- **Snap while drawing** [C]:
  - *Snap to Grid*: the stroke follows the nearest grid line.
  - *Align to Grid*: directional alignment only.
  - Options: *Allow Traceback*, *Allow Turns*, and *Auto-complete*, which joins the stroke's start and end points.
- **Snap while editing** [C]: *Tap to Key Points* snaps a selection's key points (ends, corners, centre) to other strokes' key points or to the grid.
- **Measure** [C]:
  - Every stroke carries measurement data.
  - Drawing scale can be a preset or custom (for example 1:100), in Digital, Metric or Imperial units.
  - Dynamic labels appear while drawing; static labels attach to selections and guides.
- **Shape Guides** [C]: line, arc, angle, ellipse, rectangle.
  - Drag the crosshair to move the guide.
  - Tap+hold+drag the handles to resize.
  - Two fingers rotate and scale the guide. While a guide is active, 2-finger gestures act on the guide, not the canvas.
  - Double-tap the crosshair to make a perfect circle or square.
- **Shape Recognition** [C]:
  - Draw a shape in 1 to 4 strokes, then **hold at the end of the last stroke**.
  - Once the shape is recognised, **moving the stylus without lifting it scales and rotates the shape**.
  - The hold time can be set.
- **Nudge** doubles as a precision tool. Strokes drawn at 100% smoothing, or with only four points, respond differently to Nudge. [C]

### 1.4 Workspace, the tool wheel and hiding the UI

**Layout.** [C]

- Status Bar, from left to right: Gallery, drawing name, canvas menu toggles (Precision, Layers, Objects), zoom and angle readouts, Pro Store, Import, Export, Settings, Help.
- Floating on the canvas: the Tool Wheel, Precision panel, Layers panel and Objects library.
- On iOS only, a colour palette also sits under the wheel.

**Tool wheel anatomy.** [C]

- **Outer ring**: 8 configurable tool slots.
  - Tap a slot to activate the tool.
  - Tap the active tool again, or **double-tap** an inactive one, to open the Brushes menu.
  - Undo and redo buttons also sit on the wheel.
- **Middle ring**: Size, Opacity and Smoothing buttons.
  - Tap a button to open a slider with 4 presets at the top.
  - Tap a preset to select it, drag to change its value, or tap+hold the preset to type an exact number.
  - **Spring-loaded slider**: tap+hold+slide on a button, and "the slider will open as you slide it and close as soon as you lift your finger".
- **Centre dot**: the current colour and opacity.
  - Tap it for the colour wheel (COPIC, HSL, RGB).
  - Tap+hold it for the Colors menu (palettes and mixer; iOS only).

**Moving and resizing.** [C]

- Tap+hold+drag any panel to move it.
- Pinch on the wheel to scale it.
- Dragging the wheel to the middle of the left or right edge turns it into a **Tool Bar**. On Android and Windows, a layout manager in the centre of the screen does the same job.
- Menus flip their orientation for left-handed use when the wheel moves to the other side.

**Three workspace modes.** [C]

1. **Normal**: everything is visible.
2. **Compact**: labels shrink to icons, the wheel docks, and the size, opacity and smoothing sliders disappear. You get here by swiping outward on the Layers or Precision label, or by pushing the wheel into a corner.
3. **Hidden**: swipe outward again. All menus disappear and a small Concepts icon tab remains. Tap the tab to bring the menus back.

A 4-finger tap toggles the interface by default. Android also gained a "Hide Everything" option in October 2024.

### 1.5 Gestures (defaults, from the Gesture Quick Reference and the manuals)

| Input | Action | Configurable? | Label |
|---|---|---|---|
| 1 finger or stylus drag | Draws with the active tool. With no tool active, 1 finger pans. "One-finger pan" can be switched on. | Via Finger Action | [C] |
| 2 fingers: drag, pinch, spread or twist | Pan, zoom and rotate at once. **Zoom snaps at common increments; rotation snaps at standard angles.** On Android and Windows (not iOS) you can turn either snapping off. | Rotation and zoom can each be disabled | [C] |
| 2 fingers while a Shape Guide or selection is active | Scales and rotates the guide or selection, not the canvas | No | [C] |
| 2-finger tap | **Undo** | Yes. Options include redo, select last item, show layers, show colour wheel, tool setup, toggle canvas rotation or zoom, select all, do nothing | [C] |
| 3-finger tap | **Redo** | Yes | [C] |
| 4-finger tap | **Toggle interface** (hide or show) | Yes | [C] |
| Tap+hold on the canvas | **Activates Selection**: lasso or item picker, depending on settings. The manuals also describe tap+hold as a way into the colour picker. The hold time is a slider. | Action and timing | [C] |
| Tap+hold+drag (lasso) | Selects the strokes the loop encloses. Lasso again to subtract. A second finger toggles between lasso and item picker. | No | [C] |
| Tap+hold on a menu | Picks up the menu so you can move it | No | [C] |
| Tap+hold+slide on the Size, Opacity or Smoothing button | Spring-loaded slider | No | [C] |
| Pinch on the tool wheel | Scales the wheel | No | [C] |
| Double-tap the eraser/mask icon | Quick Clear menu | No | [C] |
| Tap or tap+hold the zoom or angle value | Editor, or inline edit. Double-tap the zoom value to return to centre. | No | [C] |
| Finger action while a stylus is in use | **Do Nothing, Use Active Tool, Pan Canvas, Select, Nudge, Slice, Configured Tool** | Yes | [C] |
| Stylus buttons and eraser end | Configurable shortcut and eraser actions | Yes | [C] |

**Palm rejection.** Concepts "supports Palm Rejection, writing angle adjustments, and pressure curve trimming". [C] On Android this probably relies on the Finger Action split, with the finger set to pan or do nothing, plus the OS's own palm rejection. [I]

### 1.6 Selection in detail (the part to learn from)

**Four ways in** [C]:

1. Tap+hold on the canvas.
2. The Selection tool (arrow) placed in a wheel slot.
3. Finger Action = Select, so the finger always selects while the pen draws.
4. The layer popup's "Select All" for one layer.

**Selection menu** (bottom of the screen) [C]:

- Type: Item Picker, Lasso or Color Picker.
- **Partial on/off**: lasso takes whole strokes or partial ones.
- Include locked strokes.
- Active layer only, or all layers.

**Item Picker** [C]:

- Drag the crosshair until a circle appears around a stroke. Lift to select it.
- Tap with another finger to add more strokes.
- Hovering the crosshair over a selected stroke turns the plus sign into a minus, which subtracts it.

**Popup above the selection box** [C]: Copy (to the clipboard), Duplicate, Group, Lock, Delete, Flip H, Flip V.

**Adjust toggles** [C]:

- Rotate on/off.
- **Scale / Stretch / Off**. Scale changes the stroke width too; Stretch keeps the tool width.
- Filters.
- Exact angle and scale fields.

**Direct manipulation** [C]:

| Action | Result |
|---|---|
| Drag the selected strokes | Move |
| 2-finger pinch or twist | Scale and rotate the selection |
| Drag a corner handle | Scale or stretch. A second finger on the screen locks the aspect ratio. |
| Tap one corner (it "activates") and drag it | Distort |
| Activate two corners and drag with one finger | Skew |
| Activate two corners and pinch or spread | Warp |

**Highlight Selection** (default on): selected strokes stay full-strength while everything else greys out. [C]

**Strength.** There is no modal ✓ or ✗. You select, manipulate straight away, and carry on drawing. [C: the workflow as documented. That the selection ends on the next draw action is [I].]

**Complaints** [R]:

- Selection is "very finicky" when adjusting position.
- Early reviews said there was no marquee.
- Grid snapping gets "knocked out of place" by tiny finger movements.

### 1.7 Colour

- **Three wheels** [C]:
  - **COPIC**: the "Too Corporation" marker set, with letter-number codes and tonal and greyscale groupings.
  - **HSL** and **RGB** sliders with numeric entry. These reached Android in October 2022.
- **Colour Picker**: a crosshair sampler with an Alpha toggle and a "pick brush" option. [C]
- **Palettes of up to 8 colours, and the colour mixer** (tap+hold+drag across palette swatches to blend them into a gradient) are **iOS-only**. [C]
- Older reviews complain there were no custom RGB or HSV colours. The 2022 wheels fixed this. [R]/[C]

### 1.8 Layers

- **Automatic layering** is the default [C]:
  - Each tool family gets its own layer. For example, all pens share one layer and both pencils share another.
  - **Manual** mode is a toggle.
  - Reviewers dislike the automatic mode. [R]
- **Panel gestures** [C]:
  - Tap a layer to activate it.
  - **Double-tap for Focus Mode**, or **scrub along the eye icons**.
  - Tap+hold+drag to reorder.
  - Drag a selection onto a layer to move strokes there.
- **Layer popup** [C]: Select All, Lock, Duplicate, Delete, Rename, Merge Down, Opacity 0–100%.
- **Missing** [C]/[R]: blend modes, layer masks and clipping. Merging "causes unexpected colour loss". [R]
- **Layer limit** [C]: 5 layers on the free tier; unlimited with Essentials or the subscription. A reviewer saw memory warnings at about 20 layers on an iPad Pro. [R]

### 1.9 Export [C]

- **Raster**: PNG (with transparency), JPG, PSD (layered), and flattened PDF.
  - Size choices: 72, 150, 300 or 600 ppi for the whole drawing or the artboard; 100%, 200% or 400% for a screenshot.
- **Vector**: SVG (single line weight per stroke; can filter out textured strokes), DXF R14 (wireframe option), and vector PDF (paths only, textures lost).
- **Native**: .concepts on Android and Windows; .concept on iOS.

### 1.10 Android and Windows versus iPad (as of Sept 2026)

- **iOS-only** [C]:
  - The custom brush editor and brush sharing. The roadmap says "coming to Windows and Android".
  - Palettes and the colour mixer.
  - External displays.
  - Barrel-roll and squeeze (Apple Pencil Pro).
  - Pointers to off-screen strokes.
  - The iOS 26 "Liquid Glass" UI.
- **Android and Windows only** [C]:
  - Switching zoom and rotation snapping off.
  - The "Enable artboard drag" toggle.
  - A centre layout manager for docking the wheel.
- **Android roadmap milestones** [C]:
  - 120 Hz live rendering (Dec 2020)
  - Perspective grids (Oct 2021)
  - Advanced transforms (Feb 2022)
  - HSL and RGB wheels (Oct 2022)
  - Shape recognition (Apr 2024)
  - Focus Mode and the Highlight Selection toggle (Jun 2024)
  - Hide Everything (Oct 2024)
  - Brush preview ring (Dec 2024)
  - **Hover previews (Mar 2026)**, meaning S Pen hover
  - Custom Objects (May 2025) and object sharing (Aug 2026)
- **Purchases do not carry across platforms**, and native files do not open across platforms either. [C]/[R]

### 1.11 What users praise and what they complain about

- **Praise** [R]:
  - The infinite canvas.
  - "Minimalist UI [that] works great on small tablets".
  - The tool wheel.
  - Smoothing ("100% smoothness… has eliminated using the ruler").
  - Tapping to pluck out any stray line at any time.
  - Isometric grid and snap ("my biggest lifesaver").
  - The COPIC wheel.
  - Textured brushes that "don't obviously look vector".
- **Complaints** [R]:
  - No bucket fill, no blend modes, no smudge or blending ("brush engine is clunky for today's standards").
  - Aliasing on exports with clean edges.
  - The eraser (a mask) doesn't really erase: hidden lines come back in vector exports and get in the way of text boxes.
  - Snapping reliability, and crashes when toggling the grid.
  - Automatic layering.
  - The subscription.
  - Android and Windows lag iOS.
  - A steep learning curve.
- **Developer note** [C]: TopHatch told a reviewer that **Apple's guidelines** stopped them from adding **double-tap-to-undo** on the Pencil, because it risks accidental destructive actions.

---

## 2. Infinite Painter (Infinite Studio LLC)

### 2.1 Architecture and performance facts

- **Memory model** [C]:
  - The whole open project (all layers and assets) is **held in RAM**.
  - The maximum canvas size and layer count therefore depend on the device and the moment. The New Project dialog **shows the maximum number of layers for the chosen canvas size** and shrinks a canvas that is too large, with a warning.
  - The feature list says "up to 8000×8000 px, layers… up to thousands for small canvases".
  - 6 GB RAM is recommended. Android 9 or later is required for 7.2.x.
- **Colour** [C]:
  - **64-bit colour (16 bits per channel) by default**, with a switch to turn it off on devices where it fails.
  - Gamma-correct mixing is a toggle.
  - On-screen colour proofing.
  - "Pixel-perfect" unfiltered display when zoomed in.
- **Latency** [C]: Google's **Low Latency** API, with the settings Disabled, Active and Fastest, draws "directly to screen overlay". This is front-buffer rendering. Relevant for Joy Creator: on Android it is `androidx.graphics:graphics-core`'s `GLFrontBufferedRenderer`, which works with OpenGL ES. [I]
- **Virtual transformations** [C]: a transformed layer keeps its original pixels until you paint on it. Repeated transforms therefore lose no quality ("like Smart Objects, used always").
- **Tiling and GPU** [I]: the vendor docs don't describe tiling. The in-RAM model, the per-canvas layer maximum and the 8K cap point to full-canvas GPU textures per layer, not a sparse tile store.
- **Undo** [C]:
  - 64 steps in v7. The older gesture page says 32. The wikidot feature list says "limited only by memory".
  - Undo is per session only.
  - Long-press Undo clears the layer.
  - **Project version history**: every save keeps a snapshot, 6 by default and up to unlimited.

### 2.2 Brush engine

**Brush modes** [C]:

- Paint, **Blend** (smudge) and Erase appear as three icons on the main toolbar. **Any brush works in any mode.** Clone is effectively a fourth mode.
- Each mode remembers its own current brush. Tapping the active mode icon again opens the brush selector for that mode.
- Favourites are kept per mode.
- You can assign a mode to the finger, so the stylus paints while the finger blends.

**Built-in library** [C]: 168 brushes in 16 folders: Pencils, Pens, Calligraphy, Markers, Paint, Watercolor, Sprayers, Chalks, Charcoals, Design, Fills, Glow, Misc, Screentone, Textures, and Harmony (procedural).

**The Brush Editor has five tabs and more than 100 controls** [C]:

| Tab | Parameters |
|---|---|
| **Stroke** | Size; Opacity; **Smoothness**; **Adhesion** (paint build-up threshold that gives "gloppy" strokes); Size range ×0–4; **Wet edges**; **Glaze strokes** (stops overlapping strokes building up); **per-brush blending mode** (30 modes, each with a Strength); Dynamics curves for Pressure, Velocity, Tilt, plus **Tilt Offset** (which way size grows relative to the tilt, positive for pencils and negative for airbrush); Size jitter; **Size profile and Flow profile curves** along the stroke. The profile graph is a 3×3 grid: a fixed-length start, a stretchable middle and a fixed-length end, which is how tapers work. |
| **Head** | Source bitmap (any image: white-on-black, black-on-white, colour RGBA) with Color, Invert, Tonal curve and Colorize; **application style** (how successive stamps interact); Flow; **Spacing 0–200%** of head width; **Structure** (impasto relief); Softness; **Depth** (reads head alpha as a height map, so light pressure touches only a few bristles); Angle 0–360; **Rotation** (−100 reverse, 0 fixed, +100 tangent); **Stylus rotation** (the pen's angle controls the head); **Orient to screen**; Flow and Scatter dynamics; initial jitter (angle); continuous jitter (scatter, angle, flow). |
| **Texture** | Source bitmap (grayscale or colour, with invert, tonal curve and colorize); **Style: Fixed / Rotate / Warp** (tiles side by side, rotated along the stroke, or warped along the stroke); Depth; Scale 0–2; **Stretch 1–50× along the stroke**; Structure; Softness; Angle; **Scale with size**; Depth dynamics; **Gradation** (tilt falloff from tip to heel, so a tilted pencil is darker at the tip); jitter (initial position; continuous position, scale, angle). |
| **Paint** | **Mix-in** (picks up the colour underneath and replaces the brush colour); **Dilution** (picks up without replacing; 100% is a pure blender); **Wet paint** switch (wet paint moves transparency too); Blur; **Pull** (smudge length); Flow multiplier while blending; **Sample lower layers** (global); Dilution dynamics (paint versus blend by pressure or tilt); HSB colour jitter, both initial and continuous. |
| **Special** | **Watercolor** (Bleed out, Glaze as water amount, Mix-in, Dryout); **Screentone** (snaps stamps to a grid: spacing, angle, scale with size); **Particles** (1–64 attractors, 1–400 particles, radius, particle size, overshoot; only by duplicating a particle brush); **Filters and colour adjustments as brushes**, applied to the stroke or to the layer under the stroke, with a Strength of −100 to +100. When a filter is set, the colour dot on the toolbar turns into a circular strength slider. |

**Curve graphs** [C]:

- X is the input and Y is the output. Tap to add a node; drag a node onto its neighbour to delete it.
- There is an invert button and preset shapes; the leftmost preset resets to linear.
- **Global calibration curves** for pressure, velocity and tilt sit in Settings and apply to every brush. Tapping a label turns that input off everywhere.

**Other brush features** [C]:

- **Paper layer**: a background colour plus a texture that **interacts with brush strokes on every layer**, with depth, opacity and scale. It is separate from the brush's own texture, and the two can combine.
- **Procedural brushes**: Harmony (sketchy, fur, ribbons) and Fill brushes, which fill the drawn outline, can preview live and support lasso-fill.
- **Brush from selection**: select an area, then "Make Brush" opens the Brush Creator with that pixel area as the head.
- **Formats**: proprietary **.prbr** (one brush) and **.przp** (a ZIP pack). **There is no ABR or Procreate import.** [C] Reviewers note the missing ABR support. [R]
- **Community brush repository**: free, a plain scroll list with no search.

### 2.3 Tools

All of these are [C].

- **Guides**:
  - Lines (parallel and perpendicular rulers).
  - Ellipse.
  - **Hatching**, which is unique: hatch without lifting the pen.
  - **Lazy** (a rope stabiliser with a radius and an Elastic option that grows the radius with speed).
  - **Pen** (Bézier and polyline).
- **Shapes**: Line, Rectangle, Circle, Path (Bézier), Arc.
  - Any brush and any brush mode can draw them.
  - Shapes stay editable until you move on.
  - A **Stamp** button leaves a copy.
- **Shape detection**: hold at the end of a stroke for about 1 s. It recognises 8 geometries: line, arc, smooth path, polyline, triangle, rectangle, quadrangle and ellipse. Tap the shape's line to edit it.
- **Symmetry**: vertical, horizontal, radial and kaleidoscopic, with up to 32 planes.
- **Perspective grids**: 1-, 2- and 3-point, curvilinear (fish-eye), isometric, and plain 2D.
  - Strokes snap by default. Hiding the grid does not turn snapping off.
  - Red guide lines toward the vanishing points follow the cursor.
  - Rectangle and Circle shapes follow the perspective planes.
- **Fills**: Solid (bucket), Linear, Radial, Sweep gradient, and Pattern.
  - **Live Tolerance**: drag out from the seed point to set tolerance by eye.
  - Sampling can use all layers or a single layer, or you can **set any layer as the fill source**. The recommended line-art workflow: the lines are the source and the fill goes on a layer underneath.
- **Transform**: Basic, Anchor, Distort (includes perspective) and Warp (a Bézier cage, like Photoshop's).
  - Two fingers inside the cage move, scale and rotate the content (Basic mode only). Two fingers outside the cage navigate.
  - The toolbar holds Cancel, unlock bounding box, mode, Flip, Rotate 45°, **Stamp** and Confirm.
  - You can transform several layers at once by tapping their thumbnails during the session.
- **Liquify**: Move, Pinch, Bloat, Swirl CW/CCW, Restore and Rewind.
  - A mesh records the deformation.
  - Normal undo doesn't work inside Liquify.
- **Patterns**: Symmetry, Path, Quilt and Tile, plus dedicated Pattern projects for seamless tiles.
- **Other tools**: Cloning (Photo, Artistic, Smart), 40+ filters, adjustments, curves, Text, Crop, Resize, Straighten, Panels (comic frames), reference images (floating, several at once, sampled by the eyedropper), Navigator, Timelapse (720p to 4K).
- **Animation**: **none.** The docs have no animation chapter. The nearest feature is **Trace visibility mode**, which progressively dims every layer below the active one. That is a sort of onion skin. Solo mode also exists. [C]

### 2.4 Layers and blend modes [C]

- **Layers**:
  - Layers are RGBA. The number of layers is limited by RAM.
  - **Paper layer** at the bottom of the stack.
  - Adjustment layers and filter layers.
  - Groups, with Passthrough versus Normal blending.
  - Layer masks (8-bit).
  - **Clipping**.
  - Lock transparency.
  - Hide when recording.
  - **30 blend modes**, including 3 unique **masking modes**: Erase, Mask and Line Art.
- **Layer panel gestures**:
  - The panel opens **collapsed**, as a strip of thumbnails. **Slide left to expand it; slide right to collapse it.**
  - In the collapsed view you can:
    - Tap the tiny eye to toggle visibility.
    - Tap a thumbnail to make that layer active, and tap it again for its options popup.
    - Long-press and drag to reorder; the panel expands temporarily while you drag.
    - **Pinch a run of layers to group them.**
    - Tap the Paper icon to open the Paper properties.
- **Layer options popup**: Rename, blend mode, opacity, Hide, Duplicate, Clear, Lock transparency, Merge (down only), Delete, Clip, **Select** (non-transparent pixels, plus a **Boolean submenu**: Add, Subtract, Reverse subtract, Intersect, Difference), Mask, and More.

### 2.5 Selection tools: how you reach them, and why they frustrate users

**Reaching them** [C]:

1. Tap the **Editing Tools** icon on the top bar. If "Split Tools menu" is on, the Creative and Editing menus are separate icons, and you can swipe between them.
2. Find the **Selection tools** row. It holds 9 tools: Lasso, Rectangle, Circle, Poly, Path, Wand, Brush, Color Range and Layer.
3. Tap a tool. You are now in the **Selection workspace**.

Other routes:

- Tablets only: long-press a tool icon and drag it onto the top bar's **Custom toolbar**. The docs say "Sorry, phone users."
- Layer options → **Select** selects the layer's pixels.
- The keyboard shortcut for toggling the selection is `\`.

**How the workspace behaves** [C]:

- A common **selection toolbar** holds:
  - A tool selector (tap the tool's name to switch)
  - An **Add/Subtract** toggle
  - **Cancel**, which closes the workspace *and clears the selection*
  - **Confirm (✓)**, which leaves the workspace with the selection still active
  - Mask operations: Select all, Invert, Clear, Transform mask, Expand, Contract, Feather
  - Content operations: Transform, Duplicate (to a new layer), Copy Merged, Isolate (cut to a new layer), Delete, Copy to Clipboard, Make Brush
- "**The toolbar is scrollable; not all icons may be visible** on your device simultaneously."
- "As long as the Selection workspace is active, **all regular editing tools affect the selection mask, not the actual content**." Brushes, patterns, liquify and filters all edit the mask.
- After you confirm, **two icons appear on the top bar**: the Selection menu, and an **Active selection toggle**, which turns the mask on or off without deleting it. The docs say: "a selection mask may sometimes be **invisible but still active**, so the red indicator is there to spare you confusion."
- "**After using any of the editing functions, the selection (mask) is cleared.**" To reuse a mask, you must first copy it to a layer mask.
- **Transform is a second modal workspace** with its own Cancel and ✓.
- **Expand and Contract max out at 6 px per use.** Apply them repeatedly for more.
- Rectangle, Circle and Poly selection are "in fact the Lasso tool working with respective Shapes".
- The mask shows either as marching ants or as a colour overlay whose colour and opacity you choose.

**Why it frustrates.** Each row gives the evidence and its label.

| # | Friction | Evidence |
|---|---|---|
| 1 | **Selection is a destination, not a gesture.** Moving a lasso'd area takes about 7 taps: Editing Tools → Lasso → draw → Transform → drag → ✓ (transform) → ✓ (selection) or ✗. Long-press is spent on the eyedropper and double-tap on fit-to-screen, so no gesture is left for selecting. | [C] mechanics; [I] tap count |
| 2 | **Stacked modal ✓/✗ confirmations** everywhere (selection, transform, fill, liquify, adjustments). | [R] review: "you have to tap a check mark to get out and accept any changes in all features. This is extremely slow and annoying." |
| 3 | **Brushes act on the mask while the workspace is open.** Users who "just want to paint inside the selection" must confirm first. | [C] mechanics; [I] that this is a confusion point |
| 4 | **Invisible-but-active selection state** makes later strokes appear to fail. | [C]: the docs admit it |
| 5 | **The selection disappears after Duplicate, Isolate, Transform and similar operations**, so the lasso must be redone. | [C] |
| 6 | **Cancel = clear.** The easy exit throws the work away. | [C] |
| 7 | **Scrollable toolbars** hide operations off-screen on phones and small tablets, and the Custom toolbar is unavailable on phones. | [C] |
| 8 | **Content operations and mask operations sit side by side** (Transform versus Transform mask, Clear versus Delete) with similar icons. | [C] layout; [I] error-prone |
| 9 | Low discoverability generally: "There's little to no guidance… so many things are just never discovered through trial and error." | [R] |

### 2.6 UI layout: how it stays "clean"

**Always visible** [C]:

- **Top bar**: Home, Creative Tools, Editing Tools, Layers toggle, Options (⋯).
- **Undo and Redo**, bottom-left. Redo appears only after you have undone something. After an undo, a History button appears; it opens a scrub slider (drag left to undo, right to redo).
- **Main toolbar**:
  - Paint, Blend and Erase icons.
  - **Size** button: tap for a popup with size, angle, the blend mode and the Brush Editor; drag vertically for a virtual slider.
  - **Colour dot**: tap for the Colour panel; drag along the toolbar axis to change HSB brightness (hue is kept even at black or white); **drag out perpendicular to the toolbar to get the eyedropper**.
  - **Opacity** button: tap for a popup with Opacity, Flow and Softness, where tapping a parameter name makes it the target of the virtual slider; drag for the virtual slider.
  - An optional **Recent Colors strip** holding 10 colours with similar ones merged. Dragging along it previews colours in a large floating swatch.

**Moving and docking the toolbar** [C]:

- Drag the toolbar **with two fingers**. It snaps to the screen edges.
- Drop it into a lower corner to get the **corner-docked** legacy form:
  - Colour dot in the corner, with Blend, Erase and Paint radiating out from it.
  - Drag out from Paint to change size.
  - Long-press any icon to undock.
- This corner form is recommended for phones.

**Appears only when needed** [C]:

- A **Tool settings cog** on the top bar, for tools that have settings. When several tools are active, their settings stack in one panel.
- A **Selection menu and indicator**.
- A **Reference menu** (pushpin icon).
- **Mini-toolbars** in the top-right for each active modifier tool (symmetry, grid, shape, guide). Tapping the main icon closes the tool; secondary toggles such as snap and visibility sit next to it. They stack horizontally.
- **HUD readouts** at the top: numeric values for size, opacity and flow while you change them. The size ring is drawn at true on-canvas size, with the inner ring showing the current size and the outer ring the maximum. Undo and redo toasts also appear here.

**Floating widgets** [C]: Navigator (with a monochrome value-check mode), a Colour wheel widget, reference images, and the main toolbar. Each can be pinched to scale.

**Interface settings** [C]: theme (dark or light), UI scale, hide the system navigation bar, split Tools menu, Recent Colors strip, disable the Back button, and bind volume keys to functions.

**Slider micro-interactions** [C]:

- Every linear slider has invisible **"virtual −/+" zones at its ends**. Tapping them steps the value by 1% (or about 3–4° for angles).
- Tapping the number opens a keypad on some sliders.
- Circular sliders respond to drags left, right, up or down, and a tap opens a linear popup.

### 2.7 Gestures table [C]

| Gesture | Default action | Configurable |
|---|---|---|
| 1 finger or stylus | Paint or use the tool. Once a stylus has been detected, the **finger gets its own function**. | Finger function |
| 2 fingers | Pan, zoom and rotate at once. Rotation can be disabled. | Rotate on/off |
| **Quick pinch-in and lift** | **Fit the whole canvas** ("got lost?") | No |
| 1-finger double tap | Fit to screen | Yes (double-tap function) |
| 2-finger tap | Undo | No |
| 3-finger tap | Redo | No |
| **3-finger drag up or down** | **Brush size**. Works for other tools too. | No |
| **3-finger drag left or right** | **Brush opacity**. Can be reassigned to **Flow** or **Softness** from the opacity popup. | Target parameter |
| 4-finger tap | **Immersive mode**: hides the UI. Floating widgets and mini-toolbars stay; a corner icon or another 4-finger tap restores the UI. | No |
| Long-press on the canvas | **Eyedropper** | Yes (long-press function) |
| Long-press on a list item | Options for the item (brushes, folders, swatches, projects, textures) | No |
| Long-press+drag | Move or reorder (layers, brushes, folders, projects, docking icons) | No |
| 2-finger drag on the main toolbar | Move the toolbar | No |
| **Touch-Alt** (finger resting anywhere while the stylus works) | Brushes: temporarily switches the stylus to the finger function (default **Eyedropper**; or Blend, Erase or move canvas). **Rectangle** constrains to a square. **Line** snaps to 15° steps. **Ellipse** snaps its axis to 15°. **Move** constrains to 45°. **Resize** steps in 100% increments. **Gradient and pattern** axis constrains to 45°. **Crop** keeps the aspect ratio. "May not work on all Android devices." | Finger function |
| Pinch across layer thumbnails | Group those layers | No |
| Double-tap a reference image | Flip it | No |
| Stylus drag of a reference image over the central ✖ | Hide it | No |
| Hold at the end of a stroke | Shape detection | On/off |

**Stylus** [C]:

- **S Pen works "without the need of pairing or calibration"**, including pressure and tilt.
- **Front and back side buttons** can each be mapped to a function. Non-standard codes from secondary buttons may be misread.
- There are two calibration charts, for pressure and tilt.
- The Stylus settings section **appears only after the first stylus stroke**.
- **Palm rejection** comes from the stylus-versus-finger split: once a pen has been seen, finger touches no longer paint unless you set that. [C]

### 2.8 Export and interop [C]

- **Export**: PNG (with or without paper), JPG (quality), WebP, **PSD** (layered), **ZIP** of PNG layers, and **.pntr**.
  - .pntr is a ZIP containing the full project, with optional timelapse and history. It moves between iOS and Android.
- **Import**: PSD import is listed, along with images from the gallery, camera, Files or Pixabay.
- **Licensing**: the iOS and Android licences are sold separately.

### 2.9 What users praise and what they complain about

- **Praise** [R]:
  - "Zero clutter between me and the tools I need."
  - Pinning favourite tools to the top bar.
  - The 3-finger size and opacity drag ("probably the best thing since the two finger tap to undo"; "I really miss it" back in Procreate).
  - Realistic pencils and watercolour.
  - Brushes with filters.
  - Editable shapes (you can change the brush, colour and size after drawing).
  - Perspective snapping.
  - Multiple floating references.
  - The Navigator.
  - Lasso-fill.
  - Gradient maps.
- **Complaints** [R]:
  - ✓-to-exit everywhere.
  - The 3-finger gesture "feels awkward" for some users.
  - Sliding the layer panel out to reach functions.
  - The undo button "blends in".
  - The path tool is tricky ("I wish if you tapped away, it would turn it off").
  - Crashes and lost layers on large files or older devices.
  - Layer limits on big canvases.
  - No ABR import.
  - Discoverability.

---

## 3. Synthesis for Joy Paint

### 3.1 Side-by-side UI patterns

| Pattern | Concepts | Infinite Painter | Shared or different |
|---|---|---|---|
| Primary control cluster | Circular **tool wheel**: 8 tool slots, size, opacity and smoothing ring, colour centre. Turns into a bar at the edge and a compact dock in a corner. | Linear **main toolbar**: Paint, Blend, Erase, Size, Colour, Opacity. 2-finger drag moves it; edges snap; corners dock it in a legacy radial form. | **Shared idea**: one movable cluster for everything you touch per stroke. **Different shape**: wheel versus bar. |
| Tool switching | 8 slots on the wheel, each a *tool+brush+preset* bundle | 3 modes (Paint, Blend, Erase), each remembering its brush. Other tools live in menus or the custom toolbar. | Different |
| Brush menu | Tap the active tool again | Tap the active mode again | **Shared**: "tap the active thing again to open its library" |
| Size and opacity | A slider with **4 presets**; **tap+hold+slide spring-loaded** | A **virtual slider** (drag on the button), a popup on tap, and **3-finger drags** | Shared concept of direct drag; different details |
| Colour access | Tap the centre dot for the wheel (COPIC, HSL, RGB) | Tap the dot for the panel; **drag the dot for brightness or the eyedropper** | IP's dot does more |
| Eyedropper | In the colour wheel, or tap+hold if configured | **Long-press on canvas (default)**, drag out from the dot, finger function, stylus button | IP is far faster |
| Undo and redo | 2-finger and 3-finger tap (configurable), plus buttons on the wheel | 2-finger and 3-finger tap, bottom-left buttons, a scrub history slider | **Shared** |
| Hiding the UI | 4-finger tap; Normal, Compact and Hidden modes via swipes | 4-finger tap; immersive mode keeps widgets and mini-toolbars | **Shared** |
| Navigation | 2-finger pan, zoom and rotate with **zoom and rotation detents** | 2-finger pan, zoom and rotate; **quick pinch-and-release to fit**; double-tap to fit | Shared, with different extras |
| Finger vs stylus | Finger Action: Nothing, Active tool, Pan, Select, Nudge, Slice, Configured | Finger function: Eyedropper, Blend, Erase, Move; plus **Touch-Alt** modifiers | Shared concept |
| Long-press on canvas | **Selection** | **Eyedropper** | **Opposite choices** |
| Selection model | Object (stroke) selection. Stays live: popup above the box, direct manipulation, **no confirm**. | Pixel-mask selection. **Modal workspace, ✓/✗, second modal transform, cleared after use.** | Main divergence |
| Contextual chrome | Status-bar toggles for panels; a selection popup | **Cog appears only when needed**; **mini-toolbars** for active modifiers; a Selection indicator | IP does this more systematically |
| Precision | Grids as layers; Snap (to grid, align, traceback, auto-complete); **Measure with real units**; Shape Guides; Shape Recognition | Guides (lines, ellipse, **hatching**, lazy, pen); editable Shapes with **Stamp**; shape detection; 6 perspective grids including curvilinear | Shared: hold-to-shape. Concepts leads on CAD-style precision, IP on painterly guides. |
| Layers | Automatic by tool (default) or manual; no blend modes | Full Photoshop-grade layers, 30 modes, clipping, masks, adjustment layers, groups by pinch | Different |
| Brush engine | Vector path plus raster stamp/grain textures; the editor is iOS-only | ~100-parameter raster engine: smudge, wet paint, watercolour, particles, filter brushes | Different |
| Canvas | Infinite; resolution-independent | Fixed pixels; bounded by RAM; about 8K | Different |
| Animation | None | None (Trace mode only) | Shared gap |

### 3.2 Patterns Joy Paint should copy

Each item gives a specification a designer can build from.

**P1. Undo, redo and history.**

- A 2-finger tap undoes. A 3-finger tap redoes.
- A tap counts only if:
  - all fingers go down within 150 ms of each other,
  - no finger moves more than about 12 dp,
  - all fingers lift within about 300 ms,
  - no stylus stroke is in progress.
- The tap cancels any stroke the first finger may have started. On Android, delay committing finger strokes until the tap window closes, or roll them back.
- Show a HUD toast naming the action ("Undo: Brush stroke").
- After the first undo, show a **history scrub slider** between the Undo and Redo buttons (IP). Drag left to step back, right to step forward.
- *Why*: both apps use this, users love it, and IP's scrubber fixes the "20 taps" problem. [C]/[R]

**P2. Unified navigation with detents.**

- Two fingers pan, zoom and rotate together.
- Add **soft detents**: zoom sticks at 25/50/100/200/400%, and rotation sticks at 0/90/180/270° (optionally every 15°), each within ±3–5°. Give a light haptic tick on engagement.
- Settings switches: "Snap zoom", "Snap rotation", "Allow rotation" (Concepts and IP).
- A quick pinch-in that ends under 250 ms at a scale below about 0.6× means **Fit canvas** (IP).
- A double-tap on the zoom/angle readout resets to 100% and 0° (Concepts).
- For Joy Paint's vector/infinite layer mode: add edge pointers to off-screen content (Concepts iOS).

**P3. Four-finger tap for immersive mode.**

- Hide everything **except** mini-toolbars for active modifiers (symmetry, grid, guide) and any floating widgets the user pinned (IP).
- Leave a 24 dp translucent restore tab in the corner the user last docked to (Concepts).

**P4. Stylus/finger split set automatically on first pen contact (IP), with a Finger Action setting (Concepts list plus IP list).**

- Finger Action options: Nothing, Pan, Use active tool, Eyedropper, Select (lasso), Blend, Erase, Nudge.
- **Default for S Pen users: Pan/Zoom.**
- On Android, detect the pen with `MotionEvent.getToolType() == TOOL_TYPE_STYLUS` or `TOOL_TYPE_ERASER`. Treat `TOOL_TYPE_ERASER` (inverted pen) as Erase mode. [I]

**P5. Touch-Alt modifier (IP).**

- While the pen is down, one resting finger anywhere turns on a constraint:
  - Line: snap to 15°.
  - Rectangle and ellipse: square or circle.
  - Move: 45° axis.
  - Scale: uniform in 100% steps.
  - Brush: the temporary finger tool (eyedropper by default).
- This works like Shift and Alt on a desktop, with no buttons. It is cheap to build and invisible until you need it.

**P6. Direct-drag value controls.**

- **Virtual sliders**: dragging vertically on the Size or Opacity button changes the value. Give 1 unit per ~4 dp and accelerate with speed.
- **Spring-loaded sliders**: tap+hold+slide opens the slider under the finger and closes it on lift (Concepts).
- **Three-finger drag**: vertical for size, horizontal for opacity, with the target reassignable to flow or softness (IP).
- Show a HUD with the number **and a true-scale brush ring** (IP).
- Put invisible **−/+ step zones** at both ends of every linear slider (IP).
- Tap+hold a value to type it in (both apps).

**P7. Four presets per tool for size, opacity and smoothing (Concepts).**

- They appear as four dots above each slider. Tap to jump; tap+hold to edit.
- Presets make repeated line weights consistent in inking and animation.

**P8. Multi-purpose colour dot (IP), plus a long-press eyedropper on canvas (IP).**

- Tap the dot to open the colour panel.
- Drag the dot along the toolbar axis to change HSB brightness, with hue preserved.
- Drag the dot perpendicular to the toolbar, more than about 48 dp, to get a live eyedropper loupe. It samples on release.
- Long-press on the canvas (finger, 400–500 ms, within 8 dp) opens the eyedropper loupe.
- Keep a **Recent colours strip** of 10 colours with near-duplicates merged (IP).
- Add COPIC-style curated palettes as an optional wheel (Concepts), but the default should be HSV plus hex.

**P9. Every brush in every mode (IP).**

- Paint, Blend (smudge) and Erase are three toolbar toggles. Each remembers its own brush.
- Tapping the active mode icon opens the brush library for that mode.
- This gives textured erasers and smudgers for free, and keeps the toolbar at 3 icons.

**P10. One movable cluster that changes form with position (Concepts plus IP).**

- Floating wheel or bar → a vertical bar when snapped to a side edge → a compact radial dock when pushed into a corner. That third form suits phones.
- Move the cluster with tap+hold+drag, or with a 2-finger drag on it.
- Pinch the cluster to scale it.
- Menus flip for left-handed users when the cluster moves to the other side.

**P11. Contextual chrome instead of permanent chrome (IP).**

- (a) A **cog appears only when an active tool has settings**, and it aggregates the settings of every active tool.
- (b) A **mini-toolbar stack at top-right**, one chip per active modifier. Tap the chip's icon to switch the modifier off; secondary toggles such as snap and visibility sit next to it.
- (c) Redo appears only after an undo.
- (d) A selection chip appears only while a selection exists.

**P12. Hold-to-shape (both apps).**

- Hold still (motion under 4 dp for 500–700 ms) at the end of a stroke to replace it with a line, arc, ellipse, rectangle, triangle, quad or smooth path.
- Keep the pen down, then **drag to scale or rotate the recognised shape** (Concepts).
- After lifting, the shape stays editable with handles and a **Stamp** button (IP) until the next unrelated action.

**P13. Brush editor structure (IP) with curve graphs.**

- Tabs: Stroke, Head (stamp), Texture (grain), Paint (wet mix), Effects.
- Every dynamic uses a curve widget: X is the input, Y the output; tap to add a node; drag a node onto a neighbour to delete it; invert; presets.
- **Stroke profile curves** in three segments (fixed start, stretchable middle, fixed end) give tapers.
- **Global calibration curves** for pressure, tilt and velocity apply to every brush.
- For Joy Paint's vector brushes, reuse Concepts' **Stamp** and **Reveal** (grain) types with split-handle jitter ranges and Rotate-along-path.

**P14. Vector strokes that can be restyled (Concepts).**

- Store each stroke as samples (x, y, pressure, tilt, azimuth, time) plus a brush reference. Render at display resolution.
- Select strokes, then pick a new brush, size or colour: the selection is restyled. Scale and Stretch are separate modes (width scales, or width is kept).
- Add **Nudge** (push or pull part of a path) and **Slice** (cut the path; width 0 splits without deleting).
- Warn when a brush uses raster textures that will pixelate beyond N× zoom. Better still, generate grain procedurally so it stays resolution-independent. [I]

**P15. Low latency (IP).**

- Use front-buffer rendering (`GLFrontBufferedRenderer`) for the wet stroke and composite on lift.
- Add stroke prediction (`MotionEventPredictor`) and a refresh-rate-aware scheduler, since Concepts advertises 120 Hz rendering.
- Both apps treat responsiveness as the product. [C]/[I]

**P16. Floating references and Navigator (IP).**

- Several references, each pinch-to-scale.
- The eyedropper samples references.
- Dragging a reference onto a central ✖ hides it.
- The Navigator has a greyscale value-check mode.

**P17. Fill with a reference layer (IP).**

- Any layer can be the fill source.
- Drag from the seed point to set tolerance by eye (Live Tolerance).
- Include a lasso-fill brush.
- These matter for colouring animation frames.

**P18. Safety net (IP).**

- Automatic version snapshots on every save.
- Autosave on exit.
- Per-layer "Hide when recording" (Joy Creator already has recording).

### 3.3 Selection: the design Joy Paint should build (fixing IP's anti-patterns)

**Goal: selecting is one gesture, and selected content can be manipulated immediately with no confirmation.**

**Invocation.** Pick one route as the default; the others are settings.

- **(a) S Pen button held + draw a loop → lasso.** Android reports this as `MotionEvent.BUTTON_STYLUS_PRIMARY` in the button state. No menu trip is needed.
- **(b)** Finger Action = Select.
- **(c)** A **dedicated Select slot in the tool cluster**, 1 tap. Tapping it again opens the selection-type picker: Lasso, Rect, Ellipse, Poly, Wand, Colour range, Brush (quick mask), and "Select layer contents".
- **(d)** Tap+hold+drag on the canvas starts a lasso, and tap+hold without moving opens the eyedropper. The two are told apart by movement after the hold.

**After the loop closes:**

- Show the marching ants, plus a semi-transparent tint outside the selection.
- Draw a **transform box right away**, with handles at the corners and edge midpoints and a rotation handle.
- Show a **floating action pill** just above the box (Concepts' popup position): Move/Transform · Copy · Cut to layer · Duplicate · Fill · Clear · Invert · Feather/Grow ▸ · More ▸. Keep it to one row with no scrolling; put overflow behind "More".

**Manipulation without modes:**

- Drag inside the box: move.
- Two fingers inside the box: move, scale and rotate the content (IP Basic mode).
- Two fingers outside the box: navigate the canvas.
- Handles: scale. A second finger on the screen locks the aspect ratio (Concepts).
- Tap a corner to activate it, then drag: distort (Concepts).
- Stamp: leave a copy (IP).

**Painting while a selection exists:**

- Brush strokes are **clipped to the selection** (stencil semantics) and change **pixels, not the mask**. This is the opposite of IP's workspace behaviour.
- Editing the mask itself is an explicit "Refine" sub-mode, marked by a coloured overlay (IP's overlay idea).

**Commit and exit:**

- **No ✓.** Tap outside the box with a finger to deselect. Changes apply live, and undo reverts them.
- A 2-finger tap undoes the last transform step, not the whole selection.
- Moving content across layers keeps it *floating* until the next non-transform action. That action commits it silently.

**Persistence:**

- The selection **stays after operations** (Duplicate, Cut, Fill and so on). The pill offers "Deselect".
- Offer **"Reselect"** (Ctrl+Shift+D equivalent) in the pill after a deselect.
- **Never keep a selection active and invisible.** If the mask is off-screen, show a chip at top-right: "Selection active ▸ Deselect".

**Refine:**

- Grow and shrink by any radius with a live preview. IP's 6 px cap is an anti-pattern.
- Feather, Invert, and Boolean Add/Subtract via a finger modifier (Touch-Alt) during the lasso: a finger down while drawing the loop subtracts.
- **Save the selection as a mask/channel** for reuse.

**Vector layers** (Concepts parity):

- The same gesture picks strokes: partial or whole, this layer or all layers, including or excluding locked strokes.
- An Item Picker crosshair for single strokes: + adds, − removes.
- Selected strokes can be restyled by choosing another brush or colour.

### 3.4 Anti-patterns to avoid

1. **Selection as a buried, modal workspace** (IP). It is reached through a menu, run inside its own workspace, needs a ✓ at every step, and opens transform as a second workspace. See §2.5.
2. **Cancel means destroy** (IP). An exit control must never also delete the user's work without warning.
3. **State the user can't see** (IP's "invisible but active" selection). Any mode that changes what a stroke does needs a persistent indicator.
4. **Toolbars that scroll sideways and hide actions** (IP's selection toolbar, Tools rows, Guides and Shapes rows with a 5th hidden icon, Perspective row with 2 hidden icons). Collapse into "More" instead.
5. **Features missing on phones** (IP's custom toolbar is tablet-only). Joy Paint runs on phones too, so every customisation must work at 360 dp width.
6. **Automatic layering by tool** (Concepts). It surprises users and strokes end up on layers they didn't choose. At most, offer it as a template.
7. **An eraser that only hides** (Concepts masks). Hidden content reappears in vector export and gets in the way of other tools. If Joy Paint has masks, name them "Mask" and give the vector eraser true-delete behaviour by default (Slice).
8. **Raster textures on resolution-independent strokes** without telling the user (Concepts). They pixelate at depth and inflate files. Use procedural or high-resolution mip-mapped grain, and show "texture resolution limit" in the brush info.
9. **Losing brush dynamics on export** (Concepts SVG uses a single width). Export variable-width strokes as filled outline paths.
10. **Platform-split file formats and features** (Concepts' .concept versus .concepts, iOS-only editor; IP's separate licences). Joy Paint is Android-only, but its format should be documented and open (GPL).
11. **A ✓-to-exit pattern for simple tools** (IP fills, liquify, adjustments). Use live application with undo, and keep explicit Apply only for destructive filters with previews.
12. **Hidden gestures with no teaching** (both apps). Reviewers say features are "never discovered". Add a one-screen gesture cheat sheet (long-press the "?" or a 5-finger tap) and first-run coach marks for the six core gestures.
13. **Per-session-only undo with a hard step cap** (IP: 64). Joy Creator already has an undo stack. Persist undo across sessions where memory allows, and back it with version snapshots.
14. **Liquify breaking normal undo** (IP). Keep undo semantics the same in every workspace.
15. **Proprietary brush lock-in** (IP has no ABR; Concepts market brushes can't be moved out of their pack). A GPL app benefits from **importing ABR (sampled tips) and Procreate `.brushset`** (a zip holding a plist and PNGs), even if only in part. Neither competitor does this.

### 3.5 Gaps neither app fills (opportunities)

- **Frame-by-frame animation.** Neither app has it. IP's Trace mode, which progressively dims lower layers, shows that users will use layer dimming as a stand-in for onion skinning. Joy Paint can merge Joy Creator's timeline with a painting canvas. [C]/[I]
- **A hybrid layer model.** Allow raster and vector layer types in the same document: Concepts-style strokes on vector layers for clean line art, and IP-style wet raster for colour. Fill tools should be able to use a vector line-art layer as their source. [I]
- **S Pen specifics.** Hover preview (Concepts shipped it on Android in Mar 2026), the side button, and treating an inverted pen (`TOOL_TYPE_ERASER`) as the eraser. Air Actions are not reachable by third-party apps in the general case [I]; don't depend on them.

---

## Sources

**Concepts (official)**
- Concepts Manual index (Android): https://concepts.app/en/android/manual/
- Your Workspace (Android): https://concepts.app/en/android/manual/yourworkspace
- Your Workspace (current): https://concepts.app/en/manual/workspace
- Brushes and Tools (Android): https://concepts.app/en/android/manual/brushesandtools
- Brushes and Tools (current, incl. brush editor): https://concepts.app/en/manual/brushes-and-tools
- Selection (current): https://concepts.app/en/manual/selection
- Selection (Android): https://concepts.app/en/android/manual/selection
- Precision Tools: https://concepts.app/en/manual/precision-tools and https://concepts.app/en/android/manual/precisiontools
- Infinite Canvas: https://concepts.app/en/manual/infinite-canvas and https://concepts.app/en/android/manual/infinitecanvas
- Layers: https://concepts.app/en/manual/layers
- Colors: https://concepts.app/en/manual/colors
- Export: https://concepts.app/en/manual/export
- Settings: https://concepts.app/en/manual/settings and https://concepts.app/en/android/manual/settings
- Gesture Quick Reference (Help Center): https://tophatch.helpshift.com/hc/en/3-concepts/faq/23-gesture-quick-reference/
- Undo/redo FAQ: https://tophatch.helpshift.com/hc/en/3-concepts/faq/115-how-do-i-undo-or-redo-an-action/
- Vector watercolor FAQ: https://tophatch.helpshift.com/hc/en/3-concepts/faq/90-how-does-the-vector-watercolor-brush-work/
- The Top Gestures of Concepts Pros: https://concepts.app/en/tutorials/the-top-gestures-of-concepts-pros/
- Setting Up Your Menus, Brushes and Presets: https://concepts.app/en/tutorials/setting-your-menus-brushes-and-presets/
- Digital Art: Raster vs Vector: https://concepts.app/en/digital-art-raster-vs-vector/
- Concepts vs Procreate: https://concepts.app/en/concepts-vs-procreate/
- Android Roadmap: https://concepts.app/en/android/roadmap
- News: https://concepts.app/en/news/
- Concepts in 2018 and Where We're Going: https://concepts.app/en/stories/concepts-in-2018-and-where-were-going/
- Hardware Guide: https://concepts.app/en/concepts-hardware-guide/
- Android manual v1.0 PDF (historical): https://cdn.tophatch.com/downloads/concepts-manual-android-1.0.pdf

**Concepts (reviews / user sentiment)**
- Parka Blogs review: https://www.parkablogs.com/picture/concepts-app-review-sketching-vector-and-infinite-canvas
- App Store reviews: https://apps.apple.com/us/app/concepts/id560586497?see-all=reviews
- justuseapp review aggregation: https://justuseapp.com/en/app/560586497/concepts/reviews
- Kestrel Michaud, Raster vs Vector: https://kestrelmichaud.com/blog/2020/raster-vs-vector/
- Google Play listing: https://play.google.com/store/apps/details?id=com.tophatch.concepts

**Infinite Painter (official docs, docs.infinitestudio.art/painter/…)**
- General controls / Studio: https://docs.infinitestudio.art/painter/studio/
- Gestures: https://docs.infinitestudio.art/painter/studio/gestures/
- UI customization: https://docs.infinitestudio.art/painter/studio/customize/
- Menus and panels tour: https://docs.infinitestudio.art/painter/studio/panels/
- Screen widgets: https://docs.infinitestudio.art/painter/studio/widgets/
- Interface controls: https://docs.infinitestudio.art/painter/technical/interface/
- Application settings: https://docs.infinitestudio.art/painter/technical/settings/
- Using a stylus: https://docs.infinitestudio.art/painter/technical/stylus/
- Technical specifications: https://docs.infinitestudio.art/painter/technical/specifications/
- Keyboard shortcuts: https://docs.infinitestudio.art/painter/technical/keyboard/
- About the app: https://docs.infinitestudio.art/painter/about/app/
- Brushes overview / modes: https://docs.infinitestudio.art/painter/brushes/
- Basic brush controls: https://docs.infinitestudio.art/painter/brushes/controls/
- Built-in brushes: https://docs.infinitestudio.art/painter/brushes/types/
- Adding new brushes: https://docs.infinitestudio.art/painter/brushes/creating/
- Brush Editor overview & curves: https://docs.infinitestudio.art/painter/brushes/settings/
- Stroke / Head / Texture / Paint / Special tabs: https://docs.infinitestudio.art/painter/brushes/settings/stroke/ , /head/ , /texture/ , /paint/ , /effects/
- Colour panel & eyedropper: https://docs.infinitestudio.art/painter/color/panel/
- Layers: https://docs.infinitestudio.art/painter/layers/ ; blending: https://docs.infinitestudio.art/painter/layers/blending/ ; clipping: https://docs.infinitestudio.art/painter/layers/clipping/ ; grouping: https://docs.infinitestudio.art/painter/layers/grouping/ ; visibility modes: https://docs.infinitestudio.art/painter/layers/visibility/
- Selections: https://docs.infinitestudio.art/painter/selections/ , /using/ , /workspace/ , /lasso/ , /shapes/ , /path/ , /wand/ , /brush/ , /color-range/ , /editing/ , /layer/ , /advanced/
- Transformations: https://docs.infinitestudio.art/painter/transform/ , /workspace/ , /basic/ , /warp/ , /virtual/
- Guides: https://docs.infinitestudio.art/painter/guides/ ; Lazy: https://docs.infinitestudio.art/painter/guides/lazy/
- Shapes & detection: https://docs.infinitestudio.art/painter/shapes/ , https://docs.infinitestudio.art/painter/shapes/detection/
- Perspective & snapping: https://docs.infinitestudio.art/painter/perspective/ , https://docs.infinitestudio.art/painter/perspective/snapping/
- Symmetry: https://docs.infinitestudio.art/painter/symmetry/ ; Patterns: https://docs.infinitestudio.art/painter/patterns/ ; Liquify: https://docs.infinitestudio.art/painter/liquify/
- Fills: https://docs.infinitestudio.art/painter/fills/ , https://docs.infinitestudio.art/painter/fills/advanced/
- New project / canvas size: https://docs.infinitestudio.art/painter/home/create/
- Export: https://docs.infinitestudio.art/painter/data/export/
- Older v6 help (selection & interface): https://www.infinitestudio.art/painter/help/v6_0/selection/selection.html , https://www.infinitestudio.art/painter/help/v6_0/interface/interface.html
- FAQ: https://www.infinitestudio.art/painter/faq/
- Android Developers story (low-latency, ChromeOS): https://developer.android.com/stories/apps/infinite-painter
- Community wiki feature list (8000×8000, etc.): http://infinitepainter.wikidot.com/features ; Transform notes: http://infinitepainter.wikidot.com/transform

**Infinite Painter (reviews / user sentiment)**
- App Store reviews: https://apps.apple.com/us/app/infinite-painter/id1146543227?see-all=reviews
- justuseapp review aggregation: https://justuseapp.com/en/app/1146543227/infinite-painter/reviews
- Kamila Stankiewicz review: https://kamilastankiewicz.com/infinite-painter-app/
- Draw Your Weapon, "10 reasons": https://drawyourweapon.com/10-reasons-infinite-painter-is-the-painting-app-for-android/
- Wikidot forum thread (naming/icon confusion): http://infinitepainter.wikidot.com/forum/t-6124665/where-is-the-lasso-paint-tool

**Not reachable from this environment:** reddit.com (r/ConceptsApp, r/infinitepainter), which returned 403. A follow-up pass from a normal browser should search both subreddits for "selection", "lasso", "transform" and "check mark" to back up §2.5 items 1, 3 and 8 with user quotes.
