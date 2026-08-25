# SPEC — Horizontal preview reflow + PiP edge parking

**Written:** 2026-08-24 · JoyRaptor's request. All structural claims verified against the tree today.

JoyRaptor: *"when the transcript drawer comes out, if I'm working with a vertical video and there
is negative room on the sides, it just shoves all the way over to the left ... dramatically
less video is being covered up."*

Target user: someone cutting portrait (TikTok/Shorts) footage with the transcript open. Today
the drawer covers the video. It should slide the video into its own pillarbox slack instead.

---

## 0. THE RULE THAT MATTERS — read before writing a line

**TRANSLATE. NEVER SCALE.**

The vertical version of this feature (`reflowPreviewUnderDrawer`,
`FaditorEditorActivity.java:23611`) once scaled the preview container, and it broke placement
and text arrangement across the editor — JoyRaptor spent multiple AI sessions repairing it. Every
gesture surface computes hit geometry from UNSCALED bounds, so a scale silently invalidates all
of them at once.

The current implementation is translation-only and says so:

> *"Because nothing is scaled, no gesture surface needs scale compensation — but UiScale stays
> in the drag paths regardless: it is a no-op at scale 1 and it is what makes those surfaces
> correct if anything above them is ever scaled again."*

It even writes `.scaleX(1f).scaleY(1f)` defensively on every reflow, so a build that HAD scaled
cannot leave the container shrunk forever.

**Do the same here.** `translationX` only. Do not scale. Do not touch `UiScale`. If you find
yourself wanting a scale to make something fit, stop and report instead.

---

## 1. H1 · Shift the preview out from under the transcript drawer

**Model on:** `reflowPreviewUnderDrawer` (:23611). Read it first; mirror its shape exactly.

The vertical version computes:
- `slack = max(0, (slotH - videoH) / 2)` — the letterbox bar height
- `shift = min(drawerHeightPx / 2, slack)` — half the drawer, never past the picture edge
- animates `translationY(shift)` over 220ms decelerate, matching the drawer's own slide

The horizontal version is the same with the axis swapped:
- `slack = max(0, (slotW - videoW) / 2)` — the PILLARBOX bar width
- `shift = min(drawerWidthPx / 2, slack)`
- animate `translationX(-shift)` (drawer enters from the RIGHT, so the video moves LEFT)
- same 220ms decelerate, so drawer and video read as one movement

On a 16:9 project there is no pillarbox slack, so `shift` is 0 and nothing moves — exactly how
the vertical version no-ops on 9:16. That symmetry is the design, not a limitation.

### 1a. THE TRAP — the transcript drawer lives INSIDE the thing you are moving

Verified today with an XML parse of `activity_faditor_editor.xml`:

```
player_container       parents=[editor_root, FrameLayout]
transcript_panel       parents=[player_container, editor_root, FrameLayout]
transcript_reopen_tab  parents=[player_container, editor_root, FrameLayout]
```

The vertical drawers (FX / text / volume) are declared OUTSIDE `player_container`, which is why
translating the container slides the video out from under them. **The transcript panel is a
CHILD of `player_container`.** Translate the container and the drawer travels with it — the
video moves, the drawer follows, and the user gains nothing.

**Fix — counter-translate, do NOT reparent.** After shifting the container by `-shift`, apply
`+shift` to `transcript_panel` AND `transcript_reopen_tab` so they hold station. Two lines.

Reparenting the panel out to `editor_root` is the obvious-looking alternative and is REJECTED:
it changes z-order against every overlay declared after it, and the panel is referenced widely
in the activity. Counter-translation composes cleanly with the existing `translationY` and
changes no structure.

**Verify:** with a 9:16 project, open the transcript. The video slides left, the drawer holds
its position at the right edge, and text overlays remain draggable AT THEIR VISIBLE POSITIONS.
That last clause is the regression check — if hit-testing lags behind the picture, something
scaled.

### 1b. Reset path

`reflowPreviewUnderDrawer` resets to 0 when the drawer closes. Match it: closing the transcript
must return `translationX` to 0 on the container and on both counter-translated views. Also
reset on promote/demote — `PreviewPipController` already forces `translationY(0f)` on both
transitions (:262-264, :310-312); add `translationX(0f)` beside each so a popped-out preview
never inherits a stale horizontal shift.

---

## 2. H2 · PiP edge parking (left / right dock)

JoyRaptor: *"be able to drag the PIP over to the left hand side or the right hand side and have the
preview window then show up there ... the drawers would come in just over that."* Aimed at
tablets with wider aspect ratios.

Today `buildChrome`'s drag is free-position with `clampShellIntoRoot`, and position persists in
`lastPipTx` / `lastPipTy`.

**End state:** dragging the shell within a threshold of the left or right edge snaps it to a
docked position at that edge, vertically centred, and remembers which edge. A docked PiP is a
layout citizen: the transcript drawer opens over it rather than under it.

**Do H1 first and land it.** H2 depends on the same slack arithmetic and is worth nothing if
H1 is unstable.

---

## 3. Two existing bugs this work touches — report, do not silently fix

1. **The transcript drawer follows the preview into the pop-out.** Because it is a child of
   `player_container` (§1a), promoting reparents it into the floating shell — so opening the
   transcript while popped out renders it inside that small box. Code-verified, NOT
   device-confirmed. Report what you observe; do not redesign it inside this task.
2. **PiP content height ignores available room.** Device log, 2026-08-24:
   `PROMOTED preview → PiP (604x1073px, slot was 103dp)`. Width is a fixed fraction of the root
   and height just follows the video's aspect, capped only by `PIP_MAX_HEIGHT_FRACTION`. On a
   portrait video in landscape the shell runs off the bottom of the screen. Separate fix.

---

## 4. Working rules

**Build:** LANES.md rule 6 — never run gradle. Save and read `build.log` for a fresh
`BUILD SUCCESSFUL`, and confirm its mtime is newer than your last edit.

**Device:** `adb shell input swipe` CANNOT drive the G6 grab bar or the PiP drag — the handlers
need a real finger's MOVE cadence. Verification of any drag behaviour must be handed to JoyRaptor
with a specific one-gesture instruction. Do not claim device-verified for anything you drove
with `input`.

**Claim a lane** (rule 1) for `FaditorEditorActivity.java`,
`player/PreviewPipController.java`, and `res/layout/activity_faditor_editor.xml` before your
first edit, and `git add` each file the moment you touch it (WORKING-TREE HAZARD).

**Regression checklist — run every one before reporting done:**
- Text overlay drag lands where the finger is, transcript open AND closed
- Sprite and PiP-video layers likewise
- Crop overlay handles align with the picture
- Caption style bar and safe-zone overlay still register
- 16:9 project: opening the transcript moves nothing (shift clamps to 0)
- Promote → demote → open transcript: no stale offset
