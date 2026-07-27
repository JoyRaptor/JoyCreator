# SPEC — Object time-scrubber (precise, touch-free object navigation)

**Status:** DRAFT for user confirmation, 2026-07-26 (new account). Author: autonomous run.
**Origin:** the user's LAYER MANAGEMENT POLISH top priority + the detailed answer given
2026-07-26 (recorded verbatim at the bottom). This is the reframed "#6": *Move up/down
arranges OBJECTS, not lanes.* Layers stay nameless (rename idea dropped).

**Why this is a SPEC and not a commit:** it is a large, feel-driven feature with subtle
collision rules and a novel variable-speed control whose feel cannot be adb-verified. Per the
run's discipline (don't ship UX blind; prove, don't assert), the hard parts wait for the user
to confirm the ruleset below. Phase 1 (the unambiguous foundation) can start before then.

---

## 1. The control (lives in the object menu — `ObjectMenuSheet`)

A dedicated **object time-scrubber** shown for a selected movable object. Three parts:

1. **Variable-speed jog/shuttle scrubber.** Push a little → slow/fine movement; push more →
   fast/long-distance. The user explicitly wants BOTH fine control and long-distance travel
   from one control, and invited research on the best pattern. Design intent: a horizontal
   control that **springs back to centre**; displacement from centre maps to scrub VELOCITY
   (ms per second), **non-linear** — near centre = very slow (frame-accurate), further = fast.
   (Classic shuttle. A pure position-jog can't cover long distance on a phone width; a
   velocity shuttle can, and low velocity near centre gives the fine tier.)
2. **Optional increment (snap) toggle.** When on, the committed position snaps to increments
   (candidate: 1 frame, or a chosen step). Off = continuous ms.
3. **Live, tap-to-type time readout.** Shows the object's current start time, updating live as
   you scrub. **Tap it to type an exact time** → the object jumps there (see §3 jump-to-time).
   Keeps a from→to frame of reference ("where you had been to where you're going").

## 2. Move + collision + push-through relayering (the subtle part — CONFIRM THESE)

Moving the object along the timeline obeys a magnetic collision model with a one-step
push-through escape. Rules as I understood them:

1. **Lock on contact (default).** As the object slides and its edge meets another object in
   **its own lane**, it **stops (locks)** flush against that neighbour — objects never overlap
   by default.
2. **Push through → bump up ONE lane.** If the user keeps pushing past the lock:
   - if the lane **directly above is OPEN** across the needed span → the object moves up to it
     and keeps sliding;
   - if the lane above is **occupied** → **create ONE new lane** and keep sliding there.
3. **No chained climbing.** The limit is *exactly* one existing lane up **or** one new lane —
   never a staircase up through many lanes. That one bump is only so it can *continue sliding*
   past the obstruction.
4. **Return to origin lane past the obstacle.** Once the object clears the obstruction (the
   origin lane is free again beyond it), it **drops back into its original lane** and picks up
   right where it left off on the far side.
5. **Held = free scrub.** While the finger stays down you can scrub back and forth across the
   obstruction repeatedly; the object relayers up/down live. It **prefers its origin lane** and
   only leaves it to get past something.
6. **Release commits home.** On release, whatever lane it currently sits on becomes its new
   **home `layerId`**. If it ended back in the origin lane, no lane change is recorded.
7. **One undo step** per committed move (position ± lane change), mirroring the existing
   `mergedAction(positionRedo/Undo, trackChange)` machinery already used for cross-row drags.

## 3. Jump-to-time

Tapping the time readout opens numeric entry (mm:ss.mmm or similar). On commit the object
moves to that time. **If something is in the way at the target, the SAME §2 behaviour applies**
(it may land bumped-up / on a new lane exactly as if scrubbed into the obstruction).

## 4. Which objects (confirmed from the model)

