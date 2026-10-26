# Boards residual recovery — October 26, 2026

## Assignment and source

Owner-authorized Lead assignment: docs-only recovery, not feature implementation.
Plan: freeze source, recover current callers and historical receipts, distinguish
remaining gates from already-built functionality, propose catalogue/help entries,
check documentation links, publish only owned documentation.

- Frozen canonical base: `741fa01543265ca89634113892d4d30ea422eceb`.
- Isolated checkout: `C:/Temp/jb-board-recovery-20261026`, branch
  `codex/board-recovery-docs-20261026`.
- Before creation, advertised remote coordinator
  `origin/lead/select-reconcile-20261024` equalled that base (`git ls-remote`).
  Final report supplies this docs branch's commit and remote equality receipt.
- Exclusive paths: this report, the BOARD-RESIDUAL roadmap row, and
  [STATE proposal](BOARD_STATE_PROPOSAL_20261026.md). Root owns actual STATE edits.
- No source edits, builds, tests of the application, phone actions or locks acquired.
  Only the read-only documentation link checker is run for this patch.

Earlier inventory at coordinator `d4a279bc241f686ce0af90df9e626ce20d0c3b66`
observed advertised production `efcb293bb31285e5ce9c160f2ab91e0b67e7d588`
and coordinator `f6287b9215df989ccd07728ac22de949b413f575`; the Lead confirmed
publication was in flight. These are observations, not a divergence defect or a
reason to reset. Primary remained `fb80428412374284330eacf94233cafdb0ea9446`
with 472 porcelain entries. Original `jb-board-chrome` remained
`c5e003461f7f66018d2317d34afe0b7160420f21`, with staged BoardChromeView,
its test, INTEGRATION.md and added STRIP_LANE.md. Both were preserved. No claim
that every unrelated worktree was audited or synchronized.

## Current source registry

Links resolve in this frozen tree; callsites establish source availability only.

- [Owner contract](../specs/JB-3.00a_boards_owner_model.md): K/K10/G7a,
  including later Sprite usability clarification. Owner words outrank old designs.
- [Controller](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt):
  creation/selection/geometry, strip callbacks, preview lifecycle, grid/roll/swap,
  Tile arming and onion callbacks. `showMenu`, `beginPlacement`, `spriteGridAction`,
  `refreshOnion`, `stripDrop` and `SpriteCellOps.swap` are actual entry points.
- [Native chrome](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardChromeView.kt),
  [pure layout](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BoardChromeLayout.kt),
  [fade clock](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BoardChromePenFade.kt),
  [session](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardSession.kt).
- [Activity adapter](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt):
  Host tile/onion/export/preview hooks, layer `setLayerHeld`, picker-safe export
  target and internal destination dispatch. Root-owned: do not replace from old lanes.
- [Layer column](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/LayerColumnView.kt):
  held/animated markers; G7a actual crop reads remain host/backend responsibilities.
- [Board panels](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardPanels.kt),
  [backdrop](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardBackdrop.kt),
  [export presentation](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/ExportChoiceSheet.kt).
  Panels now use shared fill and transparent windows; their platform window Frost
  hook is API31+. Do not repeat the October7 plain-dialog gap as current absence.
  API29 exact Frost/visual compliance still needs evidence.
- [Export coordinator](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinator.kt),
  [board exporter](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardExport.kt),
  [animation runner](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt),
  [plan](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt),
  [sheet packer](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/SpritePacker.kt).
- [Internal receiver](../../../app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java):
  `joybrush_board_bundle` and `joybrush_board_studio`; parses existing sheet format,
  copies durable project assets, saves/reloads project, opens Studio or SpriteLab
  with project/sheet IDs. [SpriteSheet](../../../app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java)
  is the shared model/parser. Internal staged import is not a general external
  PNG/sidecar picker contract. BoardExport's unsupported destination branch alone
  does not mean bridges are absent: the Activity routes these separately.

## Capability / evidence / residual matrix

| Family / user entry | Present in frozen source | Evidence and remaining acceptance |
|---|---|---|
| Menu → Boards: Image, Animation, Sprite; size/lock/move | Controller + BoardDocumentOps; selected scope distinct from saved cursor | October6/7 scoped phone workflow; current mixed-content/mask/geometry and exact visual recheck owed. Editable-frame copy belongs to FRAME-DUP. |
| Animation film strip, holds, scrub, lift/drop, pegs | Native chrome + core FilmStrip; stable identity/order rejection, shared mechanics | October7 short-cell tap, hold-edge drag, hold Undo and restart receipts; current real-pointer/cancellation/lifecycle recheck owed. No claim of tween/clip completion. |
| Onion skin, held markers and scoped layer previews | Controller onion/Activity engine hook; held-layer transaction + layer column | October7 board-scoped neighbor ghosts, clipped animated art over held base and no held-base ghost recorded. G7a current saved-frame/crop/cache parity and all row states need fresh scoped proof. |
| Image pattern switch: fullscreen Tile, docked exit, saved first-wrap flag | Controller/session + existing renderer/commit routing | October7 repeated-area boundary stroke, disarm confinement, Undo/Redo and tiled flag checked. Current hint explicitly refuses realistic pencil/watercolour/oil wrapping. Preserve all existing unsupported-tool guards; do not enable by removing refusal. |
| Sprite sizing, locked finger roll/preview, separate swap arm | SpriteGridMath, CellRoll, bounded composited previews, all-layer/mask SpriteCellOps | October7 phone two-layer swap/one Undo and size/roll checks. Automated mask/boundary evidence is separate from physical phone sweep; current realistic-media/INK behavior is not inferred. |
| Board Export: image/all-images PNG; animation all/range/current, GIF/frames/sheet | Captured target + existing encoders/paper/range/budget handling | Historical PNG and Studio video receipts; current artifact agreement, refusal/preflight and picker cancellation/snapshot boundaries require exact-source tests. Active live-source/tween export is excluded here. |
| Open in Studio / SpriteLab | Existing private-stage receiver, durable assets and shared sheet JSON | October7 actual import/reopen/timing/MP4 decode recorded. No need for another receiver/format. Arbitrary external import needs contract recovery before being claimed or implemented. |
| K1/K6 app-wide consistency | Shared native board sheet/panels; Studio has its own private confirmation flow | Exact §K screenshot/state comparison and API29 Frost remain open. Reusing an icon/service is not proof the complete Studio/board presentation is unified. Coordinate any UI work with UX. |

