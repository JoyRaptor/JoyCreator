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
item at the same ABSOLUTE timeline time on the target row, ~~carries the clip's attached overlays with
it,~~ and is ONE undo step. Demotion = the reverse (insert into master with ripple). High interaction
risk — follow the hand-test rule (agents get one scripted attempt, then user checklist).

> *Struck clause superseded 2026-08-03: "carries attached overlays" is now delivered by §4 anchoring,
> which is sequenced BEFORE M12 rather than after it. See §3A.*

---

## 3A. M12 INTERACTION SPEC — BINDING (JoyRaptor, 2026-08-03)

Settled in conversation. Do not re-litigate the zone model, the pan model or the colours.

### 3A.0 What is already built (do NOT rebuild)
Verified against the code 2026-08-03:
- **A PiP overlay and a master clip are THE SAME CLASS.** `Clip` lives in two lists on `Timeline`
  (`clips`, `overlayClips`), discriminated by `isOverlayClip()` → `layerId != null`. `overlayStartMs`
  and `overlayTransform` already exist and round-trip. **M12 needs NO schema change.**
- **Edge auto-pan during an item drag** — `EditorTimelineView.edgeScrollRunnable` (A1 slice 3),
  speed ramped by depth into the zone, re-maps the item every tick, arbitrated against gap-hover
  and excursion. Vertical row auto-scroll too.
- **Minimap drag-nav** — `itemDragMinimapNav` (A2, user request 2026-07-04): a picked-up item slid
  into the minimap band navigates the view, item mapping suspended, drag resumes on leaving.
- **Transition index repair + undo** — `removeTransitionsForDeletedClip` /
  `shiftTransitionsAfterInsert`, with the snapshot-restore undo pattern audited and harness-pinned
  in `AUDIT_TRANSITION_INDEX_UNDO.md`. M12 USES this; it does not invent its own.
- **Ripple/gap toggle** — `FaditorEditorActivity.toggleRippleMode()` ships. (The M11 checkbox in
  `PLAN_LAYERS_V2.md` is stale on this point; what M11 still owes is §4 anchoring.)
- **One-undo-step merge for a diagonal drag** — M10's `pendingLayerTrackUndo`/`mergedAction`.

M12's genuinely new surface is: master clips liftable, the spine as a typed drop target, the drop
indicator states below, and the dwell-armed split.

### 3A.1 Drop zones are SCREEN-RELATIVE, not clip-relative
Clip-relative zones were rejected: on a clip longer than the viewport, "20% from its start" is
off-screen, so the affordance is unreachable exactly on the clips that need it most.

Three horizontal bands, measured against the VIEWPORT, targeting whatever clip sits under the band:
- **Centre band** → insert at the NEAREST SEAM to the finger.
- **Off-centre** → insert BEFORE / AFTER the clip under the finger (still a seam insert).
- **Outer ~10%** → auto-pan (§3A.3). No drop resolution happens here.

This degrades correctly at both zoom extremes, which is why it was chosen: zoomed OUT one screen
spans many clips and each band targets a different one; zoomed IN one clip fills the screen and all
three bands target it (nearer seam / before it / after it). One rule, no special cases.

### 3A.2 Split-insert is DWELL-ARMED, never the default
Hold over the centre of a clip past the dwell threshold to arm "cut here and wedge in". Rationale:
the safe operation stays the default, and the destructive one requires intent and announces itself.

- The dwell **pulses to show it FILLING** (users must be able to see how long to hold, or they let go
  at 300ms), then goes **SOLID the instant it arms**. Animated = still deciding, steady = committed.
- **Do NOT pulse the committed target line.** During a drag the finger moves, the item moves and the
  view may be panning; a blinking target competes with all three and reads as jitter — i.e. "unsure",
  the opposite of what a precise insertion point must say.
- **Arming is SUPPRESSED inside the pan zone.** Dwell and edge-pan are both "hold still", so their
  precedence must be explicit. Extend the existing arbitration (`isHoverGapActive()` already
  suppresses horizontal edge-pan the same way) — do not invent a second mechanism.

### 3A.3 Pan is CONTINUOUS from the current viewport — no jump-to-limit
Rejected: "quick-pan to the clip's limit, then normal pan from there."
1. It breaks the **WYSIWYG DROP PRINCIPLE** (binding, slice 3) — the view arrives somewhere the user
   did not watch it travel to, so the indicator must be re-earned.
2. It is ambiguous on exactly the clips it is meant to help: on a clip longer than the screen, "the
   limit" may be minutes away in either direction.
