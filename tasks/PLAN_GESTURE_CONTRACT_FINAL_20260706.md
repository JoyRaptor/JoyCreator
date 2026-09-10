# FINALIZED — Layer gesture language + object menu + timeline layout (co-designed with JoyRaptor, 2026-07-06)

This is the authoritative gesture/interaction contract for the layers overhaul. It supersedes the
proposed Slice G in `PLAN_LAYERS_UX_EXECUTION.md` and the proposal block in
`FEEDBACK_20260706_layers_ux.md` §6, and evolves (does NOT discard) the 2026-07-03 contract in
`PLAN_LAYER_GESTURE_CONTRACT.md` (badges principle, swipe=scrub, bookend snap all still hold).

Co-designed live with JoyRaptor. Two defaults were adopted on her "let's go with it" and are flagged
**[JOYRAPTOR-CAN-FLIP]** — change freely, they are not load-bearing.

DESIGN-FIRST track: this doc is the design. Build comes AFTER the renderer consolidation (Slices A–F)
is stable, since those add the item types this language operates on. Build slices are at the bottom.

---

## 1. The four gestures (on a layer-row item)

| Gesture | Behavior |
|---|---|
| **Tap** | SELECT. Outline the row bar AND spawn manipulation handles in the PREVIEW (move / scale / rotate / aspect corner+edge handles) for image, video, text, CC/title, sprite. Selection badges (delete/duplicate) appear on the bar (2026-07-03 badge pattern). |
| **Double-tap** | Open that object's POWER-TOOLS DRAWER — the full type editor (sprite editor / caption-style chooser / text editor / audio envelope). This is the express lane to the same drawer the hold-menu's "More…" opens. |
| **Hold → drag** | MOVE. Item "lifts" (haptic + elevation/scale/alpha lift). Then: horizontal = reposition in time, vertical = change layer / drop on "+ New layer" zone. (Same pickup-move mechanics + bookend snap as 2026-07-03.) |
| **Hold → release in place** | Open the GENERAL ADVANCED MENU (the object properties/options sheet — §2). If the finger never crossed the move slop, releasing opens the menu instead of committing a move. |

Horizontal-dominant drag WITHOUT a hold still = SCRUB the timeline (pass-through, 2026-07-03 rule).
Delete is NOT a raw gesture — it lives as a selection badge and as a header-row icon in the menu.

**Resolves the old conflict:** hold does both jobs via the phone-home-screen pattern — lift, then
drag=move / release=menu. One gesture, disambiguated by whether the finger moved.

---

## 2. The general advanced menu (hold → release)

Broad controls common to ALL objects first, then an object-specific section, each ordered
most-used → least-used. Clear general→specific hierarchy.

```
[identity header row]  name · colour swatch · header-icon (music/fx/img/slide/video/graphic)
                       · eye (visibility) · lock · duplicate badge · delete badge
── general ──          (most-used → least)
  Transform            position · scale · rotation · pivot
  Opacity
  Volume               (shown only if the object carries audio)
  Lock                 time-lock and/or transform-lock — granular; default = both (see §4.5)
  Attached overlays    piggyback / float checkboxes  (shown only on host media — §3)
── <type> ──           object-specific, most-used → least. e.g. TEXT: content · font · size ·
                       box style · box colour · alignment · outline/shadow
  More…                → opens the full type editor (identical to double-tap)
```

Each **keyframeable** property row (position, pivot, opacity, rotation, scale, volume) carries
a keyframe diamond with this behavior:
- **solid diamond** = playhead is on a keyframe of this property
- **hollow diamond** = not on one; tap it to DROP a keyframe here
- **swipe the diamond** = jump playhead to next / previous keyframe of this property
- **long-press the diamond** = delete that keyframe

**[JOYRAPTOR-CAN-FLIP] Keyframe recording = AUTO when armed:** once a property has ≥1 keyframe (armed),
changing its value on any frame auto-creates/updates a keyframe there (CapCut / After Effects style).
Alternative is manual-only (value change records nothing until you tap the hollow diamond).

---

