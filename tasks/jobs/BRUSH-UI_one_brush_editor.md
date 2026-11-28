# BRUSH-UI: one brush editor, a preview that shows what a brush does

**Milestone:** M1  ·  **State:** ⬜ card written 11-14 from the owner's hands-on session  ·  **Owner:** JoyBrush screens lane (editor, hover, refusals) + brush lane (stroke, oil taper, speed)  ·  **Checked:** 2026-11-14 on the Note 9, build 1d56a6cf4

## Why it matters
Owner, 11-14, after trying the brushes on the Note 9: "most of my trouble is I can't really see what these
brushes do. I can't see what the sliders are for." The brush editor must be one editor across wildly
different brushes, and seeing must replace reading. He called the Unibrush settings sheet "absolutely
terrible"; a screenshot of it is at `D:/JoyCreator-brush-samples/owner_feedback_20261114_unibrush_settings.png`
(outside the repo: it shows his drawing).

## What he reported (each is a defect or a requirement; verify in code before fixing)
1. **Oil brushes have no taper setting. They need one.**
2. **Hover preview is wrong.** The shape and direction preview does not work: on the palette knife it only
   points down. On hover he must see exactly where the mark will land and its general shape, following
   tilt before any mark is made. A setting chooses whether the outline is the largest or the smallest
   mark the brush can make; it must be one of those.
3. **The test stroke.** One standard, beautiful stroke for every brush: a horizontal swoosh that starts
   ever so light and gets harder as it goes across, tilt varying along it, a little curl at the end, then
   a sudden lift (not a gradual fade), so one stroke shows the most variety. It crosses one to three
   sample lines of set colours. Changing any slider rebuilds it live, while the slider moves.
4. **Unibrush settings sheet** (seen in the screenshot):
   - buttons are white with unreadable labels (three tabs beside "All", two of three "Shape A" chips, one
     "Builds up" chip, the top-right and bottom-right buttons);
   - it does not look like the other brushes' settings;
   - the preview only redraws when the pen lifts from the slider: too slow;
   - Softness shows nothing and seems to do nothing;
   - the tip's cross-section is buried in a huge Tip section far down; it belongs beside the pen pad that
     shows tilt, pressure and direction;
   - many boxes make no sense as labelled.
5. **Sliders should show what they do:** very responsive, and with a small picture of each end (A and B)
   so the effect is visible, not only named.
6. **Refusal:** some brushes answer that their "pattern has to be set" and will not paint. Find which
   brushes, why, and make them paint or hide them; the artist is never refused for a technical reason.
7. Also visible in the screenshot: the top bar's labels overlap each other ("Overlap", "Symmetry").

## Done when
- [ ] Every brush, every engine, opens the same editor: same layout, same controls, same pen pad, same test
      stroke; a feature a brush lacks is left out tastefully, never shown dead.
- [ ] The test stroke of item 3 exists once, is used everywhere (editor, drawer previews, contact sheets),
      and redraws while a slider is dragged on the Note 9.
- [ ] Items 1, 2, 4, 6 and 7 are fixed and seen on the Note 9; item 5 has a design he picked.
- [ ] The owner has tried it with the S Pen and said so.

## Rules that apply
- Hold a brush opens its advanced settings, with a live tilt / pressure / speed preview (owner ruling).
- ON is solid cyan with the icon cut out; greys fade, colour guides; drawers stay see-through.
- A reworked tool ships beside the old one where he may want to compare.
- The layout of the unified editor goes to him as a mix-and-match page at true size before the big rebuild;
  the plain bugs (unreadable labels, the refusal, the knife's hover direction, the overlapping top bar,
  redraw-on-lift) are fixed first, without waiting.
- The paint engine's files belong to the paint-engine session: agree any engine change (oil taper, hover
  footprint from tilt) with it first.

## K. The owner's picks from the design page (11-14 afternoon; this is the spec now)
Picked from `tasks/design/UI-Brush-editor-options.html`, told in words to the orchestrator.

1. **Layout: L1 "Stage"** at true size, with one change: **no tab buttons under the stage.** The top bar matches
   Studio's tabs drawer: the brush's title (for example "Ink pen") on the left, then a row of **round icon buttons**
   for the setting groups (colour, wet, scatter, and so on) to jump between quickly, then Done / Cancel (X) at the
   far right. Reuse the Studio tabs drawer's look and parts; do not draw new ones.
