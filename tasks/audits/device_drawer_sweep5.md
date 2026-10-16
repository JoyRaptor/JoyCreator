# Device drawer sweep 5 (Note 9, Restore video test)

Report only. No source edits, commits or installs. Test project was edited (throwaway); no clip or project deleted, no Export tapped, no recording started.
Screenshots: scratchpad folder `s5` (file names below, e.g. `mute_test.png`, `image_hide.png`).

## Results

| Drawer | Tab | Control | Action | Result | Screenshot |
|---|---|---|---|---|---|
| Video overlay | Header | Mute (speaker) | tap | OK: icon turns red, lane sound icon removes; Undo reverts it | mute_test.png, undo_test.png |
| Video overlay | Header | Sound on timeline (waveform) | tap x4 across runs | odd: drawer icon flips grey, but lane sound icon stays on and state reverts after Back | d0_hdr_c.png, d1_hdr_c.png, d2_lanes_c.png |
| Video overlay | Header | Hide (eye-slash) | tap x2 | unverified: icon toggles, but overlay is at Pos X 150% (off-frame), so no picture change can be seen either way | hide_test.png, zoom_hide.png |
| Video overlay | Header | Lock (shield) | tap x2 | no-undo: icon toggles OK, undo counter does not change | lock_test.png |
| Video overlay | Header | Let taps pass through (hand) | tap x2 | OK: lane shows slashed-hand icon when on | passgrid.png |
| Video overlay | Header | Grid (dotted) | tap x2 | no response on lanes; preview effect not captured | passgrid.png |
| Video overlay | Lanes | New lane Above/Below, Move up/down | looked for tab | no response: no Lanes tab in overlay drawer (tabs: Transform, Blend, Mask, Chroma key, FX). Lanes tab exists in the Sprite drawer (not exercised) | ov_drawer_s.png, tab2_s.png, tab_pen_s.png |
| Video overlay | Header | Lane caret (lane header) | tap | odd: collapses lane (not a menu); counter moved 49 to 50 | lane_menu_s.png, lane_restore_s.png |
| Video overlay | FX | Header pill | open | clipped: "Effects" label cut off by header toggles | tab4_s.png |
| Video overlay | Blend / Mask / Chroma / FX | tab open | open | OK: tabs open and render (Blend notice reads correctly) | tab2_s.png, tab3_s.png, tab_pen_s.png |
| Captions | Fit | Off / Uniform / Per cue | tap | OK: each selects | fit_batch1.png |
| Captions | Fit | Truncate | tap x2 | OK: toggles (restored) | fit_batch1.png |
| Captions | Fit | Words 6w | tap | no response (only a brief dim of the chips) | fit_batch2.png |
| Captions | Fit | Max lines | tap | no response | fit_batch2.png |
| Captions | Fit | Grouping (Karaoke to Slide) | tap | OK: cycles (left changed, not restored) | chips3.png |
| Captions | Fit | Grow (center to down) | tap | OK: cycles (left changed, not restored) | chips3.png |
| Captions | Fit | Align (center to left) | tap | OK: cycles (left changed, not restored) | chips3.png |
| Captions | Fit | Copy to all | tap | no response: no visible feedback | cp1_c.png |
| Captions | Fit | Floor slider | not tested | not tested | |
| Captions | Style | Font dropdown | open, pick | OK: list opens; an accidental pick set Rounded, then restored to Standard | fdd_eye.png, fr_c.png |
| Captions | Style | Font save / share / download | not tapped | not tested (share and download may open outside the app) | |
| Captions | Style | Font delete | not tapped (per rule) | not tested | |
| Captions | Style | Hide-captions eye | tap x2 | unverified: no caption text on the preview at 00:00, so no change can be seen | eye_test.png |
| Captions | Style | Presets Pop, Zoom, Bounce, Boxed, Hot, Meme, Bright | tap x7 | no-run: drawer dropped after first tap; later taps hit the preview and selected the IMG clip. Presets not verified | presets.png, after_pr_s.png |
| Captions | Style | Outline swatch | not tested | not tested | |
| Sprite (Starguy) | Lanes | New lane, Move up/down | tap Lanes icon | odd: opens a Lanes view that forces landscape, and it stays landscape after Back (fixed by setting rotation to portrait) | back1_s.png, back3_s.png |
| Sprite (Starguy) | Transform | Frame palette strip | looked for it | no response: strip not found at bottom of drawer. Bottom bar's last label (Opacity) is clipped | sp_drawer2_s.png |
| Sprite (Starguy) | Transform | Pos X keyframes, prev/next | add 2 keys, step | unverified: drawer did not stay open for the second key; playhead moved and the sprite may have moved | k1_s.png, kf_test.png |
| Image | Transform | Eye (hide) | tap, then tap again | OK: picture disappears at once and returns | image_hide.png |

## Counts

- OK: 9 rows (Mute, Pass-through, Fit modes, Truncate, Grouping, Grow, Align, Font dropdown, Image hide). Tabs Blend/Mask/Chroma/FX also open OK.
- Non-OK: 15 rows (no response 5, odd 4, no-undo 1, clipped 2, unverified 3, no-run 1; some rows count under more than one category).
- Not tested: 3 (Floor slider, Outline swatch, Font save/share/download).

## Problems to act on

1. Captions presets: the 7-preset test did not run. Retest with the Captions drawer open, one tap per preset.
2. Overlay Lanes tab: not in the overlay drawer. It exists in the Sprite drawer (New lane / Move). Confirm this is the intended place.
3. Sprite Lanes view forces landscape and stays there after Back.
4. Undo counter: did not change on Lock, Hide, Grid or waveform taps, while Mute did revert on Undo. Counter behaviour is inconsistent.
5. Overlay waveform (sound on timeline): drawer icon and lane icon disagree after toggling.
6. Overlay FX pill label and Sprite bottom bar "Opacity" label are clipped at the right edge.
7. Captions: Words and Max lines chips give no visible response; Copy to all gives no feedback.
8. Sprite frame palette strip not found; keyframe test inconclusive.
9. Hide-overlay and hide-captions cannot be verified visually in the current project (overlay off-frame; no caption text at 00:00).

## Side effects left in the throwaway project

- Captions Grouping = Slide, Grow = down, Align = left (not restored).
- Font restored to Standard; Truncate restored; Lock restored; Pass-through restored.
- The IMG clip was selected by stray taps; the playhead has moved; the Sprite may have been nudged.
- Device rotation was forced to portrait via system settings and auto-rotate was set back on.

## Crashes

None seen in the crash log buffer at the checkpoints run (logcat -b crash tail empty).
