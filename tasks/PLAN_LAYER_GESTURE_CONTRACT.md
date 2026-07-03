# PLAN — Layer-row gesture contract redesign (2026-07-03)
From live device flight-recorder (ROWGESTURE log, build 23930bb) + user hand-test. The M10 drag MECHANICS
work (log shows real onItemDroppedOnNewLayer + onItemMovedToTrack commits) — but the interaction model is
wrong and the drop target is off-screen, so the feature is unusable in practice.

## CONFIRMED ROOT CAUSES (from the log, not hypothesis)
1. **New-layer drop zone renders OFF-SCREEN.** `ZONE laid out: ... zoneInViewport=false | SCREEN-y[1021..1099]`
   on a ~924px-tall view (band starts topPx=714, wants 367px, only ~210px to screen bottom → overflows below
   the physical screen). User can't see the "+ New layer" strip they're supposed to drop onto.
2. **Every DOWN on a row item immediately arms MOVE** (`ROUTE DOWN -> item gesture (m7ItemGestureActive=true)`).
   So a horizontal swipe over an item = nudging the item a few ms (`kind=MOVE dx=-6`), NOT scrubbing. Items
   fill most of the row → the row feels "stuck / stifled," unlike the main timeline. Holding still to aim a
   move fires the DELETE dialog instead (`LONG-PRESS FIRED`).

## TARGET CONTRACT (industry-standard: swipe=scrub, hold=grab) — makes layers "easy, stable, intuitive"
On a layer-row item:
- **Horizontal-dominant drag = SCRUB the timeline** (pass through, identical feel to the main timeline),
  EVEN when the finger starts on an item. This is the #1 user ask. Item is NOT moved by a plain swipe.
- **Tap = SELECT** the item (selection stroke already exists; trim handles show when selected).
- **Long-press (hold ~400-500ms, minimal movement) = PICK UP for move** — haptic + a visual "lift" (raise
  elevation/scale/alpha) so it's obvious. ONLY after pickup does drag move the item: horizontal = reposition
  in time, vertical = change layer / drop on the "+ New layer" zone. Release = drop (keep M10's one-undo-step
  merge). Before pickup, vertical drag in empty row-band space keeps M6 row-scroll.
- **Trim**: drag an in/out handle while selected (existing).
- **DELETE relocates** off raw long-press (that gesture is now pickup): expose delete when the item is
  SELECTED (a small delete/trash affordance in the selection state, or reuse whatever selected-overlay delete
  path already exists — find the least-disruptive existing affordance; do NOT invent a whole toolbar). If a
  clean selected-state delete affordance already exists elsewhere, route to it.
- **New-layer drop zone MUST be visible during a pickup-drag**: pin it at the BOTTOM OF THE VISIBLE VIEWPORT
  (not below off-screen content). Also make the layer-row band vertically scrollable if content exceeds the
  viewport (M6 intended this) so no row is permanently off-screen. Add a subtle affordance/label so the user
  knows the zone is a drop target.

## ANCHORS
- timeline/EditorTimelineView.java: row-band touch routing (handleM6RowTouch, the pending-axis + parent-
  intercept logic from 1a13557, onUp/onCancel, resetRowGestureFlags), getM6RowsTopPx, viewport height.
- layers/LayerGestureController.java: onRowBodyDown/Move/Up, armMove, long-press runnable (currently delete),
  MOVE_SLOP, hover-target + new-layer-zone detection, band/locked/hidden rejection guards.
- layers/LayerRowRenderer.java: drawNewLayerZone geometry (the off-screen bug), selection stroke, hit tests.
- FaditorEditorActivity: layerGestureCallback, delete-overlay path (for relocating delete).

## VERIFY (device + flight recorder — keep the ROWGESTURE logging until user confirms)
Contract cases, each provable from the log: (a) horizontal swipe over an item → `AXIS ... HORIZONTAL -> scrub`
(NOT a MOVE); (b) tap → select (no move); (c) long-press → new `PICKUP armed` line + lift visible, THEN drag
moves; (d) during pickup-drag the zone logs `zoneInViewport=true` and screen-Y bottom < view height; (e) drop
on zone → onItemDroppedOnNewLayer, new row visible; (f) drop on other band row → onItemMovedToTrack; (g)
delete still reachable via selection. Scripted drags impossible on this device — user hand-tests; design log
lines decisive. ONE strong-model agent (gesture state machines are subtle); build green throughout.

## ~~FOLLOW-UP (sub-lane overlap rendering)~~ — SUPERSEDED 2026-07-03 by the user's hand-test feedback
The sub-lane idea is DEAD. The user's new binding model (described in their own detailed spec, feedback
2026-07-03 post-redesign hand-test): **items on the same layer row DO NOT OVERLAP** — a row's items butt up
sequentially, and dropping an item onto an occupied row SNAPS it to a bookend of the occupant(s). See the
BOOKEND MANEUVER spec below, which replaces this section. (Existing projects that already contain overlaps
are NOT migrated — drag-drop simply never creates new overlaps; other creation paths unchanged for now.)

