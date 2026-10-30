# STU-1: Studio phone-test backlog

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** open (a careful phone agent; Sonnet is enough)  ·  **Checked:** 2026-10-27 against STATE §2

## Why it matters
Studio is the strongest wing, and the owner is happy with it. These are the features that are built but
nobody has driven on a phone. Built-but-unproven is where one-star reviews come from.

## Done when
- [ ] Each item below is driven on the Note 9 (sandbox), the result written here, and its STATE row
      moved to 🟢 (agent) or fixed.

## What is left (each was 🟡 in STATE on 10-27)
- [x] **Undo after closing and reopening:** quick edits keep their own steps after reopening, and a crash no **Agent-checked 10-28 on the Note 9: passes.**
      longer leaves the undo history behind the project (`b2a85dff7`). Test project "Redo test clip": three fast
      rotates, reopen, expect three steps; two edits, wait for autosave, force-stop, reopen, expect a matching history.
- [x] **Adjustment layers really change the layers beneath them**: agent phone-tested 10-27 by the VisLab
  lead on the Note 9 (an effect layer's Flash washes the clip beneath in the preview (visualisers, pictures, text and sprites sit above effect layers in both preview and export);
  Echo Trails and a music-linked Flash in exported videos). Fixed on the way: an export froze a layer's
  keyframed or music-linked values at the first frame. Keyframes themselves not re-tried on the phone;
  owner has not checked.
- [ ] **Bend on text** and **corner-pinned text with an effect** (export must match the preview).
- [ ] **Corner pin on sprites**, and sprites on the transform surface: nobody has dragged one on a phone.
- [x] **Single-frame export** (JPG/PNG): agent-checked 10-28, both formats land in the folder.
- [ ] **Audio ducking:** "Duck under…" from the lane menu, then export and listen. 10-28: on music-only lanes it says "no speech found" (by design); a voice lane is needed for a real test. The fade-in readout showed 24.41 s for a short drag: check.
- [ ] **VisLab in Studio:** the effects and Echo Trails were agent-tested 10-27; the owner wants to look (S12).
- [ ] **Transitions:** the owner wants to check them himself (S12). Prepare a test project with every transition type. 10-28: test project "Untitled audio 5" on the Note 9 has 9 transitions (Glitch, Push Left, Mirror Wipe, Wipe Left, Fade to White, Fade to Black, Crossfade, Radial, TV Channel); the 47 shader ones under "More effects" not yet placed.

## Specs, handoffs and evidence
- [STATE.md §2](../STATE.md) rows; undo detail in LANES history of the "Studio undo history drops steps after export" session (account C, 10-24).

## Files and traps
- Use throwaway projects only ("Restore video test", "Redo test clip"); never the owner's real projects.
- Phone lock and no `monkey`: see [AGENT_START.md](../AGENT_START.md).