## 3. Rendering the menu — peek sheet + on-demand keyframe ribbon ("the sandwich")

Principle: **the drawer that covers something is always the one you're NOT looking through.** Values are
judged by watching the PREVIEW → they go on the bottom. Keyframes are judged by watching the TIMELINE →
they go on top. See mockup `hold_drawer_peek_vs_sandwich_layout`.

- **Peek (default):** bottom sheet at peek height = ONE row (the active property's control + its
  keyframe diamond) + grip. Preview AND timeline both stay visible → scrub, nudge, drop keys with
  nothing important covered. This is the everyday keyframing state.
- **Expanded (drag the grip up / tap):** the full §2 menu unfurls upward, covering the timeline. Fine —
  when typing text or picking colour you watch the preview, not the timeline. Bulk setup, not keyframing.
- **Top keyframe ribbon (◀ ◆ ▶ add/del for the focused property):** appears ONLY while a keyframeable
  property is focused. Rides the lower edge of the preview so the timeline stays fully clear during
  keyframing. Hidden the rest of the time (e.g. setting a colour → no ribbon → timeline fully free).

Rejected alternatives (recorded so we don't relitigate): center popup (covers both — worst), top-only
drawer (hides the video — bad for text/colour), bottom-only fixed drawer (hides the timeline — bad for
keyframing).

---

## 4. Overlay attachment — piggyback vs stratified (JoyRaptor's model)

Overlays (captions, visualizers, other decorations) READ content from a SOURCE and may be TETHERED to a
host clip. These are TWO INDEPENDENT AXES (JoyRaptor 2026-07-06 — they were previously conflated):

**Axis 1 — Attachment / source (where content comes from):**
- **Attached to a host** (base media: a specific video / image / audio; hosts don't host overlays) →
  content = that host's data: its audio for a visualizer, its transcript for a caption. Unambiguous.
- **Detached (no host)** → content = the GENERAL source: visualizer reads the full audio MIX; caption
  reads the transcript of whatever transcribed clip is currently under the playhead (temporal
  "underneath", NOT spatial layer order).

**Axis 2 — Visual tether (only meaningful when ATTACHED): piggyback vs stratified:**
| State | Rides host in TIME? | Inherits host LOOKS? |
|---|---|---|
| **Piggyback** | yes | yes — a host fade-to-black also fades it; rendered as a sub-band on the host row. |
| **Stratified (float)** | yes | no — floats above host compositing, untouched, but still slides/trims with the host; own row in the OVERLAY strata. |
Detached objects have NO host, so piggyback/stratified is moot — free-floating, independent in both
time and looks.

**⇒ The practical THREE states = attached-piggyback · attached-stratified · detached.** (Resolves the
earlier two-vs-three flip: "detached" is a separate axis, not a third rung on the looks axis.)

**Defaults by type:** visualizer → attached-piggyback (fades with the video). Caption →
attached-stratified (hovers unaffected). Either can be detached.

### 4.1 — Detached source semantics + the caption-overlap question (JoyRaptor, 2026-07-06)
- **Visualizer detached = always safe:** "full audio mix" is one unambiguous source.
- **Caption detached = safe ONLY when ≤1 transcribed clip is under the playhead at a time** (the common
  single-track case: shows clip A's lines then clip B's as the playhead crosses them). Attaching is how
  the project AVOIDS confusion today.
- **Ramification JoyRaptor flagged — two overlapping transcribed videos under a detached caption:** no single
  source, so it must tie-break. **Fable lean (confirm):** the TOPMOST transcribed clip under the playhead
  wins (PiP over main), with a small SOURCE INDICATOR on the caption bar showing which clip it reads and
  a one-tap **"pin to this source"** (= re-attach, the explicit disambiguator). Alternatives:
  main-layer-wins, or prompt-to-attach on overlap. Guidance: detached = convenience for simple/sequential
  edits; for overlapping transcripts, ATTACH to be explicit. Not a flaw — it's why attach exists.
- **RESOLVED (JoyRaptor 2026-07-06) — detach is a PROMPTED mode, not a fixed rule:** tapping "detach" opens a
  dialog *"How should this behave detached?"* offering the behavior choices (caption: follow topmost
  transcript / follow main layer / pin to a specific clip; visualizer: full mix) PLUS a **Cancel** that
  ABORTS the detachment. The tie-break is thus a per-object choice made at detach time, not a global
  hardcoded rule. Keep the source indicator + one-tap re-attach after detaching.
- **The detach dialog is only a SHORTCUT — the setting is PERMANENT in the hold-menu (JoyRaptor 2026-07-06):**
  attachment / source behavior lives in the object's general-advanced-menu object-specific section (§2),
  editable ANY TIME after the fact — reattach to a specific clip, switch detached behavior, change the
  overlap source, etc. The detach prompt just writes the same setting the menu exposes. So for
  captions/visualizers the object-specific menu section includes: attach/detach · source behavior ·
  reattach-to-clip.

**Toggling — three ways, kept in sync:**
1. Host's hold-menu "Attached overlays" checkboxes (one per tethered overlay).
2. The overlay's own hold-menu (its float checkbox).
3. A BADGE on the overlay bar (2026-07-03 badge pattern, spaced clear of the trash badge to avoid
   mis-taps): **down-arrow** = drop into piggyback; when piggybacked the badge becomes a **cloud /
   up-arrow** = float back to stratified.

**Overlay strata visibility:** if NOTHING is stratified, the overlay strata is fully collapsed /
invisible — it pops into existence the moment the first stratified overlay (e.g. a caption) exists.
Z-order of multiple stratified overlays: higher row = in front; reorderable (mechanism TBD — likely
hold-drag within the strata, reusing move-between-layers).

Open (deferred, non-blocking): re-parenting an overlay onto a different host (likely hold-drag it over
the new host); interior z-reorder UI.

---

## 4.5. Controls belong to OBJECTS; layers are substrate (JoyRaptor, 2026-07-06)

JoyRaptor's model: **a layer is just the substrate that suspends objects** — users do NOT add / name /
delete layers as managed entities; rows are scaffolding, and objects reshuffle between them constantly.
Therefore controls that were per-LAYER (visibility / lock / mute in the track header) move to the
OBJECT, since tying them to a row that the object keeps leaving is fragile.

- **Per-OBJECT controls:**
  - **Eye (visibility)** — show / hide the object.
  - **Lock** — two facets: **time-lock** (can't be moved / trimmed on the timeline) and
    **transform-lock** (can't be moved / scaled / rotated in the preview). Default lock = BOTH
    ("stop touching it"); granular time-only / transform-only in the hold-menu. This ABSORBS the old
    "freeze" idea (which JoyRaptor meant as a timeline pin, not a preview pose-hold) — "freeze" is retired.
  - Surfaced BOTH as quick selection badges on the object AND in the hold-menu identity header.
    Badge crowding management: core badges = eye · lock · delete; duplicate / float-toggle live in the
    menu (keep the selected-state badge cluster economical).
- **Per-LAYER control (the only one that survives):** **Solo** — isolate this row ("show only this").
  No per-layer rename / lock / visibility; the layer isn't a user-managed thing.
- **Lanes are NAMELESS** (JoyRaptor confirmed): no user-facing lane names, no per-lane label in the gutter.
  Minimal row gutter = solo + twirl only. The generic "Sprite" row label from T8 is dropped.

### 4.5a — Lanes = nameless mini-main-layers; only MAIN ripples
A lane is not "one object per row" — like the main layer, a lane can hold MULTIPLE objects butted up
sequentially (no-overlap; bookend snap on drop). The ONLY thing special about the **main/master layer**
is **ripple** (CapCut-style): trimming/moving a main clip shifts everything downstream to close the gap,
and relative objects move with it. Other lanes do NOT ripple — objects hold their absolute time.

### 4.5b — Lanes appear on demand, vanish when empty (RESOLVED — replaces the "+ New layer" strip)
JoyRaptor confirmed: the explicit "+ New layer" drop strip (2026-07-03) is retired. Lanes materialize
automatically when an object needs its own row and disappear when empty. The move gesture targets become
"lift to its own (new) lane" vs "drop onto an existing occupied lane → bookend snap" — no literal
"+ New layer" target to aim at.

### 4.5c — Layer identity is for the AI only (relational, not names)
Users never name lanes, but the AI must address them RELATIONALLY: "put the text below this video, above
the main layer," "the lane above main," etc. So lane identity is positional/relational (order + relation
to main + host), computed on demand — never a stored user-facing name.

### 4.5d — Lane CONSOLIDATION (manual + AI)  ★ JoyRaptor's explicit want (CapCut pain point)
A "compact lanes" operation that drops objects DOWN into the fewest lanes possible, leaving a separate
lane ONLY where two objects would otherwise overlap in time. Rationale: an object floating alone on its
own empty lane is VISUALLY IDENTICAL to the same object sitting lower — negative space between lanes
renders nothing — so consolidation is visually free and undoable. JoyRaptor's history: CapCut parent/unparent
churn left 12–13 orphan lanes of negative space with no way to reclaim them.
- **Manual:** a "consolidate / compact lanes" action (button or gesture).
- **AI:** an AIToolExecutor command ("consolidate my layers to the minimum") — belongs in the AI sprite/
  layer tools track (FF-B / T7). The AI reads the lane occupancy, computes the minimal packing that
  preserves no-overlap, and moves objects down in one undo step.
Depends on the generalized no-overlap resolver (Slice F). Log against Slice F + the AI tools track.

**Build implication:** the `LayerRowRenderer` per-track header (currently name / visibility / lock /
mute / twirl) shrinks to a minimal gutter (solo + twirl + object-derived label); visibility/lock/mute
move to per-object state + badges. Fold into Slice D (header hit zones) and the renderer consolidation
(Slices A–C) rather than building the old per-track header only to tear it out.

---

## 5. Resizable timeline (JoyRaptor, 2026-07-06)

A thin, attractive GRAB BAR sits between the preview and the timeline. Drag it to reallocate vertical
space between preview and timeline — no fixed split.
- Light on layers → drag down → bigger video preview.
- Heavy on layers → drag up → more rows visible at once.
- **Ultimate (drag to the top):** timeline goes (near) full-screen and the preview becomes a small
  **draggable PiP window** you reposition anywhere as you work.
- Persist the user's chosen split. Sensible snap detents (e.g. video-dominant / balanced /
  timeline-dominant / fullscreen-PiP) with free-drag between them.
- This also satisfies FEEDBACK #3's "MASTER editing layer should be larger" without hardcoding a
  layout — the user sizes it themselves.
- **[JoyRaptor 2026-07-07] Small preview ⇒ PiP (incl. landscape).** When the preview shrinks past a
  threshold — whether by dragging the split up OR by rotating to landscape (JoyRaptor does NOT want a
  portrait lock) — it should turn INTO the draggable PiP window instead of a cramped inline box. The
  reflowPreview() rotation fix (a2e1101) is the hook: the same size-change signal that re-flows the
  canvas should, below the threshold, promote the preview to PiP. **Constraint:** the caption-style
  strip (Pop/Zoom/…) and the tool row must stay reachable when PiP is active (don't let the PiP or the
  expanded timeline bury them) — reserve/relocate those controls, not hide them.

Files (build phase): `EditorTimelineView` (viewport height math already central to row layout),
the Faditor preview container / `FaditorEditorActivity` layout, a new PiP-preview overlay + drag handler.

---

## 5.5. Multi-select (marquee) mode (JoyRaptor, 2026-07-06)

A toolbar TOGGLE that changes drag behavior. Default OFF: tap+drag scrubs the timeline (unchanged —
JoyRaptor likes this). This is the standard "window vs crossing" selection convention (AutoCAD / Figma), so
borrow its mental model + icons (Tabler box-select family).

**Three-state toggle:**
1. **OFF** — gray dotted-line box icon. Drag = scrub (normal).
2. **INCLUSIVE (crossing)** — one tap → armed, app highlight color; icon = a SOLID box that intersects
   the dotted line on one side. Drag paints a marquee; touching ANY part of an object selects the WHOLE
   object.
3. **EXCLUSIVE (window)** — tap again → icon = a SOLID box fully INSIDE (or the always-solid-inner +
   outlined-edge pairing JoyRaptor sketched). Only objects FULLY enclosed by the marquee are selected.
   (Existing convention icons exist — don't hand-design; pick from Tabler.)

**Behavior:**
- Marquee drag selects across multiple tracks + time.
- **Edge-scroll while dragging** (BOTH axes): dragging the marquee toward an edge auto-scrolls the
  timeline (horizontal = time, vertical = tracks) so a long/tall selection box can rope objects over a
  distance the viewport doesn't show. (Interacts with the resizable timeline §5 / fullscreen.)
- Tap in select-mode toggles an individual object in/out of the selection.
- **Batch long-press:** with MULTIPLE objects selected, hold on any of them → a menu of controls
  UNIVERSAL to all selected (batch move, batch opacity, batch lock/visibility, batch delete, batch
  rename). Only properties common to every selected type appear. (Batch rename semantics — base name +
  auto-number vs same-name-to-all — decide at build; lean base+number.)

---

## 5.6. Object linking — granular grouping (JoyRaptor, 2026-07-06)

Repurpose the existing toolbar **link button** (next to redo — today it opens the media catalogue to
relink an asset). RELINK / replace-broken-asset MOVES into the per-object hold-menu (object-specific
section / "replace source"; aligns with T3 S7 relink + `RelinkCatalogBottomSheet`). The button becomes a
GROUPING control:

- **Multiple selected + unlinked → LINK icon.** Tap → "What would you like to link?" dialog: position on
  the timeline (time), position in the preview / canvas (space), and other linkable props (opacity /
  scale / rotation / etc). Creates a link group over the chosen dimensions.
- **One selected + it IS linked → UNLINK icon.** Tap → "What do you want to unlink?" dialog: the linked
  properties, plus scope — **unlink just this object** or **unlink all (dissolve the whole group)**.
- Other states (multiple selected already linked / one selected unlinked): define at build; the button
  reflects the dominant state of the selection.

**Architecture note (fork for JoyRaptor's awareness, Fable leans A):** the piggyback/stratified overlay tether
(§3–§4) is a SPECIAL CASE of this general link system (link time + looks to a host). (A) Build ONE link
engine and express piggyback/stratify as a named preset over it — avoids two parallel grouping systems.
(B) Keep them separate. Lean A.

---

## 5.7. Sprite lane thumbnails (JoyRaptor, 2026-07-06)

The sprite bar shows the actual sprite CELL image beneath the keyframe row:
- Each keyframe that CHANGES the cell starts a new thumbnail whose LEFT EDGE is justified to that
  keyframe's time on the timeline; the thumbnail segment runs until the next cell-changing keyframe.
- **Z-order when compressed / crowded:** when zoomed out so segments stack/overlap, the cell under the
  PLAYHEAD is raised to the front — but ALWAYS behind the keyframe diamonds (diamonds draw last, on top).

**Performance plan (source sprite size must NOT matter — JoyRaptor's concern):**
- Decode each UNIQUE cell ONCE into a small pre-scaled thumbnail (≈ row height) and CACHE it; thereafter
  just blit the cached bitmap → a 4K sprite costs the same as a tiny one.
- VIEWPORT-CULL: draw only segments currently on screen.
- LOD COLLAPSE: at extreme compression (thumbnails would overlap into mush), fall back to ONE
  representative thumbnail (or a color band) per bar instead of many.
Cost scales with VISIBLE SEGMENTS, not sprite resolution. Build: `LayerRowRenderer` SPRITE
`drawItemBody` + a cell-thumbnail cache; log against the renderer consolidation (Slice B/C).

---

## 6. Cross-cutting rules

- **Zone discipline (prevents the collisions that already bit us):** every pixel has exactly one
  gesture meaning. Define per-zone routing — header zone, item-bar zone, empty-lane zone, ruler/scrub
  zone, keyframe-diamond zone. Swipe-on-diamond (next/prev key) must not be confused with swipe-to-scrub
  or swipe-to-scroll-rows; the diamond owns its small hit area, scrub/scroll own the rest.
- **Badges are the extension point** (2026-07-03, JoyRaptor-endorsed): quick per-item actions
  (delete/duplicate/split/float-toggle/eye/lock) are selection/overlay BADGES, not new gestures. The
  hold-menu is for deep properties + keyframes. They complement, not compete.
- **Badge geometry — trash needs TWO gaps** (JoyRaptor, 2026-07-06): keep the trash badge clear of both
  (a) neighboring badges AND (b) the RIGHT trim cap of the bar, so trimming doesn't clip the delete
  badge (fat-finger risk). Delete's confirm dialog already prevents accidental data loss, but the mis-tap
  is still an annoyance — reserve margin on both sides. Core selected-state badge cluster stays economical
  (eye · lock · delete); duplicate / float-toggle live in the menu.
- **No destructive raw gestures:** delete only via badge or menu, never via a bare long-press in the
  layer area. Everything is undoable (one undo step per committed action).
- **Discoverability:** hold and double-tap are invisible — add one-time coach-marks the first time an
  item is selected, since JoyRaptor explicitly can't predict hidden gestures.
- **Freeze semantics: CONFIRM with JoyRaptor** before building that row (read as "hold current transform
  from this frame onward" — a freeze-frame/hold segment).

---

## 7. Build slices (AFTER renderer consolidation A–F is stable; each ALWAYS-GREEN + device-verified)

- **G1 — Hold/tap/double-tap state machine.** Reconcile with 2026-07-03 pickup-move: hold→lift, then
  drag=move (existing bookend/new-layer path) / release-in-place=open menu. Tap=select+preview handles.
  Double-tap=type drawer. STRONG-MODEL (gesture state machines are subtle); keep ROWGESTURE logging.
- **G2 — General advanced menu as a peek/expand bottom sheet.** Identity header + general rows +
  type-specific section + "More…". Peek height = active control + diamond; expanded = full menu.
- **G3 — Keyframe diamonds + top ribbon.** Diamond states/drop/swipe-nav/long-press-delete; ribbon
  shows only while a keyframeable property is focused. Auto-record when armed [JOYRAPTOR-CAN-FLIP].
- **G4 — Preview manipulation handles.** New preview-handles overlay (move/scale/rotate/aspect) shown
  on tap-select for image/video/text/CC/sprite; wire to the same transform properties as the menu.
- **G5 — Overlay attach/detach + piggyback/stratified model (§4, §4.1).** Two axes: attachment/source
  (attached-to-host vs detached→mix/positional) and visual tether (piggyback vs stratified). Three
  practical states, defaults by type, detached source semantics + caption tie-break (topmost + source
  indicator + pin-to-source), three sync'd toggles, collapsed overlay strata, badge toggle. Depends on
  CAPTION/VISUALIZER Track kinds from Slice A.
- **G6 — Resizable timeline + fullscreen PiP preview.** Grab bar, persisted split, snap detents,
  draggable PiP at the top extreme.
- **G7 — Coach-marks + zone-discipline audit.** First-run hints; verify every zone routes one gesture.
- **G8 — Multi-select marquee mode (§5.5).** Three-state toggle (off/inclusive/exclusive), marquee hit
  math, both-axis edge-scroll, batch long-press menu (universal props only). Also touches the toolbar +
  `EditorTimelineView` touch routing.
- **G9 — Object linking (§5.6).** Repurpose the link toolbar button → link/unlink dialogs + state
  machine; relocate relink/replace to the object menu (coordinate with T3). If lean A: build the shared
  link engine first, then re-express piggyback/stratify (G5) as a preset over it.

Sequencing note: G1 is the spine (everything hangs off select/hold). G5 needs Slice-A Track kinds.
G6 is independent and can land early if JoyRaptor wants the space win sooner.