## FOLLOW-UP 1 (NEW, binding — user spec 2026-07-03): occupied-row BOOKEND SNAP + animated view excursion
**The rule: no multiple items occupying the same time on the same layer.** Dropping item A onto a row
occupied by item B (the primary case: B is long, both its ends off-screen) snaps A to a bookend:
- While hovering an occupied row during a pickup-drag, the choice of bookend = which HALF of the TIMELINE
  PANEL the finger is in (panel, NOT screen — the user explicitly wants this panel-relative for future
  landscape layouts where the preview panel sits beside the timeline panel). The dividing line is the
  panel's horizontal midpoint.
- **Finger in LEFT half** → the timeline view ANIMATES a quick scroll to show B's START (row's FIRST item's
  start), with a ghost preview of A butted up immediately BEFORE it (A.end == B.start, exact, no gap).
- **Finger in RIGHT half** → quick animated scroll to B's END (row's LAST item's end), ghost preview of A
  butted immediately AFTER it (A.start == B.end).
- The user can flip left/right repeatedly while hovering that row; each flip re-animates to the other
  bookend. Smooth animated scrolls ONLY — never teleport (mental-map preservation is the point).
- **CRUCIAL playhead rule:** during this excursion the playhead stays LOCKED TO TIMELINE CONTENT (it
  scrolls away off-screen with the content), NOT re-centered on screen. That is the deliberate visual cue
  that the maneuver is temporary and shows how far you've traveled ("I observe the playhead is way back
  there, so I am such-and-such far from where I was").
- **Leaving the row upward (or to any other row/zone) without dropping** → the view quickly ANIMATES BACK
  to where it was (the pre-excursion scroll anchor), and the normal preview for the new hover target shows
  (empty row placement / cross-band insertion line / new-layer zone).
- **Drop while a bookend preview is showing** → commit A at exactly the previewed snapped position (one
  undo step, as with every completed drag).
- Multi-item rows generalize: LEFT half = butt before the row's FIRST item; RIGHT half = butt after the
  row's LAST item. (Placing into interior gaps of a multi-item row: future refinement, not this task.)
**Engineering notes:** needs a "view excursion" mode in EditorTimelineView — temporarily animate
scrollOffsetPx while REMEMBERING the pre-excursion anchor to return to, with the playhead drawn at its
content position instead of re-centered (updatePlayheadFromX's recentering must be bypassed during the
excursion); LayerGestureController's applyMove must override finger→time mapping with the snapped preview
position while a bookend is armed; drop commit in onRowBodyUp. STRONG-MODEL (Opus-tier) task per the
user's budget rule — this is a gesture state machine with animation coupling. Extend ROWGESTURE logging:
excursion enter/flip/exit/commit lines with anchor + target offsets.

## FOLLOW-UP 2 (user report + flight-recorder confirmed 2026-07-03): post-pinch dead zone
After a pinch ends with one finger still down, the surviving finger's MOVEs are ignored (log: onScaleEnd →
isScaling=false → continuing action=2 stream with activeDrag=NONE, nothing consumes it) until the user lifts
and re-touches = "stuck for a while." ALSO: the tracked x jumped 1151→541 at scale end (active-pointer
switch) — a naive continuation would teleport the view. FIX: on onScaleEnd with a pointer still down,
hand back to the normal pan/scroll path RE-ANCHORED at the surviving pointer's current position (proper
activePointerId handling; zero jump, zero dead zone — pinch→pan as one fluid motion). Applies to the main
timeline surface (not just the row band). Verify via the existing onTouchEvent debug lines: post-scale MOVEs
must show scroll consumption, and scroll position must be continuous across the handback.

## Status
- [x] Contract redesign (9cf3080 — recovered agent diff + 2 orch fixes: dead-field compile error, onUp missing pending-TAP branch that left the pickup timer live)
- [x] off-screen zone fix (9cf3080 — zone pinned to visible viewport bottom, zone height reserved so last row scrolls clear)
- [x] delete relocation (trash roundel on the SELECTED item, right end inside the trim cap; ItemZone.DELETE hit-tested FIRST with finger slop; fires the same onItemDeleteRequested confirmation on DOWN like header icons; DownResult.CONSUMED; badge skipped on too-narrow items — geometry single-sourced in deleteBadgeCx)
- [ ] sub-lane overlap rendering (follow-up)
- [ ] post-pinch pan handback (follow-up 2)
- [ ] device hand-test confirmed (checklist relayed 2026-07-03; pull `adb logcat -d -s ROWGESTURE:D` after)
