# SPEC — Object time-scrubber (precise, touch-free object navigation)

> ## ⚠ STATUS CORRECTIONS 2026-08-06 (line-by-line code audit — trust these over the body)
> - **§8 editor wiring IS built** for TEXT overlays: `ObjectMenuSheet.setTimeScrub` is called
>   from `FaditorEditorActivity.attachTextOverlayTimeScrub`. Path: long-press a text object
>   → object menu → "Move in time". The §7 header saying it is NOT built is wrong.
> - **"'End here' does NOTHING" is fixed** — range chips are peek-visible during a scrub.
> - Still genuinely MISSING: extending the scrubber to sprite / audio / PiP payloads
>   (`setTimeScrub` has exactly one call site), and the §9 cross-lane y-GLIDE.
> - ORPHANED: `ObjectTimeScrubSession.currentLane()` / `currentStart()` have no callers —
>   the wiring took a shortcut around the designed read-back contract.



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
1. **Mute (`8d528db`):** tap the mute glyph on a TEXT/visual lane (no audio) → nothing should
   happen (no undo entry appears). On an audio lane → it still toggles. Correct = disabled-looking
   mute is inert.
2. **Gap target (`fd9a77a`, 5dp):** pick up an object and drag it toward a NEIGHBOURING lane →
   it should land ON that lane, not spawn a new one. Deliberately aim at the thin gap between rows
   → a new lane should still be creatable. If lane creation feels too hard, nudge 5dp→6dp; if it
   still steals row taps, 5dp→4dp (`GAP_HIT_HALF_DP`, LayerRowRenderer).
3. **Hold-release menu (`eec7ed2`, 8dp):** select an object → hold → release in place → the object
   menu should now open reliably (was "sometimes"). Retune `MOVE_SLOP_DP` in EditorTimelineView.
4. **Trim unchanged (`9c732e1`):** grab a text/audio/PiP item's edge and trim → should feel exactly
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
- [x] scrubber v1 — text overlays, lock-only (`fcd5ade`); shuttle/engine/session proven (31/31).
- [x] edge-tracking — pan before the edge (`dc39134`).
- [x] **B-WARP + B-BELOW** — root cause was the NON-UNIFORM time axis (min-width clamp + inter-clip
  gap); proven off the SCRUBWARP log (dur constant). Fixed by making time UNIFORM (`d697ca9`).
  Awaiting the user's confirm that the warp is gone + clips still read separate.
- [x] **B-OVERSHOOT** — push-past now steps flush just past the obstacle, no shoot-past (`3d58688`).
- [x] diagonal hand-drag loosened — small side-move breaks the lane time-lock (`c0d4241`).
- [x] terminology — rows="lanes", object move="Move layer", kept "object" (`1ab0d88`).
- [x] **B-ENDHERE FIXED (status corrected 2026-08-06 by code audit)** — "End here" does NOTHING
  was a real logic bug in `setOverlayRangeEdgeAtPlayhead`'s end case; both edges now validate and
  set symmetrically (`ph <= o.getStartMs()` guard, `afterEnd = ph`) — see
  `FaditorEditorActivity.setOverlayRangeEdgeAtPlayhead` (~line 19450).

**OPEN BUGS:**
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
- [x] **F-COLOR** DONE `c174205`. Palette now lives ONCE in
  `com.fadcam.ui.faditor.layers.ObjectPalette` (text=purple, visualizer=pink, sprite=amber,
  image=teal, audio=green, caption=gold, video/master=blue). The purple→blue bug was structural:
  `drawExpandedItems` hoisted `baseColorFor(track.getKind())` OUT of the item loop, so every
  object wore its LANE's colour — body colour is now resolved per ITEM (including the cross-lane
  drag proxy, which used to change hue mid-drag). The hues had ALSO been duplicated in two
  hand-kept tables (`COLOR_ITEM_*` + `COLOR_PH_*`) that had already drifted — neither had an
  IMAGE case, so images took VIDEO blue via `default:`. Both deleted. `payloadKindOf` moved into
  the palette so badge / body / playhead tint / mini-map share one definition.
  PROVED: `tools/jvm-harness/ObjectPaletteTest.java` 30/30, each check with a positive control.
  NOT proved: that the pixels actually paint this way (needs a Canvas) — **wants the user's eyes**.
- [ ] **F-BADGE** object badge: fully WHITE (not gray); badge at the left edge, text starts right
  after it, the two SLIDE TOGETHER as the left edge scrolls off — never overlapping.
