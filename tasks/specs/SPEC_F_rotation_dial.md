# SPEC F — Rotation dial (the AE angulator) instead of rotation sliders

**Difficulty: MEDIUM. One custom view + one integration point per object type. Read
`_RULES_READ_FIRST.md` first, and `SPEC_A_rotation_beyond_360.md`'s "Delivery record" —
the dial exists because of F1 in that record.**

## What JoyRaptor asked for, verbatim

> "I think Adobe After Effects gets around that by instead of having a slider, they have a
> dial with a little point at the end, and that dial can extend as many times as it needs.
> And I think they even have a little wrap-around tape that has a low opacity — so each
> time it wraps around, you can actually see the opacity building. So if something is like
> one and a half turns, you'll see gray going towards white where they overlap, and if you
> have like 10 turns it's like pure white because of so many overlaps. You have the top
> with a little notch for one rotation. There's a little number readout that shows the
> change, and tapping on that can enter in the values — 360 degrees, 16x, etc. You have
> negative and positive. So it might be that we just have the wrong type of helper."

## The task

Replace every rotation SLIDER row in the object drawers with a rotation DIAL:

- A circle with a needle/point at the end, pointing at the current angle.
- **The needle is not limited to one revolution** — it keeps its total winding. The value
  behind it is the raw stored degrees (SPEC A), never folded.
- **The wrap tape**: each full turn lays a translucent arc under the dial; overlaps
  accumulate opacity, so 0.5 turns, 1.5 turns (one gray overlap region) and 10 turns
  (near-solid) are visually distinct — the dial shows the WINDING at a glance.
- **A notch at the top** marking the single-turn boundary (0°/360°).
- **A numeric readout** beside it that behaves exactly like today's tap-to-type: tapping
  opens the prompt, and `720`, `-45`, `16x`, `16 x` parse via
  `KeyframeSet.parseRotationInput` (already built, SPEC A). Garbage keeps the old value.
  The readout text stays honest (`720°`, `-45°`, `5760°`).

Integration: every row whose prop key is `KeyframeSet.ROTATION` — text overlay
(FaditorEditorActivity ~25858), PiP clip (~27695), image overlay (~29978), sprite drawer
(~26551), waveform visualizer sheet (~28993). The dial feeds the SAME
`ObjectMenuSheet.Prop` write path (`prop.write(v, playhead)`), so keyframe diamonds,
arming, undo snapshots and autosave all keep working unchanged.

House precedent for a custom circular control with touch handling:
`ui/faditor/tools/ColorWheelView.java`. Angle-from-touch math precedent:
`overlay/PreviewHandlesOverlay` (atan2 around a centre, already used for rotation
handles).

## THE TRAP

**Rebuilding the collapse this spec exists to kill.** Three ways to reintroduce it:

1. Folding the needle angle for drawing and then writing the FOLDED value back on
   gesture. The dial must track winding: gesture delta is ADDED to the current raw value
   (`newRaw = raw + deltaDeg`), and the needle angle drawn is `raw` too (canvas.rotate
   accepts any degrees; the wrap tape is what makes 720° *look* different from 0°).
2. Modulo anywhere in the dial's read or write. The tape opacity comes from
   `floor(|raw| / 360)` accumulation — it must not derive from a folded angle.
3. Keeping a slider next to the dial "as a fallback" — a rotation slider write is the
   F1 collapse. Rotation rows get the dial, not the slider plus the dial.

Also: do not regress SPEC A. `parseRotationInput` is the one door for typing; the dial is
the door for dragging. Both write raw. Neither clamps.

## Acceptance criteria

1. With value 540: the needle points straight down (1.5 turns) AND one overlap band is
   visibly darker than a single pass — 540 and 180 look different.
2. Dragging the dial a quarter turn from 0 stores 90; three more quarter turns store 360,
   then 630 — raw winding, no fold, readout keeps up live.
3. Tapping the readout: `720` → 720, `16x` → 5760, `-45` → −45; `abc`/empty keeps the old
   value. (Same behaviour SPEC A shipped.)
