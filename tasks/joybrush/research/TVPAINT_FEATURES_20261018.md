# TVPaint Animation: features worth weighing for Joy Brush

Researched 2026-10-18. Research only: no code changes, no phone use, no commits.
Scope: TVPaint Animation 12 (current, including the 12.1.0 changelog) and 11 where the manual cites it.

## How to read this file

- Each feature gives: what it does; why animators value it; Fit for Joy Brush (Easy / Medium / Big) with how it maps to our boards, frames and layers, or where it conflicts.
- Easy = a display rule or small UI on data we already store. Medium = new per-frame data or a new panel. Big = new structure or a heavy engine.
- "Why valued" uses only what a source says. Otherwise it says unclear.
- Edition labels come from tvpaint.com/en/products (Standard / Professional). "Pro" = Professional only.
- Our model is taken from the brief: boards; an Animation board with frames, current frame, fps and per-frame holds; layers that animate inside a board or stay still; frames that are duplicated, linked, blank or held; onion skin; playback; GIF, PNG-sequence and sprite-sheet export; lines as editable vectors or pixels.
- Fit ratings follow the phone-first rules in project memory: one undo per batch action, the old behaviour stays selectable, everything reachable one-handed.
- Source quality: the official TVPaint manual (doc.tvpaint.com), its 12.1.0 changelog and product page are primary. The Cartoon Brew article is sponsored (labelled as such in the article). Items marked third-party come from blogs, forums or tutorial sites.
- Links marked "(search summary)" were seen in search results and not opened in full. All other links were opened.
- Several manual pages returned partial text. Where a detail was not in the text read, it is marked unclear.

## Open items (not confirmed)

- Colour labels on instances or exposure cells: no TVPaint page found. Only folder colour groups (12.1.0).
- Vector layers: no TVPaint manual page found. The TVPaint 11 brochure calls the software bitmap-based.
- Pen pressure curves: not documented in the pages read.
- Camera interpolation and easing: not described in the camera overview.
- Anim brush cycle trigger (stroke length, time or pressure): not stated.
- FX stack ordering, and whether effects are baked or non-destructive: not stated.
- Light table frame limit: the Cartoon Brew article says ten; the manual states no maximum.
- Light table pivot point: the 12.1.0 changelog says it can be moved; how is not in the page read.
- Papers: whether a paper applies per tool or globally is not stated.
- Animator complaints about TVPaint, or about leaving it: not found.
- The instances intro page is an overview only; instance detail comes from the applications and splitting pages.

## 1. Timeline, layers and exposure

### 1.1 Instances: head image plus exposure cells, Auto Create and Auto Break - Medium
- What: An instance is a run of exposure cells that share one head image. Cells mirror the head by default. With Auto Break Instance on (set per layer), editing an exposure splits the run into two instances. Auto Create lets you draw on an empty frame to make a new instance; with it off, drawing on a frame with no instance is blocked. Both are on by default (search summary).
- Why valued: the manual says instance management avoids copying and deleting frames by hand. Beyond that: unclear.
- Fit for Joy Brush: maps to a run of linked frames whose shared drawing is the head. Auto Break means drawing on a linked frame forks it. Conflicts with our current links, which share one drawing so an edit reaches every link; TVPaint's default is the reverse. Ship as a per-board setting.
- Source: https://doc.tvpaint.com/docs/animation-first-steps/instances/introduction ; https://doc.tvpaint.com/docs/animation-first-steps/instances/instances-application ; https://doc.tvpaint.com/docs/animation-first-steps/instances/splitting-instances ; https://doc.tvpaint.com/docs/animation-first-steps/deletion-addition/add-instances (search summary)

### 1.2 Pre- and post-behaviour per layer (None, Loop, Ping-Pong, Hold) - Easy
- What: Each layer has its own Pre (before its first image) and Post (after its last image) setting, set from small icons at the start and end of its instances. None stops the layer. Loop repeats 1-2-3-4-5-1-2-3-4-5. Ping-Pong runs back and forth. Hold keeps the last image on screen. Layer Folders can set this for all their layers (12.1.0), overriding layer settings.
- Why valued: the manual calls Hold "especially useful for background layers" and says Loop and Ping-Pong run cycles. Beyond that: unclear.
- Fit for Joy Brush: a display rule for each layer's "after last frame". Hold equals our "stays still" rule. Loop and Ping-Pong repeat at playback with no new frames. The folder setting maps to a board-level default.
- Source: https://doc.tvpaint.com/docs/animation-additional-functions/timeline-options/pre-post-behavior ; https://doc.tvpaint.com/docs/changelogs/12-1-0

