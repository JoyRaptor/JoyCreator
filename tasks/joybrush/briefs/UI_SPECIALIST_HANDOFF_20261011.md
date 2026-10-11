# Handoff: Joy Brush Select UI specialist (JB-5.20e), 2026-10-11

Paste this into a new session to continue the UI specialist's work. Read `briefs/UI_SPECIALIST_BRIEF.md` first: it is
the standing brief (status **GO for both phases**, lead 2026-10-11). This file says where the work stopped.

## Where things are

- **Worktree:** `C:/Temp/jb-select`, branch `ui/select-tool` (from `origin/joy-creator` at 7db941d3). Pushed to
  `origin/ui/select-tool`. Never work in the owner's main folder.
- **Phase 1 deliverable 1, the interaction spec:** `tasks/joybrush/specs/JB-5.20e_select_ui.md`. Written. 24 decisions,
  the Concepts mapping with file:line, hooks H1–H8 for the lead, build slices e1–e3 with tests, owner checks.
  §K (the owner's picks) is empty until he replies.
- **Phase 1 deliverable 2, the owner's options page:** `tasks/design/UI-Select-options.html`. Written, with a working
  true-size mockup (the live phone), three directions (Concepts recommended, Thumb, Quiet), nine scenes, ten parts plus
  JB-5.20's two open questions (Q1 Flatten Lower, Q2 Paint/Airbrush default), a "Copy picks" line, a compare table, and
  the recommendation. The script passes `node --check`. **It has not yet been looked at in a browser**: that is the
  next step (see below).
- `tasks/joybrush/research/concepts-app-interaction-reference.md` was untracked in the owner's main folder; it is now
  committed on this branch so the spec's `CR:nnn` references resolve. When this branch is merged into the main folder,
  git will refuse while the untracked copy sits there: the lead deletes the untracked copy first (same content).

## Next steps, in order

1. **Look at the page in a browser and fix what is broken.** Open `file:///C:/Temp/jb-select/tasks/design/UI-Select-options.html`
   in the browser pane. Check the console for errors. Try every numbered item in "Try it" with the mouse (it acts as
   the S Pen; right-drag is the S Pen button) and with "Mouse is: Finger". Check every option in "Mix and match" changes
   the live phone. Check the scenes render (they are static `Phone` instances with a `setup`). Check it at 411 px wide.
2. **Publish it as an Artifact** (load the `artifact-design` skill first; the file already follows its contract: own
   `<title>`, single dark look on purpose, Google Fonts only). Give the owner the link. He picks on his Note 9 with the
   S Pen; the page's pointer code treats `pointerType === "pen"` as the pen and `buttons & 2` as the S Pen button.
   Note for him: if the S Pen button does nothing in the browser, the Note 9's Chrome may not report it; right-drag on a
   computer shows the same behaviour.
3. **Tell the lead** (Claude session "Joybrush orchestration and completion", via SendMessage, or a dated heading in
   `tasks/joybrush/AGENT_BOARD.md`): spec and page are up for review; hooks H1–H8 in the spec's §H are what phase 2
   needs from them; H6 is two default-preserving flags in `:studiokit`'s `TransformOverlayView`.
4. **When the owner sends his picks line**, write it verbatim into the spec's §K with his words, resolve each "§K-Pn"
   reference, and update `D3` to say taps pick on the active layer only (the mockup does this; the spec does not say it).
5. **Phase 2 (code)** starts only after §K is locked: slice e1 (core `LineLasso`, `LineTransform`, `StrokePicker.rank`,
   `SelectionState`, JVM tests), then e2 (androidkit `select/`: `SelectTool : CanvasTool`, `SelectBoxHost`), then e3
   (joybrush-android `select/`). One Gradle at a time through `jb-gradle.lock`. Never install on the phone.

## Design decisions worth knowing before you touch anything

- **Nudge means two things in this repo.** The owner's word "nudge" (and Concepts') is bending part of a line inside a
  ring. The code's `core/tool/Nudge.kt` is a 1 or 10 screen-px step move. The spec keeps the code name for the step move
  (keyboard arrows only) and uses "Nudge" on screen for the bend. Do not merge them.
- **The box is the Studio's** (`studiokit/.../transform/TransformOverlayView.java`, owner ruling R16, signed off over six
  rounds as TRANSFORM_UI_FEEL V6). Its handles, colours, rotate arc 46dp above the top edge, snapping and long-press role
  ring are reused unchanged. Only two flags are needed (H6).
- **Lines must keep "all the pressure, velocity, and tilt"** through transforms: `LineTransform` divides
  `StrokeRecord.screenPerDoc` by the scale (smoothing and speed are screen-relative, `InkReplay.kt:134`), rotates and
  mirrors `PenSample.azimuth` and `barrel`, and scales `widthScale` only when weights scale. Tests are in the spec.
- **Facts about the live code** (verified 2026-10-11): `JbCanvasView.tool` exists but nothing sets it; `penSeen` is
  private; the tool is offered every DOWN before palm rejection; `ACTION_POINTER_DOWN` is never offered despite the
  `CanvasTool` doc; the S Pen button's hold action (lasso) is not wired; `InkEditSession` still has its own 50-step undo;
  no code decides which lines a loop encloses, and none moves/scales/rotates a line. All of these are in the spec.

## Known gaps in the mockup (fine for a pilot, listed so nobody mistakes them for the design)

- Lines are drawn more simply than Joy Brush draws them. The Studio role ring, Tilt, Free and Bend appear in a scene
  only, not in the live phone. Two-finger rotate of the page is not built (pinch zoom and pan are).
- Home, Guides, Pin, More, the strip grip, Opacity and most layer-panel rows say "not part of this mockup".