3. It would be a THIRD navigation mode competing with nudge-pan and the minimap.

Instead — **content-aware pan velocity**, which buys the same benefit continuously:
- speed ramps with edge depth (already built), AND
- **accelerates through the interior of a long clip** (no seams there = nothing to target), and
  **eases down as a seam approaches**. Fly across the boring middle, arrive gently at the target.
- **The seam magnet engages only BELOW a pan-speed threshold** — on a 45-minute project a magnet that
  grabs every passing seam stutters. Fast pan glides, slow pan snaps. Slots into the existing **A9
  SNAP-PRIORITY RULE**; it does not define its own precedence.
- The **minimap stays the O(1) escape** for genuinely distant placement.
- **Timecode chip pinned near the finger** during pan and minimap-nav. Jumping the viewport mid-drag
  costs the user their spatial anchor; `00:14:32` under the thumb restores it for almost no work.

### 3A.4 Colour language — extend the existing one, add exactly one
Green/yellow were REJECTED because they collide with shipped meanings: `updateRippleModeButton()`
already uses **green `0xFF4CAF50` = ripple mode** and **amber `0xFFFFB300` = gap mode**, on screen
simultaneously. Amber-means-gap beside yellow-means-split is the worst case, since a gap and a cut
read as related.

| Cue | Meaning | Status |
|---|---|---|
| White outline | same-row, time-only move | shipped |
| Purple `0xFF8C3DFA` | lands on another row | shipped |
| Light purple, dashed | creates a new layer | shipped |
| Green `0xFF4CAF50` / amber `0xFFFFB300` | ripple / gap mode | shipped |
| **Solid purple line** | **insert at a seam** (incl. before/after) | **M12** |
| **Red + scissors glyph, dashed** | **this will CUT the clip** | **M12** |
| **Gutter chevron, no colour change** | **panning** | **M12** |

Purple already means "this is where it lands", so the seam insert and the before/after case share it —
before/after IS a seam insert and needs no colour of its own. Red is unused in the drag language and
is the only destructive state in the system. **The scissors glyph is REQUIRED**, not decoration:
colour alone fails for colourblind users. Panning gets no colour because it is not a landing state.

### 3A.5 Payload legality
Spine drops are legal for **VIDEO and IMAGE payloads only** (v1 covers both — JoyRaptor, 2026-08-03).
Do **NOT** add `MASTER` to `TrackKind.isLane()` — that whitelist is a deliberate data-safety guard
(a text item on the spine would write a layerId nothing routes to, orphaning it into a phantom lane).
Use a separate narrow predicate. Text/sticker/sprite drops are REFUSED WITH A VISIBLE REASON, never
silently ignored.

### 3A.6 Build order — the testable thing arrives at step 2
1. **Model ops + JVM harness.** promote/demote pair, spine repair, transition snapshot, gapless
   resync, one undo step. No UI, no split. Correctness is bought here because it is cheapest here.
2. **Gesture core.** Master clips liftable, spine as typed drop target, solid-purple seam line,
   existing pan + minimap extended to reach it. **HAND-TEST HERE** (§5 testing economics).
3. **Dwell-armed split-insert.**
4. **Polish.** Timecode chip, magnet threshold, refusal feedback.

Estimate of record: **4–6 sessions**, after §4 anchoring lands.

### 3A.7 Back-propagation (logged, not drifted into)
If the dwell-arming, content-aware pan and two-colour landing language prove out here, they are to be
carried back to trims, reorders and lane moves so the whole timeline speaks one dialect
(JoyRaptor, 2026-08-03). Log it as explicit follow-on work — do not let it happen by drift, and do not
do it opportunistically inside M12.

## 4. Anchoring / magnetic follow (elevated INTO M11, no longer "later")
Layer items are ANCHORED by default to the master clip they sit above (pin-to-master ON by default):
if master is A,B,C with stacks above A and C, deleting B ripples C left AND C's stack moves with it.
- Per-item unlink (chain icon) for absolute-time items; a global magnet toggle can come later.
- Anchor definition: item stores `anchorClipId` + offset from that clip's start; on master ripple, anchored
  items shift by the same delta. Unanchored items keep absolute time.
- This is now part of M11 (ripple/gap milestone) scope, per user's 2026-07-02 message.

### 4A. ANCHOR SPEC — BINDING (JoyRaptor, 2026-08-03). Sequenced BEFORE M12.

