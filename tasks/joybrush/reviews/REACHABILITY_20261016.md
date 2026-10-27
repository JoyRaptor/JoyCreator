# REACHABILITY AUDIT — what the artist can actually reach (2026-10-16)

Source: `C:/Temp/jb-live-current`, branch `lead/jb-live-current`, HEAD `fe2838486`. Read-only, **static reading only — nothing built, run, or tried on a phone.**
Author: sonnet helper 1 (mid tier) for the JoyBrush Lead. Five Haiku sweeps did the grunt reading; the checks marked **[V]** I re-read in the code myself. Everything else is a sweep's report, cited but not re-read: treat as probable, not proven.

Path keys: `JCV` = `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` · `GPE` = same dir `gl/GlPaintEngine.kt` · `CS` = `io/CanvasSnapshot.kt` · `JBA` = `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` · `BRC` = `…/android/board/BoardRuntimeController.kt` · `BEC` = `…/board/BoardExportCoordinator.kt` · `CORE` = `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core`.

## 0. THE HEADLINE
**None of the Codex 10-12→10-16 recorded/captured/promotion/cold-load/joint-undo machinery is switched on, and the shipped build actively refuses any drawing that would need it.**
- **[V]** Opening a file with stroke records is refused: `JCV:1900-1902` "this drawing has ink strokes in it, and this screen cannot show ink yet". Slabs / looks / any cel with `strokesFile` → `CS:20-24` "This drawing has editable lines or pixel slabs that this screen cannot show yet; your saved drawing was kept". Frozen brush copies → `CS:25-27`.
- **[V]** Nothing in production makes a `StrokeRecord`: the only `StrokeRecord(` in app/engine main code is `bench/BenchRunner.kt:87`. `onDiagnosticStrokeCapture` is null except in `BenchRunner.kt:205`.
- Every entry to `createRecordedScene / createCapturedRecordedScene / createSavedRecordedScene / loadFreshRecordedArtwork / preparePlainRecordingPromotion / publishJointPaintStep / bakeRecordedLines / snapshotJointRecordedArtwork` is called only from `gl/Recorded*Probe.kt` (GPE:2365-2429, 2926, 4219, 4354, 4388; sweep E). `ColdRecordedLoadPlan` callers are probes + tests only. Production load is the legacy tile loader (`JCV:1842-1893`, writes `writeTile/writeCelTile/writeMediaTile`).
- The probes sit behind `ApplicationInfo.FLAG_DEBUGGABLE` + the `jb_bench` intent extra (`JCV:2209, 2293, 2363`; `BenchRunner.kt:280-281`). **So the 876/203 test receipts and the Note 9 "captured-joint" device receipts prove debug probes, not anything the owner can touch.**
- Select tool: **not reachable at all** (see §1.8).

## 1. FEATURE TABLE
Legend: ✅ reachable in normal UI · ❌ not reachable · 🧪 only behind debug/bench · ❓ unknown.
All refusals reach the user as a LENGTH_LONG Toast via `canvas.onRefused` (`JBA:452`, `JBA:2234-2236`). Refusals fire at **stroke start, not when picking the brush** (`BrushDrawerView.kt:106` always accepts the pick).

