# Animation clips: boards that overlap across layers, play together and combine (lead spec, 2026-10-18)

**Owner decision (2026-10-18), condensed from his words:** "I draw a small character on one layer, and in another part of the
canvas, on another board, animate a background or another character. Then drag those boards over so they overlap, and test
their animations independently by hovering or pressing play." Way A of the "Clips and Stages" page
(claude.ai/artifact/WuM16ynpDDcM6DYqteKJAE) was chosen: "if we go with A we get B for free … character A can go into
background A, B or C on board A, B or C; everything is composable. Let's do it." He loved TVPaint; its per-layer
"pre/post behaviour" is the model for §C3 (research: research/TVPAINT_FEATURES_20261018.md).

Read first: specs/JB-3.00a_boards_owner_model.md (boards are regions, R50), LAYER_OPS_20261018.md (merge, flatten).

## 1. Words (use these in code comments and UI)
- **Clip** = an Animation board: a rectangle of the canvas with its own frames, speed (fps), frame holds and end behaviour.
- A layer **animates in** a clip when it has frames there (`RegionFrames` with `held = false`); otherwise it is **still** there
  (its pixels in that rectangle live on the shared canvas, like anywhere else).
- **Play all** = every clip plays at once, each on its own clock.
- **Combine** = two overlapping clips become one clip covering both (the "stage"), every layer keeping its own timing.

## 2. What exists today (verified 2026-10-18, file:line in the 10-18 tree)
| Fact | Where |
|---|---|
| A new Animation board gives EVERY layer frames and moves each layer's pixels in the rect into frame 1 | core doc/RegionDocumentOps.kt `create` 48–74 |
| A still layer can start animating ("Animates in this board" runner glyph on the layer cell; `setHeld`) | RegionDocumentOps.kt 160–175; android chrome/LayerColumnView.kt 206–250 |
| Two Animation boards may never overlap (any layers) | core doc/DocOps.kt 106–112; RegionPaintPlan.kt 46–49 (per layer) |
| Dragging a clip onto another snaps back with a toast naming raw ids, repeated per move event | android board/BoardRuntimeController.kt 437–441, 638–639 |
| Playback: one `playingBoard`, `PlaybackClock` (LOOP / PING_PONG / ONCE), loop mode kept in controller memory only | core anim/PlaybackClock.kt; BoardRuntimeController.kt 831–845 |
| Hover (mouse/stylus) over an unselected clip plays it in a loop, never writing the saved frame | android board/BoardChromeView.kt 523–539; core chrome/BoardChromeLayout.kt 364–368 |
| The engine can show many clips' preview frames at once (`setFramePreviews(Map<boardId, frameId>)`) | androidkit gl/GlPaintEngine.kt `setFramePreviews` |
| Board fields: name, kind, rect, fps (1–60), frames (id, holdFrames 1–999), currentFrameId, locked, tiled | core doc/DocModel.kt 107–126 |
| Exports honour holds (GIF delays, sprite clip repeats, PNG timing file) | androidkit io/AnimExport.kt, core export/AnimExportPlan.kt |
| Merge Down on an animation frame merges only that frame (built 175d8f042, lead/layer-ops) | gl/GlPaintEngine.kt `mergeDownStep(frameBoardId)` |

## 3. Engine changes (C1–C7). Each: one commit, JVM tests, then a device check
**C1. Clips may overlap when no layer animates in both.** `DocOps.validate`: replace "animation boards overlap" with "layer
L animates in two overlapping animation boards" (checked per layer over its non-held regions). `RegionPaintPlan`'s per-layer
check stays (it is exactly this rule). Inside the overlap a layer shows the frame of the ONE clip it animates in, or its
still pixels. Moves and resizes of a clip go through the same validation, so a move that would put one layer in two
overlapping clips is refused, in words naming the layer and the two clips (never ids), once per gesture.

**C2. A new clip animates only the ACTIVE layer.** `RegionDocumentOps.create` gives the active layer frames (its pixels in the
rect go into frame 1, as today) and gives every other layer a held region (`held = true`), so their pixels stay on the shared
canvas, untouched. Adding more layers stays the existing "Animates in this board" toggle (`setHeld(false)`).