The ripple/gap TOGGLE ships (`toggleRippleMode()`); what M11 still owes is this. JoyRaptor's ask:
CapCut-style layer follow — *"if I have objects above and surrounding clip 2 and more at clip 4 and I
delete clip 3, that pulls things together but everything moves relative to the clip nearby. And if I
lengthen clip 1 I'm not breaking anything above clip 2 or 4."*

> **⚠ REVISED 2026-08-03 after an adversarial audit found 18 confirmed breakages in the first
> draft. This is draft 2. Anything here that reads like a bare assertion was traced to code —
> `file:line` given — because draft 1's mistakes were all "plausible and unchecked".**

**THIS IS AN INSTANCE OF `RiderAttachment`, NOT ITS OWN SYSTEM.** See
`PLAN_TIMELINE_MANIPULATION_V1.md` §2.0. Layer anchoring is the SHIFT-ONLY policy on a shared
host/rider model whose other two policies are the visualizer's (shift + truncate, already BUILT)
and the caption's (shift + re-source, specced in `PLAN_GESTURE_CONTRACT_FINAL_20260706.md` §4.1,
no code yet). **Do not build a second attachment mechanism** — draft 1 was about to, and would
have made three.

**THE ONE RULE — every edge case below falls out of it, so do not add a second:**

> An item anchors to the master clip under its **START**, storing `hostClipId` + offset from that
> clip's timeline start. On any master structural edit, compute each master clip's start-time delta
> and shift every anchored item by ITS host's delta, per its policy. Unlinked items keep absolute
> time.

Anchoring by START (not by overlap, not by centre) is what makes an item spanning two clips
unambiguous. Per-item unlink is the chain icon from §4.

**⚠ THE SEAM IS AMBIGUOUS TODAY AND YOU MUST PICK ONE.** The model and the view disagree at exactly
the boundary §3A.1 steers the user toward:
- `Timeline.attachVisualizerToHostUnderStart` (`:1563-1584`) uses **half-open** `start >= s &&
  start < s + span` → a seam-exact start binds to the **LATER** clip.
- `EditorTimelineView.xToTime` (`:3588`) uses **inclusive** `x >= left && x <= right`, first match
  wins → a seam-exact x resolves to the **EARLIER** segment.

**DECISION: half-open wins** (bind to the LATER clip). It is the shipped model-side rule, it makes
"the clip under time T" a total function with no overlap, and the hit-test is the one that should
change. Fix `xToTime` to match, and pin BOTH in the harness — a seam-exact case is the first thing
that will regress.

**⚠ THERE IS NO CLIP START-TIME FIELD.** A clip's start is a PREFIX SUM, computed only in
`EditorTimelineView.getSegmentStartTime` (`:2063`) over `SegmentData.effectiveMs`, whose convention
is `hasLoopExtension() ? getVisualDurationMs() : trimmedMs / speed`. **`Timeline` has no equivalent
and must gain one** (`getClipStartMs`/`captureClipStarts`) — the anchor hook must not depend on a
View. Consequence: a start changes when ANY earlier clip changes membership, order, in/out point,
speed, or loop extent. There is no setter to hook; the mechanism must be capture-before →
diff-after.