### 1.1 Brush engines (drawer = 11 folders in `brushes/index.txt` + `MediaPresets.ALL` appended at `BrushLibrary.kt:48`; no reachability filter, `BrushDrawerView.kt:40`)
| Engine | Reach | Presets | Dispatch (file:line) | Refusals / notes |
|---|---|---|---|---|
| stamp (dab) | ✅ | ink, pencil(stamp), marker, softair | `JCV:1455-1461` dabber else-branch; `GPE:3594` | none specific; general gates §1.1b |
| tuft / sable / bristle | ✅ | sable, bristle | `JCV:1449-1453`; `GPE:3745`; shader `jb_tuft` | none found; behaviour on tile/mask ❓ |
| media pencil (dry) | ✅ | Proto, 2H, HB, 2B, 6B, Woodless 8B (code presets, no JSON) | `JCV:1404`→`startMediaStroke JCV:1232` | M-list below |
| media watercolour (wet) | ✅ | All-round, Round, Wash, Dry brush, Water, Thirsty | same | M-list below |
| media oil / knife / scraper (paste) | ✅ | Oil flat/round, Fan blender, Palette knife 1&2, Scraper | same | M-list below |
| "watercolour line" (engine `watercolour`) | ✅ (shelf=PAINT, not WATERCOLOUR: `BrushShelf.kt:52`) | `brushes/watercolour` | `JCV:1445-1447`, `GPE:3614` | tile: "Turn tiling off to use watercolor" (US spelling) `JCV:1405` **[V]**; mask+multiply refused `JCV:1400` **[V]**; `JCV:1655` "Watercolor stroke reached its dab budget; lift the pen and continue" |
| smudge | ✅ | smudge, flatpaint | `JCV:1372` **[V]**; `GPE:3613` | tile: "Turn tiling off to use smudge" |
| fill (flood / fill pen) | ✅ | fill | `JCV:1373-1376` **[V]**; raster commit `JCV:1565-1590` | tile refused; mask: "Select the paint layer to use the fill pen; masks are not supported yet." **[V]**; `JCV:1727` "Fill stroke is too long; draw a smaller shape"; **silent**: a fill under 4 screen-px² commits nothing, says nothing (`JCV:1580`) |
| eraser | ✅ | eraser | `JCV:1377` (`b.erase||eraser`), stamp path | general gates only. Eraser over a media brush goes down the stamp path (`!eraser` at `JCV:~1407`) |
| ink / vector line brushes | ❌ | 10 JSONs in `brushes/lines/` — not in index, README says "not extra drawer entries yet" | none on live path; `BrushShelf.makesLines` has no caller | Even if drawn, load refuses them (§0) |
| push / wet engine | ❌ | none | validator-only | falls to stamp |
| media eraser | ❌ dead code | — | `mediaErase` only ever assigned false (`JCV:1221,1316,1407`) | |
**1.1b General gates (all engines):** content lost "Reopen your saved drawing before continuing." `JCV:1355` · locked `"<layer>" is locked.` `JCV:1360` · hidden `"<layer>" is hidden. Show it to paint on it.` `JCV:1366` (all **[V]**).
**Media refusals (M):** mask `JCV:1236` "A mask is painted with paint brushes. Pencil, watercolour and oil paint on the layer itself." · no float-colour GL `MediaCanvas.kt:257` "This phone's graphics cannot paint pencil, watercolour and oil (it has no float colour buffers)." · free-RAM `JCV:1243-1251` / `LayerNames.kt:141-147` "There is not enough free memory to paint with water right now…" / "…for pencil and oil…" · tile board `JCV:1258` "Turn tiling off to use pencil, watercolour and oil" · per-drawing ceiling `LayerNames.kt:150-152` "This phone has room for <N> MB of pencil, watercolour and oil paint, and this drawing has used it…" (`GPE:4692`) · setup `JCV:1271` "This drawing could not take pencil, watercolour or oil". **Silent:** `JCV:1307` no stroke painted and nothing said if `boardDocument==null` (reachability ❓).
**Drawer silent-drop:** a built-in preset that fails decode/validate vanishes from the drawer with `Log.w` only (`BrushLibrary.kt:70-77`).

