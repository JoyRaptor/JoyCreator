# R51 Feature Accessibility Audit — Boards + Editable Lines/Tween (Assignment C)

Base (verified by coordinator): `e44d86c35f4ffbf9dcc6835ddc20b73e4e440682`.
Scope owned: `tasks/joybrush/reviews/R51_FEATURE_ACCESSIBILITY_AUDIT.md` (does not exist in this checkout; this report is its content). No FilmStrip implementation audit, no memory/grain/media duplication, no dirty worktrees, no code changes. Static source inventory only; no runtime/device claim. Tests cited, none executed.

Read: `AGENTS.md`, `START_HERE.md`, `tasks/joybrush/ROADMAP.md` (§R51), `tasks/joybrush/LEAD_RULINGS.md` (R50/R51), `tasks/joybrush/specs/JB-5.20_lines_in_the_one_layer.md`, `tasks/joybrush/specs/JB-3.00a_boards_owner_model.md`, `tasks/joybrush/design/ONE_LAYER_MODEL_BRIEF_20261007.md`.

## Path registry (evidence IDs)

- E1 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt`
- E2 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardPanels.kt`
- E3 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinator.kt`
- E4 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt`
- E5 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardChromeView.kt`
- E6 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BoardChromeSession.kt`
- E7 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BoardChromeLayout.kt`
- E8 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardSession.kt`
- E9 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardDocumentOps.kt`
- E10 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/Tween.kt`
- E11 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/CelComposer.kt`
- E12 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkEditSession.kt`
- E13 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BrushShelf.kt`
- E14 `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/fill/FillTrace.kt`
- E15 `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt`
- E16 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/LayerColumnView.kt`
- E17 `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/ExportChoiceSheet.kt`

## Requirement vs observed vs unknown

Requirement (JB-3.00a §B/G/K + JB-5.20 §D + brief §4.5–4.6): user can create/type/place/lock/resize boards, set cell sizes, use frame controls and export, all from visible chrome; on any layer, lines stay selectable/editable (select, nudge, tip-drag, slice, recolour, rebrush, delete, Rasterize Down, Flatten Lower), two smudges, vector fill, tweens.
Observed: board chrome chain is wired entry→handler→engine. Line/tween chain is core-built with no UI caller.
Unknown (no phone/shell): actual on-device visibility, gesture feel, focus order, TalkBack traversal, timing/perf gates.

## Accessibility table (accessible = reachable + wired; visible ≠ wired is noted)

| Feature | Verdict | Exact source evidence |
|---|---|---|
| Board creation/type/placement | Accessible (wired) | E4:804 `Boards…` → E1:724 `showMenu()` → E1:727–732 kind list → E1:735 `beginPlacement()` drag rect E1:758–772 → E1:778 `creationForm()` → E9 via E1:789–791 `createImage/createAnimation/createSprite`. |
| Lock (incl. animation fixed-after-2 rule) | Accessible (wired) | Visible `lock` glyph E7:192–194; handler E1:520 `setLocked`, animation multi-frame diverts to `moveBoardForm(allFrames=true)`; engine E9:40 `setLocked`. UI visible and callback present. |
| Resize/move (drag + numeric) | Accessible (wired) | Handles drawn E7:205–206, `control("handle-$n")`; drag E5:613–615 → E1:594–609 `resized()` + `resizeBoard()`; numeric E1:429–439 `boardSizeForm` and E1:703 `moveBoardForm`. Engine E9:56 `resize`, animation path `AnimationBoardOps.resize` E1:491. |
| Cell sizes (sprite grid) | Accessible (wired) | `cell-size`, `grid-count/px`, `cols/rows`, `subgrid` handled E1:532, E1:511–519, E1:448–487 `spriteGridAction()` → E9:8 `setSpriteGrid` with `MAX_CELL_PX` refusal. |
| Frame controls (add/duplicate/link/blank/hold/delete/reorder/scrub/play/fps/loop/onion) | Accessible (wired) | `add` E1:521, `frameMenu` E1:679–687, `stripDrop` E1:546–559, `stripHold` E1:560–567, `chooseFrame/stripScrub` E1:493,538, `togglePlay/startPlay` E1:800–804, fps/loop/onion E1:522–530. HostEngines: E4:410 `selectBoardFrame`, E15:1866 `selectBoardFrame`. |
| Board export | Accessible (wired) | `export` glyph E7:195; E1:531 → E4:420 `pngOptions(layersBtn,boardId)` → E4:2066–2074 → E3:38 `show()` → E3:88–95 `BoardExportRequest.capture` → `choose()` → E4:1790 `BoardExport.stage` → E3:161–189 `write()`. Scopes/formats/gating E3:97–132. |
| Layer animated/held marker + previews follow board | Accessible (wired) | Layout E7:303–308 `layerMarker()`; view E16:205–249 `AnimationMarker` click → `setLayerHeld`; E4:1002–1008 → `RegionDocumentOps.setHeld`; markers refreshed E4:1125–1127, E4:421,427. Previews: E6:7–24 `BoardLayerPreview.scope`, thumbs carry `selectedBounds` E4:1150–1158. |
| Chrome a11y labels / touch targets | Partially accessible | Targets expose `contentDescription=tooltip` E5:178, clickable+focusable E5:571; layout enforces `TOUCH_DP=40f` E7:59,122; dialogs set `accessibilityPaneTitle` E2:116, E17:75; form fields set `contentDescription/hint`, 44dp min E2:59–71,152–156. Device traversal order not verifiable statically. |
| Fill pen brush (pixels path) | Accessible (wired, pixels only) | E15:1378–1404 `ENGINE_FILL` → `FillPenRaster.prepare/plan` → `engine.replaceTiles`. This is paint output, not JB-5.20 D10 vector fill. |
| Editable lines: Select/nudge/tip-drag/slice/recolour/rebrush/delete | Absent from UI (core built-but-unwired) | `StrokePicker` callers are tests only; `InkEditSession` has zero production callers (definition E12:68; refs only KDoc + `InkEditSessionTest`). `CelComposer` production refs are type-only (`UndoLog.LineChange`); methods (`bake/applyLines/paint/look`) called only by `CelComposerTest`/`LineUndoTest`. No `Select` tool entry, layer-menu Rasterize/Flatten buttons, or tip-drag/slice handlers found by targeted searches. |
| Rasterize Down / Flatten Lower buttons | Absent from UI | Strings appear only in spec/brief/composer KDoc (E11:165–169 `bake` = Rasterize Down primitive). No menu/dialog/handler caller. |
| Two smudges, badges, one-time toasts | Absent from UI | `BrushShelf.makesLines` exists E13:64–70 but per-brush `Editable lines` switch explicitly deferred to 5.20e E13:62. Toast/badge strings (`bakes to pixels`, `That line became pixels`) occur only in specs/brief, zero production callers. |
| Flood-fill-as-vector (`FillTrace.trace`) | Built-but-unwired | `FillTrace.trace` callers are `R51Measurement` + `FillTraceTest` only; no canvas/tool caller. The pixel fill-pen path is wired; a tap flood-fill tool connection was not established. Keep these distinct. |
| Tweens (`Tween.between`) | Built-but-unwired | `Tween` callers are `TweenTest` only (19-hit repo search: E10 + test). No toggle, scrubber colour change, hold-gated interpolation, or playback caller. Spec `JB-5.40_tweens.md` gated on 5.20. |
| Board type/placement dialogs | Accessible (wired) | `BoardPanels.menu/confirm/formValidated` E2:34–102 with validation, error retention, keyboard resize handling E2:122–131. Entry E1:688–701 `boardMenu`, E1:679 `frameMenu`. |