**Edge cases — decided (⚠ = draft 1 was WRONG here; the audit's evidence is cited):**

- **Item spanning two clips** — anchored by its start, and it does **NOT stretch**. Duration belongs
  to the user. *(Survived the audit. Note this is the SHIFT-ONLY policy; the visualizer rider
  deliberately differs — see `RiderAttachment`.)*
- **⚠ OPEN-ENDED ITEMS — DRAFT 1 DID NOT MENTION THEM AND WOULD HAVE CORRUPTED THEM.**
  `endMs == Long.MAX_VALUE` is the open-end sentinel (`TextOverlayItem.java:88-90`), and
  `setTimeRange` stores `(endMs <= startMs) ? MAX_VALUE : endMs` (`:435`). A naive
  `setTimeRange(start+d, end+d)` with a NEGATIVE delta yields `MAX_VALUE + d`, which is still
  `> start`, so it is silently stored as a **CLOSED** end at an astronomical value.
  `Timeline.textEndForPacking` (`:684-687`) treats only EXACTLY `MAX_VALUE` as open, so that item
  then owns its lane forever and the load-time enforcer exiles every sibling.
  **RULE: shift the START only; if `endMs == Long.MAX_VALUE`, leave it EXACTLY as-is. Never
  arithmetic on the sentinel.** Pin it in the harness with a negative delta — that is the case that
  breaks. This is the same family as the `2^61−1` stranding already in `LEDGER.md` §1l.
- **⚠ SPLIT MINTS NEW IDS ON BOTH HALVES**, so every split ORPHANS every anchor on that clip unless
  re-anchoring happens in the model. `new Clip(other)` assigns a fresh `UUID.randomUUID()`
  (`Clip.java:486-488`), used by `Timeline.splitAt` (`:319,322`). **There are TWO independent split
  implementations** — `Timeline.splitAt` (called from `FaditorEditorActivity:21402, 24169, 25211`
  and `AIToolExecutor:1516`) and a separate one in `EditScriptApplier.java:755-758`. Re-anchor by
  which half the item's START falls in, **inside `Timeline`**, so both routes inherit it.
- **Reorder** — anchored items travel with their host. Cover it explicitly in the hand-test; it is
  the most surprising correct behaviour. Note the AI path `EditScriptApplier.applyReorderClips`
  (`:866-889`) **clears and rebuilds the entire list**, so the hook must be DIFF-based, not
  index-based.
- **⚠ GAP MODE IS NOT INERT — DRAFT 1 CLAIMED IT WAS, TWICE, AND BOTH HALVES ARE FALSE.**
  (a) `getRippleMode()` is consulted at exactly ONE edit site (`FaditorEditorActivity:25450`,
  delete). **Trim, split, reorder, duplicate and insert all ignore it** — and JoyRaptor's own example
  ("*if I lengthen clip 1*") is a TRIM, which shifts every later clip in either mode.
  (b) The spacer is a `new Clip(blackUri, …)` with a **fresh UUID** (`:12195`), so anchors do NOT
  "re-anchor to the black spacer" — they DANGLE. Gap delete must transfer anchors onto the spacer
  explicitly. Also `max(100, dur)` rounding means a sub-100ms clip yields a real non-zero delta.
- **Item in a gap / past the last clip** — no host, absolute time. *(Note the shipped visualizer
  rule differs: `attachVisualizerToHostUnderStart` falls back to `clips.size()-1` for an item past
  the end. That divergence is intentional under per-type policy, but WRITE IT DOWN in the harness so
  it is not later "fixed" into agreement.)*
- **⚠ THE HARD ONE — COLLISIONS. DRAFT 1 SPECIFIED A RESOLVER THAT FIGHTS THE ONE ALREADY SHIPPED.**
  There is an existing no-overlap enforcer, run unconditionally on EVERY project load
  (`FaditorEditorActivity:1256,1264` → `Timeline.enforceNoOverlapVideoLanes:701-731` /
  `enforceNoOverlapTextLanes:651-681`), and **its repair is to MOVE THE ITEM TO ANOTHER LANE**
  (`setLayerId("video-"+id)`), not to displace it in time. Draft 1's "shift, then resolve
  left-to-right in time" would be undone on next load, with the item silently jumping ROWS.
  Additionally `LayerGestureController.nearestFreeStart` (`:1118-1214`) returns the NEAREST legal
  start, which can place an item to the LEFT of one already placed (branch (a), `:1183-1190`) — so
  "left-to-right, deterministic" is not even self-consistent on that primitive, and it moves exactly
  one item, never a chain.
  **DECISION: ONE invariant, ONE repair semantics — RE-LANE, matching what ships.** A ripple that
  produces a collision moves the loser to its own lane, exactly as a load-time repair would, so the
  two can never disagree. **And it must SAY SO** — *"2 items moved to their own lane to avoid
  overlap"* with undo to hand. **Do NOT write a second time-displacement resolver.**
- **Undo** — one step, restoring every offset AND any re-laning. Note `UndoManager.undo()/redo()`
  IS a genuine chokepoint for all 11 `EditActions` classes plus every `LambdaAction`, so it can be
  wrapped once rather than per-action.

**DELETING A CLIP THAT ITEMS ARE ANCHORED TO — ASK THE USER (JoyRaptor, 2026-08-03).**
Not a silent default in either direction. On a master delete that would orphan anchors:

- Prompt naming the count: *"N layer objects start on this clip — re-anchor them to the next clip, or
  delete them with it?"* Two actions, plus **"Remember my preference"**.
- The remembered value is **TRI-STATE**, not a boolean: `ask` (default) / `always re-anchor` /
  `always delete` — a boolean cannot express *which* choice was remembered.
- Follow `SharedPreferencesManager.isFaditorAskToTranscribeEnabled()` (`:823-834`) +
  `Constants.PREF_FADITOR_*` in SHAPE, with a row in `FaditorSettingsBottomSheet`.
  **⚠ It cannot be mirrored EXACTLY — draft 1 demanded something impossible.** The precedent is
  `getBoolean`/`putBoolean` and the sheet only has `addSwitchRow` (a boolean lambda,
  `FaditorSettingsBottomSheet:151-155`); a tri-state needs a **new row helper**, which that file's
  own comment (`:150`) already anticipates ("or a new row helper"). Build the helper; it is the
  smallest honest option, and the next tri-state preference will reuse it.
- **⚠ THE REPAIR DOES NOT LIVE IN THE PROMPT PATH.** There are ~35 `removeClip` call sites across
  5 files — `AIToolExecutor:1538`, `EditScriptApplier:572,740,866,888,966`, `EditActions:525-796`,
  plus a dozen in the activity. Only `deleteSelectedSegment` (`:25470`) is the "user pressed delete"
  route the PROMPT targets. **Anchor repair belongs in `Timeline` so every other route inherits it;
  the prompt is only the user-facing CHOICE on the one path where a human is present.** AI and undo
  paths take the remembered/default policy silently and correctly.
- **⚠ IT MUST MERGE WITH AN EXISTING DIALOG, not stack on it.** `confirmDeleteLinkedPair`
  (`FaditorEditorActivity:25461-25465`) already raises a `MaterialAlertDialog` for a dual-stream
  linked-pair delete. Two modals on one action is not acceptable; fold the anchor question into
  that dialog when both apply.
- **Do NOT prompt when it cannot matter:** zero affected items. *(Draft 1 also exempted gap mode —
  WRONG, see the gap-mode edge case above: the spacer has a fresh id, so gap delete orphans anchors
  too and must transfer them.)*
- **BATCH it** — a multi-clip or linked-pair delete raises ONE prompt, not one per clip.
- When `always delete` is remembered the deletion is silent, so the confirmation toast MUST report
  what went with it. Undo still covers it in one step.

Estimate of record: ~~2–3 sessions~~ **4–5 sessions** (revised 2026-08-03 — the audit found no
chokepoint, no start-time field, an id-minting split, a conflicting shipped resolver, and the
open-end sentinel trap; draft 1's estimate was priced against a spec that did not survive contact).
Land this BEFORE M12, so the spine drag inherits a settled answer for what the layers do when the
spine moves.

## 5. Testing economics (user mandate, applies to ALL interaction milestones)
Agents get ONE scripted attempt at drag/gesture verification. Then: verify state-level ground truth
(project.json, undo, screenshots after taps) and hand the user a numbered HAND-TEST CHECKLIST
(where to touch, what should happen). Do not grind synthetic input.

## 6. Row-item selection + parent/child linkage highlight (2026-07-02 hand-test feedback)
TAP a layer-row item = SELECT it (M7/M10 only wired long-press/drag; tap-select was missing and blocked
the user's whole hand-test). Selection must show a visible LINKAGE: the row item's accent color (e.g the
purple bar) is echoed onto every connected representation — the on-canvas render of that same object and
any attached children — via a ~50% tint/outline in the SAME color. Rule: "everything that will move with
this item wears its color while it's selected/long-pressed." Applies doubly once anchoring (§4) and
promote/demote (§3) exist.

## 7. Duration-on-create + numeric duration/placement (2026-07-02)
Creating any layer-bound object (text, image, sprite, …) prompts for duration: default = LAST-USED
duration; explicit option = "Entire project". After creation, duration and start time must be TYPEABLE
(numeric fields, e.g. in the move/position drawer) — "put this at 00:15 for 6s" without scrolling and
edge-dragging for minutes. This extends the existing move-tool spirit (numeric placement for fiddly
small screens). A full-project-duration item is legitimate (simple projects) but must never be the
unavoidable default for everyone.

## 8. Locked/hidden item styling (2026-07-02, decided with user)
Locked/frozen timeline items: KEEP THE HUE, cut saturation ~50%, overlay a subtle 45° diagonal hatch
(thin translucent dark stripes — same family as the preview's out-of-canvas pattern), padlock glyph on
the row header. The user must keep color-bearings ("the red thing is still over there") while instantly
reading "this won't move." On an attempted interaction with a locked item: briefly brighten the hatch +
wiggle the padlock — teaches WHY nothing moved. Full saturation = live/movable; desaturated+hatched =
frozen. Hidden rows: same treatment + collapsed/dimmed further.