Movable (payload has a free timeline start + a `layerId`): **text, sticker, sprite,
visualizer (waveform), PiP (overlay clip), audio.** NOT master video clips (they ARE the
sequence — reordered, not free-time-moved; a separate clip-reorder mode already exists) and
NOT caption spans (read-only views of a clip's caption).

Time setters per payload: `TextOverlayItem.setStartMs`, `AudioClip.setOffsetMs`,
`SpriteOverlayItem.setStartMs`, `WaveformOverlayInstance.setStartMs`, overlay `Clip` start
(verify exact API). Lane = each payload's `layerId`. Keep END anchored so a move shifts the
whole span (start and end together), not a trim.

## 5. Code anchors (verified this session)

- `TimedItem` (layers/) is a VIEW over live payloads; `getTimelineStartMs/setTimelineStartMs`
  proxy the payload. Moving persists through the payload setter, not the view.
- Object menu: `ObjectMenuSheet` (621 lines) — takes a title, `Action` chips/buttons, and
  `Prop` slider rows (currently **drag-only, no numeric entry** — the gap the readout fills).
  Opened via the hold-release `onItemMenuRequested` path.
- Existing single-undo-step machinery for position+lane: `mergedAction` /
  `PendingLayerTrackUndo` (`FaditorEditorActivity` ~:12289-12340).
- Existing overlap/lane helpers to study before writing collision: `Timeline.resolveAudioOverlap`
  (audio shifts to nearest free slot at add-time), `enforceNoOverlapTextLanes` /
  `enforceNoOverlapVideoLanes` (load-time lane separation), and the gap-insertion new-lane path
  in `LayerGestureController` (`gapIndexAt`). New-lane creation is `LayerTrackDef` + zIndex splice.
- Lane stacking order today = lane `zIndex` (per-lane), NOT per-object; "above/below" in §2
  means the adjacent lane by zIndex.

## 6. OPEN QUESTIONS for the user (before Phase 2)

- Q-A **"lane above" direction:** by zIndex (visually higher in the composite) or by the row
  order shown in the timeline? Assumed: the adjacent lane in the same band, upward in the stack.
- Q-B **increment step:** frames (needs project fps), or a fixed ms step (e.g. 100ms), or a
  chosen menu value?
- Q-C **new-lane kind:** the bumped object's own kind, or a neutral LAYER lane? (Neutral LAYER
  is the current "any object can land here" substrate — likely right.)
- Q-D **audio vs visual collision:** audio's contract is "never overlap in time on its lane."
  Does audio use the SAME push-through relayering, or does audio just lock (no new audio lane)?

## 6a. DECISIONS TAKEN (user, 2026-07-26)

- **Build the whole feature** (not phased-behind-confirmation) — Phases 1+2 together.
- **Push-through is a TOGGLE, for ALL object kinds incl. audio.** Toggle ON = the §2 relayer;
  toggle OFF = just lock flush, never leave the lane. So audio uses the same push-through as
  visuals when the toggle is on. (Resolves §6 Q-D.)
- **Animation is a first-class requirement: "buttery, not jarring."** See §9.
- Remaining §6 open questions default as written unless the user says otherwise: Q-A lane-above =
  adjacent by zIndex upward; Q-B increment step = TODO pick (frames if fps known, else 100ms);
  Q-C new lane = neutral LAYER kind.

## 7. Build status (2026-07-26, new account)

**LOGICAL CORE — BUILT + PROVEN OFF-DEVICE (3 commits):**
- `ObjectTimeMover` (engine): collision lock + push-through one-lane-up-or-new relayer,
  return-to-origin, breakthrough threshold, toggle-off=lock. `ObjectTimeMoverTest` 15/15.
- `TimeShuttleView` (widget): frame-synced (Choreographer) variable-speed jog/shuttle with a
  dead zone + cubic velocity ramp and an inertial eased spring-back. Compiles; feel = device.
- `ObjectTimeScrubSession` (session): tick→resolve→preview, one-step commit / no-op-if-unchanged,
  origin-anchored lock reference. `ObjectTimeScrubSessionTest` 10/10.

**REMAINING — EDITOR WIRING + ANIMATION (NOT built; blueprint in §8, contract in §9).**
Deliberately deferred from the unattended run: it is surgery in the 20k-line
`FaditorEditorActivity` + `LayerRowRenderer`, it is feel-driven, and the cross-lane glide can't
be verified by automated device tooling — writing it blind risks a silent regression in the live
editor. It should land as a device-verified step. It is ADDITIVE (new control + new code path
reachable only via the new UI), so it must not alter any existing menu/gesture behaviour.