### 1.3 Repeat Images - Medium
- What: Right-click an exposure cell (not a head cell) and choose Repeat Images. The exposure then reuses the previous N images from earlier instances, in Loop, Ping-Pong or Random order. A reset option clears it.
- Why valued: the manual's example is a line boil: five-frame Loop of previous exposures gives "a convincing boil" without drawing many frames. It also suits a character that stops moving while its lines still shake.
- Fit for Joy Brush: a hold that cycles earlier drawings from the same layer, with no new drawings. Medium because it changes what a hold means at playback and at export. Caveat: a TVPaint forum report (11.0.8) says looped Repeat Images frames became holds after a JSON export. Test our GIF and sprite export before shipping.
- Source: https://doc.tvpaint.com/docs/animation-advanced-functions/repeat-images-function ; https://www.tvpaint.com/forum/viewtopic.php?p=114320 (third-party forum)

### 1.4 Layer types and layer folders - Easy (folders); other types are in their own items
- What: Animation layers hold the drawing; a new project starts with one. CTG (colour-fill) layers are Pro (see 7.1). Camera layer is Pro (see 4.1). Sound tracks (see 8.2). Puppet (bitmap rigging) layers are Pro (see 8.4). The Layer Stack reorders, groups into folders, merges, splits, renames. Layer presets exist for Animation and CTG types. No vector layer type was found.
- Why valued: unclear.
- Fit for Joy Brush: folders map to grouping layers inside a board. The other types are covered in their own items.
- Source: https://doc.tvpaint.com/docs/animation-first-steps/animation-layers/introduction ; https://doc.tvpaint.com/docs/interface/layer-stack-overview ; https://doc.tvpaint.com/docs/animation-additional-functions/layer-preset-manager/anim-layer-preset (search summary) ; https://www.syntex.cz/media/document/122/100821_11_brochure_EN.pdf (TVPaint 11 brochure, "bitmap-based")

### 1.5 Instance names - Easy
- What: Right-click an instance, then Rename > Current instance. Names matter in two places: CTG squiggles can be copied to every instance with the same name, and puppet layers build their Library tree from layer and instance names.
- Why valued: unclear.
- Fit for Joy Brush: a name on a linked run, used to spread fills (see 7.1) and to name exports or sprite cells.
- Source: https://doc.tvpaint.com/docs/animation-puppet-layer/creating-puppet-layer-folder ; https://doc.tvpaint.com/docs/colorize-texturize/colorize-with-ctg-layers/application-methods

### 1.6 Marks, loop range and bookmarks - Easy
- What: Mark In and Mark Out set a range shown as overlays on the Timesheet (light green before In, light red after Out). A "Fit to Mark In/Out" zoom exists. 12.1.0 added Shift-click on the top of the Timeline to select a frame range that loops playback and instance navigation, and clear-all or clear-selected for bookmarks.
- Why valued: unclear.
- Fit for Joy Brush: in and out points on the Animation board for loop playback and export ranges.
- Source: https://doc.tvpaint.com/docs/timesheet/timesheet-overview ; https://doc.tvpaint.com/docs/changelogs/12-1-0 ; https://doc.tvpaint.com/docs/interface/project-window-canvas-overview

### 1.7 Colour labels and colour groups - Easy (scope unclear)
- What: No colour label on instances or cells appears in the pages read. Collapsed layer folders take their colour group's colour (12.1.0). A third-party AYON guide describes colour groups that gather timeline layers into one render layer.
- Why valued: unclear.
- Fit for Joy Brush: put a colour tag on boards or layer folders first. Do not copy the render-layer idea without a TVPaint source.
- Source: https://doc.tvpaint.com/docs/changelogs/12-1-0 ; https://help.ayon.app/help/articles/8337707-working-with-tvpaint-in-ayon (third-party)

## 2. Timesheet and frame roles

