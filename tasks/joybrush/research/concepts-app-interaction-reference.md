# Concepts (TopHatch) — UI & Interaction Reference for Building Compatible Apps

**Purpose:** A machine-readable specification of how the Concepts vector sketching app works: its gesture grammar, interface architecture, subsystems, design patterns, constraints, and vocabulary. Written to be fed to an AI so it can design and build similar, compatible apps and features using the same interaction language.

**Research date:** October 5, 2026. Concepts iOS version at time of research: 6.36.

---

## 0. How to read this document

Every statement is tagged by evidence level.

- **[DOC]** — stated in TopHatch's official manual, help center, tutorials, or App Store listing.
- **[INF]** — my inference or synthesis from documented behavior. Treat as a hypothesis, not fact.
- **[REC]** — a recommendation for someone building a compatible app. Not a description of Concepts.

**Source conflicts noted:**
- The Help Center gesture reference says *one-finger drag pans the canvas when no tool is active*. The manual says *two fingers pan by default; one-finger pan is an optional setting*. The manual is newer and more detailed. Treat two-finger pan as default and one-finger pan as configurable.
- The Help Center says *double-tap an eraser icon clears all content on the current layer*. The manual says double-tapping the mask tool opens a **Quick Clear menu** (layer / all strokes / text / images / measurements). The manual describes the newer behavior; treat it as a menu, not an instant clear.
- The older tutorial "Top Gestures of Concepts Pros" (2023) describes a "Selection Toolbar" and "HUD." Current manual terminology is **Selection Menu**, **Selection Popup**, **Status Bar**, **Precision / Layers / Objects menus**. Use current terms.

**Not verified (see §14):** the PDF-annotation chapter, Accounts, Concepts for Business, iPhone-specific layout behavior, exact timing values for tap+hold.

---

## 1. Product summary

**Concepts** is an infinite-canvas, vector-based sketching, note-taking, and design app by TopHatch, Inc. [DOC] Platforms: iOS/iPadOS (iPhone and iPad), Android, Windows, ChromeOS. [DOC] Optimized for touch and stylus. [DOC]

**Core promise:** every stroke is an editable object. You draw like on paper, then select, nudge, slice, recolor, re-brush, scale, and measure anything afterward without redrawing. [DOC]

**Primary use cases:** ideation, whiteboarding, mind maps, storyboards, product sketches, architectural plan markup, scaled drawings with real-world measurements, note-taking over imported PDFs/images. [DOC]

**Recognition:** Apple Editors' Choice; 4.7 rating across ~27K ratings on the App Store. [DOC]

### 1.1 The engine model (critical for compatibility)