## 8. Wiring blueprint (the exact remaining work)

1. **ObjectMenuSheet — additive "Move in time" section.** Add a `setTimeScrub(...)` setter (do
   NOT change `show()`'s signature — keep it additive so every existing caller is untouched).
   The section holds: a `TimeShuttleView`; the increment toggle + push-through toggle (two small
   toggles); a live time readout `TextView` that updates on `onScrubTick`/`onPlayheadChanged` and,
   on tap, opens a numeric time-entry dialog → `session.jumpTo(ms)`. Put it in PEEK (it needs the
   timeline live under it, like the range chips).
2. **Host impl in FaditorEditorActivity.** Implement `ObjectTimeScrubSession.Host` for the
   selected `TimedItem`:
   - `originLaneSpans()`/`aboveLaneSpans()`: from the object's home `Track` (id = payload
     `layerId`, or the default lane) and the adjacent higher-zIndex `Track` in the same band
     (`getLayers()`/`getAudioTracks()` are already zIndex-sorted). Each OTHER item's span =
     `[timelineStartMs, timelineStartMs + getDisplayDurationMs(total))`.
   - per-payload start setter on preview: `TextOverlayItem.setStartMs` / `AudioClip.setOffsetMs` /
     sprite & waveform `setStartMs` / overlay `Clip` start. Keep END anchored (shift whole span).
   - lane change: reuse the cross-row-drag precedent — set the payload `layerId`; for `NEW`,
     `Timeline.createLayerTrack(neutral LAYER, "")` and splice zIndex just above the home lane.
   - `onCommit`: ONE undo step via the existing `mergedAction(positionRedo/Undo, PendingLayerTrackUndo)`
     machinery (`FaditorEditorActivity` ~:12289-12340) — the same one cross-row drag uses.
3. **Live preview vs commit:** move TIME live (set start + `invalidate`), but show a lane change
   with the renderer's PROXY (do not mutate `layerId` every tick — mirror the drag's
   proxy/homeGhost, `LayerRowRenderer.setHomeGhost`/`proxyItem`), committing `layerId` +
   new-lane creation only in `onCommit`. This avoids per-tick timeline rebuild thrash.
4. **Selection required:** the section only shows for a movable payload (text/sticker/sprite/
   waveform/PiP/audio) — NOT master clips or caption spans (§4).

### 8a. REFRESH STRATEGY — the reason the live wiring is DEVICE-GATED (found 2026-07-27)

Verified in code, and it is the load-bearing risk:
- `TimedItem` **snapshots** the payload's start at construction (`ofTextOverlay` copies
  `getStartMs()`), and the renderer draws from `layerTracks` fed via `EditorTimelineView.setLayerTracks`.
  So a live `setStartMs`/`setOffsetMs` does NOT show on a bare `invalidate()` — the Track views
  must be rebuilt (`getLayers()` → `setLayerTracks`) for the moved block to redraw.
- `syncTimelineOverlays()` (the normal post-edit refresh, `FaditorEditorActivity:10757`) is HEAVY:
  it also runs `resyncAttachedVisualizers`, `resyncLinkGroups` (propagates time deltas to linked
  members — desirable on COMMIT, wrong to run every frame), `synthesizeG5PresetLinkGroups`,
  `syncBelowVideoOverlays`, preview re-bind, sprite-sheet feed. Calling it ~60×/s would not be
  buttery.
- A per-frame scrub therefore needs a LIGHT refresh (rebuild the row views + `invalidate` only),
  with the full `syncTimelineOverlays()` run ONCE on commit. **But the light refresh's per-frame
  cost is unknown and MUST be device-profiled** — this app has a documented long-project
  ANR/2-3fps history (see memory `faditor-long-project-perf`: `onDraw`/rebuild cost scales with
  the whole timeline). On a 45-min project a per-frame row rebuild could ANR. That is NOT a
  "feel-tuning" item the user can accept blind — it is a usability/correctness risk that needs
  on-device profiling.

