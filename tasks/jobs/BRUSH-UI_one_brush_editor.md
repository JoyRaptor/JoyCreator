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
