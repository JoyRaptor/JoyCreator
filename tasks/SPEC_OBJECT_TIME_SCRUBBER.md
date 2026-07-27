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