2. **Keep as they are now in the paint brushes:** the pressure and direction helper, the tilt curve and the pressure
   curve, and the ramp that shows how high your pressure goes while you draw ("I can tell exactly what my brush
   stroke is actually doing").
3. **Slider-end pictures: liked.** Each setting picks its own kind of picture:
   - **dabs** for softness, shape and size;
   - **tiny strokes** for spacing, taper, ink pickup, blending and the like;
   - **words always**, beside every picture.
4. **A more compact helper row:** the footprint and the tilt may be stacked **vertically** instead of side by side, to
   pack the row tighter.
   - Do NOT overlay Unibrush's shape editor on the live pressure/tilt readout. The readout shows what you are doing
     now; the shape editor is a circle with a direction mark and handles to grab. They do different jobs.
   - Unibrush's advanced shape editing **opens by tapping the footprint**, which expands into the shape editor.
5. **Test stroke (brush lane):** his own scribble (`joybrush/research/test_stroke_20261114/`): no other shape, and
   no need to ask him again. The colour lines it crosses are **painted first, underneath**. They are the print
   colours **cyan, magenta, yellow, black and white**, close together with a little space between each, so you can
   see what the brush does with each one.

## Progress 11-14 (JoyBrush screens lane)
- **Unreadable Unibrush chips: fixed, seen on the Note 9.** Cause: the unselected chip's faint 8% fill had its alpha reset to
  255, so it drew solid white under white words (`UnibrushSheetChip`). No other JoyBrush sheet has the pattern (all
  checked). Test `UnibrushSheetChipTest`. c8e193fa6.
- **Top bar overlap: fixed, not yet seen on a device.** The symmetry pill (x2 / Mirror / Overlap / Symmetry) is placed beside
  the line's middle and was only kept on screen; it now stays below the top bar while the bar shows.
- **Preview redraw on lift: fixed in code.** Each slider move cancelled the picture being drawn, so none finished mid-drag;
  now one stays in flight and the newest settings follow at once. On the Note 9 the stroke itself is hard to see: it is
  drawn in black on the dark sheet. A paper strip behind it is on the design page.
- **Softness doing nothing: fixed, seen on the Note 9.** The Hard tip profile ignores hardness in the shader; Hard now reads
  0% soft and moving Softness switches the tip to the Smooth profile. Mid-drag on the Note 9 the cross-section changed live.
- **Tip cross-section beside the pen pad: built, seen on the Note 9** (`TipSectionView`, same rules as `jb_profile.glsl`).
- **Design page: sent to the owner** (`tasks/design/UI-Brush-editor-options.html`): four stroke shapes, four colour sets
  built on colour theory (as bands the stroke crosses, with a wet pickup mode), live sliders with A/B end pictures,
  cross-section, tilt footprint, three layouts at the Note 9's true size, and a line of picks to copy. Waiting for his pick.
- **"Pattern has to be set": not found yet.** The nearest text in the code is the validator's "source is image but no
  image path is set: …"; no shipped, Unibrush or imported brush in this checkout trips it on the PC. Owed: the
  every-brush sweep on the Note 9 with a screen dump of any refusal.
- **Hover outline:** waiting for the paint-engine session's footprint function (asked 11-14 with a proposed signature).
- Found for the brush lane: the drawer keeps old cached previews after the test stroke changed (the cache key lacks a
  stroke version).


## Brush lane, 11-14 evening: test stroke and oil taper landed
- **Test stroke = his exact curve** from his own vector drawing (`research/test_stroke_20261114/owner_test_stroke_exact.svg`),
  his proportions kept (`core/brush/TestStroke`, stroke version 7; lab twin `lab/media/js/tests.js`). The settings stage
  paints the colour lines first, then the swatch and lines, then his curve; drawer previews key on the stroke version.
- **Oil Taper setting** (hair brushes; default = the old look). Seen on the Note 9: taper 0 gives blunt starts and
  taper 1 gives pointed ones, on both the flat and the round.
- **Seen on the Note 9** (cloud build bef2d9889, bench `tstripe0..14,tmark0..3,tcurve`): HB pencil, watercolour Round,
  sable ink, oil flat and round with taper 0 and 1. No crash or refusal; the loop crosses where he drew it. Curve busy
  time p50: ink 12 ms, pencil 23, watercolour 37, oil 35-57 ms.
- **Owed (his 11-14 notes on the phone shots):** (a) one colour line per colour, spaced further apart, on a diagonal,
  and running past the edge where the picture is cropped (wet lines laid close together merge into one block now);
  (b) very large brushes (wide impasto, washes) are too big to read in the box: they need a size cap or a zoomed-out view.
## Section K build, step 1 (11-14 afternoon, screens lane; joy-creator caf3f90a6)
- **Built, seen on the Note 9:** the paint brushes' settings in the Stage layout. A Studio-drawer top bar: the brush's
  name, round group buttons (Shape, Ink, Pressure & tilt, Spacing & scatter, Colour, Water, Stroke ends, Paper, Feel;
  only the groups the brush has; the open one fills cyan with its name; tapping jumps the list there and scrolling
  lights the group you are in), then Done and ✕ (cancel puts every slider back as the sheet opened). Reset / Clear /
  Test moved under the stage. Settings come in their groups, each with a small heading. Every slider has **words at
  both ends** (e.g. Spacing "Smooth"/"Dotted", Point "Sharp"/"Blunt"), with each setting's picture kind already chosen
  (dab for softness, shape and size; stroke for the rest; `core/chrome/BrushKnobGroups`).
- **Kept as they were:** the pen readout, the pressure and tilt curves with the live pressure marker.
- **Added:** the tip's cross-section beside the curves for dab brushes (stamp, smudge, push).
- **Not yet:** the end *pictures* (they wait for the brush lane's short-stroke preview option), the footprint stacked
  under the tilt (waits for the engine session's footprint function), tapping the footprint to open Unibrush's shape
  editor, and the Unibrush sheet moved onto this same editor.
- **Found on the Note 9:** the stage preview of pencil brushes is blank (it was blank before this change too); told
  the brush lane. Before shots in `tasks/screens/brush-editor-20261114/`.
## Section K build, step 2 (11-14 night, screens lane)
- **Slider-end pictures, seen on the Note 9** (4cd03822d): each end shows the brush with only that setting at its end,
  drawn one at a time, redrawn when a setting settles. Softness, shape and size show **one dab** (brush lane's
  `TestStroke.Part.DAB`); everything else the test stroke's S-turns. "Thinnest" shows the stroke, not a dab: it is
  about the lightest press and a full-press dab looked the same at both ends.
- **Dabs fill the picture** (brush lane d096e4122, wired here; seen on the Note 9: Marker's Hardness ends now read as a
  fuzzy blob and a crisp square, `after_marker_dab_ends_filled.png`). A dab setting that changes the brush's size draws
  both ends at the larger end's scale (`dabScalePx`), so they still differ; no shipped paint slider changes the size
  today, so that path is unit-tested only. Pencil, watercolour and oil ends still show the whole stroke until the
  brush lane wires the part into the media preview.
- **Unibrush on the one editor, seen on the Note 9:** the same top bar (name, Shape / Pressure & tilt / Colour / Paper /
  Stroke ends / Feel, Done, ✕ puts the mode back as it opened), the Quick / Tip / All tabs replaced by one list in the
  top bar's order, words at both ends of every slider. Screens: `after_unibrush_hard_one_editor.png`,
  `after_unibrush_groups_in_order.png`, `after_marker_dab_ends.png` in `tasks/screens/brush-editor-20261114/`.
- **Footprint, seen on the Note 9 (11-14 night):** the mark a brush leaves seen from above, upright, at 35° and laid
  over at 70° (one mark and "same at any tilt" for a brush that ignores tilt). Made from the brush's own code
  (`core/chrome/Footprint`): oils and knives from `HoverFootprint`, every dab brush from the dab `BrushDabber` would
  lay, traced with `TipMath.coverage`; pencil, watercolour and Sable are a circle of their size for now. Paint brushes:
  stacked under the tilt curve. Unibrush: under the tip's side view, and **tapping it jumps to the tip's shape editor**
  (or the switch that turns four-point shapes on), which blinks. The pen's tilt lights the nearest mark. Screens:
  `after_knife3_footprint_under_tilt.png`, `after_unibrush_calligraphy_footprint.png`,
  `after_footprint_tap_opens_shape_editor.png`.
- **Still owed:** Unibrush's stage still shows the stripes with its own preview rather than the shared stage; the
  hover outline under the pen still draws its own shapes (it can now use `Footprint`), and nobody has seen it with a
  real S Pen since the atlas batch landed (agents cannot inject a hover on either device).
## Section K, 11-15 (screens lane)
- **Unibrush slider-end pictures, seen on the test tablet** (58f256930): every Unibrush slider shows the brush at
  each end, as the paint brushes do (a dab for softness, shape and size; the S-turns for the rest). Modes whose image
  tips must be prepared first skip the pictures and keep the words.
- **Still owed:** Unibrush's own stage (no Clear / Test yet), the hover outline from `Footprint`, the media brushes'
  dab ends.

## Brush lane, 11-15: watercolour paints on the settings stage
- **Watercolour lays its test curve and the owner's own strokes on the settings stage**, on a wet sheet of the stage's
  own (512 cells along its long side, `JbCanvasView.PREVIEW_SHEET_LONG_CELLS`; `WetSheet.ownLongCells` via
  `MediaCanvas.sheetLongCells`). The stage's strokes go to the media engine as the pen's would (`drawStrokes` ->
  `feedMediaSamples`; the live pen's `feedMedia` only builds the samples and calls the same function).
- **Seen on the Note 9** (cloud build cbd810e7f): Round paints whole, "sheet memory: 38 MB for 608 x 288 cells";
  graphics 88 MB before, 116 MB open, back down on close. The drawing's own sheet is unchanged (bench band0,band1:
  720 x 1376 cells, 218 MB, as the Detailed receipt). Fold 4 too (before the sheet was made small it took 257 MB).
- **Pencil and oil are not laid on the stage yet** (the stage logs it, no curve, as before). An atlas for them took
  about 600 MB of graphics on the Fold even at 6 x 4 slots; the atlas session's condition (11-15): make it small by
  construction (about 3 x 2 slots, under 100 MB by the atlas's own count) or build it only inside the media budget,
  and let it go on pause and on graphics loss as well as on close. Next brush-lane step. (Done 11-16, below.)

## Brush lane, 11-16: pencil and oil paint on the settings stage too
- **Owner (11-16): opening a brush editor shows a stroke waiting, and every dial re-lays it live; never a blank pad.**
- Pencil and oil go in a preview atlas of their own, fixed at **4 x 3 slots** (`JbCanvasView.PREVIEW_SLOTS`), never sized
  from the phone. 3 x 2 held 4 areas (two slots kept back) and one pencil dab with its look ring needs 9: nothing drew.
- Let go when the stage leaves the screen: `MediaCanvas.releaseAtlas()` from `onPause` and `surfaceDestroyed`, for a
  settings preview only (explicit guard: the drawing's own atlas is never let go at a pause, JBX-37). Graphics loss
  forgets it as before (`forget()` in `onSurfaceCreated`). Log "settings preview: atlas let go", 4-12 ms after the
  surface goes, no stall.
- Its size by its own count, logged when made ("settings preview atlas memory"): HB 92 MB, Palette knife 3 86 MB, Oil
  round 86 MB. The atlas session signed for 4 x 3 at about 110 MB.
- **Thinned (wet) oil is laid without its thinner on the stage**: with water one dab needs 12 areas (109 MB) and the atlas
  holds 10; 4 x 4 (about 145 MB) was refused by the atlas session, and its water park shows tile squares, not the brush.
  So Oil round's stage shows its stroke and hairs, not its wet spread. Two gaps seen in its cyan line with water on were
  not explained (they went with the water). Wet oil's true stage look is a later job.
- **Seen on the Note 9** (cloud builds c2f0959f7, 95a2e104e, 3167dd93e): HB whole curve (same picture as the old 6 x 4),
  Palette knife 3 whole (frames split in time, none refused), Oil round whole; moving its Hairs dial re-laid the stroke.
  Cloud tests on 3167dd93e: jb-core 3012, jb-androidkit 1353 pass.

## Brush lane, 11-16: underpaint the brush really works into (owner rulings via the screens lane)
- Brief: shared notes `BRUSH_STAGE_UNDERPAINT_BRIEF_20261116.md`. Root cause: the selector painted its colour lines into
  a ground the stroke never read; the stage's lines distracted.
- **Selector rows: the stroke only**, no colour lines (`BrushPreviewRenderer`). Smudge and push rows on a fine grey
  checkerboard (`PreviewRaster.checker`, 6 px) in the layer they sample, instead of the amber/blue blocks; erasers keep
  their strip. `TestStroke.VERSION` 10, so stored pictures redraw.
- **Stage underpaint by brush** (`TestStroke.underpaintFor`):
  - smudge, push and erasers: a fine light/dark grey checkerboard (4 dp) written into the stage's own layer
    (`JbCanvasView.replaceWithStrokes(checker = true)`);
  - colour-carrying paint (media watercolour and oil, the line watercolour): a round blob of a second colour of the
    same paint, laid first by that paint's brush (`TestStroke.blob`, `lineBrushId`), low in the middle so the second
    S-turn's foot and the loop's bottom graze its top edge (a first, smaller blob between the turns vanished under Oil round);
  - everything else, pencils included: plain paper.
- The `STRIPES` data stays for the bench's `tstripe` cases only.
- **Seen on the Note 9** (cloud build 4b54b6a25): Oil round crosses the orange blob and pulls a little of it; Round
  watercolour mixes into the orange wash wet in wet; Smudge drags the checkerboard to grey with its taper showing (slider
  ends on the checker too); HB on plain paper; the Pencil and smudge selector rows as ruled.
- Open: wet oil on the stage is still laid without its thinner (11-16 above), so its mixing with the blob is weak.

## Brush lane, 11-18: 14 underpaint options for the owner to pick
- Owner (11-18): the fine checker greys too soon; larger checkers, colour checkers, plaid or others; 14 options on a variety
  of smudge brushes, with texture, paper grain and water if we have them. Test blobs and lines go down before the stroke
  (they do: the blob is its own pass first).
- Built: `androidkit/preview/UnderpaintPatterns` (14 patterns in dp: fine/small/medium/large grey checker, black and white,
  four-colour, orange and teal, rainbow checker, tartan, red gingham, rainbow stripes, diagonal stripes, graph paper, polka
  dots); the stage takes any as its ground (`replaceWithStrokes(ground = ...)`); debug `UnderpaintSheetBench`
  (`--ez jb_underpaint_sheet true`) lays each under 9 brushes on a stage-sized canvas and copies the screen.
- Note 9 sheets (e51d2b429), sent to the owner; his pick owed. What they showed:
  - Smudge, Flat Paint and the bench-made textured and long-pull smudges really pull the pattern; the larger checkers and
    the colour patterns show it, the fine one greys.
  - **Push paints a plain stroke**: the canvas draws the push engine on the stamp path, so it moves no pixels yet.
  - **Water (watercolour) barely changes paint already on the layer**: clear water does not lift or move it.
  - **Scraper leaves no mark** on the pattern; Fan blender and the palette knife lay their own grey oil over it.
  - So a real push, a lifting water brush and a scraper that moves paint would be new brush work if he wants them.

## 11-27 (website lane, no phone): audit of section K, and the hover outline now uses Footprint
- **Read against the card and code: Stage layout, round group buttons, words on every slider end, slider-end pictures and tap-the-footprint-to-open-Unibrush's-shape-editor were already built** (step 1/2 and 11-15 above, seen on the Note 9). Nothing to rebuild.
- **Built now: the hover outline under the pen** (`HoverOutlineView`) shows, for every dab brush (stamp, smudge, push, Unibrush), the dab the brush would lay at this tilt, from `Footprint.outline` (the same outline the editor shows), scaled to the brush size and turned to the pen's lean; kept while brush and tilt are unchanged. Blades, oil/knife slabs and the circle for pencil, watercolour and Sable are unchanged. Cloud debug build run 46012707287 on `5ac9b6da0`: green (compiles). No host test: it is a drawing view.
- **Owed: phone look with a real S Pen hovering** (agents cannot inject a hover): a Unibrush calligraphy tip should show its flat nib turned with the pen. Still open as before: Unibrush's own stage (no Clear / Test), and the media brushes' dab ends.

## 11-27 (website lane): preview layout across screen sizes (owner: "out of sorts" on the Note 20)
- **Read in code, not seen on a phone.** The stage is fixed `PREVIEW_DP` 180 dp, the footprint 58 dp, the tip section 92 dp, the test stroke is fitted to the stage's own box: all dp, same on every phone. The Note 9 and a 1080x2400 Note 20 are the same width in dp, so these do not differ.
- **Only height-proportional sizes differed:** the paint brushes' list was capped at 55% of the screen height (465 dp on the Note 9, ~503 dp on 20:9) and Unibrush's sheet at 70% (592 dp vs ~640 dp), so a taller phone got a longer sheet and less drawing showing. Now both stop at the Note 9's dp (`BrushSettingsView.MAX_SHEET_DP` 465, Unibrush 592); shorter screens still get the percentage. Cloud debug build 46014052685 on `claude/preview1-build`: green.
- **Not found in code, so unproven as the cause:** his "previews all over the place" may also be an older build (stale cached previews before `TestStroke.VERSION` 10, or the pre-Stage layout) or a Note 20 density different from the Note 9. Owed: Note 9 shot of both sheets and the same on the Note 20 with the new build; the phone lane can take the Note 9 ones. The tablet is unaffected by the new caps (it only gets the lower of the two numbers).
- **11-27, what is left of section K and why I stopped (website lane):** (a) *Unibrush on the shared stage* (Clear / Test, the 180 dp stage and his scribble): Unibrush's picture is a cached bitmap drawn through the host's preview cache and PNG-resource lane (`UnibrushHostController.redrawPreview`, 84 dp), not the live `JbCanvasView` stage; moving it needs a second live canvas that can hold frozen PNG tips, which is paint-engine-adjacent, so it is left for the brush/engine lane. (b) *Media brushes' dab ends*: waits on the brush lane wiring the part into the media preview. Note 9 shots of the editor asked of the phone lane (hover-labels) for `tasks/screens/brush-editor-20261127/`; BRUSH-UI is not "done" until they are looked at.

## §L Owner's review and mockup, 11-27: one editor for every brush
Pictures: [before, Unibrush](../design/brush-editor-20261127/before_unibrush_calligraphy.png) ·
[before, paint](../design/brush-editor-20261127/before_paint_round.png) ·
[**owner's mockup**](../design/brush-editor-20261127/owner_mockup.png).

The paint (watercolour) editor and the Unibrush (calligraphy) editor look like two different apps: different
darkness, buttons and order. Make them **one editor**, with Unibrush adding its A/B shape parts inside it.
1. **One look and one order** for every brush: same background, same tab buttons, same sections in the same order.
2. **Test stroke:** never dark on dark. Show paper under the stroke in the brush's colour (as watercolour does). If a
   stroke has no paper behind it on the dark pane, draw it white, like the footprint samples.
3. **Pressure and tilt:** remove Unibrush's big "Pressure → Tilt ↓" box. Use paint's two separate boxes, a
   **Pressure** curve and a **Tilt** curve with Bézier handles, at a smaller size. New: pressing fills the area under
   the curve to show the shape it makes. Afterwards a plain **grey ball and stick** (no fill, not white or cyan) marks
   where the last stroke ended.
4. **Pen monitor = shape editor.** Drop the separate tip-shape widget and its unexplained rectangle. The pen monitor
   (slightly larger) is shared by all brushes: a ball that grows with pressure moves on the circle, with a **cyan
   triangle** from the centre to the ball's edges so the tilt reads at a glance, and a readout under it ("85% · 4°").
   For Unibrush, the **A and B shapes sit inside the monitor**. Tap A or B to select it and see its handles to reshape
   it. A **rotation caret** on the ring drags to turn A and B, with a live preview.
5. **A/B drop-down:** under the selected shape, a box with an arrow pointing to it holds that shape's own options
   (Corners, Thinness, Morph A→B, the rest of its properties). Selecting the other shape moves the arrow and box.
6. Beside the monitor sit the **0° / 35° / 70°** angle footprints. To their right are the Pressure and Tilt boxes.
7. **Section links:** keep the jump links. Drop the separate Pressure link, since shape and pressure are now one section.
8. **One close button:** Done or ×, never both. (Recommendation: keep ×, and let Done-style behaviour be the default
   on close, because changes already apply live.)
All other brush properties follow below, as now. Owner's design: build to the mockup; ask before changing it.
**Owner additions, 11-27 (later the same day):**
- **Pen monitor size:** the same size as paint's today. It only needs to grow compared with Unibrush's current small one.
  The two must look like one thing.
- **Expand button:** a small, discreet grey button in the monitor's corner toggles it large, taking over the space
  where the Pressure and Tilt boxes sit. Toggling back returns it to its normal size and the boxes reappear. This is
  for anyone who wants it bigger (eyesight, fine shape work).
- **Unibrush on the shared stage:** the owner doesn't expect this to need the paint engine. Unibrush can draw its own
  test stroke with its own renderer into the same stage frame, on the same paper. Only call it engine work if that is
  proven impossible.
- **The drawer is a real drawer on the phone:** no rounded bottom corners and no enclosed pop-up look. It slides up
  from the bottom edge and uses those 10–15 px for content.
- **Test stroke area:** round its hard corners a little.

**§L built, 11-27 (orchestrator's Select lane, Opus; cloud-checked, Note 9 shots owed):**
- **Step 1, one look:**
  - The phone sheet sits on the bottom edge with square bottom corners, and the card's margins go to content.
  - Unibrush drops its own panel inside the drawer.
  - One × keeps the live changes; there is no separate Pressure link (it lights Shape).
  - Unibrush's test stroke sits on the drawing's paper in the paint editor's 180 dp frame, with slightly round corners.
- **Steps 2 and 3, one pen row for every brush** (`chrome/PenHearingRow`):
  - Left to right: the pen monitor at paint's size, the 0/35/70° footprints stacked beside it, then the Pressure and Tilt boxes.
  - A small grey corner button makes the monitor large over the boxes, and back.
  - The boxes fill under the curve while the pen presses, and keep a plain grey ball and stick where the last stroke ended.
  - The monitor's lean is a cyan triangle to the ball's edges.
  - Unibrush's big Pressure → Tilt pad and its two 288 dp response boxes are gone; its response curves are now the row's boxes.
  - The tip side view, which is not in the mockup, is gone from the row.
- **Step 4, A and B in the monitor** (`ShapeMonitorView`, `ShapeOptionsBox`):
  - Tap a shape or its A / B dot to choose it; B is made from A on first use.
  - Drag its numbered points to reshape it; drag the cyan caret on the ring to turn the tip. Each drag is one step.
  - The chosen shape's options sit in a box under the row, with an arrow pointing at A or B.
  - The old square shape editor and the Shape endpoint choice are gone.
- **Step 5, one live stage** (`BrushStage` in `JoyBrushActivity`):
  - Unibrush modes draw their real test stroke on the same live stage as paint, on the drawing's paper. The stage can be drawn on, and has Clear and Test.
  - Picture-tip brushes (Unibrush image modes and imported ones) read their tips through the drawing's own lookup. A Unibrush mode whose pictures are not prepared yet keeps a picture on paper.
  - The live stage's corners are rounded by caps over the surface (`StageCorners`, since Android 10 cannot clip a SurfaceView).
- **No engine change was needed.** The earlier note that a second live canvas could not hold picture tips did not hold: the canvas resolves tips per stroke from a lookup, and the stage now gets the drawing's.
- **Tests:** `PenHearingRowTest` 2, `ShapeMonitorViewTest` 4, `UnibrushSettingsViewTest` +1 and updated, `BrushEditorStageTest` updated.
- **Owed:**
  - Note 9 before and after shots (`tasks/screens/brush-editor-20261127/`). The phone was offline.
  - The owner's look.
  - On a real pen, whether a picture-tip mode's live test stroke matches the drawing's.
- **Owner's pick 11-28: number 4, the large grey checker.** The stage lays it under smudge, push and erasers
  (`UnderpaintPatterns.STAGE`, 40 dp squares); drawer smudge rows get squares a third of the row high; `TestStroke.VERSION`
  11 so stored rows redraw. Note 9 seen (c470ca7df): Flat Paint drags whole squares; the rows show the bigger squares.
  Cloud: jb-core 3026, androidkit 1394, android 642 pass.
- **§L after shots taken 11-28** (Note 9, c470ca7df): `tasks/screens/brush-editor-20261127/after_*.png`. Calligraphy and
  paint now share one editor. Still owed: a real pen comparing a picture-tip mode's stage stroke with the drawing's.
- **Whole lines eraser phone check:** `SelectBench` step `wholeLinesEraser` (+ `wholeLinesEraserUndo`) written (83a5ed563,
  APK built; landed 9c2353200 with jb-android 644 pass) but not run: the Note 9 was held by another lane, then dropped
  off adb (11-28 11:40, offline; the Wi-Fi reconnect could not reach it; it needs a USB plug). Run with
  `am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench select --es jb_select_run_id <32 hex> --ez jb_no_shots true`
  and read `adb logcat -s JoyBrushPerf | grep wholeLines`.