### 2.1 Timesheet panel (exposure sheet) - Medium
- What: Windows > Animation > Timesheet (TVPaint 12). Japanese layout: image number, Action, Audio, Dialogue/Notes, Cell, Camera. Occidental layout: a Layers column replaces Action and Cell. Page View (72 images per page) or Scroll View. Divider lines every 2, 3, 6 (default), 12 or 24 frames. Edit Displayed Info toggles playhead, Mark In/Out, audio, dialogue, camera, animation, CTG, puppet and inactive layers. A memo panel records work title, episode, cut, cut length and animator name. 12.1.0 adds PDF and XDTS export and XDTS import.
- Why valued: the manual says the panel "helps you plan a scene and track progress." Beyond that: unclear.
- Fit for Joy Brush: a sheet view of the Animation board, frames as rows, holds as lines, dividers every N frames, roles in the cells. Start with one layer and a scroll view; a full multi-layer grid is heavy on a Note 9 screen.
- Source: https://doc.tvpaint.com/docs/timesheet/timesheet-overview ; https://doc.tvpaint.com/docs/changelogs/12-1-0

### 2.2 Frame roles (Keyframe, Breakdown, Inbetween, Special inbetween) - Easy
- What: Status tags on frames. Keyframe: a circle showing the frame's name; it must be named, and naming a new keyframe with an existing name copies that keyframe's content. Breakdown: triangle. Inbetween: small black dot, To Do or Done. Special inbetween: larger see-through circle (Japanese practice), To Do or Done. Set from the Timeline, a right-click menu or the Timesheet Role menu; several frames at once except Keyframes. Symbols can be hidden or faded from the Layer Stack. In the Japanese layout only Done inbetweens count toward the CELL column.
- Why valued: roles let you track which frames are finished during in-betweening (manual). Beyond that: unclear.
- Fit for Joy Brush: a per-frame status field, not drawing data, drawn as a dot or triangle on the frame strip. Do not copy the name-copies-content rule; it is a trap.
- Source: https://doc.tvpaint.com/docs/timesheet/timesheet-roles ; https://doc.tvpaint.com/docs/changelogs/12-1-0

## 3. Light table and registration

### 3.1 Light table (onion skin) with per-side colour and per-frame choice - Easy (we have onion skin; extend it)
- What: Turned on per layer (dot under the light-bulb icon in the Layer Stack). Panel from Windows > Animation > Light Table. "0" is the current frame; clicking another frame number toggles the light table for that frame. Previous and next each have a colour option: Image Colour (original), Gradient, or Tint (one shade, recommended for filled shapes). Defaults: previous green, next orange. Opacity sliders per side (closer frames more opaque by default); Link Sliders moves both. Instance menu: Activate All, Inactivate All, and a mode (Instance Mode uses heads only; Frame Mode uses all exposure cells; Bookmark, Rig Key and Image Mark modes also exist). Blend to Front draws light-table images over the current image. Light tables can be saved and loaded. Bridge Between Clips applies it across clips in the storyboard timeline.
- Why valued: Cartoon Brew (sponsored) calls it a "really well thought out feature" and says up to ten drawings can be shown, with opacity and line colour set and arrow keys to flip between them. The manual calls the light table one of the "classic animator tools."
- Fit for Joy Brush: maps directly onto our onion skin. New to us: per-side colour, per-frame on and off, Instance versus Frame mode, saved presets.
- Source: https://doc.tvpaint.com/docs/animation-advanced-functions/light-table/light-table-overview ; https://doc.tvpaint.com/docs/animation-advanced-functions/light-table/light-table-out-of-pegs (manual wording) ; https://www.cartoonbrew.com/sponsored-by-the-art-of-aaron-blaise/aaron-blaise-offers-tips-getting-started-2d-animation-software-153102.html (sponsored)

### 3.2 Out of Pegs (move ghost frames without changing the animation) - Medium
- What: A light-table option that moves the images before and after the current frame (X, Y, angle, scale) so in-betweeners can line them up. Each frame has a grey square to toggle it; when active it shows an "O" and a green overlay. Changing the offsets does not modify the animation, including during preview. The product page lists a "Pegs Stabilizer" under the light table.
- Why valued: the manual says it makes in-betweening easier and leaves the animation untouched.
- Fit for Joy Brush: a per-frame display offset used only by onion skin, never written into the drawing. Needs a transform per ghost frame and touch handles.
- Source: https://doc.tvpaint.com/docs/animation-advanced-functions/light-table/light-table-out-of-pegs ; https://tvpaint.com/en/products

