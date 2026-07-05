# Feedback Batch — Drag/Move/Trim UX v3 + KineMaster adoptions (2026-07-03, user hand-test)

> User tested JoyRaptor's gesture cluster (through 8f8c764). Items marked RE-VERIFY may already be fixed by
> 8f8c764's butting/ghost work — the user's test may predate that build; verify on-device before re-fixing.
> Companion: tasks/RESEARCH_COMPETITOR_UX_20260703.md (in progress) for the KineMaster-inspired items.

## A. Drag/move (v3 on the row-gesture system)
1. **Edge auto-pan while dragging (P1):** dragging an item near the screen's left/right edge must
   continuously pan the timeline to open more room — currently placement is limited to what's visible.
   (Distinct from the bookend "excursion": this is sustained scroll-while-held.)
2. **Minimap drag-navigation (P1, killer feature):** with an item held, sliding the finger ONTO the
   timeline minimap slides the view to that region; pulling the finger back down continues the drag with
   the item still attached, ready to place. Long-distance moves without repeated edge-crawling.
3. **Free placement on a track (P1):** items must be placeable ANYWHERE on a row — currently placement
   gravitates to ahead-of/behind existing clips only. Butting stays as a gentle snap, not the only option.
4. **Snap tolerance (P1):** ~4mm today → 1–2mm target. Express as dp constant (~8–12dp at 420dpi);
   single source for all drag snaps. "Not too aggressive" is the user's explicit principle.
5. **Snap-to-origin confirmation (RE-VERIFY):** outline glow/lighten when snapped back to origin =
   "release now and it won't count as a move/undo". 8f8c764 claims ghost light-up — confirm it reads.
6. **Butting outline clearance (RE-VERIFY):** when snapping to a clip's front/back, the dragged outline
   must fully CLEAR the underlying clip (edges meet exactly, no overlap). 8f8c764 claims fixed.
7. **Under-finger shift at butting (RE-VERIFY):** dragged clip may shift under the finger so the joint
   is visible (excursion centers the joint per 8f8c764 — confirm it satisfies).
8. **BUG — audio overlap allowed (P0):** audio items can still be stacked overlapping on a track.
   Same-row overlap must be rejected (snap to adjacent butting position or snap-back on release).
9. **Vertical layer-swap guardrail (P1):** moving an item straight up/down = change LAYER ONLY, time
   locked, with a guardrail snap (small horizontal tolerance before time unlocks). While time-locked:
   1px dotted vertical lines at the item's front/back bounds (dim), disappearing the moment movement
   goes diagonal, reappearing if timing re-matches exactly. Also: swapping adjacent items A↔B vertically
   is currently near-impossible (hover-over-item pans away; hover-above does nothing) — a time-locked
   drop onto an occupied row places at same time and resolves collision via the butting rules.

## B. Trim polish
10. **Trim ghost shading:** trimming IN → the subtracted region renders dark (~50% opacity of item
    color); trimming OUT → the added region renders ~10% BRIGHTER than the item color. Reads as
    "what am I removing vs adding" at a glance.
11. **Trim callout:** small pill/rounded-rect with a caret, hovering above the dragged edge, showing
    exact time + frame number. On release: fades if no net change; flashes-then-fades if a change
    committed.

## C. KineMaster-inspired (screenshots studied; web research in flight — see RESEARCH doc)
12. Full-height playhead through the entire timeline; with a layer item selected, the playhead segment
    over that item is visually marked (KM: dotted yellow) = "a split applies HERE, to THIS item".
13. Playhead time chip displays exact time (mm:ss.mmm).
14. **Bookmarks:** droppable markers on the ruler; long-press a marker → options (jump/remove/remove all).
15. **Keyframe transport row:** add-keyframe button that flips to remove-keyframe when the playhead sits
    on one; adjacent CURVE/easing button opening a preset gallery (KM ships ~18 curves; our KeyframeSet
    already has Easing — this is surfacing, not new math).