- [ ] **F-CENTER** on adding a new object, auto-scroll VERTICALLY to its layer if off-screen
  (playhead stays — objects land at the playhead).
- [ ] **F-PANDELAY** the pan-on-approach onto a long object needs a longer delay (kill false
  positives); EXEMPT any object spanning the full master length (can't move past the timeline).
- [ ] **F-CAPTIONDRAWER** the caption Pop/Zoom/Bounce drawer (`caption_drawer`/`showCaptionDrawer`,
  opened by the captions tool) should only appear + pop-animate when a caption is SELECTED
  (timeline OR preview), not stay up.
- [x] **F-MINIMAP** DONE `c174205`. Per-layer lines above the master tape: 1.2dp lines on a 2dp
  pitch, floating band then audio (timeline order), capped at 12, coloured by type through
  `ObjectPalette`, selected object blinks white, all clipped to master length so an object parked
  past the end mid-drag can't paint outside the strip. An EMPTY layer still draws a faint rail so
  it reads as a layer that exists. The band is ADAPTIVE (zero when a project has no layers), so a
  plain single-track project measures/draws exactly as before; height recomputes in
  `setLayerTracks` and `onMeasure` reads the live strip height, not the base constant.
  **CONFIRMED BY THE USER 2026-07-27 on the Note 20's real project: "minimap looks great".**
  So the adaptive band, the 2dp pitch and the by-type colouring all read correctly at real layer
  counts — no density tuning needed. (`b0400b8` playback also re-confirmed the same session:
  "played well".)
- [ ] **F-MOVEDRAWER** (SPEC §11) also build the scrubber into the move drawer.

## 13. ROUND-2 DEVICE FEEDBACK (2026-07-27 ~15:00) — after the uniform-axis batch

CONFIRMED WORKING: uniform timeline (drags now uniform width), "lane" terminology, caption size
slider resizes text live.

- [x] **B-PLAYFREEZE** (Note 20, large project) FIXED `b0400b8` — **CONFIRMED ON DEVICE
  2026-07-27 17:48**, in the user's real project. 90 post-fix PHDIAG samples: **0 frozen, 0
  `drag=true`**, playhead advancing throughout.
  **The confirming run is more informative than a clean one would have been.** The self-heal
  FIRED once (`17:48:50.834 userDragging was stranded with no active gesture — clearing`) and
  playback continued uninterrupted straight through it (next sample `t=321 moved=true`). So:
  (a) the layered fix is doing real work, not decorating a single-path repair; and (b) **a
  SECOND stranding path still exists** — the post-pinch pan was fixed directly, so something
  else stranded the latch here. Under the old build that moment would have frozen the playhead
  permanently. It is now caught by the net, so it is no longer user-visible, but it is a latent
  defect worth closing so the net stays a net.
  **TODO (not user-visible, do when the user is NOT mid-test — a save reinstalls and kills their
  session):** name the second path. Prime suspects are the bypassing returns found during this
  work and deliberately not edited blind: the `if (isScaling) return true` UP, the audio-band
  tap/double-tap returns, and the slide double-tap return. Cheapest instrument: record which
  touch branch consumed the last ACTION_UP and log it in the heal warning.
  Reported as: during playback the playhead stops, the timeline stalls and text layers stop
  compositing in as they enter, while video+audio keep playing; scrubbing to a text's range still
  renders it; intermittent; "works zoomed in, breaks when zoomed out".
  ROOT CAUSE, measured not guessed (PHDIAG instrumentation, `61f184d`, two independent app
  processes): `playing=true` with `playerPos` advancing 157900→188175 over 30s while `head`
  never changed — and `drag=true` throughout. `userDragging` was a STRANDED LATCH.
  `updatePlayheadPosition()` early-returns on it, so the return became permanent.
  MECHANISM: pinch to zoom out → lift one finger → the surviving finger drives the "post-pinch
  handback pan" → that pan calls `updatePlayheadFromX` → `onPlayheadSeeked(isDragging=true)` and
  LATCHES → its `ACTION_UP` branch returns before the shared block that fires
  `onPlayheadDragFinished()`. Intermittent because a hard flick starts a fling whose completion
  path DOES notify (recovers), while a gentle lift does not (sticks). Zoom-only because the
  pinch is what hands off to that pan.
  Worth recording: a read-only pass produced two plausible hypotheses (`selectedClipIndex < 0`,
  `getSegmentAtPlayhead() == -1` in a gap) and the instrument showed BOTH were wrong (`sel=7
  segAtHead=7`). That is why it was instrumented instead of patched.
  FIX: (1) that branch now ends the drag it started unless it handed off to a fling; (2) the
  class is closed — the view tracks finger-down at the TOP of `onTouchEvent` where no branch can
  skip it, exposes `isGestureActive()` (finger down OR fling gliding), and the editor clears a
  latch that outlived its gesture instead of trusting ~12 exit paths to each notify. Also
  `onPlayheadSeeked` no longer latches for a DISCRETE seek (a tap has no drag-finished edge).
  PHDIAG is deliberately LEFT IN as the confirmation instrument — one play session after a
  zoom-out should show `drag=false`/`moved=true`. **Remove PHDIAG once confirmed.**
  STILL OPEN from the same report, NOT yet investigated: the unnaturally long pause at
  inter-clip gaps, and playback perf with many text/visualizer/PiP layers. First thing to rule
  out for the gap pause: the display-only inter-clip inset from `d697ca9` is a RENDERING inset
  and must not be costing playback time.

- [ ] **B-DIAGPREVIEW** (diagonal drag) — the loosen (`c0d4241`) WORKED: the object now goes
  where it should. BUT the PREVIEW during the drag is wrong — it stays snapped VERTICALLY and
  doesn't show the horizontal (side-to-side) offset until the finger LIFTS. So it PLACES correctly
  but isn't WYSIWYG — the user can't see where it'll land until release. Fix the drag PROXY to
  render the live diagonal position (horizontal offset) during the drag, not just on drop.
- [ ] **B-GREENHILITE** — the green highlight on the clip under the playhead/selection
  disappeared; user asked why. Green = `COLOR_SEGMENT_SEL` fill / `COLOR_BORDER_SEL` border for
  `i == selectedIndex`. Uniform `drawSegment` change only inset `r`; VERIFY it's not a regression
  (note: the green FILL only draws on clips WITHOUT thumbnails — thumbnailed clips show selection
  via border/overlay). Check on-device whether a clip is still visibly selected.
- [ ] **F-COLOR reconfirmed OPEN** — a text layer STILL changes color when moved to another
  lane. Color BY TYPE (SPEC §12 F-COLOR) is the fix; still not built.
- [ ] **D-OVERSHOOT-v2** — the step-past (`3d58688`) still overshoots. User's refined design: on
  breakthrough (nudge past the obstacle WITHOUT releasing), transport the object flush past it
  AND RESET SHUTTLE VELOCITY TO ZERO so motion STOPS completely — giving the user a beat to
  register + decide — then continued pushing re-accelerates from zero. All without lifting the
  finger. Needs session↔shuttle coordination (an onStepPast → shuttle re-baseline / zero-velocity).
- [ ] **F-CAPTIONGEST** — long-press OR double-tap on a caption IN THE TIMELINE should open the
  caption style drawer (currently nothing happens). Map both gestures to caption style.
- [ ] **F-CAPTIONPREVIEW** — long-press on a caption in the PREVIEW currently toggles invisible;
  change it to open the caption style drawer (invisible is redundant — it's on the bottom bar).

### Round-2b (2026-07-27 ~15:20)

- [x] **caption-size undo flooding — FIXED** (`this batch`): the size slider recorded an undo on
  every onChange tick; now suppressed during the drag + ONE undo on release (Slider touch listener).
- [ ] **STRAY-DOT-PAST-END** — DIAGNOSED on project `129d8643`: `timeline.spriteOverlays[1]` has
  `startMs=12645` (past the ~11305ms 4-clip end) and NO endMs (open-ended), so its drawn width
  collapses to a dot and it sits off past the end; zooming pushes it out of frame; can't be
  reached/expanded. General fix (for all users): (a) an object stranded PAST the master length
  should stay reachable — the mini-map (F-MINIMAP) would surface it, and/or clamp/flag it; (b) a
  degenerate-width object (open-ended start past end, or ~0 duration) should render at a MIN
  visible/tappable width so it's not an untouchable 1px dot. Immediate: the user's specific dot can
  be cleared by fixing that sprite's start/end in the project JSON, but the class needs the general
  handling above.
- [ ] **B-GREENHILITE clarified** — NOT gone: the selected-clip green (`COLOR_SEGMENT_SEL`) is
  still there, but it no longer tracks the clip UNDER THE PLAYHEAD dynamically (used to move as the
  playhead moved; now static on the selection). Check whether a playhead-follows-selection / active-
  clip highlight regressed with the uniform `computeRects`/`drawSegment` change, or was always
  selection-only. Lower priority; user just wants the under-playhead clip visibly indicated.

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
