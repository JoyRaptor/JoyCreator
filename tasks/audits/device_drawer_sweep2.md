# Device drawer sweep 2 (report only)

Device: the Note 9 (unlocked). App: com.fadcam.beta, editor on project "Restore video test" (throwaway copy). No source edits, no commit, no install. Export not tapped, no clip or project deleted, no delete dialog confirmed, colour picker inner controls not touched. Screenshots are in the session scratchpad, named as in the table.

Result key: OK = responded as expected, nothing clipped. PROBLEM = confirmed defect. ODD = unclear or looks wrong, needs a human look. NOT TESTED = not reached within the budget.

## Image drawer

| drawer | tab | control | action | result | screenshot |
|---|---|---|---|---|---|
| Image | Transform | active tab (re-tap) | tap | PROBLEM: drawer collapses to header only, body disappears | img_tr_retap_s.png |
| Image | Mask | + add mask | tap | OK | img_mask_added_s.png |
| Image | Mask | shape chips 1/2/3 | tap | OK | img_mask_plus_s.png |
| Image | Mask | − remove shape (3 then 2 then 1) | tap | OK, ends at "No mask" | img_mask_removed_s.png |
| Image | Mask | Add / Subtract / Intersect | tap each | OK (selected state changes) | img_mask_subtract_s.png |
| Image | Mask | Center X ‹ › | tap | OK (50 to 51) | img_mask_cx_step_s.png |
| Image | Mask | Center X slider | drag | OK (51 to 100) | img_cx_drag |
| Image | Mask | Width ‹ | tap | OK (30 to 29) | img_w_down |
| Image | Mask | Roundness ›, Rotation › | tap | OK (0 to 1) | img_round, img_rot |
| Image | Mask | Soften edges slider | drag | OK (0 to 58); first drag started off the track, retry worked | img_soft_drag2_s.png |
| Image | Mask | Show only inside the box | tap | OK | img_inside_box_s.png |
| Image | Mask | Move with the object | tap | OK | img_move_obj_s.png |
| Image | Mask | Key at playhead | tap | OK (not animated to animated) | img_key_playhead |
| Image | Mask | Undo after keyframe | tap Undo | ODD: "animated" label stayed after Undo; inconclusive (undo counter did change) | m_c_s.png |
| Image | Mask | Clear | skipped (destructive) | NOT TESTED | n/a |
| Image | Effects | + Add effect (picker) | tap | OK | img_fx_picker |
| Image | Effects | Invert (added) | tap | PROBLEM: effect card appears but preview shows no visible change, before or after Bypass | img_fx_invert_s.png, inv2_crop.png |
| Image | Effects | Bypass (eye/◉) | tap | OK ("1 pass" to "0 passes") | inv3_crop.png |
| Image | Effects | Delete effect (✕) | tap | OK, no dialog | img_fx_deleted_s.png |
| Image | Effects | Gaussian Blur and other blur entries | not tapped | NOT TESTED; the screen note says blur is skipped in preview and export, yet the picker offers it | img_fx_picker |
| Image | Lanes | Above (New lane) | tap | ODD: no new lane visible in the visible timeline area; unclear | lanes_cmp.png |
| Image | Lanes | Up a lane / Below / Undo | tap | ODD: not verified visually | lanes_tile.png |
| Image | Puppet | Grab / Pin / Stiff / Dangle / Free / Bone | tap | ODD: no crash; visible change not confirmed | img_puppet_after_s.png |
| Image | Puppet | SELECTED / CHARACTER / RECORDING | tap | OK (RECORDING and CHARACTER switch) | img_puppet_rec_s.png |
| Image | Puppet | Record on touch / Snap to keys | not tapped (checkbox was not on the Character tab) | NOT TESTED | n/a |
| Image | Puppet | floating buttons at right edge (SELECTED tab) | look | OK: not cut off (suspicion b answered NO on this tab) | img_puppet_tab_s.png |
| Image | Puppet | floating buttons (RECORDING tab) | look | PROBLEM: column moves to the left edge; top circle sits partly under the chip row, cut off | img_puppet_rec_s.png |

## Sprite drawer (Starguy, Transform tab)

| drawer | tab | control | action | result | screenshot |
|---|---|---|---|---|---|
| Sprite | Transform | Pos X slider | drag | OK (48% to 68%) | sp_next_s.png |
| Sprite | Transform | keyframe diamond | tap, then Undo | OK (diamond back to outline) | sp_key |
| Sprite | Transform | next keyframe › | tap | ODD: no visible response | sp_next_s.png |

## Not reached (budget)

- Video overlay (Transform, Blend mode, Mask, Chroma key, Effects, Lanes): NOT TESTED
- Captions (Style, Fit, preset strip): NOT TESTED
- Audio drawer (Level, A/V Sync, Effects): NOT TESTED
- Sprite Lanes tab, frame palette, Bend button, pin dragging: NOT TESTED
- Image Lanes Up/Below/Undo and Puppet Record checkboxes: NOT TESTED or unverified (see rows above)
- Text-motion undo (suspicion c): NOT TESTED

## Suspicions

- (a) Re-tapping the active tab in the Image drawer collapses it to header only: YES (img_tr_retap_s.png)
- (b) Puppet floating buttons cut off at the right edge: NO on the SELECTED tab; YES, clipped at the top, on the RECORDING tab, where the column moves to the left edge (img_puppet_rec_s.png)
- (c) After Undo of a text-motion change, the motion chip still shows the old value: NOT TESTED

## Notes

- The Undo button sits at about x=451, y=1094 in this layout, not y=926 as briefed.
- Counts: OK 19; PROBLEM 3 (Transform re-tap collapse, Invert no visible effect, Puppet floating column clipped on RECORDING); ODD 6 (Mask Undo label, Lanes Above, Lanes Up/Below/Undo unverified, Puppet mode pills, Sprite next-keyframe, Effects blur entries); NOT TESTED: most of the mission (see list above).