16. **Full-screen timeline mode:** expand toggle → timeline fills the screen, preview becomes a draggable
    picture-in-picture window. Perfect fit for layer-heavy work + DESIGN §5 menu philosophy.
17. **Interaction mapping (PROPOSED, user to veto):** TAP the time chip = jump-to-time entry + quick nav
    (move tool / layers); TAP the playhead line itself (or double-tap the chip) = drop bookmark;
    long-press a bookmark marker = options. Separates "numeric/exacting" (chip) from "marking" (line).

## A-additions from the 2026-07-04 hand-test (user, after slice 1)
12. **Wedge-insert (P1, slice 2):** dropping between two BUTTED items on a row must wedge in — push the
    later sibling(s) right by the dragged duration, with a live preview of the shifted layout — or at
    minimum resolve to an end. NEVER overlap. (Commit-time no-overlap guard shipped in caa628e as the
    stopgap: a drop now re-resolves to the nearest butting edge; the wedge is the desired ideal.)
13. **Above-top new-layer zone (P1, slice 2):** the "+ New layer" drop zone exists only BELOW the bottom
    row (M10's documented deviation) — so items can move DOWN to new layers but never UP above the top
    row. Mirror the zone above the top row; z-addendum applies (higher row = higher z, so "new layer
    above" = "draw on top of everything").
14. Slice status (2026-07-04 evening): ✅ slice 1 (A3+A4 free placement + gentle snap, user-confirmed);
    ✅ 1.1 drop guard (caa628e); ✅ 1.2 trim guards both lanes (f646bb9); ✅ 1.3 honest closed-length
    drag preview + open-end restore at drop + joint hysteresis so the excursion pan fires (40338ea);
    ✅ 1.4 ghost restyle (passive gray dashed box — was near-white stroke reading backwards) + **A2
    MINIMAP DRAG-NAV BUILT** (5d23d5b). REMAINING: slice 2 = A13 above-top new-layer zone + A12
    wedge-insert w/ preview; slice 3 = A9 dotted time-lock verticals + vertical guardrail; A1 edge
    auto-pan; B10/B11 trim shading + callout. All hand-test-gated by the user.

## A9 SNAP-PRIORITY RULE (2026-07-04 late, user hand-test of Phase R 26cf3cf — BINDING for slice 3)
✅ User-confirmed: audio clips no longer stack (R2 works). ⚠️ Vertical swap "keeps wanting to pull
diagonal — conflicting, wants to snap to something next to it." Diagnosis: the sibling butt-magnet
(nearestButtWithin) stays live DURING the vertical time-lock and yanks horizontally = tug-of-war.
THE RULE (user-specified):
1. In vertical time-lock (|dx| ≤ snap constant, hovering another row): sibling butt-magnets FULLY
   SUPPRESSED — nothing pulls horizontally; the drag rails straight up/down.
2. Butt-magnets re-engage ONLY when (a) intentional horizontal motion breaks the lock (|dx| > lock,
   hysteresis), OR (b) the dragged item's edge comes within snap radius of a neighbor's edge AT ITS
   LOCKED TIME ("only when it comes close to touching it").
3. Same-row collision at a time-locked drop resolves at RELEASE via the R2 butt-displace — never as a
   mid-drag magnet.
User is deliberately deferring re-test until the buildout completes — fold into slice 3 (A9 verticals +
guardrail) alongside A1 edge auto-pan.

### SLICE 3 EXPANDED SPEC (2026-07-04 night, second hand-test — the WYSIWYG DROP PRINCIPLE, BINDING)
User re-tested; five defects, one root principle: **at every instant mid-drag, what is drawn = exactly
what release will produce.** Today the drag draws the raw finger position (overlapping!) and only
resolves at release — "I can't tell if it's going to overlap or not... nothing tells me it's actually
going to butt up. Very imprecise."
1. **Live RESOLVED preview:** while held, if release would butt-resolve, draw the outline AT the butted
   position (never overlapping a sibling). The outline IS the landing forecast. (Follows caa628e's
   commit-time resolver — run the SAME resolver per-move and draw its output.)
2. **Outline color/state wrong:** shows WHITE; user expects the established PURPLE cross-row affordance
   when the drop target is another row/linkage context. Audit outline colors per state (same-row move vs
   cross-row vs new-layer vs snap-home) — each state one unambiguous color, purple = cross-row family.
3. **Diagonal pull still present** (A9 snap-priority rule above NOT yet built — this re-confirms it).
4. **Excursion overshoot:** the auto-pan "camera moves too far over — disorienting." Tune: pan the
   MINIMUM needed to reveal the joint + small margin; never past it.
5. **Off-screen butt placement (user-designed interaction, center-playhead panel):** while holding an
   item whose butt target lies outside the window — item held LEFT of panel center → view scrubs
   EARLIER and previews the item butted-BEFORE the neighbor; item held RIGHT of center → view shifts to
   preview butted-AFTER. Both must render the butted (resolved) preview, zero overlap, before release.
   This composes with (A1) edge auto-pan: sustained hold at screen edge = continuous scroll.
PRIORITY: this slice-3 round jumps the queue — runs IMMEDIATELY after Phase P lands (before dedup).

## SLICE 2 BLUEPRINT — unified gap-insertion model (2026-07-04 eve; CapCut-gap analysis, BINDING)
The jank root cause: THREE bolted-on new-layer affordances from different eras — the pinned
"+ Drop here for new layer" zone (below-bottom only), the cross-band "new layer here" arm w/ purple
line, and nothing at all between rows or above the top. They can render SIMULTANEOUSLY and disagree
(user screenshot 2026-07-04 eve). The market model (CapCut/VN/LumaFusion) has ONE vocabulary:
- **The GAP is the target.** While an item is held: every gap between adjacent rows, PLUS above the
  topmost row, PLUS below the bottommost, is a new-layer insertion target. Hovering a gap draws ONE
  insertion-line style (horizontal accent line in the gap, item's band color). Releasing creates the
  new track AT THAT POSITION (ordering = z per the addendum: higher row = higher z).
- **Hovering a row body = join that row** (existing highlight ring). Line-in-gap and row-highlight are
  mutually exclusive by construction — never two affordances at once.
- KILL: the pinned bottom zone, the cross-band arm/purple line, the "new layer here" text — all
  replaced by gap targets. Empty user-track auto-prune (M10) stays.
- Requires: onItemDroppedOnNewLayer gains an insertion INDEX (activity creates the LayerTrackDef at
  that position, not appended); renderer gap hit-zones + insertion-line draw; controller hover state
  becomes {rowTarget | gapIndex | none}. Solves A13 (upward creation) as a special case of "gap above top".
- EXECUTE at the top of a fresh window (widest rework of the slices — half of it helps nobody).

## A14 — retire the legacy teal overlay lane (user question 2026-07-04 eve: "should overlays piggyback
on the layer?") YES, direction confirmed: the teal lane and the rows render THE SAME objects twice,
far apart (the visual weirdness the user feels), and the teal lane is where the overlap-law bypass
lived. Plan: parity audit first — the teal lane's remaining exclusives (keyframe-diamond editing,
opacity keyframe drag) must exist on rows before the lane collapses. Then the lane goes; rows become
the single home. Big vertical-space win. NOT a quick flip — schedule after slice 2+3.

## Priority order
P0: A8 (audio overlap). P1 core feel: A1, A2, A3, A4, A9. RE-VERIFY pass: A5/A6/A7 (cheap, first).
B10/B11 = contained quick wins. C-items: after research doc lands; C12/C13/C14 cheap, C15 medium,
C16 medium-large (own milestone).
