# Device drawer sweep 3: Video overlay + Captions

Project: "Restore video test" (throwaway). Undo counter at start: 15.
No source edits, commits or installs. No clip or project deleted, Export not tapped.
Crash buffer checked after each batch: 0 crashes throughout.
Screenshots are full resolution in the scratchpad folder `sweep3` (file names in the Screenshot column).

Undo rule checked on every slider: the counter should rise by exactly 1 per slow (1.5 s) drag.

## Results

| drawer | tab | control | action | result | screenshot |
|---|---|---|---|---|---|
| Video overlay | Transform | Pos X slider | slow drag 72% to 150% | OK, undo +1 | r1_1.png |
| Video overlay | Transform | Pos Y slider | slow drag 22% to -30% | OK, undo +1 | r1_2.png |
| Video overlay | Transform | Scale slider | slow drag 35% to 5% | OK, undo +1 | r1_3.png |
| Video overlay | Transform | Opacity slider | slow drag 100% to 49% | OK, undo +1 | r1_4.png |
| Video overlay | Transform | Rotate, keyframe arrows and diamonds | not tested | not covered | |
| Video overlay | Transform | Volume | not found in the drawer | not found | |
| Video overlay | Blend mode | Blend dropdown | open, pick Multiply, reset to Normal | OK visually; **undo 0 (no step recorded)** | bl4.png |
| Video overlay | Mask | Add mask shape | tap + | OK | mk3.png |
| Video overlay | Mask | Center X slider | slow drag 50 to 200 | **undo 0 (no step)** | mk4.png, mk5.png |
| Video overlay | Mask | Center X step up | tap at max | no change (already 200) | |
| Video overlay | Mask | Width slider | slow drag 30 to 100 | **undo 0 (no step)** | mk4.png |
| Video overlay | Mask | Soften edges slider | slow drag 0 to 86 | **undo 0 (no step)** | mk4.png |
| Video overlay | Mask | Show only inside the box | on, then off | visible change; **undo 0 (no step)** | mk4.png |
| Video overlay | Mask | Move with the object | tap | **no visible change; undo 0** | |
| Video overlay | Mask | Add / Subtract / Intersect | tap Subtract | visible change; **undo 0** | mk5.png |
| Video overlay | Mask | Remove this shape | tap | OK ("No mask"); **undo 0** | |
| Video overlay | Mask | Key at playhead, Clear, Rotation | not tested | not covered | |
| Video overlay | Chroma key | Key out a colour checkbox | on | visible change; **undo 0** | ck2.png |
| Video overlay | Chroma key | Key out blue / green swatches | tap blue, back to green | colour changes (#0000FF, then #00FF00); **undo 0** | ck3.png |
| Video overlay | Chroma key | Pick from video (eyedropper) | not tested | not covered | |
| Video overlay | Chroma key | Tolerance slider | slow drag 18 to 98 | **undo 0 (no step)** | |
| Video overlay | Chroma key | Edge softness slider | slow drag 10 to 86 | **undo 0 (no step)** | |
| Video overlay | Chroma key | Choke / spread slider | slow drag to min | **undo 0 (no step)** | |
| Video overlay | Effects | Add effect picker | open | OK | ef1.png |
| Video overlay | Effects | Gaussian Blur | tap | OK: explains it needs an adjustment layer, not added (info dialog, Got it) | ef2.png |
| Video overlay | Effects | Brightness / Exposure | add | OK, undo +1 | ef3.png |
| Video overlay | Effects | Exposure, Lift, Amount, Opacity sliders | slow drag each | OK, undo +1 each (values move) | ef5.png |
| Video overlay | Effects | Bypass effect toggle | on, then off | OK, undo +1 each | ef6.png |
| Video overlay | Effects | Blend mode sheet | open | OK | ef7.png |
| Video overlay | Effects | Save look, Delete effect, effect Blend | not tested (Delete skipped on purpose) | not covered | |
| Video overlay | Lanes | New lane, Move up/down | not tested | not covered | |
| Video overlay | header | Mute, sound on timeline, Hide, Lock, pass-through, Frost | not tested | not covered | |
| Captions | Style | Caption size slider | slow drag 6% to 15% | OK, undo +1 | cs1.png |
| Captions | Style | In timing slider | slow drag 0% to 50% | OK, undo +1 | cs1.png |
| Captions | Style | Out timing slider | slow drag 0% to 50% | OK, undo +1 | cs1.png |
| Captions | Style | Highlight Zoom | tap | OK, undo +1 | cs1.png |
| Captions | Style | Highlight Bounce | tap | **undo 0 (no step)**; visual change not confirmed | cs2.png |
| Captions | Style | Highlight Pop | tap | **undo 0 (no step)** | cs2.png |
| Captions | Style | Text colour | open, Cancel | OK (picker opens, Cancel closes, undo 0) | tc1.png |
| Captions | Style | Box toggle | tap | visible change; **undo 0 (no step)** | tg1.png |
| Captions | Style | Outline swatch | tap | **no visible response**; undo 0 | |
| Captions | Style | Shadow toggle | tap (twice in test) | visible change; **undo 0 (no step)** | tg1.png |
| Captions | Style | Font row (name, save, delete, share, import) | not tested (delete skipped on purpose) | not covered | |
| Captions | Style | Motion None to Fade | pick Fade | OK, undo +1 | mo2.png |
| Captions | Style | Motion back to None | attempted, see next row | **FAIL: left at "Type - word"; undo +1** | mo3.png |
| Captions | Style | Hide captions eye | tap twice | **no visible change; undo 0** | hid1.png, hid2.png |
| Captions | Style | Preset strip (Pop, Zoom, Bounce, Boxed, Hot, Meme, Bright) | one stray tap at Bounce position | no visible change; not tested properly | |
| Captions | Style | Timing text and In/Out sliders | layout check | **OVERLAP: the timing text and In/Out sliders sit on top of the preset strip, text is hard to read** | cs1.png, mo3.png |
| Captions | Fit | Floor slider | slow drag 45% to 100%, then back | **undo 0 (no step)** | fit2.png |
| Captions | Fit | Uniform, Off | tap | visible change; **undo 0 (no step)** | fit2.png |
| Captions | Fit | Words, Max lines, Grouping, Grow, Align, Copy | not tested | not covered | |

## Problem list

1. **Undo 0 (no step recorded)** on many controls: Mask Center X, Width, Soften edges, Show only inside, Subtract mode, Remove shape; Chroma key toggle, Tolerance, Edge softness, Choke, swatches; Blend mode dropdown; Captions Highlight Pop and Bounce, Box, Shadow, Hide eye; Fit Floor, Uniform, Off. Sliders that DO record steps: Transform, effect, Captions size/In/Out, Motion. Not verified whether Undo reverts the 0-step edits.
2. **Captions Motion left changed**: the test ended at "Type - word" instead of "None" (the original). Needs a manual fix.
3. **Captions Box and Shadow** were toggled during the test. The final on/off state is not certain, and the Highlight was changed to Pop (originally not recorded). Check against the original before showing the owner.
4. **Layout overlap**: Captions timing text ("Applied to every caption line...") and In/Out sliders overlap the preset strip.
5. **Hide captions eye**: no visible change after two taps.
6. **Outline swatch**: no visible response to a tap.
7. **Move with the object**: no visible change.

Incidents (my errors, recovered, no data change):
- Opened the Joybot chat by tapping the top-right avatar while the drawer was closed. Back returned to the editor.
- A swipe over the lane list selected a lane and turned on Bend mode for an image. Bend was turned off again. The image still shows its selection handles.

Not covered (budget): Lanes tab, header toggles (Mute, sound, Hide, Lock, pass-through, Frost), Transform rotate/keyframes/volume, Mask Key at playhead, Chroma eyedropper, Effects Save look and effect Blend, Captions font row, position, Box options, text and highlight colours beyond Cancel, preset strip, Fit Words/Max lines/Grouping/Grow/Align/Copy, timer.

Counts: 17 rows OK. 21 rows recorded undo 0 (the "undo 0" rows above, including the two no-response rows). 1 row FAIL (Motion end state). 1 OVERLAP (timing text on preset strip). No undo-spam (no slider rose by more than 1).