- **Vector–raster hybrid engine.** [DOC] Strokes are stored as vector paths with tool/brush properties. Many brushes render raster textures on top of the path. Consequences:
  - Strokes stay movable/adjustable/restylable (change a selected stroke's brush by tapping a different tool). [DOC]
  - Textured brushes can pixelate when zoomed in far, and raster-textured brushes increase file size and cost performance. [DOC]
  - The **Dynamic Pen** "renders a raster on top of the vector line," giving larger files and possible edge pixelation at high zoom. [DOC]
  - **Wire** and **Fixed Width** pens are the "clean vector" brushes. Wire keeps constant line width at any zoom level. [DOC]
- **Objects in the document:** strokes, fills, text labels, imported images, imported PDF pages, measurement labels, groups, plus special layers (Grid layer, Measurement layer). [DOC]
- **Per-stroke measurement data** is attached to all strokes, selections, and imported items, invisible until Measure is enabled. [DOC]
- **Native file formats:** `.concept` (iOS) and `.concepts` (Windows/Android). These are **not interchangeable** — iOS opens only `.concept`; Windows/Android open only `.concepts`; TopHatch states it is working to unify them. [DOC] **No public file-format specification was found.** [INF: none exists publicly]
- **Interchange formats (the actual compatibility surface):** export JPG, PNG, SVG, DXF (R14), PSD (layers), PDF flattened, PDF vector paths. Import JPG, PNG, PSD, PDF, native files. [DOC]
- **Infinite canvas ≠ infinite zoom.** Zoom range is limited for technical reasons. [DOC]
- **Per-drawing size cap exists** (a user review mentions a usage cap per drawing, attributing it to vector/technical constraints). [INF from user review]

---

## 2. Design principles (synthesized)

These are [INF] unless a quote is marked [DOC]. They explain *why* the interface looks the way it does and should guide extensions.

1. **Physical metaphor first.** The founder's origin story: while playing with a rubber band, he wanted a tool that "grows when your hand grows, rotates when your hand rotates." [DOC] Direct manipulation is the foundation; gestures map to physical actions.
2. **The canvas is sacred; chrome is minimal and movable.** Every menu can be repositioned, collapsed to icons, or hidden entirely. [DOC]
3. **Few input primitives, combined multiplicatively.** One finger, two fingers, tap, long-press, drag, plus a *second finger as a live modifier*. A small vocabulary produces a large number of operations.
4. **Context decides meaning, not modes.** Tap+Hold on canvas means "select" without switching tools. A second finger toggles sub-modes mid-gesture. [DOC]
5. **Progressive disclosure through tap depth.** Tap = use. Tap again = configure. Tap+hold = deeper/alternate. Double-tap = special action. Every control follows this ladder. (See §9, Pattern 1.)
6. **Speed lane and precision lane for every value.** Drag a slider for fast approximate input; tap+hold the number to type exact values. (Pattern 5.)
7. **Editing over undoing.** "Because Concepts is vector-based, you can always edit your lines instead of undoing them." [DOC]
8. **Accidental-trigger risk is weighed explicitly.** Responding to a user request for Apple Pencil double-tap Undo, TopHatch declined, citing Apple's guideline against using double-tap for content-modifying or potentially destructive actions. [DOC] Destructive actions elsewhere sit behind menus (Delete layer, Quick Clear via double-tap on the eraser is the notable exception — see §4.3).
9. **Everything user-facing is configurable at the gesture layer.** Tap-and-hold timing and action; two/three/four-finger tap actions; finger vs. stylus roles; stylus button actions. [DOC]
10. **Handedness is a first-class citizen.** Move the Tool Wheel past the center line and the whole UI mirrors. [DOC]
11. **Precision is opt-in, not default.** Grids, snap, measure, shape guides, shape recognition live in one panel with independent checkboxes. Freehand stays frictionless until the user asks for constraints. [INF]

---

## 3. Screen architecture

### 3.1 Two top-level contexts

1. **Gallery** — file browser. 2. **Canvas** — the drawing workspace.

### 3.2 Gallery [DOC]

**Layout elements:** Start Something New (+) button; Sidebar toggle (shows current folder name); Pro Store status; Account; Help; Sidebar (folders, Recents, Recently Deleted under "Extras", iCloud location on iOS); Back-to-parent; Folder name (tap to rename); Folder notes/description; Grouping (Date Created / Date Updated / Folder / None); Sorting (Name / Date Created / Date Updated); drawing thumbnails; subfolders; "+ New Drawing" shortcut.

**"Start Something New" menu options:** New Drawing, New Folder, Photos (create drawing from photo), Files (import), Clipboard (create from clipboard), Take a Picture, Recently Imported.

**Gallery gestures:**

| Gesture | Result |
|---|---|
| Tap / click | Open drawing or folder |
| Tap+hold / right-click | Context menu: Rename, Duplicate, Delete (and Share on iOS/Android) |
| Drag & drop | Move drawings between folders (Gallery or Sidebar), or out to other apps |
| Scroll | Browse drawings; scroll sidebar for folders |
| Two-finger pinch/spread | Zoom the Gallery view (iOS/Android only) |

**Storage model:** On iOS the Gallery mirrors a dedicated **Concepts** folder in the Files app; changes sync both directions. Moving a file out of that folder removes it from the Gallery but the file still opens in Concepts via Files. iCloud is a separate sidebar location — drag drawings onto it to sync across devices on the same Apple ID. Deleting the app on iOS deletes the Concepts folder and its content. On Windows, a Concepts folder in File Explorer; gallery location is changeable; deleting the app does not delete files. On Android, App Storage by default (deleted with the app), optional Internal Storage permission, optional Google Drive backup (not sync). No native sync on Windows/Android — use third-party folder sync. [DOC]

### 3.3 Canvas anatomy [DOC]

Top to bottom:

1. **Status Bar** (top). Left to right: Gallery button · Drawing name (tap to rename) · Menu toggles (Precision, Layers, Objects/Library — tap to show/hide on canvas; active ones show an underline) · Canvas status & measurements (zoom %, rotation angle, selection dimensions) · Pro Store (PRO / Go PRO) · Import · Export · Settings · Help. Windows adds a title bar.
2. **Tool Wheel** (or Tool Bar). Movable, scalable.
3. **Color Palette & Mixer** (iOS only), below the Tool Wheel.
4. **Floating menus:** Precision, Layers, Objects/Library — all movable canvas elements.
5. **Contextual UI that appears on demand:** Selection Menu (bottom of screen), Selection Popup (above selection), Control Points (corners of selection), Shape Guide handles, Grid edit handles, Color Picker loupe, toast notifications ("Editing Grid — Done").

### 3.4 Status Bar details [DOC]

- **Zoom and rotation field:** shows current zoom % and canvas angle. *Tap* opens an editor/popup with presets, lock/unlock, and dimension fields. *Tap+hold* edits inline with keyboard. *Double-tap* the zoom value returns you to the center of the canvas (and resets zoom/rotation — this was reinstated in iOS 6.31). With an active selection, the field shows the selection's measurements instead.
- **Measurement popup:** with one stroke selected: length, width, height, rotation. Multiple strokes or image: width, height, rotation. Fill: width, height, length, area, rotation. Values accept any unit and auto-convert to document units. A **chain icon** locks/unlocks width-height ratio. Presets for rotate and scale.
- **iOS only:** pointers appear at screen edges pointing to off-canvas strokes; tap one to jump to the nearest stroke ("lost on the infinite canvas" rescue).

### 3.5 Three UI density modes [DOC]

| Mode | How to enter | What changes |
|---|---|---|
| **Normal** | Default | All menus visible; Tool Wheel fully on canvas |
| **Compact** | Swipe outward on the Layers or Precision menus, or corner the Tool Wheel | Labels collapse to icons; Tool Wheel docks; size/opacity/smoothing sliders hidden from Tool Bar |
| **Hidden** | In compact mode, swipe outward again | All menus hidden; a small Concepts icon remains on canvas to bring them back; can also be bound to a finger-tap gesture (default: four-finger tap = Toggle Interface) |

Return to normal: drag the Tool Wheel out of the corner, or swipe inward on Layers/Precision.

**Small-screen relevance [INF]:** compact/hidden modes plus a scalable, dockable tool control are the documented mechanisms that make the app usable on phone-sized screens. There is an iPhone-only "Essentials" purchase SKU, confirming iPhone is a supported target. [DOC] The manual does not describe a separate iPhone layout; it appears to be the same system with more aggressive compact-mode use. [INF]

---

## 4. The Gesture Grammar

This is the core of the interaction language.

### 4.1 Input channels [DOC]

| Channel | Role |
|---|---|
| **Stylus** | Draws with the active tool. Supports pressure, tilt, velocity, barrel roll (Apple Pencil Pro), hover previews (M2+ iPads), squeeze action, shortcut buttons, eraser end. |
| **One finger** | Draws (if no stylus) — or, with a stylus present, configurable via **Finger Action**. |
| **Two fingers** | Canvas navigation (pan/zoom/rotate), or transform the current selection / shape guide / grid. |
| **Tap+Hold** | Alternate "intent" channel — select, pick color, edit value, move a menu. |
| **Multi-finger taps** | Customizable shortcut actions (two, three, four fingers). |
| **Mouse/keyboard** | Parity layer: Space = pan, R = rotate, S = zoom, E = eraser, Ctrl+Z / Ctrl+Shift+Z, Ctrl+V; shortcuts are editable. Scroll wheel over Tool Wheel scales it. |

**Finger Action options (with a supported stylus):** Do Nothing, Use Active Tool, Pan Canvas, Select, Nudge, Slice, Configured Tool. [DOC] Typical pro setup: **stylus draws, finger pans** (or finger selects). [DOC]

**Palm rejection on iOS:** turn off "Multitouch Gestures" in iPadOS system Settings → General to avoid conflicts. [DOC]

### 4.2 Canvas navigation gestures [DOC]

| Gesture | Action | Notes |
|---|---|---|
| Two-finger drag | Pan | One-finger pan is an optional setting |
| Two-finger pinch/spread | Zoom | Snaps at common zoom levels (snap toggle added in iOS 6.36) |
| Two-finger twist | Rotate canvas | Snaps at common angles (toggle added in iOS 6.36) |
| Pan tool / Zoom tool / Rotate tool | Same functions as tools | Zoom tool: draw right/up = zoom in, left/down = out |
| Space (hold or tap) | Pan mode | Hold = momentary; tap = toggle |
| R (hold or tap) | Rotate mode | Also rotates active selection/shape guide when one exists |
| S (hold or tap) | Zoom mode | Also scales active selection/shape guide |
| Tap zoom/angle value | Numeric editor | |
| Tap+hold zoom/angle value | Inline keyboard entry | |
| Double-tap zoom value | Return to center / reset | |

Users can disable two-finger canvas rotation and/or zoom independently (Settings → Interaction → Touch Input → Two Fingers) to lock the view. [DOC]

### 4.3 Tap-count shortcuts [DOC]

| Gesture | Default | Configurable options |
|---|---|---|
| **Two-finger tap** | Undo | Do nothing, Undo, Redo, Select last item, Show Layers, Show Color Wheel, Tool Setup, Show Objects, Toggle Shape Guide, Toggle Interface, Toggle canvas rotation, Toggle canvas zoom, Select all |
| **Three-finger tap** | Redo | Same list |
| **Four-finger tap** | Toggle Interface | Same list |
| **Double-tap on eraser/mask tool in wheel** | Open Quick Clear menu | Clear layer / all strokes / text / images / measurements |

Undo/Redo are also buttons on the Tool Wheel and stylus shortcut actions. Undo is unlimited ("saves every stroke"). [DOC]

**Deliberate omission:** no double-tap-Undo on Apple Pencil, justified by Apple's HIG on accidental double-taps and data loss. [DOC]

### 4.4 Tap+Hold — the most important gesture [DOC]

Configurable in Settings → Interaction → Tap+Hold (action and activation time).

| Target | Result |
|---|---|
| Blank canvas (default) | Activates **Selection** (Lasso by default) with the Selection Menu at the bottom |
| Canvas, toggled to Color Picker | Samples color/brush properties |
| Canvas | Paste icon appears for clipboard paste |
| A floating menu (Tool Wheel outer ring, Precision, Layers, Library) | Pick it up and drag it; a **map of alignment/dock positions** drops down |
| Color dot at wheel center | Opens the Colors menu (iOS) |
| Numeric value (size preset, HSL value, measure label, status bar value) | Inline keyboard edit |
| Layer | Pick up and reorder |
| Vanishing point of a perspective grid | Enters grid edit mode |
| Artboard corner | Move the artboard |
| Measurement label on a stroke | Drag it along the stroke path |
| Brush pack title / palette (iOS) | Share menu; tap+hold+drag a brush pack exports it as a file |
| Drawing thumbnail in Gallery | Context menu |
| Selection (with "Drag & drop active selections" on) | Lift selection as PNG to drag into another app |

### 4.5 Second finger as modifier [DOC]

While one touch is active, a second finger changes behavior without breaking the gesture:

| While doing | Second finger does |
|---|---|
| Tap+Hold selection | Toggles Selection Menu filters (Lasso ↔ Item Picker, partial, lock, layers) |
| Using the Selection tool | Anywhere touch toggles Lasso/Item Picker temporarily |
| Item Picker crosshair hovering a stroke | Tap = add to / subtract from selection |
| Resizing selection by a control point | Locks aspect ratio |
| Control points | Two active points + one finger drag = skew; two points + two-finger pinch = warp |

### 4.6 Draw & Hold (shape recognition) [DOC]

Draw a shape in 1–4 strokes, **hold at the end of the final stroke** (activation time configurable; the manual screenshot shows 0.5 s). The app replaces the rough strokes with a clean shape. Without lifting, **move the stylus to scale and rotate it**. Supported shapes: straight and curved lines, arrows, triangles, squares, rectangles, circles, ellipses. Enable/disable and timing in Settings → Interaction → Draw & Hold. iOS 6.31 adjusted snapping and prevented the start point of recognized lines from shifting.

### 4.7 Scrub gestures [DOC]

- **Scrub across layer eye icons** → enter Focus Mode and shift focus rapidly between layers.
- **Tap+hold+slide on a Tool Wheel property shortcut (size/opacity/smoothing)** → slider opens as you slide and closes when you lift (one continuous gesture, no dismiss tap needed).
- **Tap+hold+drag on the color palette** → color mixer: palette colors blend into a continuous gradient you can sample.

### 4.8 Complete gesture table (quick lookup)

| Gesture | Context | Result |
|---|---|---|
| Tap tool (outer ring) | Tool Wheel | Activate tool |
| Tap active tool again / double-tap inactive tool | Tool Wheel | Open Brushes menu |
| Tap size/opacity/smoothing symbol | Tool Wheel inner ring | Open slider with 4 presets |
| Tap+hold+slide on symbol | Tool Wheel inner ring | Immediate in-flow adjustment |
| Tap+hold preset value | Slider | Type exact value |
| Tap center color dot | Tool Wheel | Open Color Wheel |
| Tap+hold center color dot | Tool Wheel | Open Colors menu (iOS) |
| Tap+hold on canvas | Canvas | Selection (default) |
| Drag around strokes | Lasso active | Select; lasso again to subtract |
| Drag crosshair, release | Item Picker | Select single stroke |
| Tap with another finger while hovering | Item Picker | Add stroke(s) |
| Drag from corner point | Selection | Scale / stretch (lock ratio with second finger) |
| Tap 1 corner then drag | Selection | Distort |
| Tap 2 corners then drag one finger | Selection | Skew |
| Tap 2 corners then pinch/spread two fingers | Selection | Warp |
| Pinch / spread | Selection active | Scale selection |
| Twist | Selection active | Rotate selection (clockwise positive) |
| Drag selection to a layer | Layers panel | Move strokes to that layer |
| Drag selection onto New Layer button | Layers panel | Create new layer containing the strokes |
| Drag selection into Object pack view | Library | Create an Object |
| Tap active layer | Layers | Layer Options menu |
| Double-tap layer | Layers | Focus Mode on/off |
| Tap+hold+drag layer | Layers | Reorder (switches to Manual sorting) |
| Tap+hold+drag grid layer | Layers | Move grid within layer stack |
| Tap crosshairs, drag | Shape Guide | Move guide |
| Double-tap crosshairs | Shape Guide | Special function per guide (see §8.4) |
| Drag handle | Shape Guide | Reshape guide |
| Two-finger twist | Shape Guide active | Rotate guide |
| Tap measurement label | Measure active | Stick label to canvas |
| Tap+hold measurement label | Measure + selection/guide | Type exact length; shape resizes to match |
| Tap edge pointer (iOS) | Canvas edge | Jump to nearest off-screen stroke |
| Swipe outward on Layers/Precision | Canvas | Compact → Hidden mode |
| Pinch/expand on Tool Wheel | Tool Wheel | Scale the wheel |
| Drag wheel past center line | Tool Wheel | Flip UI to left-handed layout |

---

## 5. The Tool Wheel / Tool Bar (primary control surface)

### 5.1 Structure [DOC]

Concentric rings:

- **Outer ring:** up to **8 configurable tool slots**. Tap = activate. Tap an already-active tool (or double-tap an inactive one) = open the Brushes menu to change what that slot holds.
- **Inner/middle ring:** three property controls — **Size, Opacity, Smoothing** — each with **4 presets** and a slider.
- **Center:** color dot showing current color and opacity.
- **Side buttons:** Undo, Redo (Redo appears when available).

Each slot stores its own brush, size, opacity, smoothing, and color. [DOC: "You can configure each tool slot with its own brush, size, opacity, smoothness, and color."] [INF: this makes a slot a complete "pen preset," so switching tools is one tap with no re-setup.]

### 5.2 Properties [DOC]

- **Size:** slider + 4 presets (tap a preset to select; drag along slider to set; tap+hold to type a number).
- **Opacity:** 100% opaque → 0% transparent.
- **Smoothing:** 0% = raw hand input; 50% = bumps removed; **100% = a perfectly straight line between start and end point** regardless of wobble. Smoothing also applies to selected strokes — select a line and drag smoothing to straighten it after the fact. Fill needs smoothing under 100% or the shape collapses to a line/point.

### 5.3 Wheel ↔ Bar [DOC]

- Drag the wheel (grab the **outer ring**) to an edge: on iOS, to either screen edge; on Windows/Android, onto the canvas layout manager in the center. It transforms into a **Tool Bar** with the same functions.
- Switch via Settings → Workspace → Tool Setup.
- **Scale** by pinching on it (mouse: scroll wheel while hovering).
- **Layout manager:** while dragging a menu, a map of snap/dock positions drops down; release over one.
- **Left-handed:** cross the center line; menus mirror. Settings also has a handedness option (iOS) that determines default layout and the side of measurement labels.

### 5.3.1 Design analysis [INF]

The wheel's outer ring holds *what* (tool), the inner ring holds *how much* (size/opacity/smoothing), the center holds *what color*. This ordering puts the highest-frequency, lowest-cost choice (switch tool) at the outermost ring where a thumb travels least, and the numeric parameters inside. All of it fits in one thumb-reachable circle that can be moved to any edge and shrunk by pinching.

### 5.4 Color Palette & Mixer (iOS) [DOC]

Under the wheel: active palettes (swipe between them). Tap a color to set the tool's color. **Tap+hold+drag** enters mixer mode: palette colors blend into a continuous gradient you can choose any point from.

---

## 6. Brushes and tools

### 6.1 Brushes menu [DOC]

Opened by tapping an active tool. Contains: live **brush preview** (on iOS you can test strokes in it), **My Brushes** (Basic Brushes and Tools), **Brush Market**, edit existing brushes, create custom brushes (iOS, paid). Any brush can be applied to existing strokes: select strokes, then tap a different tool.

### 6.2 Basic brushes [DOC]

| Brush | Responds to | Notes |
|---|---|---|
| Pen | Velocity | Fast = thinner, slow = thicker |
| Fountain Pen | Velocity | Inverse: slow = thinner, fast = thicker |
| Dynamic Pen | Pressure | Raster over vector; larger files; edge pixelation at high zoom |
| Fixed Width Pen | None | Uniform thickness; recommended for SVG/DXF export |
| **Wire** | None | Width constant at every zoom level; wireframes, light CAD, perspective; recommended for SVG/DXF |
| Soft Pencil / Hard Pencil | Tilt, pressure, velocity | Tilt stylus to shade |
| Marker | Stylus rotation | Chisel follows rotation; Apple Pencil Pro barrel roll |
| Airbrush | — | Soft edges, subtle texture |
| Watercolor | — | Blends with consecutive watercolor strokes **on the same layer**; interrupt blending with other stroke types or other layers; masks don't disrupt blending |
| **Fill** | — | Draw any shape; fills the interior; shows **area** when Measure is on |
| Dotted | — | Single vector stroke with raster dots on top |

### 6.3 Tools [DOC]

- **Selection** — arrow icon; can be its own slot. Two modes: Lasso and Item Picker (plus Color Picker via the toggle).
- **Nudge** — push/pull existing strokes like string. Smaller size + zoomed in for tight corners; larger + zoomed out for gentle curves. Behavior depends on how many vector points a stroke has (100%-smoothed lines and 4-point squares nudge differently). With Highlight Selection on, you see before/after.
- **Slice (vector eraser)** — destructive; cuts the vector path; no sharp mask edge. Width set to **0** splits strokes into pieces without removing anything. Cannot erase imported images (use masks).
- **Hard Mask / Soft Mask** — non-destructive erasers. Hide parts of drawings or images; mask strokes remain editable (resize, change opacity, move). Exclude them from selection via filters. Mask strokes show as white lines in SVG/DXF exports.
- **Quick Clear** — double-tap the mask tool to clear layer/all strokes/text/images/measurements.
- **Text** — tap canvas to place a label, tap elsewhere to commit. Edit by selecting then tapping the text-edit icon in the Selection Popup. Text transforms like any stroke including control-point perspective warps. Supports any language the device can input, including emoji.
- **Pan, Rotate, Zoom tools** — navigation as tools (useful with a stylus-only setup).
- **Brush Market** — purchasable brushes (many raster-textured; may pixelate; can raise file size).

### 6.4 Custom Brush Editor (iOS, paid) [DOC]

Two types:
- **Stamp brush:** repeats one to nine stamp images along the path, optionally over a grain texture.
- **Reveal brush:** unmasks a grain texture; even and non-overlapping.

Editor sections: Live Preview · Core Setup (type) · Stamp & Grain (up to 9 stamps JPG/PNG; seamless grain; align grain per stroke; grain scale/rotation) · Presets & Basic Dynamics (size/opacity/smoothing presets; **variance curves** mapping pressure, tilt, velocity → size/opacity, drawn directly in a graph; midpoint = value from the Tool Wheel; double-tap vertical axis resets; smoothing has no variance curve) · Shape Controls (spacing, scatter, rotation, each with **split handles** — together = no jitter, apart = random range — plus "Rotate along stroke path") · Advanced Settings (which **automatic layer** the brush draws onto: Image, Pencil, Marker, Airbrush, Pen, Wire, Text, Fill, Watercolor, Custom/typed name, or Active Layer).

**Sharing:** live-share brush packs by link (read-only for recipients, updates propagate; unshare removes it); export pack files by tap+hold+drag to Files. Imported/market brushes can be duplicated and edited but not moved out of their pack. Sharing is iOS-only currently. [DOC]

---

## 7. Color system [DOC]

- **Tap** the center dot → Color Wheel. Three wheels: **COPIC** (curated by Too Corporation; coded letter+number; drag up/down to turn the wheel, tap to set), **HSL** (hue, saturation, lightness sliders), **RGB** (three sliders). Tap+hold any value to type it.
- **Star button** (inner ring) → Colors menu (iOS): current color; COPIC/RGB/HEX/HSB value fields; **My Palettes** (unlimited palettes, up to 8 colors each); **Dynamic Palettes** (matching shades generated from current colors).
- **Palette editing:** tap+hold+drag a color from the current dot, the Copic wheel, or another palette; drop on a palette when the **+** appears; reorder by dragging; remove by dragging it away and releasing.
- **Drag & drop colors** from other apps (split-screen) into a palette. Also, copy colors to the clipboard from other apps and see them on the Color Wheel (iOS).
- **Live-share palettes** by link (iOS, subscription).
- **Color Picker:** tap the picker icon in the Color Wheel's inner circle, or tap+hold canvas → toggle to Color Picker. A loupe shows crosshairs (sample point); the **bottom half of the ring = current tool color; top half = color under the picker**; **lift to commit**. **Alpha toggle:** on = reads color and opacity ignoring background; off = reads at 100% opacity including background. **Brush Type** icon: pick the stroke's *brush properties* into the active slot, not just color. [INF: this makes the picker a "style eyedropper."]

---

## 8. Selection, layers, precision, objects

### 8.1 Selection [DOC]

**Four ways to enter selection:** (1) the Selection tool in a slot; (2) **Tap+Hold** on canvas (default); (3) Finger Action = Select (stylus users); (4) tap the active layer → Select All icon.

**Selection Menu (bottom of screen):** Selection Type toggle (Item Picker / Lasso / Color Picker) · Partial on/off (Lasso: include partially-enclosed strokes) · Lock toggle (include/ignore locked strokes) · Layers toggle (active layer only vs all layers). When selecting via tap+hold, the menu stays while the finger rests; a second finger toggles its buttons.

**Lasso:** drag around strokes. Lasso again over a selected area to subtract. **Item Picker:** drag a crosshair; a circle appears when a stroke is selectable; release to select one; second-finger tap to add; hover a selected stroke (+ turns to −) and tap to remove.

**Highlight Selection (default on):** selected strokes are shown normally, others grayed; the selection comes to front. Toggle in Settings → Interaction → Tap+Hold. Works with Nudge to show before/after.

**Selection Popup (above selection):** Copy to clipboard (paperclip; pastes as transparent PNG in other apps) · Duplicate · Group (select a whole group by touching one stroke; tap again to ungroup) · Lock · Delete · Flip horizontal · Flip vertical · (Text) edit.

**Adjusting toggles (bottom, once selected):** Rotate allow/disable · Scale / Stretch / Off · Filters. **Scale** scales stroke width too; **Stretch** keeps tool size. Two-finger pinch/twist transform the selection. Exact values via the status-bar angle/size fields (tap+hold to type). "Lock rotation and scale" first to avoid accidental transforms while moving.

**Control points (four corners):** drag one = scale/stretch (second finger holds aspect ratio); tap one then drag = distort; tap two then one-finger drag = skew; tap two then two-finger pinch = warp. Requires scale/stretch to be active.

**Drag & drop of selections** to other apps as PNG, enabled by a toggle.

### 8.2 Layers [DOC]

- **Free: 5 layers. Pro: unlimited.**
- **Two sorting modes** (button at top of panel):
  - **Automatic** (default): strokes go to a layer determined by the tool. A new layer is created when you switch to a new tool type. Pen, Fountain, Dynamic, Fixed Width, and Dotted share a layer; Soft and Hard Pencil share one.
  - **Manual:** everything goes to the active layer regardless of tool. Touching a layer, or reordering, auto-switches Automatic → Manual.
- **Per-layer:** thumbnail, name, eye (visibility). Tap = activate. Tap active = Layer Options (Select all, Lock, Duplicate, Delete, Merge Down, Rename, Opacity slider).
- **Double-tap a layer = Focus Mode**: that layer fully visible, others dimmed; tap another layer to move focus; double-tap the focused layer to exit. **Scrubbing across eye icons** also focuses.
- **Drag a selection onto a layer** to move strokes; **onto New Layer** to create a layer from them.
- **New Layer** inserts above the active layer.
- **Special layers:** Image layer (auto-created at bottom for imports in Automatic mode), **Grid layer** (bottom; movable; cannot be drawn on), **Measurement layer** (top).

[INF] **Automatic layering is a notable design choice:** novices get free organization by tool without ever learning layers; experts switch to Manual. Focus Mode means you can safely edit one layer among many without locking the others.

### 8.3 Navigation & artboards [DOC]

Artboard = optional fixed-size frame (presets or custom) on the infinite canvas, used for framing and export region. Tap+hold a corner to move it. Export regions: Screenshot, Entire Drawing, Artboard, PDF Bounds, Selection.

### 8.4 Precision tools (one panel, five independent checkboxes) [DOC]

**Panel row pattern:** each row = *checkbox on the left (toggle on/off) + label/value on the right (tap for settings)*. Rows: Grid · Snap · Measure · Guide · Recognition.

**Grid:** Dot, Graph paper, Lined, Isometric, Triangle, 1-/2-/3-point Perspective. Settings: preset, spacing, divisions, line weight, color (auto or custom), opacity, orientation, "Confine to artboard." Perspective grids: vanishing points are directly manipulable. **Grid edit mode** — activate the Grid layer, or tap+hold a vanishing point: drag to move; rotation handle with a **snap target line every 45°** (release while the line shows to snap, or wait for it to fade to set an in-between angle); tap vanishing points to activate them; two fingers to scale the grid; toast with "Done." Grid is a movable layer in the stack.

**Snap — while drawing:** Snap to Grid (strokes follow the nearest grid line), Align to Grid (strokes directionally aligned but not constrained to lines), Allow Traceback (retrace to redraw), Allow Turns, **Auto-complete** (small circles appear at connectable points; tap to connect), Active Layer Only (for auto-complete). Direction constraints per grid:

| Grid | Snap | Align |
|---|---|---|
| Dot / Graph / Lined | Horizontal & vertical only | H, V, and 45° |
| Isometric / Triangle | 60° diagonals only | same |
| 1-point | — | H, V, and lines toward the vanishing point |
| 2-point | — | Vertical and lines toward each of two vanishing points |
| 3-point | — | Lines toward any of the three vanishing points |

**Snap — while editing:** Snap to Grid (selection key points), Snap to Key Points (other strokes' key points), Active Layer Only. Snap points: stroke start/end, four corners, center; apexes when drawn with Snap to Grid; multi-stroke selection = four corners + center; with Shape Guides = handles + center.

**Measure:** Strokes carry measurement data; enabling Measure shows **live labels** while drawing (length; for Fill, area at its center). **Dynamic labels:** tap to attach to the stroke ("sticks" on its layer); selecting the label selects the stroke. **Static labels:** created from selections or Shape Guides (tap a guide's label without tracing); saved to the **Measurement layer**; labels export only while Measure is on and the layer is visible. Tap+hold a label to type a value — **the stroke/guide resizes to match**. Drag labels along strokes. **Drawing scale** (presets or custom ratios), **units** (Digital px/pt; Metric auto/mm/cm/m/km; Imperial auto/in/ft/yd/mi), **format & precision**, "show length on the right side" (left-handers), "show scale % in status bar." **Set scale from an imported plan:** import image → Measure on → Line Guide → align handles to a known dimension → double-tap crosshairs to bound the line → tap the 1:n scale → enter the real-world length. Tool presets keep their size but display in converted units (a 2 pt pen shows as 0.015 in).

**Shape Guides (5):** Line, Arc, Angle, Ellipse, Rectangle. A gray template shows the path; **draw anywhere** to trace it. Handles reshape; center **crosshairs** drag to move. **Double-tap the crosshairs** for each guide's special function: Line → limits the stroke to between the handles; Arc → perfect half circle; Angle → 90°; Ellipse → perfect circle; Rectangle → perfect square. Two-finger twist rotates. With Measure on, label values are editable (tap+hold).

**Shape Recognition:** see §4.6.

### 8.4.1 Design analysis [INF]

Precision tools are not modes — they are *constraints on the same drawing gesture*. You still draw with the pen; the guide or snap bends the result. This keeps the one-finger/one-stylus drawing grammar unchanged while adding CAD-like accuracy.

### 8.5 Library / Objects [DOC]

Objects are adjustable, royalty-free vector assets grouped into packs. Open via the Library icon (movable, pinnable, resizable panel: 2–6 columns; compact view on iOS). Filter, search (searches names, tags, descriptions; also Pexels stock photos if enabled), favorite packs and individual objects. **Tap or drag** an object onto the canvas; it arrives *already selected* so you can move/rotate/scale/recolor/re-brush immediately. Free plan: 5 objects per month.

**Make Your Own Objects (paid):** select strokes → drag into the pack view (or tap + with selection active); strokes auto-group. Pack settings: name, description, default properties (brush type / size / color from the active tool can override at placement). Per-object: name, tags, property overrides. "Apply Properties" bulk-applies. **Live sharing** across platforms (read-only for recipients; removed if unshared). **Pexels** integration (opt-in with ToS acceptance) for free stock images.

---

## 9. Reusable interaction patterns (named, for the build spec)

Use these names when instructing an AI to replicate behavior.

| # | Pattern | Definition | Where it appears |
|---|---|---|---|
| 1 | **Tap-depth ladder** | Tap = use, tap-again = configure, tap+hold = alternate/deep, double-tap = special/destructive-adjacent | Tools, layers, grid layer, shape guide crosshair |
| 2 | **Second-finger modifier** | A second touch during a primary touch toggles a sub-mode or constraint | Selection menu, item picker, aspect lock |
| 3 | **Slide-to-adjust shortcut** | Tap+hold+slide on a button opens its slider live and closes on lift | Size/opacity/smoothing |
| 4 | **Split control** | Left = checkbox toggle; right = open settings for the same feature | Precision rows |
| 5 | **Dual-speed value** | Drag for approximate; tap+hold the number to type exact | Presets, color values, zoom/angle, measure labels |
| 6 | **Drag-to-target assignment** | Drag a thing onto a destination to reassign/create | Selection → layer; selection → New Layer; selection → Object pack; color → palette |
| 7 | **Dock-map placement** | Pick up a panel with tap+hold; a map of valid positions appears; release on one | Tool Wheel, Precision, Layers, Library |
| 8 | **Contextual popup anchored to object** | Actions appear above the selection, not in a global toolbar | Selection Popup |
| 9 | **Context-filter bar** | A bar at screen bottom filters what a gesture acts on | Selection Menu |
| 10 | **Scrub focus** | Drag across a column of toggles to rapidly shift state | Layer eye icons → Focus Mode |
| 11 | **Draw-then-hold recognition** | Pause at stroke end to convert; keep dragging to size/rotate the result | Shape Recognition |
| 12 | **Handle-based edit mode with toast** | An on-canvas edit state with handles and a "Done" toast; exit by tapping away | Grid editing |
| 13 | **Style eyedropper** | Picker samples brush properties, not only color | Color Picker "Brush Type" |
| 14 | **Property inheritance at placement** | Inserted assets can take color/size/brush from the active tool | Object pack default properties |
| 15 | **Automatic organization with manual override** | System sorts by tool; any manual act flips to manual | Layers |
| 16 | **Non-destructive twin of a destructive tool** | Mask (editable) vs. Slice (cuts path) | Eraser family |
| 17 | **Unified selected-state on insertion** | Everything inserted (object, image, text, paste) lands selected for immediate transform | Import, Library, clipboard |
| 18 | **Three-density chrome** | Normal / compact / hidden via a swipe gesture on the chrome itself | Workspace |
| 19 | **Hardware parity layer** | Keyboard/mouse equivalents for every touch navigation gesture | Space/R/S/E |
| 20 | **Gesture remapping** | Tap-count and tap+hold actions are user-assignable from a fixed action list | Settings → Interaction |

---

## 10. Problems solved and the mechanism that solves each

| Problem | Mechanism in Concepts | Why it works on a small touch screen [INF] |
|---|---|---|
| Fingers lack mouse precision | Item Picker crosshair; selection popup when multiple candidates exist; control points; numeric entry fields | A crosshair/loupe-style picker plausibly keeps the target visible instead of hidden under the fingertip (offset geometry is not documented) |
| Too many tools for a small screen | 8 user-defined slots, each a complete preset; everything else is behind "tap active tool" | Switching costs one tap; depth is hidden until requested |
| Menus consume the canvas | Movable, scalable, collapsible, hideable chrome; wheel dockable to bars | User decides the trade-off per task |
| Changing a mind after drawing | Vector strokes: select, nudge, slice, restyle, mask, re-brush | No redraw; iteration cost near zero |
| Switching between "draw" and "edit" modes | Tap+Hold on canvas = selection from any tool; Finger Action = Select with stylus | No mode-switch UI, and the tool stays active |
| Straight lines and perfect shapes by hand | Smoothing 100%; Shape Guides; Draw & Hold recognition; snap/align | Three tiers: quick, guided, constrained |
| Real-world dimensions | Scale + units + live labels + editable labels that resize geometry | Architecture/engineering usage without a CAD app |
| Perspective drawing | Perspective grids with draggable vanishing points + Align to Grid | Strokes bend toward vanishing points without rulers |
| Organizing without layer expertise | Automatic layering by tool | Beginners get structure for free |
| Editing one layer among many | Focus Mode (double-tap / scrub) | One gesture to isolate, one to restore |
| Accidental touches while drawing with a stylus | Finger Action; palm rejection; stylus-only options; two-finger zoom/rotate toggles | Input roles are separated by device |
| Hand fatigue / repetitive reaching | Remappable tap gestures; stylus shortcut buttons / squeeze; keyboard parity | Move frequent actions to where the hand already is |
| Left-handed users | UI mirrors across the center line; label side option | Occlusion reduced for either hand |
| Getting lost on infinite space | Double-tap zoom field resets; edge pointers to off-screen strokes; Artboard | Always a one-gesture way home |
| Precision values vs. speed | Dual-speed value pattern everywhere | Same control serves sketching and engineering |
| Reuse of repeated elements | Objects; Make Your Own Objects; live sharing | Library is drag-to-canvas, arrives selected |
| Passing work to other tools | SVG/DXF/PSD/PDF export; drag-and-drop selections as PNG; clipboard | Multiple lanes, but fidelity varies (see §11) |
| Presenting | AirPlay/second display with "Mirror Everything" and "Show Touches" toggles | Sketching live in meetings |

---

## 11. Limitations, trade-offs, and documented criticisms

These matter if you copy the design: some are deliberate trade-offs, others are weaknesses to avoid.

1. **Vector–raster hybrid creates export gaps.** SVG supports only a single line weight per stroke and rough texture; textured brushes don't survive export; Hard/Soft Mask strokes appear as white lines; Illustrator export needs "Filters" off; for vector export use Fixed Width or Wire. PDF vector paths lose brush textures; PDF flattened loses vector data. [DOC]
2. **Native file formats are split by platform** (`.concept` iOS vs `.concepts` Windows/Android) and iOS files cannot be moved to the other platforms. Users cite this in reviews. [DOC]
3. **Feature parity is uneven.** Color palettes/mixer, custom brushes, brush/palette sharing, off-screen stroke pointers, recently imported, compact object view, presentation mode, handedness setting are iOS-only per the manual. Zoom/rotation snap toggles were "not available on iOS" in the manual and then added in 6.36. [DOC]
4. **Free tier gating inside core flows.** Free: 5 layers, JPG export, 5 objects per month. Essentials unlocks selection & editing tools, infinite layers, shape guides, custom brushes/grids, PNG/PSD/SVG/DXF export. [DOC; App Store listing] The most distinctive features (select/edit, shape guides) are paywalled — a conversion strategy, not a UX virtue. [INF]
5. **Per-drawing size ceiling** and **no infinite zoom.** [DOC / user review]
6. **PDF import is page-by-page** in some workflows; a reviewer requested whole-PDF import. [user review; verify against current PDF chapter]
7. **Dynamic Pen is raster over vector** — breaks the "everything scales cleanly" promise. [DOC]
8. **Gesture discoverability.** The company's own tutorial quotes users calling the gestures "beyond the understanding of mere mortals." [DOC] The language is learnable but not self-evident; there is no on-canvas hint layer in the documentation reviewed. [INF]
9. **Tap+Hold collides with other long-press uses** (select vs. move menu vs. edit value). It works because each target is distinct, but it forces timing configurability. [INF]
10. **No Apple Pencil double-tap Undo** by deliberate choice. [DOC] Squeeze/shortcut-button Undo exists as configurable. [DOC]
11. **Reported stability issues** appear in reviews (e.g., a crash toggling grid off). [user review; unverified]

---

## 12. Build spec: replicating the language in a compatible app

Everything in this section is [REC] unless marked otherwise.

### 12.1 Minimum viable data model

```jsonc
{
  "document": {
    "id": "uuid",
    "name": "string",
    "units": {"system": "digital|metric|imperial", "unit": "pt|mm|in|…", "scale": {"drawing": 1, "real": 100}},
    "artboard": {"x":0,"y":0,"w":0,"h":0} | null,
    "background": {"type":"paper|blueprint|darkprint|transparent|color","value":"…"},
    "view": {"zoom":1.0, "rotation":0, "center":{"x":0,"y":0}},
    "layers": [ /* ordered bottom→top */ ],
    "layerMode": "automatic|manual",
    "grid": {"type":"dot|graph|lined|isometric|triangle|persp1|persp2|persp3|none", "params":{}, "layerIndex":0},
    "measurementLayer": {"visible":true, "labels":[]}
  },
  "layer": {
    "id":"uuid","name":"string","kind":"drawing|image|grid|measurement",
    "visible":true,"locked":false,"opacity":1.0,"items":[]
  },
  "item": {
    "id":"uuid","type":"stroke|fill|text|image|pdfPage|group|measurementLabel",
    "groupId":"uuid|null","locked":false,"layerId":"uuid",
    "transform":{"a":1,"b":0,"c":0,"d":1,"tx":0,"ty":0},
    "controlPointWarp": null | {"corners":[[x,y],[x,y],[x,y],[x,y]]},
    "style":{"brushId":"string","color":"rgba","size":0,"opacity":1,"smoothing":0,"widthMode":"scale|stretch"},
    "path":{"points":[{"x":0,"y":0,"pressure":0.5,"tilt":0,"velocity":0,"t":0}]}
  },
  "toolSlot": {"brushId":"string","size":0,"opacity":1,"smoothing":0,"color":"rgba","presets":{"size":[0,0,0,0],"opacity":[0,0,0,0]}}
}
```

Key requirements derived from documented behavior:
- Store **raw input samples** (position, pressure, tilt, velocity) with the stroke so brush changes, smoothing changes, and Nudge/Slice remain possible after drawing. [INF]
- A stroke's **brush is a property**, so selecting a stroke and tapping a new tool re-styles it. [DOC]
- **Smoothing is non-destructive and re-appliable** to an existing stroke (select + slider). [DOC]
- **Width mode** per transform: `scale` (width scales with geometry) vs. `stretch` (width constant). [DOC]
- Every item has computable **length/width/height/rotation/area** for the Measure system. [DOC]
- A **dynamic measurement label** is a child of its stroke; a **static label** is independent on the Measurement layer. [DOC]
- **Grid** and **Measurement** are *layers* in the stack (reorderable; grid not drawable). [DOC]

### 12.2 Gesture arbitration — recommended state machine

```
Touch-down event arrives. Classify by pointer type:

STYLUS (pen):
  → if FingerAction == draw-or-configured: route to active tool
  → pen buttons / squeeze / double-tap → mapped action (undo, select, colorwheel…)
  → pen hover (if supported) → show brush stamp preview

FINGER:
  count = number of simultaneous fingers within ~50–100 ms
  if stylus is active/recent AND FingerAction != "use active tool":
      route finger to FingerAction (pan | select | nudge | slice | configured tool | none)
  else if count == 1:
      if touching a UI element → UI handler
      else if touching a selection control point → transform handler
      else begin DRAW-or-HOLD race:
          if moved > slop before holdTime → DRAW with active tool
          if held still ≥ holdTime (configurable) → TAP+HOLD action (default: selection)
          if lifted quickly without moving → TAP (deselect / place text / commit)
  if count == 2:
      if selection / shape guide / grid-edit active → TRANSFORM that object (pinch=scale, twist=rotate, drag=move)
      else → NAVIGATE canvas (pan, zoom, rotate) — unless disabled in settings
      if quick lift without moving → TWO-FINGER TAP action (default undo)
  if count == 3 or 4: quick lift → mapped tap action; otherwise ignore

SECOND TOUCH while a primary touch is active:
  → treated as MODIFIER (toggle selection sub-mode, add item to selection, lock aspect ratio)
  → NOT as a new primary gesture

Rules:
  - Cancel in-progress stroke when a second finger lands within ~100 ms (so a pinch doesn't draw a dot).
  - Tap+hold must be cancelled by movement beyond a small slop radius.
  - Make hold time and the mapped action user-configurable.
  - Provide palm rejection when a stylus is detected.
```

The exact timing thresholds used by Concepts are not documented. Pick defaults (e.g., hold ≈ 0.5 s, as shown for Draw & Hold) and expose them in settings. [INF]

### 12.3 Settings schema to mirror (Interaction tab)

```
touchInput:
  fingerAction: doNothing | useActiveTool | panCanvas | select | nudge | slice | configuredTool
  oneFingerPan: bool
  twoFingerRotate: bool
  twoFingerZoom: bool
  zoomSnap: bool
  rotationSnap: bool
  twoFingerTap:  action   // default undo
  threeFingerTap: action  // default redo
  fourFingerTap: action   // default toggleInterface
  // action ∈ {none, undo, redo, selectLast, showLayers, showColorWheel, toolSetup, showObjects,
  //           toggleShapeGuide, toggleInterface, toggleCanvasRotation, toggleCanvasZoom, selectAll}
tapAndHold:
  action: selection | colorPicker | …
  activationTime: seconds
  highlightSelection: bool
  dragDropActiveSelections: bool
drawAndHold:
  shapeRecognition: bool
  activationTime: seconds
stylus:
  pressure: bool, pressureResponseRange: [min,max]
  tilt: bool
  tapAndHold: bool
  barrelRoll: bool, hoverPreview: bool
  buttonAction1, buttonAction2, squeezeAction: action
  eraserAction: slice | mask | …
handedness: left | right
keyboard: map of shortcut → action (editable)
workspace:
  toolSetup: wheel | bar
  background, gridType, artboard, drawingScale, units, format, precision
```

### 12.4 Build order recommendation

1. **Canvas + transform stack** (pan/zoom/rotate with a single 2D affine view matrix; snap-points at common zooms/angles).
2. **Stroke capture** (store raw samples; smoothing as a re-appliable filter; velocity/pressure/tilt-driven width).
3. **Tool Wheel** with 8 slots, three property rings with 4 presets each, color center, undo/redo. Add the dual-speed value editor and slide-to-adjust shortcut.
4. **Selection:** Tap+Hold activation, Lasso, Item Picker with a crosshair, Selection Menu filters, Selection Popup, scale/rotate/control points.
5. **Undo/redo** as a command stack covering transforms, restyles, and layer moves, not only strokes.
6. **Layers** with automatic/manual modes, Focus Mode, scrub.
7. **Nudge, Slice, Masks** — Nudge needs editable control points on the path; Slice needs path cutting; Mask needs a render-time stencil.
8. **Precision panel:** grids → snap → shape guides → shape recognition → measure/scale.
9. **Docking/layout manager** with the three density modes, left-handed mirroring.
10. **Library/Objects:** group + insert-as-selected + property inheritance.
11. **Import/export:** PNG/JPG, PDF, SVG (Fixed Width/Wire-style strokes only), DXF, PSD layers.

### 12.5 Compatibility strategy [REC]

- **You cannot read or write native `.concept` / `.concepts` files** unless TopHatch publishes a spec; none was found. Design your own format; target **SVG, DXF (R14), PDF, PSD, PNG** as the interchange lane with Concepts.
- When *importing Concepts SVG*, expect: one weight per stroke, textured strokes flattened or missing, mask strokes as white lines, dotted/dynamic pen raster artifacts. Provide an import option to ignore white-stroked mask lines.
- When *exporting for Concepts users*, emit clean vector strokes (constant width) and, if possible, PDF with vector paths and embedded page bounds so Concepts' **PDF Bounds** export region and PDF import behave sensibly.
- **Share the vocabulary, not the assets.** COPIC is a Too Corporation trademark; brush packs and object libraries are TopHatch/third-party products. Replicate the *interaction patterns* in §9, not the brand names or art.

### 12.6 Phone-first adaptations [REC/INF]

Concepts documents no iPhone-specific layout beyond compact/hidden modes and a smaller scalable wheel. For a phone-first compatible app:

- Default to **compact mode** with the tool wheel docked to the **dominant-hand edge**; pinch-to-scale the wheel.
- Treat **two-finger tap = undo** and **long-press = select** as non-negotiable defaults; they free the toolbar.
- Collapse Precision/Layers/Library into **single-icon sheets** that appear above the thumb zone and move by long-press.
- Keep the **second-finger modifier** pattern: with one hand holding the device and one drawing, the thumb of the holding hand is the natural modifier.
- Replace the status bar numeric fields with a **single expandable pill** (zoom · angle · selection size), using tap = popup, long-press = edit, double-tap = reset.
- Enlarge hit targets of control points; keep the crosshair-style picker for precise selection and consider offsetting it from the fingertip.

---

## 13. Glossary (use these exact terms)

| Term | Meaning |
|---|---|
| **Gallery** | File browser of drawings and folders |
| **Canvas / Infinite Canvas** | Unbounded drawing area (zoom is still limited) |
| **Artboard** | Optional fixed-size frame; defines export region and scale reference |
| **Status Bar** | Top bar: gallery, name, menu toggles, zoom/angle/selection fields, store, import, export, settings, help |
| **Tool Wheel / Tool Bar** | Circular (or bar) control: 8 tool slots + size/opacity/smoothing + color + undo/redo |
| **Slot** | One of the 8 tool positions, each storing a complete brush preset |
| **Preset** | One of 4 saved values on a property slider |
| **Smoothing** | Line-straightening amount, 0–100%; 100% = straight line |
| **Brushes menu** | Tool picker opened by tapping the active tool |
| **Selection Menu** | Bottom filter bar during selection |
| **Selection Popup** | Floating action bar above a selection |
| **Item Picker** | Crosshair single-stroke selector |
| **Lasso** | Drag-to-select region |
| **Control Points** | Four corner handles on a selection for scale/distort/skew/warp |
| **Nudge** | Push/pull stroke editing tool |
| **Slice** | Destructive vector eraser |
| **Hard Mask / Soft Mask** | Non-destructive erasers, crisp/soft edge |
| **Quick Clear** | Menu from double-tapping the mask tool |
| **Focus Mode** | Dim all layers except one |
| **Automatic / Manual layering** | Tool-based vs. user-controlled layer assignment |
| **Precision menu** | Panel with Grid, Snap, Measure, Guide, Recognition |
| **Snap vs. Align** | Constrain strokes to grid lines vs. orient them parallel to grid directions |
| **Auto-complete** | Connect stroke endpoints to nearby points |
| **Shape Guide** | Template (line, arc, angle, ellipse, rectangle) traced by drawing |
| **Draw & Hold** | Hold at stroke end to recognize and clean a shape |
| **Measure / Dynamic / Static label** | Live length/area label; stuck to a stroke; free-standing |
| **Drawing Scale** | Ratio between drawing units and real-world units |
| **Object / Library / Pack / MYOO** | Reusable vector asset / its browser / a collection / Make Your Own Objects |
| **Brush Market / Object Market** | Stores for packs |
| **Finger Action** | What a finger does when a stylus is in use |
| **Tap+Hold** | Press-and-hold; configurable action (default select) |
| **Compact / Hidden mode** | Reduced/removed chrome |
| **Pro / Essentials / Everything** | Paid tiers: one-time Essentials; subscription "Everything" |

---

## 14. What this research did not verify

- Exact **tap+hold activation time** defaults and gesture slop radii (settings exist; values not documented).
- The **PDF annotation chapter** (multi-page import behavior, PDF Selection Menu) — only the Import/Export summaries were reviewed.
- **Accounts, Concepts for Business, Pro Shop** details beyond App Store pricing.
- **iPhone-specific layout** — no dedicated documentation found.
- **Android/Windows deviations** beyond what the manual notes (e.g., tool wheel docking to the canvas center layout manager, Low-Latency Mode toggle, Windows Ink, title bar).
- **Internal rendering and data structures** — §12.1 is an inferred minimal model, not Concepts' actual schema.
- Pricing observed on the App Store listing (Everything $4.99/mo or $29.99/yr; Essentials $39.99; Essentials iPhone-only $4.99; MYOO $3.99; PDF Essentials $4.99) may change.

---

## 15. Sources

All accessed October 5, 2026.

- Concepts Manual (official): Getting Started, Gallery, Your Workspace, Infinite Canvas, Brushes and Tools, Colors, Selection, Layers, Precision Tools, Library, Import, Export, Settings — `https://concepts.app/en/manual` and sub-pages.
- Concepts Help Center, *Gesture Quick Reference* — `https://tophatch.helpshift.com/hc/en/3-concepts/faq/23-gesture-quick-reference/`
- Concepts tutorial, *The Top Gestures of Concepts Pros* — `https://concepts.app/en/tutorials/the-top-gestures-of-concepts-pros/`
- Concepts tutorial, *3 Ways to Draw a Straight Line* — `https://concepts.app/en/tutorials/3-ways-draw-straight-line/`
- Concepts Quick Start Guide 5.0 (PDF) and Android manual 1.0 (PDF) — `cdn.tophatch.com/downloads/`
- Concepts media page — `https://concepts.tophatch.com/en/media`
- Concepts on the App Store (listing, version history, developer reply, pricing, user reviews) — `https://apps.apple.com/us/app/concepts/id560586497`