4. No rotation slider remains on any ROTATION row; nothing else about the rows changes
   (position, scale, opacity rows untouched).
5. Keyframes still work: diamonds drop at the playhead, dial edits write at the playhead,
   playback animates through the values, undo restores raw values.
6. An existing project's stored rotation renders the same pose as before the change.
7. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.

## Deliver

The list of rotation rows converted (file:line each), what the dial does for the wrap-tape
rendering, how gesture delta preserves winding, the build verdict, and whether it is
compile-verified or device-verified — honestly worded.

---

## Design candidates (2026-09-05) — AWAITING JOYRAPTOR'S PICK

`tasks/design/ROTATION_DIAL_OPTIONS.html` — one page, four WORKING dials (touch + mouse),
all sharing the SPEC A grammar via a faithful JS port of `parseRotationInput` (720 / -45 /
16x / 2.5x in, garbage keeps old), all showing raw winding, a top notch, and a shared
"pose preview" card so 540° vs 180° FEELS different:

1. **AE angulator** — needle + one translucent arc per full turn at the same radius;
   overlaps brighten (JoyRaptor's After Effects reference, most literal).
2. **Turn rings** — each completed turn is its own ring stacking inward, turn count in
   the middle; turns are COUNTABLE, no alpha pile-up needed.
3. **Jog wheel** — the whole wheel rotates under the finger (jog/shuttle feel), fixed
   notch, readout in the hub, wrap tape outside.
4. **Spiral reel** — the tape unrolls as a spiral, one loop per turn, head dot = needle.

Interactions on the page: drag = rotate (pointer-angle delta ADDED to raw value — winding
preserved by construction), tap number = typing dialog, double-tap dial = 0, preset chips
(0 / 720° / 16x / −45°). **JoyRaptor plays, picks one or a combination; record the pick here,
then implement exactly that look** — the next AI should not re-derive the design.

## Integration map (traced 2026-09-05, against the current tree)

### The universal contract
Every rotation row already flows through ONE abstraction:
`ObjectMenuSheet.Prop` (`ui/faditor/ObjectMenuSheet.java:86-199`) — key / label / slider
min-max / `format(v)` / `valueAt(playhead)` / `write(v, playhead)` + keyframe machinery
(arming, diamond drop, prev/next/delete key, ease, span). Two renderers consume a Prop:

- `ObjectMenuSheet`'s own slider rows — SeekBar at `ObjectMenuSheet.java:818-896`,
  `GestureHooks.onSliderStart/onSliderCommit` bracket one drag for undo.
- `PipDrawerTabs.addPropRow` (`tools/PipDrawerTabs.java:144`); the value text tap opens
  `promptForValue` (`PipDrawerTabs.java:260/358 → 377`), whose rotation branch (SPEC A)
  is keyed on `prop.key() == KeyframeSet.ROTATION`.

**So the dial is one custom view + one row-renderer change, not five features.** Build a
`RotationDialView` (house precedent for a custom circular touch control:
`tools/ColorWheelView.java`; angle-from-touch math precedent:
`overlay/PreviewHandlesOverlay`), then render it in place of the SeekBar whenever
`prop.key()` is `ROTATION` — in BOTH renderers. Everything else (typing, keyframes,
undo, autosave) keeps working through the same Prop.

### The five rotation rows to convert (Prop factories)
| # | Object | Prop factory site | Value source | Write path |
|---|--------|-------------------|--------------|------------|
| 1 | Text overlay drawer | FaditorEditorActivity.java:25858 (`overlayMenuProp(o, K_ROT, "Rotate", -180,180, deg, ms->o.animatedRotation(ms))`) | `TextOverlayItem.animatedRotation` (raw keyframes/static) | sheet's Setter → keyframe-aware write |
| 2 | Image overlay drawer (per-axis sheet) | FaditorEditorActivity.java:29977-29979 (`addPropRow` + `overlayMenuProp` K_ROT) | same as text | same |
| 3 | PiP (overlay video clip) drawer | FaditorEditorActivity.java:27695-27696 (`pipMenuProp(c, ROTATION, "Rotate", -180,180, deg)`) | `clip.getOverlayTransform()` KeyframeSet (absolute-timeline ms) | `write(ROTATION, deg, timeMs)` (25641), diamond-drop 25241-25251 |
| 4 | Sprite drawer | FaditorEditorActivity.java:26136 (`spriteMenuProp(s, K_ROT, "Rotate", -180,180, deg, …)`) | `SpriteOverlayItem` keyframes ROTATION + `setRotationDeg` (raw) | setter 26287-26288; diamond-drop 25488 |
| 5 | Waveform visualizer sheet | FaditorEditorActivity.java:28872 (`staticProp(…, "Rotate", -180,180, deg, ms->wf.getRotationDeg(), …)`) | `WaveformOverlayInstance.setRotationDeg` (static, no keyframes) | static setter + `refreshVizAfterMenuWrite()` |

Undo hooks already in place per sheet — the dial MUST call them exactly as the slider
does: `onSliderStart()` at gesture start, `onSliderCommit(label)` on release
(FaditorEditorActivity.java:25894 text/image, 26144 sprite, 28884 viz) so one drag =
one undo step. Playhead refresh flows through `refreshOpenDrawerRows` (25331) and
`ObjectMenuSheet.onPlayheadChanged`; the dial must redraw on the same signal.

### FIXED while tracing (SPEC A gap, 2026-09-05)
The waveform Rotate row's key was `"viz_rot"` (FaditorEditorActivity.java:28872), which
did not match `promptForValue`'s rotation branch — typing 720 there took the numeric
branch and was CLAMPED to 180 (a fold on the way in; the one thing SPEC A forbids).
Renamed to `KeyframeSet.ROTATION` (unique within that sheet; `"viz_rot"` had no other
references). `assembleDefaultDebug` → BUILD SUCCESSFUL in 57s; rotation harness ALL
GREEN. This row now gets the turns grammar, and the dial will land on it for free.

### Places that must NOT get the dial (rotation maths that are legitimate, not object pose)
- Decoder/metadata orientation: `ExportManager.sourceDisplayDims:4335`,
  `FaditorEditorActivity.displaySize:8732`, `SurfaceFrameReader:152`,
  `SequentialFrameReader:216/452`, `FilmstripSweepExtractor:500`,
  `FxPreviewTextureView:1299`.
- Quarter-turn clip frame orientation: `Clip.java:1115-1118`, rotate-90 button
  (FaditorEditorActivity.java:7567) — 0/90/180/270 by design.
- Gesture cardinal snap: `PreviewHandlesOverlay.java:718` (display-only).
- Shadow angle: `TextOverlayItem.java:230`. Hue wheels: `ColorWheelView:103/205`,
  `TranscriptPanelView:451`. Audio-reactive amplitude mapping: 15027 (not a pose).

### Cross-lane coordination (the "universal helper" ask)
JoyRaptor wants this dial ANYWHERE rotation exists:
- **Spine rotation** (`SpineTransform`, key "spineRotation", SpineTransform.java:60) and
  the puppet/mesh ROTATE handle (`transform/TransformOverlayView.java:1059`) belong to
  the SPEC B/E lane. When JoyRaptor approves the design, that lane adopts the same
  `RotationDialView` for spine rotation rows (its key is not `ROTATION`, so either the
  row renderer takes an explicit "isRotation" flag or the helper is keyed by
  `parseRotationInput`-capable keys — decide at implementation).
- **Mask rotation** (`maskRotation`, MaskAnimator.java:38) is keyframe-only today — no
  slider row exists (MaskKeyPanel only READS object rotation for the link base,
  MaskKeyPanel.java:170-171). If a mask rotation row ever ships, it uses the dial.
- The dial NEVER changes storage — it is display+gesture only; SPEC A's grammar and raw
  storage remain the single source of truth.

### Acceptance criteria amendment (design pending)
Criterion 0 (NEW, added 2026-09-05): the implemented dial's LOOK AND FEEL matches the
design JoyRaptor picks from `tasks/design/ROTATION_DIAL_OPTIONS.html` (or his stated
combination) — that page is the design contract, including the winding-tape semantics
(one translucent arc per full turn, overlaps brightening, negative turns in a distinct
tint) and the notch-at-top.

---

## Round 2 (2026-09-05): COMPACT — JoyRaptor's picks + the one-line contract

JoyRaptor played `ROTATION_DIAL_OPTIONS.html` on a touchscreen. Picks: **Turn rings +
Spiral**, with two amendments. New contract page (the authoritative one now):
`tasks/design/ROTATION_DIAL_COMPACT.html` — round-1 page stays as visual reference.

### JoyRaptor's decisions
1. **Spiral direction: OUTSIDE-IN.** The tape starts at the outer edge and winds INWARD
   as turns stack (start always visible; core brightens with winding). Round 1's
   inside-out spiral is dead.
2. **Size: the dial must live INSIDE the existing one-line row.** Today's rotation
   slider row is one line (~44-48dp). The dial may not grow the drawer vertically.
   Compact dial = ~40dp circle with the RAW VALUE as hub text (dark plate behind it for
   contrast), the current partial turn as a bright outer arc, completed turns as inner
   rings (≤3) or a brightness disc (≥4, alpha ∝ count), notch at top. When the row has
   room (plain Rotate rows) the full readout text ("720°") stays beside the dial as
   today; in the 3-per-line stress case the hub text IS the readout and typing opens by
   tapping the dial.

### The one-line contract (zero vertical growth)
- Reference: today's drawer renders rotation as one slider row; unlinked scale adds
  MORE slider rows (`PipDrawerTabs.addScaleRow`, FaditorEditorActivity.java:29968 — the
  Prop triplet K_SCALE / K_SX / K_SY already exists at 29952-29976).
- New layout: compact dials SHARE the line. Proven in the mock at 380px phone width:
  - Rotate row: `[40dp rings dial "720"] 720° ◇` — same height as the slider row.
  - Scale unlinked, ONE line: `Rot [dial] ◇ | X [dial] ◇ | Y [dial] ◇` — three dials +
    three keyframe diamonds, no extra row, no vertical growth.
- Keyframe diamonds stay per-dial (the Prop keyframe machinery is untouched — the dial
  is only a new renderer for the same value/write path, §Integration map above).
- **Scale-dial semantics (not winding):** a scale dial is the same compact control
  driving a linear multiplier (0.02..10 clamp unchanged); its typing keeps the EXISTING
  plain-numeric path — the turns grammar is for ROTATION rows only. The demo's
  scale-type handling is a mock shortcut; the app keeps `leadingNumber` + existing
  clamps for scale.

### Open points (JoyRaptor to settle while playing)
- At true 40dp: **rings-micro or spiral-micro** for the rotation dial (both are in the
  mock — the stress row uses rings for Rot, spiral for X/Y; he can feel both).
- Hub text format: raw degrees ("720", "-45", "5.8k") vs turns ("2×") — mock shows raw
  with the full readout beside it on roomier rows.

### Acceptance criteria additions (round 2)
8. The dial row's height equals today's slider row height — measured on the device, the
   drawer's total height must not change.
9. With scale unlinked, rotate + X + Y sit on ONE line (three compact dials + three
   diamonds) and the drawer grows no new row.
10. The spiral variant winds outside-in; at 0 the start marker sits on the outer ring.
11. Hub text at 40dp is legible for 720, -45, 5760 ("5.8k" compacting allowed); full
    readout text still appears on single-dial rows.

---

## Round 3 (2026-09-05): the DRAWER ECONOMY — which properties become dials

JoyRaptor's framing: sliders are one-per-row and can't pack horizontally; dials are compact
and CAN. So the design question is not "dial vs slider for rotation" but "which
properties convert to dials to buy back vertical drawer space". Position (X/Y) reads
best as sliders; everything else in the transform block is a dial candidate.

### The property census (all through ObjectMenuSheet.Prop — §Integration map)
Per object drawer today (linked scale): Pos X, Pos Y, Scale, Rotate, Opacity = **5
slider rows**; scale unlinked adds Scale X + Scale Y = **6 rows**. Waveform sheet: X, Y,
Width, Height, Rotate = 5 rows.

### Arrangement variants (live in ROTATION_DIAL_COMPACT.html §3, switchable, line-counted)
- **A · mixed (JoyRaptor's instinct)** — position stays 2 slider rows; one dial line:
  `Rot | Sc | Op` (+ their diamonds). Unlinked scale swaps Sc → X·Y (4-dial line).
  **6 rows → 3.**
- **B · two dial lines** — position becomes dials too: `X | Y` + `Rot | ScX | ScY | Op`.
  **6 rows → 2.**
- **C · one hero line** — `X | Y | Rot | Sc | Op` all on one line (5 compact dials —
  likely too tight at 40dp on 360dp phones; the mock shows it honestly).
- Non-rotation dials (scale, opacity, position) are LINEAR knobs: value arc on a ring +
  hub text, no notch, no winding tape; clamps and plain-numeric typing unchanged from
  each property's existing Prop. Only ROTATE carries the winding grammar and tape.

### Rules for the implementer
1. The compact knob is ONE view with three render modes: winding (rings/spiral —
   rotation only), and linear (arc-fill — scale/opacity/position). Same touch code,
   same typing door, same Prop plumbing.
2. Line height invariant: a dial row's height == today's slider row height. The
   drawer's total height must not grow; ideally shrink per the chosen variant.
3. Keyframe diamonds stay per-property on the same line (the Prop machinery is
   untouched).
4. JoyRaptor picks the variant (A/B/C or a mix) before implementation; the variant defines
   which rows convert. Rotation's winding semantics are fixed by rounds 1-2 and are not
   reopened by this choice.

---

## Round 4 (2026-09-05): JoyRaptor's feel corrections on the compact mock

After playing `ROTATION_DIAL_COMPACT.html` — four corrections, all applied to the mock
and binding for implementation:

1. **SPIRAL PITCH IS FIXED, NOT FITTED.** The spiral must NOT re-anchor so the head
   reaches the centre at the current value ("moves to the middle way too soon"). Inward
   step per full turn = **the line thickness + 2px** — so at 1-2 turns it reads as
   almost a plain ring, nesting only as turns force it; when the inward radius hits the
   hub it stays there and additional turns STACK (overlaps brighten — 10 turns ≈
   solid). Start marker always sits on the outer ring.
2. **COLOUR LANGUAGE: green = positive winding, reddish-pink = negative** (was
   white/amber). Applies to the spiral, the rings, the partial-turn arc and the head
   dot. `rgb(124,201,124)` / `rgb(255,92,138)` in the mock — final values should match
   the app palette at implementation.
3. **SCALE USES THE SAME WINDING ANIMATION.** Winding = CHANGE history with scale 1.00
   as zero-winding origin: `winding = log2(value)·360°`. One full turn of drag = ×2
   (counter-clockwise ÷2). **Floor 1% (0.01×) hard; positive side UNBOUNDED unless size
   scale damages performance — bound it just before that point, by measurement, not by
   fiat.** NOTE for the implementer: the app clamps scale 0.02..10 today
   (Prop min/max; the pinch clamp was raised to 4.0 — FaditorEditorActivity:27689-27693
   comment). Keep that clamp in the data path until a perf measurement says where the
   real bound is; the DIAL itself does not impose one. Typed scale values stay plain
   numbers (no turns grammar) and re-anchor the tape at log2(typed).
4. **KEYFRAME HELPER IS WIDER THAN A PLAIN DIAMOND.** The app's real keyframe control
   has prev/next-key navigation either side of the diamond (`‹◇›` — Prop.prevKey/
   nextKey, ObjectMenuSheet.java:99); the mock now matches. Implementation must render
   the row's diamond in the app's existing wider form, not a bare diamond glyph.

### JoyRaptor's follow-up (same day, still round 4)
5. **RINGS STAY COUNTABLE UNTIL THEY PHYSICALLY RUN OUT OF ROOM.** No arbitrary cap:
   rings nest at a FIXED pitch (lineWidth + small gap)    from the rim inward until the
   next ring would hit the value hub — at 40dp that is ~5 rings, on big dials ~6-7,
   purely from geometry. Only turns that no longer fit grow a solid core FROM THE
   CENTRE OUTWARD — and the core is BORN AT HALF the natural hub-fitting radius
   (JoyRaptor: "make the center plug start fifty percent smaller"), then grows one pitch
   per excess turn, capped at the rim — so the countable state and the solid state
   are one continuous progression. NEVER a full-face plug at a threshold (JoyRaptor: "they
   eat in about 60% of the way and we get a plug — a disconnected jump"). The value
   text sits on a dark plate so rings and core pass behind it.
6. **SPIRAL: the first turn completes a near-full ring at the OUTER radius BEFORE the
   descent starts.** Radius stays at Rmax for deg ∈ [0, 360); only after that does it
   descend by the fixed pitch (lineWidth + 2px) per additional turn, clamped at the
   hub with stacking. Combined with rule 1 this means: ring-like for the first two
   turns, gentle nesting after, brightening pile-up only at high winding.

---

## IMPLEMENTATION (2026-09-05): rings dial shipped to the rotation rows

JoyRaptor's final call: **RINGS, not the spiral** — the rings preserve directionality (180°
reads as straight down at any turn multiple; the spiral's shape is ambiguous). Scope:
rotation rows ONLY; the keyframe helper and everything else untouched. Scale stays put
until JoyRaptor has seen the dial on the phone.

### What was built
- **NEW `tools/RotationDialView.java`** — the 40dp turn-rings dial, port of the approved
  mock: base track, countable rings at fixed pitch (lw + gap) from the rim until
  geometry reaches the hub, centre core born at HALF radius for turns that no longer
  fit, bright partial-turn arc on the rim, top notch, green/pink by winding sign, hub
  shows the raw value on a dark plate ("720", "-45", "5.8k"). The view owns ONLY look +
  gesture: drag = wind (pointer angle unwrapped, value += delta, raw), tap = ask the
  caller to open typing. It never clamps, never folds, never writes by itself; it
  requests upstream scroll interception only while the finger is down.
- **`PipDrawerTabs.propRow`** — when `prop.key() == KeyframeSet.ROTATION`, the row's
  FineSeekBar is replaced by the dial; drag → `prop.write(dial.getDegrees(), playhead)`
  RAW (no min/max mapping, no snap), tap → the SAME `promptForValue`, value text +
  keyframe diamond + refresh cadence untouched.
- **`ObjectMenuSheet.Row`** — the sheet's Rotate rows (text/PiP/sprite/waveform
  drawers) get the same swap. The dial's gestures mirror the slider's exactly:
  `hooks.onSliderStart/onSliderCommit` for undo brackets, `setActiveKey` +
  `maybeFlashArmingHint`, `diamond.refresh` after every write. The dial's TAP opens
  `promptForValue` (now public) via a small `PipDrawerTabs.Host` adapter —
  **this gives the sheet's rotation rows their first tap-to-type door** (before the
  dial, only PipDrawerTabs-rendered rows had one; a latent SPEC A gap this closes).
- The sheet's `viz` Rotate row (static) gets the dial too — no diamond, no arming
  hint, exactly as its slider behaved.

### Verdict
`assembleDefaultDebug` → **BUILD SUCCESSFUL in 28s** (fresh compile of the dial and
both renderers; one compile error during wiring — PipDrawerTabs.Host also declares
`pickColorFromPreview`/`recordUndo`, unused by promptForValue, implemented as
documented no-ops — then green). All three files staged.
**Compile-verified only. NOT device-verified** — JoyRaptor will judge the look/feel on the
phone; scale rearrangement (round-3 variants) waits on that verdict.