### 3.3 Peg-hole registration (scanned paper sheets) - Medium (only if we import paper scans)
- What: Window > Animation > Peg-holes registration. Place two red rectangles over the original peg holes, not too close to the pegs (a tolerance is needed). TVPaint then realigns the animation automatically.
- Why valued: fixes sheets that drift when fed through a scanner. Beyond that: unclear.
- Fit for Joy Brush: a two-point alignment step on import. Worth building only if users bring in scans or photos of paper frames.
- Source: https://doc.tvpaint.com/docs/import-export/handmade-drawings/peg-holes-registration

## 4. Camera

### 4.1 Camera layer (keyed pan, zoom, rotate, camera size; Movement Type keys) - Big (Pro)
- What: TVPaint 12 Professional. New projects start with a camera layer (deletable). An on-canvas HUD sets position, rotation, size and zoom. Placing the camera at a later frame adds a key; a line on the canvas previews the path; any number of keys. Movement Type keys name a camera move and sit on the layer; 12.1.0 added moves matching common Japanese camera moves, shown in the Timesheet camera column. A Camera View button shows the camera during playback. 12.1.0 adds next/previous and first/last camera key shortcuts.
- Why valued: Movement Type follows Japanese industry-standard camera moves (manual). Beyond that: unclear.
- Fit for Joy Brush: a per-board transform keyed over frames, applied in preview and export. Conflicts with our model: the project is one canvas of regions (boards), and a whole-canvas camera changes how boards and exports work. Start per board.
- Source: https://doc.tvpaint.com/docs/camera/overview ; https://doc.tvpaint.com/docs/changelogs/12-1-0 ; https://doc.tvpaint.com/docs/timesheet/timesheet-overview

## 5. Brushes and paper

### 5.1 Anim brushes (multi-image brushes that cycle while drawing) - Medium
- What: A custom brush made from several images that the brush engine loops through while you draw. Made by selecting frames on the Timeline, cropping with the cutting tool, and confirming. Settings include Revert (reverse order) and Anim (looped, random and others). Stamp In Place stamps the current image where the brush was cut. The manual's example is storing mouth shapes and stamping them onto a character.
- Why valued: the manual cites building more complex brushes and animating faster. Beyond that: unclear.
- Fit for Joy Brush: a brush whose tip is a short run of frames from the current board, with Stamp In Place as "place this pose." Needs a clear rule for what advances the cycle, which TVPaint does not state. Keep the old brush selectable.
- Source: https://doc.tvpaint.com/docs/drawing-basic-tools/tool-panel/brush-engine/anim-brushes

### 5.2 Papers (texture for brushes) - Medium (project memory shows a paper engine already)
- What: Papers add texture to brushes. Adjust hardness (opacity), size and angle; flip, invert, reset. Connections can link these to pen behaviour. Dual Paper blends two papers with a blend mode (designed for same-size papers). New papers come from the current display, an Image Library image or a file. Custom panels do not save papers.
- Why valued: the manual says papers mimic "drawings made on specific surfaces." A French tutorial warns that a Loop post-behaviour makes a texture repeat, which can look like random movement (third-party).
- Fit for Joy Brush: our paper engine (project memory) is the base. Suggested rule, not from TVPaint: the paper stays fixed to the canvas, not the frame, to avoid texture crawl.
- Source: https://doc.tvpaint.com/docs/drawing-basic-tools/main-panel-tools/papers ; https://www.elephorm.com/tvpaint-fondamentaux/texturiser-son-animation-ancienne-methode (third-party, French)

