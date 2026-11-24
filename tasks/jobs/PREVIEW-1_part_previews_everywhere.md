# PREVIEW-1: a tiny preview beside every part, wherever one is listed or picked

**Milestone:** M1 (polish)  ·  **State:** 🔨 first four lists done, rest queued  ·  **Owner:** website lane (Sonnet), build queue job 7  ·  **Checked:** 2026-11-23 against `joy-creator`

## Why it matters (for the user)
The owner, 11-20, testing Avatar Studio: layer and part names from an artist's file are often unclear, so he orients by sight, not by
name. "It doesn't have to be big, just a small icon size... This should be something across the whole app. So anytime that I'm
picking a part, it should show me a little tiny preview of the part." A plain list of names is not acceptable.

## Done when
- [ ] Every list of layers, parts, pieces, looks or sprites, and every picker of one (masks, carried-by, pieces, looks), shows a small
      thumbnail to the left of the name, in every wing.
- [ ] A plain drop-down of part names anywhere becomes a sheet or chips with previews.
- [ ] A host check or test names each place so a new list cannot ship without a preview.
- [ ] STATE row (proof level, entry points); Joybot help: not needed (a look, not a feature).
- [ ] Looked at on the Note 9, one screenshot per room (the owner has not seen any of this).

## Where it stands (first look 11-23, to be verified room by room)
- **Avatar Studio web pilot:** the owner's source of the rule; `cards.mini(node, px)` and `cards.pickPart(...)` exist in `tools/avatarlab`.
  The phone room (AVA-1 slice 1) is a skeleton with no part lists yet: build its lists with previews from the start.
- **Studio timeline and layer rows:** `ui/faditor/layers/LayerRowRenderer` already draws thumbnails; check every kind (text, effect,
  adjustment, sprite, visualiser, PiP) really has one and not a blank square.
- **JoyBrush:** the layer column draws layer thumbnails (re-drawn after a stroke, `JoyBrushActivity`); board lists use
  `BoardRuntimeController.thumbnails`. Check the pickers: merge, mask target, copy-to-layer.
- **Studio pickers that name a layer or part** (`ObjectMenuSheet`, mask / carried-by choices, `TextAnimPickerPopover`, effect and look pickers):
  suspected plain lists; the effect picker's swatches are STU-6.
- **SpriteLab** cell and animation lists (`SpriteSheetEditorActivity`, `SequenceImportDialog`, `JoyBrushBoardImportActivity`): check for cell thumbnails.
- **VisLab and TextLab:** parts and looks lists; TextLab looks already have WebP previews (T14-T17); VisLab parts (`KnobPanel`, room parts library): check.
- **Library, Space and asset browser** already show thumbnails (`AssetBrowserAdapter`, `RecordsAdapter`); not part of this sweep unless a list lacks one.

## What is left
- [ ] Inventory: for each room, list every list or picker of parts, and mark has-preview, blank, or name-only. Put the table here.
- [ ] One shared helper (a small view or `SheetKit` row builder) so every place draws its preview the same way; reuse what Studio already has.
- [ ] Convert the name-only places, smallest first; one commit per room.
- [ ] Host check, then a cloud build and a Note 9 look (phone work belongs to the end of the job; no phone attached means leave it written up as owed).

## Specs, handoffs and evidence
- Owner rule 11-20 (memory `part-previews-everywhere`), related rule: every button gets a hover label ([HOVER-1](HOVER-1_hover_labels.md)).
- Queue: [BUILD_QUEUE](../BUILD_QUEUE.md) row 7.

## Files and traps
- Other lanes hold Avatar (`tools/avatarlab/**`, the avatar room), JoyBrush engine files and Studio's motion-blur files (BOARD): ask in the channel before touching.
- Thumbnails must be cheap: draw once, cache, never decode full-size media on the UI thread; keep the Note 9 smooth.

## 11-23 inventory (read-only scan of every room; file:line approximate) and first build

