# Competitor UX Research — KineMaster + mobile-editor conventions (v1, 2026-07-04)

> Written INLINE by the orchestrator (Fable) from the user's three KineMaster screenshots + domain
> knowledge, after three parallel-research attempts were killed by usage caps with zero output.
> PROCESS RULE (user, binding): research is single-threaded and written to THIS doc incrementally —
> append per finding, commit often. Items marked ⚠️VERIFY need a small web check when budget allows;
> everything else is directly observed from the screenshots or high-confidence mechanics.
> Feeds: tasks/FEEDBACK_20260703_dragux_v3.md (§A/§C), sprite S2 design gate, drag-UX v3 design pass.

## 1. What KineMaster does (observed from the user's screenshots + known mechanics)

**Playhead & time**
- Full-height playhead line through preview divider, ruler, AND every track lane (screenshot 1/2).
- Time chip rides ON the playhead at the ruler (e.g. `00:07.920`), millisecond precision; total
  duration chip sits right-aligned (`00:41.978`). Chip is the visual anchor for "exactness."
- Playhead turns PURPLE when parked exactly on a bookmark; red otherwise (screenshots 1 vs 2).
- With a layer selected, the playhead segment crossing that layer is emphasized (dotted region) =
  "a split here applies to THIS item." Split scope is always visually unambiguous.

**Bookmarks**
- Tap the playhead/time chip drops a bookmark at that time ⚠️VERIFY exact trigger (tap chip vs tap
  head). Bookmarks render as small purple tabs on the ruler (screenshot 1 shows several).
- Long-press the chip/bookmark → white options sheet: "Remove All Bookmarks", a LIST of bookmark
  times (tap = jump; current one check-marked), "Remove Bookmark" (screenshot 1). So the bookmark
  list doubles as a jump-to menu — cheap navigation for long projects.

**Keyframes & curves**
- Transport row left cluster (screenshot 2): layer-expand toggle, add-keyframe diamond (+), curve
  ("Graphs") button. Add-keyframe flips to remove-keyframe when the playhead sits on a keyframe
  ⚠️VERIFY flip behavior.
- "Graphs" opens a PRESET GALLERY (screenshot 3): ~18 curve thumbnails in a grid — none/linear,
  adjust(custom?), ease families (in/out/in-out at several strengths), S-curves, steps, bounce/
  oscillate shapes. Selection = tap a tile (red highlight). NO freeform bezier editor on phone —
  presets-only is the mobile-correct call (bezier handles are misery at finger size).
- Easing appears applied per-keyframe/segment via the gallery; our KeyframeSet already has Easing
  math — gap is purely UI surfacing.

**Timeline modes**
- A full-screen-timeline expand control: timeline fills the display; the PREVIEW becomes a floating
  draggable PiP window parked wherever the finger isn't. Purpose-built for layer-heavy sessions.
  ⚠️VERIFY the exact control location/behavior.
- Layer lanes render BELOW the master filmstrip (KineMaster convention) — note: OUR stacking is the
  opposite (layers above master, audio below) per the user's physical-stacking decision; do NOT copy
  KM's lane order, only its affordances.

**Misc observed**
- Selected clip gets a bright yellow border + trim handles (screenshot 1/2, dino layer).
- Tool row is CONTEXTUAL: with a layer selected it shows Delete/Split/Volume/Animation/Speed/
  SpeedCurve/Reverse/SplitScreen — per-selection tool filtering, similar to our dynamic carousel.

## 2. Cross-editor conventions (high-confidence domain knowledge; ⚠️VERIFY tolerances where noted)
- **Magnetic snap targets** (CapCut/VN/LumaFusion): playhead, clip edges on any track, markers.
  Feedback = thin vertical guide line + haptic tick at the snap moment; snap disengages with a
  deliberate pull-away. Effective tolerance is SMALL — order of 6–10dp, not 25dp+ ⚠️VERIFY per-app.
  CapCut ships a toggleable magnet; LumaFusion has an explicit snapping toggle in the timeline bar.
- **Edge auto-scroll while dragging** is universal (CapCut/VN/LumaFusion): holding a clip near either
  screen edge pans the timeline continuously, speed scaling with edge proximity.
- **Vertical vs horizontal drag disambiguation**: dominant-axis lock at gesture start; vertical move
  between tracks preserves the clip's time position unless the finger clearly moves diagonally —
  exactly the user's requested guardrail. Nobody shows explicit "time-locked" guide lines — the
  user's 1px dotted-verticals idea is BETTER feedback than the market standard.
- **Same-track overlap**: mobile editors REJECT overlap on the main track (snap-back or push);
  overlaps only exist as explicit PiP layers. Matches our A8 fix.
- **Value callouts while dragging**: LumaFusion shows a time readout while trimming; CapCut shows
  duration deltas near the handle. Time+frame pill (user's B11) is professional-grade and cheap.
- **Minimap drag-navigation** (user's A2): NO mainstream mobile editor does drag-onto-minimap
  view-teleport while holding an item. This would be a genuine differentiator.

## 3. ADOPT / ADAPT / SKIP for Joy Creator
| Item | Verdict | Effort | Lands in |
|---|---|---|---|
| Full-height playhead + split-scope emphasis on selected item (C12) | ADOPT | S | EditorTimelineView/LayerRowRenderer |
| ms-precision time chip on playhead (C13) | ADOPT (have partial) | S | playhead chip |
| Bookmarks + long-press list-as-jump-menu (C14) | ADOPT, KM's list sheet is the right shape | S/M | ruler + small sheet |
| Add/remove-keyframe flip button + Graphs-style preset gallery (C15) | ADOPT presets-only; NO bezier editor | M | KeyframeSet/Easing + new gallery sheet |
| Full-screen timeline + floating PiP preview (C16) | ADOPT (own milestone) | M/L | editor chrome |
| KM lane order (layers below master) | SKIP — our stacking decision stands | — | — |
| Snap tolerance 6–10dp + haptic tick + guide line (A4) | ADOPT | S | one dp constant + guide draw |
| Edge auto-pan proximity-scaled (A1) | ADOPT | M | row-gesture system |
| Dominant-axis lock + user's dotted time-lock verticals (A9) | ADOPT (ours is better than market) | M | LayerGestureController |
| Free placement + gentle butting snap (A3) | ADOPT | M | drop logic |
| Minimap drag-nav (A2) | ADOPT — differentiator, nobody has it | M | minimap + drag handoff |
| Trim time+frame pill callout (B11) | ADOPT | S | trim overlay |
| Contextual tool filtering | ALREADY HAVE (carousel dynamic state) | — | — |

## 4. Interaction mapping recommendation (C17 — resolves the tap conflict)
- TAP time chip → jump-to-time entry + quick nav (move tool / layers) — the "exacting" cluster.
- TAP the playhead line (or DOUBLE-TAP the chip) → drop/remove bookmark; chip tints when parked on one (KM's purple cue — adopt).
- LONG-PRESS chip or a bookmark tab → KM-style sheet: bookmark list (tap=jump), remove, remove-all.
This keeps numeric actions on the chip, marking actions on the line, and gives long-projects a free jump menu.

## 5. Open ⚠️VERIFY list (small web bites when budget allows — append findings BELOW, incrementally)
1. KM bookmark exact trigger (tap chip vs head) + whether bookmarks have names/colors.
2. KM add-keyframe flip behavior + whether Graphs applies per-keyframe or per-segment.
3. Actual snap tolerances/toggles in CapCut + LumaFusion (settings screens).
4. KM full-screen-timeline control location + PiP preview constraints.