### 1.2 Layers (no layer control is debug-gated; opened by the "Layers" top-bar button `JBA:596,605`)
| Feature | Reach | If refused (exact) | Evidence |
|---|---|---|---|
| Add | ✅ "+" cell | "This phone has room for $max layers, and this drawing has N." `JCV:735`; **silent no-op mid-stroke** `JCV:548` | `LayerColumnView.kt:117`; `JBA:1049` ignores the Boolean |
| Delete | ✅ panel row | last layer: "A drawing needs at least one layer, so the last one stays." `JCV:568` | `JBA:1314`; `LayerStack.kt:66` |
| Reorder | ✅ long-press drag | none; clamps; silent mid-stroke | `LayerColumnView.kt:325,519`; `LayerStack.kt:76` |
| Duplicate | ✅ | budget as Add | `JBA:1309` |
| **Merge down / flatten layers** | ❌ NOT FOUND (my grep: only ORA's `mergedimage.png`) | — | animation-frame flatten only (`BoardDocumentOps.kt:106`) |
| Opacity | ✅ slider, one undo step | clamped 0..1 | `JBA:1251-1279` |
| Blend modes | ✅ picker; **all 27 enum modes** (8 Joy Brush + 19 Studio) in 6 groups + "Cut" | none | `LayerNames.kt:14-25`; enum `DocModel.kt:197-204` (count verified by me = 27) |
| Mask add / paint / delete | ✅ | media/fill/multiply-screen refusals above | `JBA:1285-1299`; `JCV:628` |
| **Mask apply (bake into layer)** | ❌ NOT FOUND | — | |
| Clip to layer below | ✅ (hidden on bottom layer) | moving/deleting a clipped layer to bottom **silently unclips** `LayerStack.kt:72,82,101` | `JBA:1300-1305` |
| **Lock** | ❌ **no UI setter**. **[V]** no `locked=` writer in app/engine main code; only a file already holding `locked:true` reaches the refusal | clear-layer `JCV:886`, stroke `JCV:1360` | `DocModel.kt:266` |
| Hide / show | ✅ (not an undo step `JCV:655`) | painting on hidden refused (§1.1b) | `JBA:1306` |
| Rename | ✅; blank rename keeps old name silently | — | `LayerStack.kt:104,137` |
| Clear layer | ✅ | `JCV:886` | `JBA:1310` |
| Held/animated marker | ✅ Animation boards | "select that Animation board again" `JBA:1061` | |
| LayerKind PAINT | ✅ only kind the UI creates (`GPE:885`) | | |
| LayerKind INK, MEDIA | ❌ legacy: decoded as PAINT (`DocJson.kt:97,112`); v9+/v10 docs containing them refused (`DocOps.kt:201,211`) | file-level only | enum is exactly `PAINT, INK, MEDIA` **[V]** |
| vector / adjustment / filter / text / image layer | ❌ none exist (filter = spec only, `JB-2.21`) | | "Image" is a *board* kind |
Mid-stroke silent guards: `JCV:548,553,564,635,748-749` (add, move, rename, blend, opacity commit, clip, addMask). Possible bug, **unverified**: `JCV:626-631` `addMask` sets `editingMask` before `commitStack` returns early mid-stroke → `editingMask` true with no mask.

### 1.3 Boards (menu: more → "Boards…" `JBA:831` → `BRC:754-826`)
| Feature | Reach | Refusal / note | Evidence |
|---|---|---|---|
| Image (CANVAS) board | ✅ drag a rectangle | keep ≥1 Image board `BoardDocumentOps.kt:114` | `BRC:765` |
| Animation board | ✅ | "Add a layer before creating an animation board"; "Start a new drawing to use region animation"; **art under the rectangle MOVES into frame 1** (one undo) | `RegionDocumentOps.kt:48-76` |
| Frames: add(dup)/link/blank/hold/delete, film strip reorder/drag-hold, scrubber wheel, onion, play/loop/ping-pong/once, fps 1-60 | ✅ | "Keep at least one animation frame" `RegionDocumentOps.kt:114`; onion/loop modes **in memory, not saved** | `BRC:507-581,708-717,831` |
| Sprite board (cols/rows or px cell size) | ✅ | "Choose a whole grid of cells within this rectangle" `BRC:814`; cell 1–4096 px | `BRC:462-530`; `SpriteGridMath.kt:48` |
| Sprite swap cells | ✅ only when locked | "Lock the board to rearrange cells" `BRC:540` | `SpriteCellOps.kt` |
| Sprite preview order | ✅ **in memory only** (`spriteRolls` `BRC:68`) | | |
| Tile / armed periodic | ✅ "New Tile board…" or the seam glyph on an Image board | `Board.tiled` is saved only after first wrapped stroke (`BoardDocumentOps.kt:~121`, `GPE:3924`) | `BRC:181,543` |
| Puppet / Character | ❌ no create; file with one refused `JCV:1906` "This renderer does not show puppet or character boards yet" | | |
| Multi-board regions | ✅ several coexist; overlap rules ❓ not read | | |
| Animation geometry | multi-frame board is fixed: "Animation with multiple frames is fixed; use Move all frames" `AnimationBoardOps.kt:10` | | |

### 1.4 Undo / redo
Production = one copy-on-write tile log (`UndoLog` in `GPE:473`) that also carries layer-stack, paper and board-document steps. **[V]** budget `192L shl 20` bytes (`GPE:203`, not overridden); oldest steps are dropped **silently** when over budget (`UL:393-397`); **no refusal string for undo exists** — buttons are disabled instead (`JBA:1710`). Undoable: strokes (incl. media), layer add/dup/delete/move/opacity/blend/rename, mask delete, clear layer, paper, board-doc edits. NOT undoable: hide/show, frame selection, guides, reference pin, selection creation. Undo is cleared by any load/Open/recovery (`GPE:1313`) and is not stored in the file. Line/slab undo exists only on the unbound joint path (`GPE:3947-3993`), unreachable in production. Cancelled watercolour leaves its painted part as one step.

### 1.5 Save / autosave / reopen / recovery / thumbnails (all sweep-reported, source-traced)
- Working file `getExternalFilesDir("joybrush")/current.joybrush` (library drawings `drawing-<time>.joybrush`); `.bak` + `.tmp` sidecars; atomic write (`JbArchive.kt:185-219`). Mirror into the Joy Creator folder after each save, failures only logged (`DrawingLibrary.kt:264-272`).
- Triggers: 30 s after last history change (`JBA:120,356`); stroke end; every `onPause` (rewrites even with no edits); 60 s clock only between strokes. **No save in `onDestroy`** (`JBA:552-566`). App killed mid-stroke → lose everything since the last completed save.
- Startup restore tries file then `.bak` (`DrawingHistory.kt:44-57`): "Recovered your drawing from its backup" / "Your last drawing could not be opened. Autosave will leave it intact: …". While unrecovered autosave refuses ("the previous drawing needs recovery before autosave can replace it").
- GL-context loss: restores from a cache copy with strings at `JBA:2064-2111`; "undo history restarted". The cache copy is not used after a process kill.
- Gaps: stale `open-*.joybrush` temp files never swept (`StagedDrawing.kt:29`, sweep only matches `joybrush-recovery-`); an unreadable working file + older `.bak` edge case (`JbArchive.kt:195-202`).
- **Open…** writes the opened drawing into *this run's* working file; the previous one survives only in "Recent drawings…" (last 5). Preservation runs before validation, so a refused file still leaves a Recent copy.
- Open refusals (exact): newer version "document is from a newer Joy Brush (version N, this build reads 11)"; unknown keys "this file has \"<key>\", which this version of Joy Brush does not know"; pre-v10 files with lines "this older drawing has lines drawn with brush \"<id>\"… cannot keep them as editable lines yet" (`JbArchive.kt:778`); plus §0 strings. `DOC_VERSION = 11`.
- Thumbnails: lobby thumbnail re-rendered after Working save only if none exists or >45 s old (`DrawingLibrary.kt:57,250`) → **stale up to a window; no trailing refresh**. Embedded `thumbnail.png` is never written (`JCV:2058` passes null). **Correction to a sweep:** `fillMissingThumbnails` *is* called — from Java `LobbyFragment.java:2023` **[V]** (the sweep grepped Kotlin only). Layer/board/sprite-cell live thumbs work (`JCV:665-702`, 250 ms debounce).
- Save a copy… (`.joybrush` via SAF, toast "Copy saved"); does not reset the dirty counter.

### 1.6 Export (per board only; menu "Export board…" `JBA:834`, sheet `BEC:38-143`)
| Output | Reach | Notes |
|---|---|---|
| PNG, Image board (this / all image boards → folder) | ✅ | `BoardExport.kt:68`; refuses any INK layer (even hidden) or `animatedIn` layer on this path: `CanvasPng.kt:36` "this PNG renderer supports raster layers; vector rendering is not connected" |
| Animation: GIF, PNG frames (folder + `.timing.txt`), sprite sheet (+`.sprite.json`), single frame PNG | ✅ | |
| "Send to Studio" / "Open in SpriteLab" | ✅ handoff to `com.fadcam…JoyBrushBoardImportActivity` — **outside this checkout, unverified** | |
| Whole page / whole document PNG | ❌ `SaveDest.Png` exists but `REQUEST_EXPORT_PNG` (`JBA:139`) is never launched | |
| ORA | ❌ `OraExport` has **no caller** | |
| PSD, APNG, WebP, MP4, Android share | ❌ NOT FOUND | PSD writer "not built" (`BenchCases.kt:313`) |
| Puppet/Character | "This board's export is not connected yet" `BEC:42` (unreachable) | |
Budget: when decoded pixels exceed the limit the **Export button is just disabled, no sentence** (`ECS.kt:38-50`). Renderer-wide: "Editable lines or pixel slabs in layer … cannot be exported by this renderer yet" (`RegionRenderer.kt:205`); "Vector artwork rendering is not available yet" (`:492`). Mask export behaviour ❓ not traced.

### 1.7 Selection / lasso / move / transform
**Select tool: ❌ not reachable. [V]** `SelectController` is defined (`SelectController.kt:36`) but constructed nowhere in main code; `ToolStripView.showSelect` has no caller except a test, and the Select item is created GONE (`ToolStripView.kt:69,220`); `JbCanvasView.tool` (`:909`) never assigned in production. No class in main implements `SelectionHost` (`SelectionHost.kt:18`, "Until it is backed, a fake drives the tests"). The pen-button lasso "does nothing rather than paint" (`JCV:958`). Designed-but-unbacked refusals (never seen by the owner): "Paint can be moved on one layer at a time." · "This loop doesn't touch any lines." · "This move is too large to make." · "Those lines changed. Tap again to choose." · "You can paint inside this selection, but it can’t be moved or edited right now." Cut, Paste, mask editing: NOT FOUND. Move/transform of pixels: no live path at all.

### 1.8 Recorded / captured stroke records
Default-on? **No — default-off and not even flippable from production.** See §0 and §3.

## 2. BRUSH ENGINE × BOARD MATRIX
Cell = works / REFUSED(words) / silent-nothing / ❓unknown. **"works" here means "no refusal or gate found in the stroke path", not "I saw it render".** Only the cells marked **[V]** were re-read by me.
| Engine \ target | Plain canvas | Animation board | Tile (armed) board | Sprite board | Layer holding media payload | Mask target |
|---|---|---|---|---|---|---|
| stamp (ink/pencil-stamp/marker/softair) | works | works — no stroke-time refusal `JCV:1352-1468`; correctness ❓ | works (wraps; `GPE:3924`) | works — correctness ❓ | works; engine bake keeps media and plain agreeing (comment `JCV:~1409`) | works, paints grey (`JCV:~1411`) |
| tuft (sable/bristle) | works | ❓ | ❓ **no gate**, wrap behaviour unverified | ❓ | ❓ | ❓ no refusal found |
| media pencil/watercolour/oil/knife | works unless memory/GPU/ceiling refusal (§1.1) | works on ONE plane: current frame, stops at board edge (`GPE:4534-4538`, R50); handoff says per-cel media is unlanded | **REFUSED** "Turn tiling off to use pencil, watercolour and oil" `JCV:1258` | ❓ no refusal found | works | **REFUSED** `JCV:1236` |
| watercolour line (engine watercolour) | works | ❓ | **REFUSED** "…watercolor" `JCV:1405` **[V]** | ❓ | ❓ | **REFUSED** if multiply and not erase `JCV:1400` **[V]**; erase variant falls to stamp ❓ |
| smudge | works | ❓ | **REFUSED** "Turn tiling off to use smudge" `JCV:1372` **[V]** | ❓ | ❓ | ❓ no refusal found |
| fill | works; **silent nothing** if <4 px² (`JCV:1580`) | ❓ | **REFUSED** `JCV:1373` **[V]** | ❓ | ❓ | **REFUSED** `JCV:1375` **[V]** |
| eraser | works | works ❓ | works (not gated) | works ❓ | goes down stamp path over media; ❓ correctness | works |
| ink / vector lines | **NOT REACHABLE** | n/a | n/a (`GPE:1922` "Recorded line replay on a periodic board is not connected yet") | n/a | n/a | n/a |
Note the tile-board hint once per session says only "Smudge, fill and the eyedropper do not wrap yet." (`BRC:199`) — **incomplete**: watercolour line and all media also refuse, and the eyedropper too (`JCV:1122,1175`).

## 3. FLAGS, GATES, GUARDS (name · where · gates · default)
**A. Runtime debuggable gates**
| Name | file:line | Gates | Default / who flips |
|---|---|---|---|
| `BenchRunner.spec()` FLAG_DEBUGGABLE + `jb_bench` extra | `BenchRunner.kt:280-281` | stroke bench + every probe case | off; only `app/src/debug/…/JoyBrushBenchActivity.java` forwards the extra |
| `runRecordedReplayProbe` | `JCV:2209` "Recorded replay probe is debug only" | replay, promotion, joint, cold, captured-copy probes | refuses on release |
| `prepareRecordedCaptureProbe` / `runRecordedCaptureProbe` | `JCV:2293 / 2363` | capture-parity probes | refuses on release |
| `startBrushLab` | `JBA:2310` (called `:518`) | hot-reload of `<external>/joybrush/lab` brush.json | off on release |
| `joybrush_test_recreate_context` extra | `JBA:377` | EGL context not preserved on pause | off |
**B. Build** — **[V]** `app/build.gradle.kts:91-96`: `debug` buildType = applicationId suffix `.beta`, `isDebuggable = true`, name "Joy Creator Beta" (`-beta9`). So **any Beta build opens every debuggable gate**, and `app/src/debug/AndroidManifest.xml:19` exports `JoyBrushBenchActivity` (`exported="true"`, forwards all extras). Whether the owner's phone runs Beta: ❓. release/pro/proPlus are non-debuggable.
**C. Internal switches (all default off, no production flipper)**
| Name | file:line | Gates |
|---|---|---|
| `publishedPaint` / `hasJointPaintPublication` | `GPE:431-447` | joint publisher; save refusal `JCV:1960`; undo branch `GPE:3942-3970`. Set only by `bindRecordedPaintPublication` (`GPE:4001`) ← probes |
| `localRecordedScene` / `recordedScene` | `GPE:454-462` | scene slot, null. Filled by `registerRecordedScene` (`:3423`), probe-only |
| `RecordedScene.CapturedSourceRenderer` explicit opt-in | `RecordedScene.kt:72, 1072` | ordinary renderers refuse to be captured sources |
| `admitLiveRecordedSource` + ticket/lease | `GPE:1684-1722` | "live" source admission — **no caller at all** (tests only) |
| `workingSaveAllowed` | `JBA:326` | default false; true only after a clean restore/open (`:1981,2058`) |
| `DrawingRecovery.backupAllowed` | `DrawingRecovery.kt:25` | default false |
| `mediaLegacyWindow`, `mediaWetQuality` | `JCV:435-442` | bench A/B only (extras `jb_media_legacy`, `jb_wet_substeps`) |
| `SelectTool.selectToolInHand/fingerSelects/addArmed/nudging` | `SelectTool.kt:54-62` | inert: SelectTool never attached |
| `canAdjustColour` | `SelectionHost.kt:82` | default false |
| `CanvasSnapshot.requireSupportedPayload` | `CS:19` | **the load/save refusal wall** ("Until JB-5.20d, the raster renderer must refuse the whole drawing") **[V]** |
| `refusalFor(contents)` | `JCV:1899` | load wall: strokes, no layers, puppet/character, non-pixel layer, reserved mask id **[V]** |
**D. Production-visible hidden gestures (not debug-gated):** long-press **More** → pen diagnostics overlay (`JBA:600,2240`) and "Checking all 27 blend modes…" self-check (`JBA:653`); long-press pin → reference menu (`:594`); long-press canvas → eyedropper, default ON (`JCV:413`); long-press brush chip → brush settings (`BrushDrawerView.kt:107`).
No `System.getProperty`/`getenv`/`SystemProperties` gate found in the JoyBrush roots. `JoyBrushActivity` and `DrawingsActivity` are `exported="false"`; the Joy Brush room is added unconditionally in the lobby (`LobbyFragment.java:480`).

## 4. TOP 10 RISKS for "all layers and boards working together flawlessly"
1. **Receipts measure probes, not the product.** All green counts (core 2442, backend 878, native 203, Note 9 captured-joint) exercise code only reachable through `FLAG_DEBUGGABLE`+bench. The owner's build has none of it. (§0; `JCV:2209`, `GPE:4001`)
2. **The load wall.** Any drawing with lines/slabs/looks/frozen brushes is unopenable in the live view (`JCV:1900`, `CS:20-27`). Activating recorded layers means replacing the loader, the saver (`JCV:1960`, `:1983`) and undo together — a single switch does not exist, and today a half-switch would strand files.
3. **Select / lasso / move / transform is entirely absent in the app** (`SelectController` never built, `SelectionHost` unimplemented, `JbCanvasView.tool` never set). Everything built for it (tests, count scrubber, box host) is unreachable.
4. **Tile boards refuse four tool families** (smudge, fill, watercolour line, all media) plus the eyedropper, and the hint tells the artist only about three (`BRC:199`). Tuft/sable on tile is ungated and unverified.
5. **Media × Animation board:** a media stroke paints one plane and stops at the board edge (`GPE:4534`); per-cel media is the unlanded JB-2.40 row. Media on Sprite boards and mask/animation combos: no refusal and no evidence either way.
6. **Layer-feature holes the artist will hit:** no merge down/flatten, no layer lock control, no mask apply, no adjustment/text/filter layers. Clipped layers unclip silently on move/delete (`LayerStack.kt:72,82,101`).
7. **Silent failures:** mid-stroke layer ops do nothing without saying so (`JCV:548-749`); fill <4 px² nothing (`JCV:1580`); media with null `boardDocument` nothing (`JCV:1307`); bad built-in presets vanish from the drawer (`BrushLibrary.kt:70`); oldest undo steps vanish past 192 MiB with no word (`UL:393`); Export button greyed with no sentence (`ECS.kt:38`). Possible `addMask` stale `editingMask` (`JCV:626`, unverified).
8. **Data-loss window on kill:** no save in `onDestroy`; kill mid-stroke loses everything since the last completed save (`JBA:346,552`). Open replaces this run's working file (previous kept only in Recent-5). Orphan `open-*.joybrush` temp files accumulate. Lobby thumbnails can be stale (>45 s rule, no trailing refresh).
9. **Export is per-board only and narrow:** no whole-document export, ORA has no caller, no PSD/MP4/APNG; per-board PNG refuses legacy animated/INK layers; Studio/SpriteLab handoff target is outside this checkout (unverified). Mask handling in export untraced.
10. **Beta = debuggable:** every `.beta` build enables the bench/probe doors and exports `JoyBrushBenchActivity` to other apps on the device (`app/build.gradle.kts:91`, debug manifest `:19`). Low severity for the artist; matters if Beta reaches testers.

## 5. WHAT I DID NOT CHECK (be blunt)
- Nothing was run: no build, no test, no phone. Every "works" is "no refusal found".
- Not re-read by me (sweep-reported only): all Layer-panel row wiring, `BoardRuntimeController` details, save/recovery strings, Select details, export string list, engine dispatch line numbers other than those marked **[V]**. Haiku line numbers can be off by a few lines.
- Not traced: stamp/tuft/eraser/fill correctness on Animation and Sprite boards; media on Sprite boards; board-overlap rules; mask handling in export; Studio-side handoff activity; whether Beta reaches users; whether any other branch backs `SelectionHost`.
- `tasks/joybrush/reviews/R51_SAVE_UNDO_INTEGRATION_AUDIT.md` is stale (base `e44d86c`, says DOC_VERSION 8) — do not use it.
