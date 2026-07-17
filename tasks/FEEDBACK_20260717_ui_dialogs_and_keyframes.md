# FEEDBACK 2026-07-17 — UI occlusion, drawer unification, keyframe ♦ standard (JoyRaptor hand-test)

JoyRaptor's morning pass over the 07-17-night build. Theme: **stop letting UI cover the thing it edits.**
Every answer below was code-verified this session (file:line anchors), not recalled from docs.

## GROUND TRUTH ANSWERS (verified live)

### Dual-stream toggle — WHERE IT IS + Note 9 IS capable
- The toggle is NOT in the floating helper panel. Path: **FadRec tab → tap the "Screen Recording"
  card (top-left, resolution row) → "Record webcam as separate file"**.
- Verified LIVE on the Note 9 (29e37138) this session: the row renders ("Off"), and the capability
  gate logged `Hardware AVC encoder max concurrent instances: 16` — far above the ≥2 requirement.
  The Note 9 is fully dual-stream capable.
- ⚠️ Installed build timestamp is 07:59:42; the Phases 1–3 recording commit (`23b194b`) was committed
  08:01. The watcher builds from saved files so the code was likely in, but treat "toggle ON actually
  produces `<name>_webcam.mp4`" as unproven until the spec's device checklist runs on a fresh install.

### Preview gesture map — what's bound today
| Object (preview) | Tap | Double-tap | Long-press |
|---|---|---|---|
| Image/text overlay | **opens MODAL dialog** (TextOverlayLayer:280 `onEditRequested`) | **UNBOUND — free** | — |
| Sprite/avatar | select/manipulate | ObjectMenuSheet drawer | — |
| Caption | style bar | style bar + caption-KF drawer | hide captions for clip |
| Visualizer | — | — | object menu (Customize/Delete) |

Timeline rows (G1): tap=select, double-tap=type editor, hold=pickup, hold-release-in-place=G2 drawer.