**Already have a preview:** Studio effect picker and effect cards (GL swatches), ease curves, text animation presets, image presets,
visualiser style carousel, lane items (video, image, sprite; **text lanes have none**), sprite palette chips, fonts (drawn in their own
face), SpriteLab cell film strip and animation presets, TextLab look chips, JoyBrush layer column, brush drawer, paper sheet, board frames.

**Name-only, ranked (the work list):**
1. Studio sprite sheet manager (`FaditorEditorActivity` ~43631): **done in this build.**
2. Avatar Studio part chips (`AvatarStudioActivity` `rebuildPartChips`): **done in this build.** Still name-only: add-part sheet list (~522), parent picker (~575), library avatars (~194) and the avatar manager (`FaditorEditorActivity` ~44513, wants the neutral bake).
3. VisLab sprite sheet chooser (`LookSheets.chooseSheet`): **done in this build.**
4. VisLab TextLab look chooser (`LookSheets.chooseTextLook`): **done in this build** (first frame from the TextLab preview cache).
5. VisLab look strip and Studio's VisLab style chips (`room/LookStrip`, `VisLabStyleTab`): colour dots only; want `StudioVis.previewFrame`. Not started.
6. VisLab parts shelf and layer rows (`KnobPanel` ~476, ~296): pills only. Not started.
7. Transition type and GL transition pickers (`FaditorEditorActivity` ~43388, ~43454): 26 transitions by name and category; wants a swatch like `FxSwatchRenderer`. Not started.
8. LUT and grade look pickers (`FilterBottomSheet`, `LutLibraryPanel`), built-in text styles (`TextStyleLibraryPanel`): not started.
9. Reactive and duck-under pickers (`FaditorEditorActivity` ~17469, ~17338): clip thumbnails exist in `LayerRowRenderer` providers. Not started.
10. JoyBrush Unibrush tip textures (`UnibrushHostController.kt` ~273): packaged grain PNGs exist; in JoyBrush's files, so left to its lane. Also text lanes in the Studio timeline, SpriteLab "other sheets" list (deliberately name-only), mask shape chips, blend modes, puppet pin chips, Avatar room stage tabs.

**Built (this branch, `claude/website-f86389`):** `ui/faditor/PartThumbs` (one place that draws the small square, 36 dp, cached, off the main
thread, blank placeholder so rows do not jump, stale rows ignored; `sheetCell`, `square`, `startOf`, `onRow`), used in four places above. Not
yet a host check naming each list. Cloud build run 45303840002 (defaultDebug, 11-23): green; the APK download was cut off, no phone attached. Not looked at on a phone.

**11-23 later:** the avatar library, add-part and parent pickers got previews too (`PartThumbs.listAdapter`), plus the host check
`tools/check_part_previews.py` (done files must keep using `PartThumbs`; the owed list is in the script and only shrinks). Cloud build
run 45309374777 on `0543b0c06`: green. Still owed: everything under "Not started" above, and one look on the Note 9 per room.

**11-23 night:** built-in text styles (drawn on "Aa") and the built-in transition type picker (a schematic glyph per type, `TransitionGlyphs`; the current type gets a tick) now have previews. Cloud build run 45313181581 on `0b74b1ad4`: green. **Left, with the reason each stays owed:** the 26 GL transitions (need a GPU render, like the effect swatches); LUT and grade pickers (need the `.cube` grade applied to a sample frame on the CPU; `LutManager.loadBitmap` layout untested, so not guessed); VisLab look strip and style chips, parts shelf and layer rows (`StudioVis` renders are GL-bound and keyed per item; the strip already shows five palette dots); reactive and duck-under pickers and text lanes (clip thumbnail providers live inside `EditorTimelineView`); Unibrush tips (JoyBrush's files); Avatar room stage tabs and the Studio avatar manager (the new room is another lane's). Phone look per room: owed (the Note 9 is held by the hover-labels lane).
