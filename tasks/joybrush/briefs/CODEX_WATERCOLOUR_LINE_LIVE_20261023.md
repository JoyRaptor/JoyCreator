# Codex brief: the watercolour line brush, live on the phone (vector specialist, 2026-10-23)

**Goal (owner):** he draws with the watercolour brush as EDITABLE LINES on the Note 9, moves, recolours and rebrushes them,
saves, reopens, and it looks like his approved proof of concept (`C:\Users\JoyRaptor\Downloads\Watercolor-POC`; plan
`specs/WATERCOLOUR_LINE_BRUSH_PLAN_20261012.md`).

**Already on main (Codex, 10-13 to 10-16):** `core/brush/WatercolourDabber`, `androidkit/gl/watercolour/*` (dab array,
finish, pooling, commit), `NativeLines` + `lines/LineAdmission` admit watercolour lines, `GlWatercolourLineRenderer`, the
Select tool on the canvas (fa1285c93), the tile-board wrap (0816ec49e), and the drawer brush `brushes/watercolour`.
**Never accepted** (`reviews/NOTE9_WATERCOLOUR_SMOKE_20261013.md`): a 1.5 s cold first stroke, and no check of edits,
save/reopen, real pen tilt, the full 1 s fade or the owner's look.

**Lanes and rules:**
- Engine Lead = `LEAD_PLAN_20261023.md`. Send the lead your branch name before touching GlPaintEngine, JbCanvasView or
  JoyBrushActivity; the lead merges.
- Note 9 = sandbox: `install -r` freely; never uninstall or clear data. Note 20 only when the owner asks.
- Phone lock `$LOCALAPPDATA/Temp/jb-phone.lock`; Gradle lock `jb-gradle.lock`, one build at a time.
- Launch with `am start -n`, never `monkey` (it unlocks his rotation).
- Log phone temperature during phone work.
- Never resolve a merge conflict; re-apply on the new base.
- Every step ends with evidence in `tasks/joybrush/reviews/` and a one-line note to the lead.

| # | Step | Done when (evidence) |
|---|---|---|
| L1 | **Measure first.** Note 9, editable lines ON and OFF: cold first stroke, stroke frames (p50/p95), input latency, and lift-to-final INCLUDING the 1 s pooling fade, with the lead's FrameStats (glFinish-bracketed). 5 runs each | A numbers table in a review file; no fixes in this step |
| L2 | **No cold stall.** Prepare the watercolour programs, dab array and backdrop when the brush is CHOSEN (or at canvas start), not at the first stroke. The media prewarm ON MAIN is b00b82667 (it starts on every canvas surface; 797c6f4d7 reverted an earlier form). First measure what main already does at canvas start, then add only what is still missing | First stroke within one frame of a warm stroke, over 5 cold launches |
| L3 | **Editable lines reachable.** Today it is a per-drawing More-menu switch, default off (`joybrush.editableLines`). The lead recommends a per-brush "makes lines" switch (JB-5.20 D6), not a global ON: a layer holding lines refuses realistic pencil, watercolour and oil today. Build the owner's pick, relayed by the lead | He draws a watercolour line with no menu trip |
| L4 | **Edit it on the phone.** Select, then move, rotate, recolour (re-finish only, no re-dab), rebrush, delete; one undo each. **The wash must travel with the line:** today `WatercolourDabber.backdropOffsetX/Y` is seeded per stroke but world-anchored (`WatercolourPass.kt:62`), so a moved line's wash "swims". Store the line's wash frame (origin and rotation) and update it in the select transform (`core/select/LineTransform`); Concepts behaves this way (R12) | Phone screenshots of each edit, and a JVM test that a moved line's finish equals the old finish moved |
| L5 | **Save and reopen.** Save, force-stop, reopen, then the first edit. Context loss. Round-trip tests with the brush library absent | Reopen shows identical pixels (hash) and the line still edits |
| L6 | **Look parity.** `tools/watercolour_check.js` against the POC's three sample paths (seeds 78234–78236), then the owner compares side by side on the Note 9 | Owner says yes, or lists changes |
| L7 | **Speed.** Stroke frames within 16.7 ms p95 on the Note 9 at size 300 / spacing 15%. If not: half-resolution live finish with full resolution at lift, then larger spacing at size (plan §2 W5) | L1's table re-run, before and after |

**Coordination with the UI specialist (`briefs/CODEX_SELECT_LINES_20261023.md`, branch `ui/select-accept`):**
- L3 is the same job as their G1. Their step A10 (the "bakes to pixels" badge and toasts) builds on L3, so land L3 first.
- L4 constraint: keep `LineTransform.apply(r, h, Weight)` **source-compatible** (SelectTool and the drag preview call it).
  Add the wash frame as RECORD DATA that the transform updates, so Select needs no change.
- Their Ring Bend (G12) waits for L4's wash frame.
- Their brief stays out of `core/select/LineTransform.kt`, `gl/watercolour/*`, `WatercolourDabber` and the
  editable-lines admission gate. Those are yours for L3 and L4.

Size guide: L1, L3, L5 and L6 are small to medium; L2 and L4 are medium; L7 depends on L1. Order: L1 → L2 → L3 → L4 → L5
→ L6 → L7.

**Shares a gate with the Unibrush:** its U3–U5 (`specs/UNIBRUSH_FINISH_20261023.md`) carry user-loaded tip images through
NativeLines, save and replay. Watercolour dabs are SHIPPED assets, referenced by name, so L5 doesn't need U3–U5. But if the
owner ever loads his own watercolour dabs (R12: his photographed samples), they ride the same U3–U5 path: build it once
for both.
