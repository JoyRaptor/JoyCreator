# Layers UX Addendum — z-order, anchoring, promotion (2026-07-02, from user discussion)

> Binding decisions layered on top of `PLAN_LAYERS_V2.md`. M10+ agents read this WITH the plan.
> Status: recommendations adopted as working decisions; user can veto — flag any conflict you hit.

## 1. Stacking direction (CONFIRMED, already matches build)
Master track = the FOUNDATION, at the bottom of the video stack. Overlay layers stack UPWARD:
**higher row on the timeline = rendered above (higher z).** Audio tracks live BELOW the master row
(CapCut/Premiere convention). So the timeline reads physically: everything above master is picture
stacked on picture; everything below is sound. M10's row reordering must preserve this row↔z mapping
(moving a row/item up literally means "render above").

## 2. Clip-attached overlays vs layers: GROUPED Z ("layer sandwich")
Decision: an overlay ATTACHED to a clip/layer renders directly above ITS layer, then the next layer
up follows. i.e. bottom→top: `L1, L1's overlays, L2, L2's overlays, …` — NOT all-layers-then-all-overlays.
- Rationale: attachment = containment. Dragging a layer up/down carries its dressing (text, stickers,
  waveform) with it, like grouping in design tools.
- GLOBAL EXCEPTIONS render above everything: the caption/CC band (subtitles are conventionally topmost)
  and any overlay explicitly promoted to its own layer row.
- Escape hatch (the "ungroup"): promote an attached overlay to its own layer row when the user wants it
  above a HIGHER video layer. The Track/TimedItem model supports this natively — it's a re-parent, not
  a new mechanism.
- Compositing implication (M-COMP-1/2, M-EXPORT-1/2): render order is per-track bottom-up with attached
  items interleaved, then the global caption band last.

## 3. Promote/demote by drag (NEW milestone M12, after M10)
User expectation: dragging a clip from the master timeline UP onto a layer row promotes it to a floating
layer item (and dragging a layer item INTO the master row demotes/inserts it). Distinct from the
long-press reorder dialog (which stays for within-master ordering).
Scope notes for M12: promotion removes the clip from master (ripple-closes the gap), places the floating
item at the same ABSOLUTE timeline time on the target row, carries the clip's attached overlays with it,
and is ONE undo step. Demotion = the reverse (insert into master with ripple). High interaction risk —
follow the hand-test rule (agents get one scripted attempt, then user checklist).

## 4. Anchoring / magnetic follow (elevated INTO M11, no longer "later")
Layer items are ANCHORED by default to the master clip they sit above (pin-to-master ON by default):
if master is A,B,C with stacks above A and C, deleting B ripples C left AND C's stack moves with it.
- Per-item unlink (chain icon) for absolute-time items; a global magnet toggle can come later.
- Anchor definition: item stores `anchorClipId` + offset from that clip's start; on master ripple, anchored
  items shift by the same delta. Unanchored items keep absolute time.
- This is now part of M11 (ripple/gap milestone) scope, per user's 2026-07-02 message.

## 5. Testing economics (user mandate, applies to ALL interaction milestones)
Agents get ONE scripted attempt at drag/gesture verification. Then: verify state-level ground truth
(project.json, undo, screenshots after taps) and hand the user a numbered HAND-TEST CHECKLIST
(where to touch, what should happen). Do not grind synthetic input.