Note on `BoardChromeSession`: E6:1–80 holds only `BoardLayerPreview`, `BoardChromeIdentity`, `FrameQueue`, `PointerCapture`. Live select/arm state is E8 `BoardSession(select/arm/reconcile)`; do not cite E6 as the session owner.

Existing tests (not executed): `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeControllerTest.kt`, `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/BoardChromeViewTest.kt`, `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinatorTest.kt`, `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/ExportChoiceSheetTest.kt`, `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/chrome/LayerColumnAnimationTest.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/CelComposerTest.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkEditSessionTest.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/TweenTest.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/fill/FillTraceTest.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/chrome/BoardChromeSessionTest.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/chrome/BoardExportLayoutTest.kt`.

## Prioritized missing connections (no arch changes proposed)

1. Select tool entry + gesture → `StrokePicker`/`InkEditSession` → `UndoLog.LineChange` → engine re-render (JB-5.20d/e). Biggest gap; unlocks nudge/tip-drag/slice/recolour/rebrush/delete acceptance.
2. Layer-menu Rasterize Down (`CelComposer.bake`) and Flatten Lower as one-undo actions with confirm/undo wording.
3. `FillTrace.trace` wiring for tap-fill-as-shape (D10, 0.75px tuck); keep the existing fill-pen path distinct.
4. `BrushShelf.makesLines` → drawer badge + `Editable lines` switch + two smudge entries + one-time toasts (5.20e).
5. Tween toggle + hold-gated `Tween.between` preview/playback (post-5.20).
6. Export `RegionRenderer` slabs+lines path (5.20f); current export does not draw `CelComposer.look`.

## Proposed phone acceptance checklist (no calls made)

- Create Image/Animation/Sprite/Tile boards by drag; type name/grid; lock blocks geometry dragging while frame controls still work; unlock re-enables move/resize; numeric W/H edits pivot top-left.
- Animation board with 2+ frames refuses move/resize except deliberate move-all-frames with confirm, one undo.
- Sprite grid count/px/sub-grid edits; armed swap carries all layers, each committed swap drop is one undo; disarming ends armed mode; sequence+preview; export sheet+JSON.
- Frame add(duplicate)/link/blank/hold/delete/reorder/scrub/play/fps/loop/onion; export animation/range/this-frame, image batch, tile preview.
- Layer marker toggles animated/held; thumbnails crop to selected board and follow current frame; passive restores.
- Lines (when wired): same-layer paint over/under line, nudge/tip-drag/slice/rebrush/recolour, smudge-paint-only vs smudge-consume toasts, tap-fill recolour, Rasterize/Flatten + undo, save/reopen editable, TalkBack labels + 40dp targets.

## Coordinator-review checklist

- Confirm base commit hash against clean checkout before filing.
- Spot-check 3–5 cited line ranges (E1:520, E1:789–791, E3:88–95, E13:64–70, E10 caller search).
- Confirm FilmStrip internals untouched by this report.
- Decide filing path for this content as `tasks/joybrush/reviews/R51_FEATURE_ACCESSIBILITY_AUDIT.md`.
- Route line/tween gaps to JB-5.20d/e/f owners; do not treat core-built as phone-accepted.

SUBTASK_REQUEST: none (read-only worker; no children needed).

Coordinator review: verified assigned base, board swap transaction semantics, fill-pen versus tap-fill distinction, and absent line/tween production callers. Static reachability is not device or TalkBack acceptance.
