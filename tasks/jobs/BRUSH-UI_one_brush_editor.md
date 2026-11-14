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
