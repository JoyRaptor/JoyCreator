# STU-4: Studio sweep leftovers

**Milestone:** M1  ·  **State:** 🔨 re-checked 10-28 (code scout + Note 9 agent pass 4): most items already fixed; three new bugs found  ·  **Owner:** lead  ·  **Checked:** 2026-10-28

## Why it matters
Small rough edges the device sweeps found. Each one is the kind of thing that makes an app feel unfinished.

## Done when
- [ ] Each item re-checked on the phone: fixed already (write the commit) or fixed now, or written up as a
      defect in STATE §9.

## Re-check 10-28 (screens in `C:/Temp/qa-sweep-20261028/`)
- ✅ Caption/text motion: Type → Fade → back changes the label, the tile and the preview (not stuck). The label is cut off ("Type · 25% in / 0% out …").
- ✅ Hide eye hides the text; the Outline swatch opens the picker and the outline shows (tested on a Text overlay; a caption not tried).
- ✅ Invert on a picture shows in the preview (it acts as an adjustment layer: everything beneath inverts).
- ✅ `OverlayNavUtil` child-overlay close (7ffd27e4b, 10-16).
- ✅ Sprite bend net sits on the sprite.
- ✅ Colour picker hex is one line (d1655f38a); "#FF000B" touches the column edge (report only).
- ❓ Not reachable by the agent: puppet RECORDING tab (no Puppet tab found from Studio drawers), sprite Lanes tab, audio envelope (see the audio bug).
- ✅ Add → Audio with a real song (`vislab_demo.m4a`) works (the WAV failure was that test file). **Found on the way and fixed 10-28:** Fix audio never baked on a phone (file name from the raw URI, then no output format); now passes.
- ✅ Undo of a sprite Bend leaves it crisp again (fabedb713, agent-checked 10-28).
- 🔴 **Open:** after a Fix audio cache hit the waveform keeps the unfixed look; Clean acts on the video clip because selecting an audio clip leaves the video clip selected too; the editor title's tap does not open Rename (hold does).
- 🔴 **New:** the toolbar's last button "Edit" toggles to "Done" with no visible effect; after closing a text drawer, tapping the clip or preview doesn't reopen it (hold does, since 10-28).

## What is left (from `tasks/todo.md` on 10-16/17; may already be fixed)
- [ ] Caption motion "Type – word" stuck.
- [ ] Caption hide-eye and outline swatch show no visible response.
- [ ] Invert effect not visible on an image overlay.
- [ ] Puppet RECORDING tab button column clipped.
- [ ] Sprite Lanes tab pills sit over the preview edge.
- [ ] Audio envelope Clear / In oddities.
- [ ] `OverlayNavUtil.dismiss` hides the whole overlay but pops one level (Library → Trash close path).
      The 10-17 UI handoff says this was fixed for child overlays: confirm.
- [ ] Sprite warp net looks detached from the sprite (owner report 10-15).
- [ ] Drawer helper duplicates (top 10 in `tasks/audits/studio_tools_audit.md`).
- [ ] Colour picker hex field wraps on the phone. **The owner said do not touch the colour helper**: report only.

## Files and traps
- Check git log for each before fixing; several were worked on after the list was written.
