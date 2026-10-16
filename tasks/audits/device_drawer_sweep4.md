# Device drawer sweep 4

Device: the Note 9 (adb, Samsung). App: com.fadcam.beta, project "Restore video test" (throwaway).
Screenshots: `C:\Users\JOYRAP~1\AppData\Local\Temp\claude\C---Projects-Screenrecorder-FadCam\a80fe9f1-9b60-42fa-b216-c9bfa84cd1bd\scratchpad\` (file names below, `_s` = 540 wide).
Undo counter baseline: 33. Final state: undo 41, redo 1. Sprite Transform edits (Pos X 35%, Pos Y 48%, Scale 55%, Opacity 72%, rotation -79 deg, one Pos X keyframe) are still applied.

## Coverage

- Covered: Audio drawer (Level, A/V Sync, Effects tabs, header), Sprite drawer (Transform, Lanes tabs, header, Bend).
- NOT covered: Video overlay drawer (Lanes tab, header toggles) and Captions drawer (Fit, font row, presets, eye, outline, motion). Budget of about 100 tool calls reached first.
- Frame palette strip (Sprite drawer): not found in Transform or Lanes tab.

## Results

| drawer | tab | control | action | result | screenshot |
|---|---|---|---|---|---|
| Audio | Level | Level slider | drag | OK: 113% to 152%, +1 undo | s_L1_s.png |
| Audio | Level | Pan slider | drag | OK: C to L6%, +1 undo | s_P1_s.png |
| Audio | Level | Fade in slider | drag | OK: 0 to 1.88s, +1 undo | s_I1_s.png |
| Audio | Level | Fade out slider | drag | OK: 0 to 1.56s, +1 undo | s_O1_s.png |
| Audio | Level | Level value (113%) | tap | OK: "Level (%)" entry dialog, Cancel changes nothing | s_PCT_OPEN_s.png |
| Audio | Level | Pan value | tap | OK: "Pan (%)" dialog, Cancel | s_PAN_OPEN_s.png |
| Audio | Level | Fade in value | tap | OK: "Fade in (s)" dialog, Cancel | s_IN_OPEN_s.png |
| Audio | Level | Fade out value | tap | OK: "Fade out (s)" dialog, Cancel | s_OUT_OPEN_s.png |
| Audio | Level | Envelope diamond | tap | ODD: +1 undo, Level jumped to 57% and a 3-point envelope row appeared. A later tap also gave +1 undo but no envelope row or "3 pts" text appeared. | s_K1_s.png, s_D2_s.png, s_CLR2_s.png |
| Audio | Level | Envelope < and > arrows | tap | OK: step between envelope points, playhead moves, no undo step | s_K2_s.png, s_K3_s.png |
| Audio | Level | Envelope Clear | tap | ODD: +1 undo, envelope row still shown after 1 s. Undo then reset In and Out to 0 ms and removed the envelope, more than one step's change. | s_CLR_s.png, s_CLR_UNDO_s.png, s_now3_s.png |
| Audio | Level | In value after envelope | observed | ODD: In changed 1.88s to 4.99s with no drag, equal to the playhead time | s_now2_s.png |
| Audio | header | Start here (flag) | tap | OK: +1 undo, Undo reverts | s_H1_s.png |
| Audio | header | End here (flag) | tap | OK: +1 undo, Undo reverts | s_H2_s.png |
| Audio | header | Split at playhead (scissors) | tap | OK: +1 undo, Undo reverts | s_SPLIT_s.png |
| Audio | header | Mute (speaker) | tap | NO-UNDO: icon turns red, counter unchanged | s_MUTE_A_s.png |
| Audio | header | Lock (shield) | tap | NO-UNDO: icon turns green, counter unchanged | hdr_strip.png |
| Audio | header | Bypass effects (FX) | tap | NO-UNDO: FX turns red, counter unchanged | hdr_strip.png |
| Audio | header | Grid icon (no label) | tap | NO-UNDO: icon turns green, purpose not identified | s_GRID_D_s.png |
| Audio | A/V Sync | Offset slider | drag | NO-UNDO: 0 to 156 ms | s_AV_OFF_s.png |
| Audio | A/V Sync | Test | tap | UNVERIFIED: no change in UI tree or screenshot; a click or flash cannot be checked from here | s_AV_TEST_s.png |
| Audio | A/V Sync | Reset to measured | tap | NO-UNDO: 156 ms to 0 ms | s_AV_RESET_s.png |
| Audio | Effects | Enhance voice checkbox | tap on, tap off | OK: +1 undo each | s_EV_ON_s.png |
| Audio | Effects | Beat-reactive link | tap, pick Voice, Cancel | OK: band picker opens, Voice checks, Cancel closes, no change | s_BEAT_s.png, s_BAND_V_s.png |
| Audio | Effects | Compressor meter | (display only) | not an input, not tested | |
| Sprite | Transform | Pos X slider | drag | OK: 18% to 35%, +1 undo | s_SX_s.png |
| Sprite | Transform | Pos Y slider | drag | OK: 40% to 48%, +1 undo | s_SY_s.png |
| Sprite | Transform | Scale slider | drag | OK: 46% to 55%, +1 undo | s_SS_s.png |
| Sprite | Transform | Opacity slider | drag | OK: 100% to 72%, +1 undo | s_SO_s.png |
| Sprite | Transform | Rotation dial | drag | OK: 0 deg to -79 deg, +1 undo | s_SR_s.png |
| Sprite | Transform | Pos X keyframe diamond | tap | OK: +1 undo, button turns into a green remove mark | s_KF_X_s.png |
| Sprite | Transform | Pos X < and > arrows | tap | NO RESPONSE: only one keyframe exists, so nothing to step to. Not re-tested with two keyframes. | s_PX_L_s.png, s_PX_R_s.png |
| Sprite | Transform | Pos X value (35%) | tap | OK: entry dialog, Cancel | s_PX_VAL_s.png |
| Sprite | Bend | Bend button | tap on | OK: "Bend on", blue pin mesh appears | s_BEND_ON_s.png |
| Sprite | Bend | One pin | drag | OK: +1 undo, Undo reverts | s_BEND_PIN_s.png |
| Sprite | Bend | Bend button | tap off | OK: mode toggle, no undo step | s_BEND_OFF_s.png |
| Sprite | header | Hide (eye) | tap | NO-UNDO: eye turns red, sprite hidden | sp_hdr.png |
| Sprite | header | Lock (shield) | tap | NO-UNDO: shield turns pink | sp_hdr.png |
| Sprite | header | Grid icon | tap | NO-UNDO: icon turns pink, preview dims, purpose not identified | sp_hdr.png |
| Sprite | Lanes | Tab | tap | OK | lanes_tab.png |
| Sprite | Lanes | Below (New lane) and Down a lane pills | observed | OVERLAP: pills sit over the top-left edge of the preview | lanes_tab.png |
| Sprite | Lanes | New lane Above | tap | OK by counter (+1, Undo reverts). New lane not clearly visible in crops. | s_NL_ABOVE_s.png |
| Sprite | Lanes | New lane Below | tap | OK by counter (+1, Undo reverts). Not visually confirmed. | s_NL_BELOW_s.png |
| Sprite | Lanes | Up a lane | tap | OK: +1 undo, Undo reverts, sprite lane changes row | s_MV_UP_s.png, lanes_fx.png |
| Sprite | Lanes | Down a lane | tap | OK: +1 undo, Undo reverts, sprite lane changes row | s_MV_DN_s.png, lanes_fx.png |
| Sprite | Lanes | Frame palette strip | (look) | NOT FOUND in Transform or Lanes tab | |
| Editor | top bar | (incident) grid tap at 965,36 with drawer closed | tap | ODD: landed near the share/export icon. No export screen or dialog appeared (checked UI tree). Recorded for transparency. | s_GRID_OFF_s.png |

## Problem list

1. Audio, Level, Envelope diamond: inconsistent. First tap changed Level to 57% and showed a 3-point envelope. A later tap gave +1 undo with no visible envelope.
2. Audio, Level, Envelope Clear: after Clear, the envelope row stayed on screen. Undo then reset In and Out to 0 ms and removed the envelope, which is more than one step's change.
3. Audio, Level, In value: changed from 1.88s to 4.99s with no drag, equal to the playhead time, once envelope points existed.
4. No undo step (change happens, counter unchanged): Audio Mute, Lock, Bypass effects, grid icon; Audio A/V Sync Offset and Reset to measured; Sprite Hide, Lock, grid icon.
5. Audio, A/V Sync, Test: no visible response; not verifiable from screenshots.
6. Sprite, Transform, Pos X < and > arrows: no response with one keyframe. Needs a retest with two or more keyframes.
7. Sprite, Lanes: Below and Down a lane pills overlap the preview's top-left edge.
8. Sprite, Lanes: New lane Above and Below changed the counter but the new lane was not clearly seen in screenshots. Recheck visually.
9. Sprite: frame palette strip not found in Transform or Lanes tab.
10. Audio header grid icon and Sprite header grid icon: no label, purpose not identified (toggle, no undo step).
11. Incident: one tap at 965,36 with the drawer closed landed near the share/export icon. No export screen or dialog appeared.
12. Not covered: Video overlay drawer and Captions drawer.
