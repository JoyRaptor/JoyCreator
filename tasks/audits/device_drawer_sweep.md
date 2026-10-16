# Device drawer sweep (Note 9, serial the Note 9)

Report only. No source files edited, nothing committed. Project tested: "Restore video test" (throwaway copy). Nothing exported, no clip or project deleted, no delete dialog confirmed.

Screenshots are in the session scratchpad: `C:/Users/JOYRAP~1/AppData/Local/Temp/claude/C---Projects-Screenrecorder-FadCam/a80fe9f1-9b60-42fa-b216-c9bfa84cd1bd/scratchpad/` (filenames below are relative to that folder).

## Totals

- Tested actions: 68 (OK 52, non-OK 16).
- Crashes: none. The crash log buffer showed zero FATAL EXCEPTION entries across every tap.
- Drawers reached: Text, Image, Sprite, Visualizer. Not reached: video overlay, Captions, Audio.

## Results

| drawer | tab | control | action | result | screenshot |
|---|---|---|---|---|---|
| Text ("hello 2") | header | Text / Transform / Effects / Lanes tabs | tapped each | OK | t_transforms.png, t_effectss.png, t_laness.png |
| Text | header | Hide, Lock, Pass-through, Frost | on then off each | OK | toggle_strip.png |
| Text | header | Close (X) | tapped | OK | |
| Text | Text | Text colour swatch | opened, Cancel | OK (inner controls not touched) | t_textcolor.png |
| Text | Text | Set (colour picker) | not tapped | not tested | |
| Text | Text | Bold / Italic / Underline | on then off each | odd: toggles and shows the state, but adds no undo step (undo count unchanged) | t_after_redo_top.png |
| Text | Text | Tt / TT / small caps | each tapped, mutually exclusive, toggles off | OK. One transient mismatch on a 1 s screenshot (Tt showed TT highlighted); not reproduced in 4 retries with 3 s waits | k_strip.png, m_strip.png |
| Text | Text | Alignment | cycled centre, right, justified, left, centre | OK | |
| Text | Text | Text width | cycled Auto, Wrap wide, Wrap medium, Wrap narrow, Fit to width, back to Auto | odd (clipped): in Wrap wide the wrap-width slider runs off the right edge, Text motion is cut off, and the Neon flicker preset chip is pushed out of view | c_widthc.png |
| Text | Text | Text motion (opens sheet) | opened | OK | c_motionc.png |
| Text | Motion sheet | 11 motion tiles | tapped each | OK. Each applies and closes the sheet; each adds one undo step | |
| Text | Motion sheet | Animate by: Letter, Word, Sentence, Block | tapped each | OK. Sheet stays open; each adds one undo step | ab_sheets.png |
| Text | Motion sheet | Undo after a motion change | 12 undos | odd: after undo the chip kept reading "Fade" until the motion was picked again (UI-tree text evidence, no screenshot) | |
| Text | Text | Start here / Span whole / End here (top row) | each tapped | OK when tapped | |
| Text | Text | Top row after undo | undid 5 steps | odd: afterwards no option was highlighted; restored by tapping Start here (adds one undo step) | anim_restoreds.png, anim_fixs.png |
| Text | Text | Animation starts here / ends here | tapped | odd: no undo step recorded; visible change not confirmed | anim_ends.png |
| Text | Text | Entrance ends here / No entrance or exit | tapped | OK | |
| Text | Text | Exit starts here | tapped | odd: no undo step recorded; visible change not confirmed | anim_ends.png |
| Text | Text | Animation spans the whole object | tapped (restored original) | OK | |
| Text | Text | Neon flicker preset chip | not tapped | not tested | |
| Text | Text | Select all / Done / MORE / drag handle | not tapped | not tested | |
| Text | Text | Clear | skipped: wipes the text | skipped (destructive) | |
| Text | Text | Delete (red trash) | skipped: destructive | skipped (destructive) | |
| Image (first IMG lane) | Transform | tab | tapped | OK. Start/Span/End, Fit/Fill, pivot, keyframes and sliders visible; none of those controls tapped | img_transform.png |
| Image | Transform | "Text motion" control (desc at 559,130) | not tapped | odd (label): an image drawer carries a control described as "Text motion"; verify the label | img_transform.png |
| Image | Transform | Clear all (keyframes) | skipped: destructive | skipped (destructive) | |
| Image | Transform | Re-tapping the active tab | tapped twice | odd (behaviour): drawer collapses to header only; may be intended | tr2s.png |
| Image | Blend mode | tab | opened twice | odd: first open showed "Normal" dropdown; second open showed an empty body | img_blend.png, it_Blend_mode.png |
| Image | Mask | tab | opened | OK. Sliders and mask-mode buttons not tapped | img_mask.png |
| Image | Chroma key | tab | opened | OK. "Key out a colour" checkbox not tapped | it_Chroma_key.png |
| Image | Effects | tab | opened | OK. Add effect not tapped | it_Effects.png |
| Image | Lanes | tab | opened | OK. New lane Above/Below and Move dropdown not tapped | it_Lanes.png |
| Image | Puppet | tab | opened | odd (clipped): floating tool buttons on the right edge are partly cut off. Pins and mode buttons not tapped | img_puppet.png |
| Sprite ("Starguy") | Transform | drawer | opened | OK. Lanes tab, Pos X/Y, Scale, Rotate, Opacity not tapped | sv_sprites.png |
| Visualizer | Style | drawer | opened | OK. Save / Export / Import, Hz, Bars, Width, Gap sliders, left icons, colour swatches, lane buttons and Transform tab not tapped | sv_vizs.png |
| Visualizer | Style | Shape pills (Bars, Line, Filled, Dots, Squares, Peaks) | each tapped, Peaks restored | OK. Lane name follows each shape | vz_shapes.png |
| Visualizer | Style | Peaks pill at right edge | viewed | odd (clipped): pill is cut off at the right edge | sv_vizs.png |
| Visualizer | Style | Shape change | viewed undo count | odd: shape changes are not recorded in undo (count stayed 9) | vz_shapes.png |
| Video overlay | - | - | long press at 700,1620 | not reached: opened the Visualizer instead | |
| Captions (CC) | - | - | not reached | not tested | |
| Audio | - | - | not reached | not tested | |
| Main toolbar | bottom | Opacity (and Delete) | viewed | odd (clipped): bottom toolbar items cut off at the right edge | s0s.png |

