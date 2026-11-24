# PREVIEW-1: a tiny preview beside every part, wherever one is listed or picked

**Milestone:** M1 (polish)  ·  **State:** 🔨 card made, inventory started  ·  **Owner:** website lane (Sonnet), build queue job 7  ·  **Checked:** 2026-11-23 against `joy-creator`

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