### 5.3 Custom panels and actions - Easy
- What: Custom Panels menu > New Custom Panel. A panel can hold brushes, colours, palettes, actions, rows and separators. Saving a panel keeps brushes and their settings only. An action bundles several steps into one icon, run in numbered order. A search summary notes that holding a panel and pressing G turns it into a custom brush.
- Why valued: unclear.
- Fit for Joy Brush: saved brush sets, and one-tap batch actions. A batch action should collapse to one undo step (owner's rule).
- Source: https://doc.tvpaint.com/docs/custom-panels/creating-custom-panels ; https://doc.tvpaint.com/docs/custom-panels/adding-elements ; https://doc.tvpaint.com/docs/custom-panels/adding-actions

### 5.4 Pen Connections (settings linked to pen input) - Easy
- What: A Connection links a brush setting to how the pen is used. "Power" needs a Connection other than Constant to produce a gradient. "Projection" uses pen inclination for splashes (may not work with every tablet). Paper hardness, size and angle can be connected too. Which input drives Power, and the pressure curve: unclear.
- Why valued: Cartoon Brew (sponsored) says the built-in pencils have pressure "dialed in just right." Detail unclear.
- Fit for Joy Brush: we already read stylus input (project memory); expose a per-brush "link to pressure or tilt" control in the advanced brush settings.
- Source: https://doc.tvpaint.com/docs/drawing-basic-tools/tool-panel/brush-engine/brush-settings-cheat-sheet ; https://doc.tvpaint.com/docs/drawing-basic-tools/main-panel-tools/papers ; https://www.cartoonbrew.com/sponsored-by-the-art-of-aaron-blaise/aaron-blaise-offers-tips-getting-started-2d-animation-software-153102.html (sponsored)

## 6. Effects and motion

### 6.1 FX stack - Big
- What: Effects menu with nine categories: Blur, Colors, Distortion, Keying, Motion, Paint, Rendering, Stylize, Transition. Multiple FX adds to the current effect; Simple FX replaces it. FX Preview applies to all layers and FX keys are frame-based (12.1.0). The FX stack is not usable on puppet layers. Whether effects are baked or non-destructive: unclear.
- Why valued: the manual says the effects are "specially designed for working on animated footage." Beyond that: unclear.
- Fit for Joy Brush: a short list (blur, colour, distort) with a per-layer stack and keyed values would be Medium. The full stack is Big. Conflicts with boards unless effects are scoped per board.
- Source: https://doc.tvpaint.com/docs/fx-stack/introduction ; https://doc.tvpaint.com/docs/fx-stack/fx-stack-advanced-features/single-multiple-effects (search summary) ; https://doc.tvpaint.com/docs/changelogs/12-1-0

### 6.2 FX keyframes - Medium
- What: Two keys (first and last frame) and TVPaint fills the frames between; a single key holds its value after that frame. Add a key with the Key button, or by changing a value on the last frame. Each parameter keys separately (for example a blur's centre, power and radius). For Transition Position, 0% leaves the image and 100% clears it.
- Why valued: unclear.
- Fit for Joy Brush: per-frame values for a board's effect parameter, with start and end keys. Tied to 6.1.
- Source: https://doc.tvpaint.com/docs/fx-stack/fx-stack-keyframes/introduction ; https://doc.tvpaint.com/docs/fx-stack/fx-stack-keyframes/managing-effects-several-parameters (search summary)

### 6.3 Keyframer (motion path for a brush, layer, image or paper) - Medium (2D path and easing); Big (3D, pivots, trails)
- What: Effect > Motion. Choose the object (brush, layer, spare image or paper). Position keys at the start and end (the HUD can place it off-screen). X, Y and Z (Z is depth). Interpolation: Linear, Spline or Smooth. Paths come from the HUD, a Path Recorder or a Pixel Tracker. Pitch, Heading and Bank rotation with pivots. Motion blur with step count and trail length. Opacity over time. Animated sources can Loop or Ping-Pong.
- Why valued: unclear; the page explains setup, not reasons.
- Fit for Joy Brush: a 2D position path with easing for one layer is Medium. Keep it an optional tool; the frame-by-frame core stays the default.
- Source: https://doc.tvpaint.com/docs/advanced-tools/keyframer

## 7. Cleanup and colour

### 7.1 CTG layer (Colour Through Gaps; "Colors and Textures Generator") - Medium (stroke-fill); Big (full CTG)
- What: Pro-only layer type. You draw squiggles (checkerboard strokes that do not render) with the Speedfill tool; each squiggle fills the closed area it covers and spreads to the outlines. One stroke can colour two areas that do not touch without filling the gap between them. Where squiggles overlap, the larger one wins. Undo (U) removes a squiggle; the eraser removes squiggles, not colour (search summary). Drawing tools are off on CTG layers, and a layer can convert to an Anim layer and back. Colour is not antialiased; 12.1.0 adds "Extract with AntiAliasing." Exclusion squiggles stop spread. "Apply Current Squiggles on Empty Instances" copies to every empty instance; "Apply current squiggles on Instances with same name" copies to same-named instances. Each CTG layer has its own palette; palettes can be extracted from an image, up to 256 colours (search summary).
- Why valued: the manual says the layers "fill closed areas in an instant" with no parameters to set. Beyond that: unclear.
- Fit for Joy Brush: a stroke-to-fill on a colour layer that spreads to matching frames. This is the fastest cleanup idea in the research. Full CTG (palette generator, conversion, colour-through-gap rules) is Big. Keep the current fill selectable.
- Source: https://doc.tvpaint.com/docs/colorize-texturize/colorize-with-ctg-layers/quick-overview ; https://doc.tvpaint.com/docs/colorize-texturize/colorize-with-ctg-layers/application-methods ; https://doc.tvpaint.com/docs/colorize-texturize/colorize-with-ctg-layers/settings-and-limitations (search summary) ; https://doc.tvpaint.com/docs/changelogs/12-1-0 ; https://www.elephorm.com/tvpaint-techniques-avancees/realiser-palette-pour-calque-ctg (third-party, French)

### 7.2 Paint bucket Source and Gap Closer - Easy
- What: Source options: (Current) Layer (default), Above, Under, Current Display, and Current Display without the canvas background. With a colour layer under the line art, set Source to Above and fill on the colour layer, so the lines form the boundary and the colour stays on its own layer. Gap Closer artificially closes gaps: low values for small gaps, high values for big gaps, with lower fill quality at high values.
- Why valued: keeps line work and colour on separate layers (manual). Beyond that: unclear.
- Fit for Joy Brush: a "read boundaries from the layer above" option on our fill tool. Works with vector lines and pixel lines alike.
- Source: https://doc.tvpaint.com/docs/colorize-texturize/colorize-with-drawing-tools/paint-bucket-in-depth

### 7.3 Shift-constrained lines, Filled Line and shape tools - Easy
- What: Shift keeps a line horizontal, vertical or parallel diagonal. The Filled Line variant closes and fills an open outline when Enter is pressed. Shape tools cover freehand, line, rectangle, circle and spline. The Stroke menu has a colour-filled freehand option and a dotted option.
- Why valued: unclear.
- Fit for Joy Brush: Enter-to-close-and-fill is a one-tap cleanup and fits our one-press, one-step rule.
- Source: https://doc.tvpaint.com/docs/drawing-basic-tools/main-panel-tools/shapes ; https://doc.tvpaint.com/docs/drawing-basic-tools/main-panel-tools/main-panel-tools-overview (search summary)

### 7.4 Warp tool (grid mesh with pins) - Medium (Pro)
- What: Pro only. A grid of blue squares (sizes 4 to 100) appears over the drawing; drag the intersections. Rigidity 1 to 100%. Lock points by right-click, or Shift-drag to select several. Source: Current Image or From Library. Apply (Enter) commits; Reset discards and deletes pins. Undo works only after Apply. Ctrl-drag moves, Ctrl+Shift-drag rotates, Ctrl+right-drag resizes. A Warp grid FX also exists for warping over time (search summary).
- Why valued: "elasticity" for pixels (manual). Beyond that: unclear.
- Fit for Joy Brush: a mesh warp on a board region, with From Library as a sprite source. Touch handles need larger hit areas, and undo follows the one-step rule.
- Source: https://doc.tvpaint.com/docs/drawing-transformations/warp ; https://doc.tvpaint.com/docs/effect-categories/distortion/warp-grid (search summary)

## 8. Project, clip, storyboard, sound and library

### 8.1 Project, Clip and Storyboard structure - Big
- What: The Project window holds the canvas, a header (project name, active clip name, zoom, active layer, image number) and the Playback Bar. Each clip has layers and instances and belongs to a scene. The Storyboard view opens from the Project tab, in column or row layouts with action and dialogue notes; the Timeline view previews the storyboard as an animatic. Clips can be locked (order frozen, contents editable) and grouped. Standard edition allows one clip per project; most storyboard functions are Pro. The manual says the section is still a work in progress.
- Why valued: the manual calls the storyboard "the link between a written script and the animation" (search summary). Beyond that: unclear.
- Fit for Joy Brush: our top level is a drawing with boards. Clips would add a level above boards and change export, which conflicts with the current model. A cheaper first step: an ordered list of boards with an animatic preview.
- Source: https://doc.tvpaint.com/docs/interface/project-window-canvas-overview ; https://doc.tvpaint.com/docs/storyboarding/managing-storyboard/clips-management-views ; https://doc.tvpaint.com/docs/storyboarding/creating-storyboard/first-clip-first-drawing ; https://doc.tvpaint.com/ja/docs/storyboarding/overview

### 8.2 Sound tracks - Medium
- What: The audio area is hidden until the Sound button at the bottom of the Layer Stack. A Master track always exists; imported tracks sit under it. Each track has name, volume and offset, with a waveform beside the Timeline. Standard allows one track per project; Professional allows several. Audio in a clip stays local to that clip; a global soundtrack can span clips. The Timesheet has a waveform column.
- Why valued: unclear.
- Fit for Joy Brush: one audio track per drawing, with a waveform on the frame strip for timing and lip-sync cues. Driving animation from the waveform is not in TVPaint's docs.
- Source: https://doc.tvpaint.com/docs/working-with-sound/adding-editing-audio-tracks/overview ; https://doc.tvpaint.com/docs/timesheet/timesheet-overview

### 8.3 Image Library - Medium
- What: A project library of stored images. Used as a source for Warp (From Library), for puppet folders (built from Library folder trees), and for new papers. Create Folders in Image Library builds a folder tree from layers and instances. The Professional library was updated in 12.
- Why valued: unclear.
- Fit for Joy Brush: maps to our sprite library for reusable poses, such as mouth shapes. Check the overlap with SpriteLab before building.
- Source: https://doc.tvpaint.com/docs/drawing-transformations/warp ; https://doc.tvpaint.com/docs/animation-puppet-layer/creating-puppet-layer-folder ; https://tvpaint.com/en/products

### 8.4 Puppet (bitmap rigging) layers - Big (Pro); park for now
- What: Pro. The product page says puppet layers make it "possible to make puppets for the first time in TVPaint," with automatic in-betweens. Puppet layer folders are built from Library folder trees, and bone setup uses layer names. The page read does not explain how in-betweens are generated.
- Why valued: unclear beyond the product claim.
- Fit for Joy Brush: conflicts with a frame-by-frame core. Do not start here.
- Source: https://tvpaint.com/en/products ; https://doc.tvpaint.com/docs/animation-puppet-layer/creating-puppet-layer-folder

## 9. Keyboard and pen speed-ups

### 9.1 Shortcut remapping with chaining - Easy (low priority on phone)
- What: Preferences opens from Edit > Preferences or with Shift+D. Pick a function, pick a free key, click Assign. Ctrl-click Assign adds a second function to the same key, so one key runs a sequence (search summary). Left and right mouse buttons and Alt cannot be bound. 12.1.0 lets panning, zoom and rotation be assigned to any key.
- Why valued: unclear.
- Fit for Joy Brush: useful to plugged-in keyboard users; a phone-first app gains little from it.
- Source: https://doc.tvpaint.com/docs/preferences/overview ; https://doc.tvpaint.com/docs/preferences/general (search summary) ; https://doc.tvpaint.com/docs/changelogs/12-1-0

### 9.2 Next and previous instance navigation; clear-to-new-drawing shortcut - Easy
- What: "Exposure: Go to Next Instance" and "Go to Previous Instance" can be mapped to a key or a tablet ring. A third-party blog prefers Instance over Frame because it follows the drawn timing. Shift+K clears the frame to start a new drawing, and Ctrl+K opens the full shortcut menu (Cartoon Brew, sponsored).
- Why valued: the third-party blog and Cartoon Brew both describe these as speed-ups. Beyond that: unclear.
- Fit for Joy Brush: "next held drawing" and "previous held drawing" keys. Easy.
- Source: https://tahnee.org/wordpress/?p=2803 (third-party) ; https://www.cartoonbrew.com/sponsored-by-the-art-of-aaron-blaise/aaron-blaise-offers-tips-getting-started-2d-animation-software-153102.html (sponsored) ; https://tutorialtactic.com/blog/tvpaint-animation-shortcuts/ (third-party list, not verified against the manual)

## 10. What animators say they miss in other apps

- Not found. The sources are vendor blogs and forum posts. A lostmarble forum poster calls TVPaint raster and frame-by-frame, contrasting it with Animate's vector approach. A Bloop vendor post says Toon Boom leans on tweening. No source names something animators miss after leaving TVPaint.
- Source: https://www.lostmarble.com/forum/viewtopic.php?p=115384 (third-party forum) ; https://www.bloopanimation.com/why-independent-animators-prefer-tvpaint-5-great-tvpaint-animators/ (vendor blog)
- Next step if wanted: search animator forums and subreddits for complaints about TVPaint itself. Not researched here.

## Top 10 for a phone-first app

Ranked by value for effort, with phone use in mind. Section numbers in brackets.

1. Pre/post behaviour per layer (Hold, Loop, Ping-Pong) - Easy. Adds cycles and held backgrounds with no new drawings, and matches our "stays still" rule. [1.2]
2. Auto Break instances (drawing on a linked frame forks it) - Medium. Makes the timing unit explicit and stops silent edits to linked frames. Keep it a board setting. [1.1]
3. Light table with per-side colour, per-frame toggles and opacity - Easy. Extends the onion skin we have with controls animators already know. [3.1]
4. Marks in and out, with loop-range playback - Easy. Previewing a range is a daily need. [1.6]
5. Timesheet (exposure sheet) with dividers and frame roles - Medium. A scroll sheet suits a phone; roles show in-betweening progress. [2.1, 2.2]
6. Repeat Images (a hold cycles earlier drawings) - Medium. Gives boil and shake without drawing many frames. Test export first. [1.3]
7. Anim brushes (a multi-frame stamp such as mouth shapes) - Medium. Speeds up repeated shapes; needs a clear cycle rule. [5.1]
8. Paint bucket reads boundaries from the layer above - Easy. Keeps colour on its own layer, apart from the line art. [7.2]
9. Stroke-to-fill on a colour layer that spreads to matching frames - Medium. The fastest cleanup idea found; full CTG is Big. [7.1]
10. Out of Pegs (move ghost frames without touching the animation) - Medium. Makes in-betweening easier with no edits. [3.2]

Next tier: instance names (Easy); papers (Medium, we already have a paper engine); warp (Medium); keyframer 2D path (Medium); camera layer (Big, Pro); FX stack (Big); clip and storyboard (Big).

## Already have / partly have / missing

Measured against the model in the brief. "Have" means already in that model. Project memory mentions stylus, paper and brush engines; the table uses them only where noted.

| TVPaint feature | Status | Note |
|---|---|---|
| Layer animates inside a board or stays still | Have | |
| Frame duplicate, blank, hold | Have | |
| Linked frames | Partly | shared drawing; no head or exposure split |
| Auto Create / Auto Break | Missing | |
| Pre/post: Hold | Partly | "stays still" is the same idea |
| Pre/post: Loop, Ping-Pong | Missing | |
| Layer folders with shared pre/post | Missing | |
| Repeat Images | Missing | |
| Onion skin | Have | per-side colour and per-frame toggle: unclear from the brief |
| Out of Pegs | Missing | |
| Peg-hole registration | Missing | only with scan imports |
| Playback | Have | |
| Loop range, Mark In/Out | Missing | not in the brief |
| Export: GIF, PNG sequence, sprite sheet | Have | export range: unclear |
| Timesheet / exposure sheet | Missing | |
| Frame roles | Missing | |
| Instance names | Missing | |
| Colour labels | Unclear | TVPaint scope unclear |
| Camera layer | Missing | |
| FX stack and FX keys | Missing | |
| Keyframer | Missing | |
| Anim brushes | Missing | brush engine exists per project memory |
| Papers | Partly | paper engine exists per project memory |
| Custom panels and actions | Unclear | |
| Pen-linked settings | Unclear | stylus input exists per project memory; no mapping in the brief |
| CTG stroke fill | Missing | |
| Paint bucket source | Unclear | fill tool not described in the brief |
| Warp tool | Missing | |
| Shift-constrained lines, Filled Line | Unclear | |
| Project, clip, storyboard | Missing | boards are the nearest |
| Sound tracks | Unclear | not in the brief |
| Image library | Unclear | sprite library exists per project memory |
| Puppet rigging | Missing | Pro |
| Shortcut remapping | Unclear | phone-first |
| Editable vector lines | Have | TVPaint: no vector layer found |