## Side effects on the test project

- Stray tap at (960,1000) on the timeline while testing motions. The undo count did not change, so it added no edit. Not visually verified.
- Three early undo presses reverted earlier project history. Restored with Redo (undo 8, redo 0).
- Final state: Text Standard, Bold/Italic/Underline off, case none, alignment centre, width Auto, motion Neon flicker, animate-by Letter, top row Start here, bottom row Span. Undo count is 9 (was 8) because restoring Start here added one step.

## Not covered (remaining time/budget)

- Text: Set, Neon flicker chip, Select all, Done, MORE, drag handle.
- Image: sliders, keyframe controls, Fit/Fill, pivot, chips, Hide/Lock/Pass/Frost.
- Sprite: Lanes tab and all sliders.
- Visualizer: sliders, colour swatches, left icons, Save/Export/Import, lane buttons, Transform tab.
- Video overlay, Captions, Audio: not reached.


## Lead's triage (2026-10-16)
- Visualizer shape/blend/mirror/glow/colour changes had no undo step: REAL, fixed (one press = one step, verified Peaks -> Undo -> Bars on the Note 9).
- Text B/I/U and animation "no undo step": by design. A text drawer session is recorded as ONE undo step when the drawer closes (recordTextStyleUndo).
- "Clipped" Wrap-wide row, Peaks pill, bottom bar Opacity/Delete: by design, these are horizontal scrollers (buildTextTopRow wraps its row in a HorizontalScrollView).
- "Text motion" label on an Image drawer: REAL, my own label; renamed "Motion".
- Image Blend mode empty on second open, tab re-tap collapsing the drawer, Puppet floating buttons, stale Fade chip after undo: NOT yet checked.