**C3. End behaviour per clip, saved.** `Board.end: String = "loop"` with values `loop | pingpong | hold | blank` (unknown values
read as loop, R3). `PlaybackClock` gains HOLD (stop on the last frame and keep showing it) and BLANK (after the last frame the
clip's layers show nothing there until the clip restarts); ONCE is HOLD. The controller's loop-mode memory goes; the board's
`end` is the truth for playback, hover, Play all, Combine and export. `DOC_VERSION` + 1 (assigned at landing, R30.3).

**C4. Play all.** One scheduler plays every Animation clip at once: per clip its own `PlaybackClock` (fps, holds, end), all
feeding ONE `setFramePreviews` map, one redraw per change. It never writes saved frames (like hover). Starting a single clip's
PLAY or a hover stops Play all; Play all stops single plays. Cost: one frame-preview composite per changed clip; it must hold
60 fps on the Note 9 with three clips of 256–1024 px (bench case `playAll`).

**C5. Combine clips.** `AnimationBoardOps.combine(doc, a, b)` → one clip, ONE undo step:
- rect = union of the two rects; name = the larger clip's; fps = the faster of the two.
- Timeline length L = the longer clip's length in ticks at the new fps. A clip at the slower fps has each drawing held
  `round(fastFps / slowFps × hold)` ticks (exact for 6/12/24; otherwise nearest, said in one sentence).
- Each layer that animated in either clip gets frames 1..L in the new clip: for ticks past its own clip's end, its source
  clip's `end` decides, written as LINKED cels (loop: cel of tick mod n; ping-pong: mirrored; hold: last cel) or an empty cel
  (blank). Linked cels mean an edit to drawing 3 shows wherever drawing 3 repeats (TVPaint instances).
- Layers that were still in both stay still. The two old clips are removed; selection and current frame move to the new one.
- Refused in words if one layer animates in BOTH clips with different drawings at the same place (C1 already prevents it).
- Board menu entry "Combine with <name>" when the selected clip overlaps or touches another animation clip.

**C6. Flatten and Merge Down across clips.** Flatten (LAYER_OPS §3) first Combines every pair of overlapping clips its layers
animate in (C5, same sentence), then flattens frame by frame (frames pair by tick; a still layer is copied into every frame).
Merge Down keeps the owner's rule (only the current frame of the selected clip merges) when both layers animate in that
clip; when they animate in two different overlapping clips, it says "Combine these clips first" with the action.

**C7. Exports.** A single clip exports as today, with `end` honoured for a GIF's loop flag (loop/pingpong loop; hold and blank
play once). "Play all" exports (the whole scene): length = the longest clip, with an option "Full loop" = the least common
multiple of the looping clips' lengths in ticks (capped at 600 frames, said if capped).

**C8. Drag a clip with its drawings.** Composing clips means picking a clip up and dropping it over another. That already
exists as the board menu's "Move all frames…" (`AnimationBoardOps.moveAll`, behind a confirm, and edge drags are ignored for
clips of 2+ frames). Make it direct: dragging an unlocked clip moves its rectangle AND every frame of the layers that animate
in it, as ONE undo step (no confirm; undo is the safety). Still layers' pixels under it never move. Locked clips don't move by
touch (R50). No separate "move layer" tool is needed for clips; moving a layer's own pixels is the Select tool's pixel lift
(a later Select phase).

## 4. UI changes (U1–U8): what the person sees. For the UI specialist, after the engine lands
The engine work above adds plain menu rows only where nothing exists (so it can be tested); everything below is design work.
| | Change | Acceptance |
|---|---|---|
| U1 | **"When it ends"** chip row on a clip's chrome or menu: Loop · Ping-pong · Hold · Blank (chosen chip solid cyan with cut-out icon, every chip a hover label). Replaces the unsaved loop-cycle control | The choice survives save/reopen; hover and Play all follow it |
| U2 | **Play all** button with the boards controls (and its Stop) | Every clip plays together; one tap stops all |
| U3 | **Overlapping clips readable**: both outlines visible, the selected one on top; a tap where clips overlap selects the one on top, a second tap the one under | Two overlapping clips can each be selected, moved and played |
| U4 | Board menu **"Combine with <name>"**, with the sentence about fps when they differ | One undo brings the two clips back |
| U5 | **New clip animates the active layer**: a first-time hint "Only <layer> animates here. Tap the runner on a layer to add it." | Other layers' pixels are untouched by creating a clip |
| U6 | Overlap refusal sentence with layer and clip names, once per gesture | No raw ids anywhere |
| U8 | **Drag a clip** directly (no confirm), with its drawings; a lock shows it can't move | One undo puts it back |
| U7 | The layer cell's runner glyph reads "Animates in <clip name>" / "Still in <clip name>" | Clear on a stylus hover |

## 5. Who does what
- **Engine (lead or the Opus helper): C1–C8** with plain, working menu rows for C4 (Play all in the ⋯ menu) and C5 (Combine in
  the board menu) so the owner can try it on the phone. JVM tests per change; bench `playAll`; Note 9 check.
- **UI specialist: U1–U8** from a handoff written when C1–C5 land (tasks/joybrush/handoffs/ANIMATION_CLIPS_UI_HANDOFF_<date>.md):
  the APIs, where the plain rows are, the screenshots, and the owner's design rules (design-language memory, hover labels,
  chosen-chip style). U1–U8 do not change engine behaviour.

## 6. Later (from the TVPaint research, ranked; each needs its own owner OK)
Light table colours and per-frame toggles · marks in/out with loop-range playback · auto-break of linked frames on edit (a
per-clip setting) · exposure sheet view · repeat-images holds (boil) · anim brushes · fill reading boundaries from the layer
above · out-of-pegs ghost moves. Per-layer timing (a layer on 2s in a clip on 1s) and a start offset per layer come from the
"Clips and Stages" discussion and belong with C3 when the owner asks.

## 7. Proof
- JVM: DocOps overlap rule cases (same layer refused, different layers allowed, touching edges allowed); create animates only
  the active layer; `end` round-trips and unknown values read as loop; PlaybackClock HOLD/BLANK; Combine (union rect, length,
  fps retime, loop/ping-pong/hold/blank cels, links, one undo, two old clips gone); Flatten across combined clips.
- Note 9: three overlapping clips (background 25 loop, character 50, character 12 ping-pong) drawn on three layers; Play all
  at 60 fps; Combine; Flatten; export GIF; reopen.
