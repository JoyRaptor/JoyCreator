# BOT-4: Joybot's new reach: everything a person can do, across the wings

**Milestone:** M1 (the parts the owner wants before launch) and after launch  ·  **State:** ⬜  ·  **Owner:** open  ·  **Checked:** 2026-10-28 against `fbfa50ce4`

## Why it matters (for the user)
The goal is "anything you can do in Joy Creator, Joybot can do with you", in any wing, and whole jobs that
cross wings (a sprite sheet → an animation → placed in the video). Each line below is one thing a person can
do today that Joybot cannot.

## Done when
- [ ] Each line is built and phone-proven, or the owner parks it.

## Where it stands (verified 10-28)
Studio: broad typed actions (text look, transforms, looks, audio incl. ducking, organise, transitions,
captions, sources, project care, export card, motion, sprites on video). App: rooms, Library, Trash, new
project from a recording. VisLab: looks, knobs, layers, brand, files, floating visualiser, blend on
visualizers. JoyBrush (a94933ad7): drawings, describe, layers, frames, brush and colour, simple shapes.
SpriteLab: grid, names, animations, sequences.

## What is left (rough order: owner value first)
- [ ] **Naming clips and layers** ("Cat 1", "Cat 2"): designed ([NAMING_DESIGN](../joybot/NAMING_DESIGN_20261014.md)),
      only `rename_clip` / `rename_asset` exist. Owner-requested.
- [ ] **Capture**: start, pause, stop a recording; webcam bubble; floating visualiser with it. Android
      permission screens stay the person's tap.
- [ ] **JoyBrush, deeper**: Select (move, scale, recolour a selection), symmetry, paper, undo/redo by voice,
      export a drawing or board, Send to Studio. With the JoyBrush lead.
- [ ] **Cross-wing jobs** (F6): sprite sheet → animation → placed in the video; a JoyBrush region → picture
      job → back as a layer; a VisLab look → a Studio visualizer.
- [ ] **Pictures**: placed reference images to picture models (canvas half: JoyBrush,
      [brief](../joybrush/briefs/PLACED_IMAGES_JOYBOT_20261014.md)); a free "design a picture" (HTML/SVG) with no
      paid model; picture jobs keep the revision they started from (M5).
- [ ] **SpriteLab, deeper**: pivots / auto-centre, background key and bake, dope sheet, viseme labels, export.
- [ ] **Avatar**: pose matrix, mic lip-sync, the .avatar library (Avatar is hidden for launch).
- [ ] **Recipes with triggers** ("every new recording: transcribe, cut ums, captions, export a draft").
- [ ] **Model routing per step** (a cheap model for simple steps, a seeing one for `look`, a strong one for plans).
- [ ] **Before/after thumbnails** on a result card (M6).
- [ ] **A Library-wide B-roll index** (tag every asset once, cached) (M8).
- [ ] After launch: video generation; semantic cells; lecture mode (see ROADMAP "After launch").

## Specs, handoffs and evidence
- [Feature gaps](../joybot/FEATURE_GAPS_20261015.md) (F6, F7, F9, M2, M5, M6, M8 and the wing tables),
  [wings inventory](../joybot/WINGS_INVENTORY_20261014.md), [action family brief](../joybot/ACTION_FAMILY_BRIEF_20261015.md)
  (how to add a family: one file + one line).

## Files and traps
- New families: one file in `ai/actions/` + one `add(...)` line; app-wide ones in `AppActions.build()`.
  One request = one undo (use `AIChatState.liveWriteRequest` as the group). Deletes ask first and take only the
  person's own yes (`PersonSaid`). Wing code belongs to its lead: agree the hook first.