## Historical receipts — not fresh activation

[BOARD_COMPLETION_20261007](BOARD_COMPLETION_20261007.md) records a disposable,
controlled Note9 two-layer fixture: placement, Animation resize/move-all/duplicate/
remove and Undo, held strip, Sprite resize/roll/swap, tiled wrapping/marker, scoped
onion, actual SpriteLab navigation and Studio reopen/export.

- Published historical feature checkpoint: `109e41c7`.
- Broad functional sweep APK SHA256:
  `B32C5D868B0DCE6EED383653F0CAD05E36A1AFE5D1735C6BEE11E881054872BF`.
- Final first-arm-hint APK SHA256:
  `3D7D881098296742FBA4C651CC2AB9437D9C717A9D1DB0A820972E4D7ABFDD37`.
  Its final phone subset was reopened Tile preview/toast/disarm; it is not a
  repeated full functional sweep on the changed APK.
- Studio output independently decoded there: H264480×240, 10 frames,
  0.333333s; frame paint present, no onion/chrome. Preset `[0,0,0,1]` at12fps.
- Historical regression count1651 core /279 backend /74 native, four optional
  ABR skips, zero failures/errors. These suites were NOT rerun for this recovery.

[October6 phone](NOTE9_BOARD_TEST_20261006.md),
[connections](BOARD_CONNECTIONS_20261006.md) and
[Sprite source integration](SPRITE_GRID_INTEGRATION_20261007.md) have narrower,
dated scopes. The Sprite integration note's disconnected-phone status was later
superseded only by the executed October7 subset above. Earlier owner confirmation
of boundary painting/Animation is recorded in chat; do not broaden it to current
all-media behavior, every §K scene, physical S Pen feel or marketing LIVE status.

[R51 export audit](R51_BOARD_EXPORT_AGREEMENT_AUDIT.md) is read-only on old
`e44d86c35f4ffbf9dcc6835ddc20b73e4e440682`; it recovers useful reuse/refusal
boundaries, not a current parity receipt. Check current callers before adopting
its findings. Historical archive Outline/Ready labels do not override working
receivers or current active lane ownership.

## Remaining checklist and bounded next assignment

- [x] Recover source, historical proof limits and existing cross-wing receivers.
- [x] Produce small ROADMAP row and separate STATE/help/reuse proposal.
- [ ] Root reconciles proposed catalogue entries and chooses frozen next base.
- [ ] Source-only K/K10/G7a matrix: icon/rest/approach/fade,40dp labels,contrast,
  glass/Frost,held markers,Tile docking and updated Sprite lock/arm sizing rules.
- [ ] Review current coverage before assigning new board-only artifact/gesture
  fixtures. Then admit exact-source focused tests through the shared build slot.
- [ ] Current API29 visual/state/device artifact checks, separately admitted by
  root, with APK/run/screenshot/data receipts and explicit owner creative limits.
- [ ] Resolve app-wide K6 presentation contract with UX/Studio owners; recover
  arbitrary external-import scope before adding it.

Concrete next task: root assigns a new **docs/test-design-only** K-state matrix
with exact current callsites and independently computed expected targets, plus a
coverage review of existing SpriteSwapExportTest. No engine/Activity/exporter
changes or new tests until paths/base/build admission are explicitly sequenced.
FRAME-DUP, TWEEN-LIVE, CLIPS, LIVE-SOURCE, Atlas and paper/UX owned work remain
excluded. Do not relax realistic-media/vector refusals to make fixtures green.

Reuse existing core FilmStrip/PlaybackClock/CellRoll/SpriteGridMath and board
ownership; studiokit FilmStrip/WeightedScrubBar/RollDragController; board
presentation; AnimExportPlan/Runner/SpritePacker and app receiver. Reuse adapters
still need independent proof. Proposed pending help IDs and catalogue rows are
in the separate proposal; no new user-help article or capability is shipped here.

## Documentation verification

`python -B tools/check_joybrush_docs.py` passed: 32 local links across the three
live entry documents. A separate read-only invocation of that checker's link
parser resolved all25 links in this report/proposal. `git diff --check` passed.
These checks establish local link targets and whitespace only, not application
behavior, evidence completeness, current deployment or owner acceptance.