**Consequence:** the LIVE wiring (Host + per-frame refresh + relayer glide) is left for a
device-verified step. The proven core (engine/session), the shuttle widget, and the additive
(inert) menu section are all landed. When building the live wiring on-device: prototype the light
refresh, profile it on the largest available project, and only then choose per-frame vs
throttled-refresh. The relayer's cross-lane GLIDE (§9) also needs the device (even the existing
drag proxy snaps rows vertically; a true y-glide is new renderer work).

## 9. Animation contract ("buttery, not jarring" — user requirement)

- **Scrub travel** is already frame-synced in the widget (Choreographer, dt-integrated) and the
  object's time move is applied per frame, so horizontal motion glides.
- **Release** eases the shuttle to centre on a cubic ease-out that keeps ticking → the object
  DECELERATES to a stop (inertia), never a hard halt.
- **Lane change (the main jarring risk)** must be a vertical GLIDE, not a teleport: when the
  object relayers up / drops back / lands on a new lane, tween its y between rows (reuse the
  `excursion` ValueAnimator pattern in `EditorTimelineView` ~:1013-1034 and the drag proxy) over
  ~180–220ms with an accelerate-decelerate interpolator. A NEW lane must EASE IN (grow/fade its
  row height), not pop; if it is abandoned (object returned to origin before commit) it eases out.
- **Jump-to-time** should GLIDE the object to the typed target (short animated seek of the same
  time-move path), preserving the from→to frame of reference, not snap.
- No layout jump when the section opens/closes: use the sheet's existing peek/expand transitions.

---

## 10. When you're back — device-session runbook + feel-test checklist

**A. Feel-test the constants already shipped this session** (each is a one-line retune if wrong):
1. **Mute (`7045904`):** tap the mute glyph on a TEXT/visual lane (no audio) → nothing should
   happen (no undo entry appears). On an audio lane → it still toggles. Correct = disabled-looking
   mute is inert.
2. **Gap target (`e6e895f`, 5dp):** pick up an object and drag it toward a NEIGHBOURING lane →
   it should land ON that lane, not spawn a new one. Deliberately aim at the thin gap between rows
   → a new lane should still be creatable. If lane creation feels too hard, nudge 5dp→6dp; if it
   still steals row taps, 5dp→4dp (`GAP_HIT_HALF_DP`, LayerRowRenderer).
3. **Hold-release menu (`4557cf9`, 8dp):** select an object → hold → release in place → the object
   menu should now open reliably (was "sometimes"). Retune `MOVE_SLOP_DP` in EditorTimelineView.
4. **Trim unchanged (`e0c510b`):** grab a text/audio/PiP item's edge and trim → should feel exactly
   as before (no dead-zone lurch at the start). If it lurches, the scope-fix regressed.

**B. Wire #6 live (the device-gated step) — do IN THIS ORDER, verifying each:**
1. First RUN the current build once and open an object menu (hold-release) for a text overlay —
   confirm the (inert) "Move in time" section does NOT appear yet (nothing calls setTimeScrub).
2. Add a generic `attachTimeScrub(...)` helper + call it at the end of each of the 5
   `showObjectMenuSheetForXxx` methods (SPEC §8). Wire ONE payload first (text overlay), build,
   open its menu → the section appears; confirm no crash on open (this is the crash-risk gate).