### The image-overlay dialog is the LAST modal object editor
`FaditorEditorActivity.showTextOverlayEditor` (:15343) puts image overlays in a
`MaterialAlertDialogBuilder` — modal, dims screen, blocks scrubbing → **"End here" is unusable**
(can't move the playhead with the dialog up). Everything JoyRaptor likes about the sprite drawer is
`ObjectMenuSheet` (G2), whose own javadoc states the governing principle: *"the drawer that covers
something is always the one you're NOT looking through"* — peek keeps preview AND timeline live.

### Sprite double-tap showing "opacity" — why
By design: `ObjectMenuSheet.show` (:285) hard-picks `"opacity"` as the peek row's active property.
The sprite type editor is behind "More…". JoyRaptor expected the sprite editor. (Decision needed: see D3.)

### Opacity keyframes "invisible" — TWO real causes, both confirmed
1. **Arming rule:** drawer sliders only write keyframes when the item is already armed
   (`spriteMenuProp` setter: `if (s.isArmed()) addPropertyKeyframeAt(...) else setOpacity(...)`).
   Un-armed drag = static opacity, silently. No feedback that you're NOT keyframing.
2. **Layer rows draw NO general keyframe visuals.** `LayerRowRenderer` draws sprite frame-swap
   diamonds (S5), caption-style diamonds, and the AUDIO volume rubber-band — but no opacity
   envelope and no property-keyframe diamonds for overlay/sprite items. The white opacity
   rubber-band JoyRaptor remembers is **master-clip only** (`EditorTimelineView:3218`,
   `Clip.OpacityKeyframe` — a SEPARATE model from the overlays' `KeyframeSet.OPACITY`).
   This is exactly the **A14 parity audit** dragux_v3 already requires: *"the teal lane's remaining
   exclusives (keyframe-diamond editing, opacity keyframe drag) must exist on rows before the lane
   collapses."* Specced, never built.

### Purple "Drop here for new layer" box — already specced away, never executed
`FEEDBACK_20260703_dragux_v3.md` **SLICE 2 BLUEPRINT (BINDING)**: kill the pinned zone, the
cross-band arm, the "new layer here" text; ONE vocabulary — every row gap (plus above-top,
below-bottom) is an insertion target drawn as a single accent **insertion line** ("incision line");
release creates the track AT that index. Renderer still draws the old zone (`LayerRowRenderer:489`).
Blueprint says "EXECUTE at the top of a fresh window" — that window never happened.

### Easing — model exists, UI does not
`keyframe/Easing.java`: LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD — applied per-keyframe in
`KeyframeTrack.valueAt`. **No UI anywhere sets easing.** Overshoot/spring/log/cubic = new enum
entries (pure math in `Easing.apply`, serializes with the project like the rest).

## NEW BINDING DECISIONS (JoyRaptor 2026-07-17)

**D1 — No modal object editors.** Object-scoped editing = non-modal drawer (ObjectMenuSheet chrome),
timeline stays live. Modals only for destructive confirms + pickers. First target: image overlays —
tap = select + handles only; **double-tap = drawer** (slot is free); retire the
image branch of `showTextOverlayEditor`. "Start/End here" + trim move into the drawer as rows/actions
and START WORKING mid-scrub (that's the point).

**D2 — The `<♦️>` program-wide keyframe control.** One reusable widget, used EVERYWHERE a property
can animate (drawer rows, caption drawer, master-clip opacity/volume, future PiP/audio props):
- `<` `>` chevrons flanking the diamond = jump prev/next keyframe (G3's swipe-nav, made visible).
- Diamond hollow = not on a key → tap drops one. Solid = on a key.
- On a key, the solid diamond renders with a small **×** subtracted from center → tap = remove THAT
  key (replaces the big "Clear" button; "clear all" moves to overflow).
- **Long-press diamond = ease picker**: visual curve presets (CapCut/KineMaster style graph
  thumbnails, never text). (Long-press-delete, its old G3 meaning, is superseded by ×-in-diamond.)

### D2a — THE CURVE SET (JoyRaptor 2026-07-17: "practical curves for real-work animation")
Researched against what CapCut (linear/ease-in/out/in-out ×2 strengths + bounce), KineMaster 7.5
(preset + custom graphs), and Figma/Motion (springs parameterized by damping) actually ship, plus
the motion-design canon (overshoot vs bounce vs anticipation are DISTINCT tools). Kept as enum
entries — `Easing.java`'s own contract ("simple, AI-authorable project file, no bezier handles")
is preserved. Existing 5 stay; **9 new**:

| Preset | Feel / real-work use | Math sketch |
|---|---|---|
| EASE_IN_EXPO | dramatic launch (harder than cubic) | 2^(10(t−1)) |
| EASE_OUT_EXPO | snappy arrive / "snap" | 1−2^(−10t) |
| ANTICIPATE | back-in: dips backward, then commits (the animation-principle anticipation) | back-in, s≈1.70158 |
| OVERSHOOT | back-out: one swing past target, settle — the UI-pop workhorse | back-out, s≈1.70158 |
| SPRING_SOFT | one gentle overshoot (ζ≈0.75) | damped sine, endpoint-normalized |
| SPRING | 2–3 visible oscillations (ζ≈0.5) | damped sine |
| SPRING_BOUNCY | lively wobble (ζ≈0.3) | damped sine |
| BOUNCE | ball-drop rebounds — never crosses target (≠ spring) | Penner bounce-out |
| STAIRS_4 | jump stair-step, 4 equal holds (JoyRaptor's "jump stairsteps") | floor(t·4)/4 → renorm to end at 1 |

Three damping levels of spring = JoyRaptor's "different levels of dampening" without exposing physics
sliders. Implementation rules: every `apply()` MUST hit exactly 0 at t=0 and 1 at t=1 (springs get
a residual-ramp normalization); **preset thumbnails are RENDERED FROM `Easing.apply()` itself**
(sample into a Path) so the preview can never lie about the math, with a small animated demo dot
on the focused preset (KineMaster-style). Picker surface obeys D4: a compact popover anchored at
the diamond (grid of icon tiles), NOT another sheet — the live preview is the object itself moving
in the preview window. The picker edits the segment the playhead is IN (on-a-key = its outgoing
segment).

**Picker visual reference (JoyRaptor 2026-07-17, KineMaster "Graphs" screenshot):** grid of
rounded-square tiles, each a thin monochrome curve thumbnail on a dark tile — axes hinted as two
faint baseline strokes, curve brighter. First tile = **⊘ "linear/none"** (no easing). KineMaster's
second tile is a custom-graph editor — we deliberately DON'T ship that (the `Easing` enum contract
keeps project files simple/AI-authorable; revisit only if the presets prove insufficient).
**Selected tile = GREEN highlight, not KineMaster's red** (JoyRaptor): stroke + faint fill tint of the
drawer's existing accent `0xFF4CAF50`, rounded-rect ring exactly like the screenshot's treatment.
- Kill the "◆ Add keyframe" mega-button + "Clear" button in `buildOverlayAnimationControls` once the
  drawer takes over image overlays.

**D3 — Drawer focus + retargeting rules.**
- Selecting/tapping a DIFFERENT object (preview or timeline) closes/retargets any open drawer.
  (Today `onItemSelectionChanged` never hides the sheet — JoyRaptor hit sprite-drawer-over-image-dialog.)
- Tapping an object in the preview also **vertically scrolls the layer band** so its row is visible
  (mirror of the pinned-thumbnail trick, opposite direction).
- Peek default property: revisit hard-coded "opacity" — JoyRaptor expected the sprite editor on a sprite.
  Options: per-type default (sprite → sprite editor row/More), or last-touched-property memory.
  NOT decided yet — ask before building.
- Drawer header: **trash REMOVED from the drawer entirely** (the timeline selection badge is the
  one delete affordance). With the trash gone, **× STAYS on the RIGHT** (JoyRaptor 2026-07-17).

**D4 — Occlusion principle (program-wide).** For any new surface, decide explicitly which of the
three costs it pays: (a) cover part of the preview, (b) cover part of the timeline, (c) shrink
preview/timeline real estate. It must NEVER cover the thing it edits or the surface the user must
touch for its own workflow (the modal image dialog violated both). Peek-height drawers between
preview and timeline are the default answer.

## SPEC CORRECTIONS — pre-execution review (2026-07-17, code-verified)
JoyRaptor asked: "anything wrong with the specs that could be executed on better?" Findings:

**C1 — D1 as first written only covered images; text overlays share the same modal path.**
`onEditRequested` fires on single tap for BOTH; retiring only the image branch leaves text
throwing a modal over the preview — inconsistent grammar, same occlusion sin. D1 now covers both:
tap = select + handles for every overlay; the text type editor (keyboard dialog) stays reachable
per the grammar decision below.

**C2 — "Start/End here in the drawer" would NOT have fixed JoyRaptor's bug as specced.**
`ObjectMenuSheet.actionsBox` is **expanded-only** (`:318`), and expanded covers the timeline — so
range actions in "the drawer" would still be unusable mid-scrub. Correction: **peek gains a slim
range-chip row** (⇤ Start · End ⇥ · duration) under the active-prop row. Peek = grip + prop row +
range chips; nothing else. That is the whole point of the feature — verify by scrubbing WITH the
drawer open and extending an image via End-here.

**C3 — Preview double-tap grammar is self-contradictory across the specs.**
G1 (timeline): double-tap = TYPE editor. Preview today: sprite double-tap = GENERAL drawer (the
thing JoyRaptor was surprised by), caption double-tap = caption-KF drawer, image (proposed D1) =
general drawer. Two grammars for one gesture. **DECIDED (JoyRaptor 2026-07-17): double-tap = TYPE editor everywhere.**
Sprite → sprite editor, text → text editor, image → the general drawer (an image's type editor IS
the general drawer — it has no other content). Matches G1's timeline grammar. The general drawer
opens via **HOLD on the object in the preview** (same ~400ms/no-move threshold as the timeline
pickup; plain drags still move objects instantly). Caption keeps its double-tap → caption-KF
drawer (that IS its type editor) but its long-press → hide-captions moves to the drawer/badge so
hold can mean "general drawer" uniformly.

**C4 — A14 "parity" is over-specced; execute display-parity, not interaction-parity.**
The teal lane's value-drag editing is redundant once the drawer + <♦️> exist. Rows need exactly:
(1) **consolidated keyframe diamonds** — union of key times across ALL properties of the item (a
34dp row cannot show 5 property lanes; solid = any property keyed there); (2) **horizontal drag of
a row diamond = move that key in time** — the ONE interaction the drawer can't do well; (3)
**opacity-only rubber-band** as the row envelope (the visually meaningful one — matches audio rows
drawing volume-only). Value editing stays in the drawer. Envelope draws with a subtle scrim so it
stays legible over the new §2 preview thumbnails/filmstrips.

**C5 — Slice 2 needs three riders the blueprint is silent on.**
(1) Gap targets between rows are thin: generous hit zones + STICKY hover (once armed, held until
finger exits by >8dp) or the line flickers at boundaries. (2) Interplay with the bookend
excursion: entering a gap must DISARM the excursion (both animate the view; two simultaneous
animated scrolls = chaos), and gap hit-testing runs in content coordinates recomputed per frame.
(3) Insertion index → z renumbering must be one undo step incl. ordering restore;
`stageCreateLayerAndMoveItem` currently APPENDS — gains an index param. Scope: floating band
first; audio-band gap parity is a follow-up.

**C6 — "Close drawer on other-object tap" should RETARGET, not close, when possible.**
If the newly selected object has drawer adapters (text/image/sprite), the open drawer re-shows
for it at the same peek/expand state — flow preserved. Close outright only for adapter-less types
(audio/PiP/viz until their §2 props land). Guard: selection changes CAUSED by a drawer action
(e.g. delete) must not immediately re-open/flicker it.

**C7 — Arming honesty: no surprise keyframes.**
Un-armed slider drags stay static (current behavior is right); the fix is FEEDBACK, not
auto-keying: first un-armed drag per sheet-showing flashes an inline hint on the row — "Static —
tap ♦ to animate" — and the diamond pulses once. Auto-dropping keys on slider touch would create
invisible animation, the worse evil.

**C8 — Slice 3 stragglers confirmed real (not in code): A1 edge auto-pan; the outline
color-state audit (purple = cross-row family).** Fold both into the Slice 2 window — same files,
same verify pass.

**C9 — Peek default property mostly self-solves.** The sheet already promotes the last-touched row
to peek (`:515`) within a showing; persist last-active-per-object-TYPE across showings and drop
the hard-coded "opacity" pick. (Moot for sprites if C3(a) wins — the drawer then opens via hold,
already property-focused.)

## EXECUTION ORDER + STATUS
1. **Drawer batch — ✅ BUILT 2026-07-17 (compile-green; device install blocked: sandbox went
   adb-unauthorized mid-session — JoyRaptor: accept the USB prompt / cycle USB debugging, the watcher
   installs on its next build).** Landed per D1/D3/C1/C2/C3/C6:
   - Program-wide preview grammar: **tap = select** (routes through `selectLayerItemById` → one
     selection pipeline), **double-tap = type editor** (text → text dialog, sprite →
     `openSpritePalette`, image → general drawer), **hold = general drawer** (~400ms
     long-press-timeout, haptic; plain drags still move objects instantly). Both
     `TextOverlayLayer` and `SpriteOverlayView` now share the same manual tap-pair/hold pattern.
   - The **modal image-overlay dialog is retired** — `showTextOverlayEditor` delegates image items
     to the drawer at the top, so every legacy call site (incl. timeline G1 double-tap) is covered.
   - **Peek range chips** ("⇤ Start here" / "End here ⇥") on the overlay drawer —
     `setOverlayRangeEdgeAtPlayhead` with the old dialog's validation + a NEW one-step undo the
     dialog never recorded. Chips visible in PEEK (actionsBox stays expanded-only per C2).
   - **Drawer trash removed; × stays right** (JoyRaptor). Sprite delete moved to the selection badge
     (`deleteSpriteWithConfirmation` branch added to `onItemDeleteRequested` — the badge had NO
     sprite branch; the drawer trash was silently the only sprite delete).
   - **C6 retarget:** `onItemSelectionChanged` re-shows the open drawer for the newly selected
     text/image/sprite, hides for adapter-less types. Also **preview-tap reveals the item's layer
     row** (`LayerRowRenderer.revealRowForItem` + `EditorTimelineView.revealLayerRowForItem`).
   - DEVICE-VERIFY OWED (JoyRaptor, real fingers — adb can't fake double-tap feel): tap/double/hold on
     image + text + sprite in preview; scrub with drawer open → End-here extends an image; drawer
     retarget on cross-object taps; row auto-reveal; sprite badge delete.
2. **Slice 2 gap-insertion — ✅ BUILT 2026-07-17 (`32d19bb`, compile-green).** Pinned purple zone
   + cross-band arm text KILLED; every floating-band gap (+above-top/+below-bottom) is an
   insertion target drawn as one accent line; release creates the track AT that index with z
   renumbered in ONE undo step (old zIndexes snapshotted for fold-in undo). C5 riders in: sticky
   hover (2x exit zone), gap entry disarms the bookend excursion, content-coordinate hit-testing.
   C8 stragglers (A1 edge auto-pan, outline color audit) NOT included — still open.
   DEVICE-VERIFY OWED: pick up an item, hover each gap incl. above-top/below-bottom, drop → new
   layer at that position; undo restores in one step with old ordering.
3. **Keyframe visibility batch:** `<♦️>` widget (D2) + C7 arming honesty + C4 display-parity on
   rows (consolidated diamonds, time-drag, opacity-only rubber-band).
   - D2a CURVE MATH ✅ 2026-07-17: `Easing.java` grew EASE_IN_EXPO/EASE_OUT_EXPO/ANTICIPATE/
     OVERSHOOT/SPRING_SOFT/SPRING/SPRING_BOUNCY/BOUNCE/STAIRS_4. Endpoint contract JVM-verified
     (exact 0/1 via guards; springs use ω=odd·π/2 so cos≡0 at t=1 — no residual ramp needed;
     BOUNCE never crosses 1; SPRING_BOUNCY max 1.34 > SPRING_SOFT max 1.036).
4. Ease picker presets (D2a UI) — after the widget exists.
5. Then back to the device-verify queue (dual-stream checklist etc.).

## STILL-OPEN SPEC DEBT (audit 2026-07-17, so we stop re-inventing)
- dragux_v3 **Slice 2** gap-insertion (BINDING, unbuilt) — item 2 above.
- dragux_v3 **A14** teal-lane retirement + row keyframe-visual parity (unbuilt) — item 3 above.
- LANE_BADGES **§4.5** eye/lock → per-object migration (deliberately deferred; needs per-object controls).
- ObjectMenuSheet §2 Prop adapters for **audio / PiP / visualizer** (drawer exists, types unwired).
- Marquee multi-select limited to floating band (audio band documented follow-up).
- **Dual-stream Phase 4** (editor `linkedClipId` mirrored edits) — editor lane, not started.
- **Visualizer-studio Phase 4** (live-recording viz, perf-gated) — not started.
- Export-side **GL transition A/B** proof — untouched.
- **H.264 baseline profile** media3-fork patch (scoped in H264_BASELINE_PROFILE_FINDING_20260714).
- Audio **ducking** (duckAmount stored, never applied), transcript windowing, relink manifest screen
  — post-v1 backlog per road_map.
- JoyRaptor-gated ship blockers: rebrand assets, de-politicize scope calls, bookmarks/time-chip fold-in.
- Gesture-contract hand-test confirmation + ROWGESTURE log strip (PLAN_LAYER_GESTURE_CONTRACT last
  two boxes) still open.