3. Implement `ObjectTimeScrubSession.Host`: `originLaneSpans` = sibling spans on the object's
   home Track (find the Track in `getLayers()`/`getAudioTracks()` containing the object id;
   OTHER items' `[timelineStartMs, +getDisplayDurationMs)`); `pushThrough`/`snapStepMs` from the
   toggles; `onPreview` = set the payload start live + LIGHT refresh + `sheet.setScrubTimeMs`;
   `onCommit` = one undo step (position-only for lock; `mergedAction` if a lane changed).
4. **Profile the light refresh on the LARGEST project** (SPEC §8a): a per-frame `getLayers()` +
   `setLayerTracks` may ANR a 45-min project. If it stutters, throttle (rebuild ≤~20/s or only
   when the resolved start actually crossed a row) before shipping.
5. Only after lock-only time-move is smooth: add push-through relayering + the cross-lane
   y-GLIDE (SPEC §9 — new renderer work; even the drag proxy snaps rows today).
6. Extend to the other 4 payloads; verify undo restores each; re-verify sandbox sha256 after any
   fixture use.

## 11. MOVE-DRAWER build recipe (the real home — investigated 2026-07-27, ready to build WITH the user)

The "move" tool's drawer (`FaditorEditorActivity.initMoveDrawer` ~:5289) is a half-built version
of this feature. Complete it in TESTABLE slices (each compiled + installed for the user to try
before the next — the drawer/menu can't be reached via adb, so the user's test IS the check).

**Payload move APIs (all verified):** move = shift the WHOLE span, preserving duration + open-end.
- text / sprite / waveform: `getStartMs`/`getEndMs` + `setTimeRange(newStart, newEnd)` where
  `newEnd = (end==MAX_VALUE)?MAX_VALUE:newStart+(end-start)` (see `applyTextOverlayMove`).
- audio: `AudioClip.getOffsetMs`/`setOffsetMs(newStart)` (no end to shift).
- PiP overlay clip: `Clip.getOverlayStartMs`/`setOverlayStartMs(newStart)`.
- caption span: read-only (`CaptionSpanRef`) — NOT movable.
- Dispatch on a `TimedItem` exactly as `onItemMenuRequested` does (`:12180-12190`):
  `getTextOverlay()/getSprite()/getAudioClip()/getClip().isOverlayClip()/getWaveform()`.

**⚠️ Selection model is FRAGMENTED (the main gotcha):** there is no single "selected object".
- master clip: `selectedClipIndex` (the drawer's existing clip-reorder buttons use this).
- audio: `editorTimeline.getSelectedAudioIndex()`.
- floating layer item (text/sprite/pip/waveform): `LayerGestureController.getSelectedItemId()`
  (on `editorTimeline`; NOT currently exposed to the activity — add a
  `EditorTimelineView.getSelectedLayerItemId()` passthrough, then find the `TimedItem` by id in
  `getLayers()`/`getAudioTracks()`).
- So build a `getSelectedMovableItem()` → `TimedItem` (floating id first, else audio index, else
  master clip = not free-movable), and a generic `moveSelectedObjectToTimeMs(target)` reusing
  `ObjectTimeScrubSession` for a discrete jump + the per-payload setter above.

**Slices (stop + let the user test after each):**
1. Fix the drawer "move to" (`performMoveToInput` :5428) to move the SELECTED floating object (not
   `seekToTimelineMs`) — keep the seek only when nothing movable is selected. Fixes the user's
   "timestamp jumps the playhead not the item".
2. Add a `TimeShuttleView` to the `move_drawer` layout XML (res/layout) + wire it like the object
   menu; make `move_position_*` track the selected object (not the playhead) via `refreshMoveDrawer`.
3. `move_layer_up`/`move_layer_down` (:5335-5340 stubs) → real object relayering (reuse
   `moveOverlayItemToAdjacentLayer`/`moveOverlayItemToNewLayer`, generalised per payload).
4. push-through relayer + cross-lane y-GLIDE (SPEC §9). Then Note 20 large-project refresh profile.

Reuse the object-menu path's `followScrubTimeMs` edge-tracking + `updateLayerItemStartLight` here.

## 12. FIELD FEEDBACK QUEUE (from device testing, 2026-07-27) — live list

Validated: the object-menu scrubber works + is "very smooth".

**DONE this chat (installed; awaiting user's final feel-confirm where noted):**
- [x] scrubber v1 — text overlays, lock-only (`3556d63`); shuttle/engine/session proven (31/31).
- [x] edge-tracking — pan before the edge (`c772384`).
- [x] **B-WARP + B-BELOW** — root cause was the NON-UNIFORM time axis (min-width clamp + inter-clip
  gap); proven off the SCRUBWARP log (dur constant). Fixed by making time UNIFORM (`1bfc121`).
  Awaiting the user's confirm that the warp is gone + clips still read separate.
- [x] **B-OVERSHOOT** — push-past now steps flush just past the obstacle, no shoot-past (`9a7a0a4`).
- [x] diagonal hand-drag loosened — small side-move breaks the lane time-lock (`699937b`).
- [x] terminology — rows="lanes", object move="Move layer", kept "object" (`1395738`).

**OPEN BUGS:**
- [ ] **B-ENDHERE** "End here" does NOTHING (Start here works) — a real logic bug in
  `setOverlayRangeEdgeAtPlayhead`'s end case, SEPARATE from the (now-fixed) scaling precision.
- [ ] **B-CLAMP** the scrub can push an object off past the timeline end forever (have to scroll
  back from infinity) — clamp so its start stops at the timeline end.
- [ ] **B-PREVIEW** during a move the preview isn't always clear; want a stable same-size box
  under the finger / a constant indicator of what's moving.

**OPEN — SCRUBBER COMPLETION:**
- [ ] extend the scrubber to the other payloads: sprite / audio (`setOffsetMs`) / PiP
  (`setOverlayStartMs`) / visualizer (currently text-overlay only).
- [ ] push-through relayer (lane up/down) + the buttery cross-lane y-GLIDE (SPEC §9); today the
  object-menu scrubber is lock-only, push-through toggle hidden.
- [ ] **F-MOVEDRAWER** (SPEC §11) build the scrubber into the move drawer (fix its "move to" to
  move the selected object; Lane up/down real relayering).

**OPEN — POLISH / FEATURES:**
- [ ] **F-COLOR** color objects BY TYPE always (not by lane, not by content color): text=purple,
  visualizer=pink, sprite=amber, image=teal, audio=green. The purple→blue-on-another-lane is the
  bug. Define the palette ONCE in a central place (single source of truth for the app's colors).
- [ ] **F-BADGE** object badge: fully WHITE (not gray); badge at the left edge, text starts right
  after it, the two SLIDE TOGETHER as the left edge scrolls off — never overlapping.
- [ ] **F-CENTER** on adding a new object, auto-scroll VERTICALLY to its layer if off-screen
  (playhead stays — objects land at the playhead).
- [ ] **F-PANDELAY** the pan-on-approach onto a long object needs a longer delay (kill false
  positives); EXEMPT any object spanning the full master length (can't move past the timeline).
- [ ] **F-CAPTIONDRAWER** the caption Pop/Zoom/Bounce drawer (`caption_drawer`/`showCaptionDrawer`,
  opened by the captions tool) should only appear + pop-animate when a caption is SELECTED
  (timeline OR preview), not stay up.
- [ ] **F-MINIMAP** enhance the mini-map: thin 1–2px per-layer lines above the master tape, colored
  BY TYPE, max 12 layers, stack order = timeline order, selected object PULSES white (medium blink,
  not outline), clipped to master length. Mockup approved. Build after the scaling bugs.
- [ ] **F-MOVEDRAWER** (SPEC §11) also build the scrubber into the move drawer.

## Appendix — user's answer, verbatim (2026-07-26)

> primary case is navigating the timeline. up down layers, shifting controlled in time like
> words in transcripts do. controllable variable speed shifter for fine or long distance moves.
> optional toggle to move in increments. as you ram one objeact into the next they lock but if
> you want you can push through, in that case the horazontal move on the timeline bumbs it up to
> a higher layer, if theres already something above it make a new layer and keeps sliding. once
> past the obstical it goes back to its origional lane. if you let go while its on a new or above
> layer it lock it in that is its new home id. but If you don't release, can you keep scrubbing
> it back and forth It will just move above and below. Try and get some best to keep in the lane
> it originated in, but it can be forced to the lane above if it's open or a completely new lane
> if it's not open. It shouldn't have a chain where it keeps going up layers. It just needs to
> have a limit of either the one above it or making a new layer so that it can continue to slide,
> and it'll pick up right where it left off on the other side of the obstruction going down. Also,
> there should be a jump to time where you can type in a specific time, and it'll move there. And
> if there's something in it's way, it will have the same behavior as if you scrubbed it over.

> [Exact values] there's a special control scrubber that will allow you to move long distances or
> slow down and do really fine tuned controlled things. Look up to see how best to execute this if
> you need to. And then also have the ability to tap the time to actually put in that specific
> time. The time will auto update as you scroll but you can always tap that time to put in their
> own custom one. That way you keep a frame of reference of where you had been to where you're going.

> [Q1 selections] On-screen position (x/y), Which lane it sits on, Time position (start)
