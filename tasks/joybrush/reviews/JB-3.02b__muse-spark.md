# JB-3.02b — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from spec writer/reviser `openrouter/stealth/space-bunny-alpha`. Independent read.
- Spec reviewed: `tasks/joybrush/specs/JB-3.02b_paper_overlay_view.md` (local HEAD `7bc60742`).
- Method: read-only. No gradle, no edits. All prior-BLOCKER fixes re-derived, not trusted.
- Board action (bunny's): may go 🟦 Ready once M1+M2 (+ m1–m7) are applied at dispatch.

**Verdict: READY, conditional on the M1+M2 refresh below. No BLOCKERs. 2 MAJORs, 8 MINORs —
all mechanical staleness from later Lead landings, none changing what the builder builds.**

## Verification counts

| Check | Result |
|---|---|
| Prior B1: `shown`→`visibility` | **Fixed in contract, 6 places** (`PaperOverlayView.shown` KDoc spec:404-415; `PegBarView.shown` KDoc spec:474-492; Steps 3–4 `init` call; Region 1b comment; Do-not spec:1396-1398; Owner's-check preamble spec:1329-1336). Honest caveat (m7): no JVM test *can* redden on an unwired setter (Views need a device — R27 `handOver()` precedent); backstop is verbatim code + Do-not + owed Owner's-check steps 1,3,4,5. The original defect (bar ships VISIBLE, 5 live TalkBack nodes over `= Unit`, forbidden by `JoyBrushActivity.kt:462`) cannot recur if the verbatim code is copied. |
| Prior B2: hide-on-panel-open 5th file | **Fixed by design**: `PegBarView.Host.panelOpen()` + `paperSource.panelOpen()` both read `popovers.isOpen` inside owned Region 3. Zero `Popovers`/call-site edits; owner area stays 5 files; stop-rule 6th-region clause present (spec:1459-1460). |
| Prior B3: `pegBarWidthPx` above count 1 | **Fixed**: `count × pitch + 2 × radius` with **248 (five)** and **72 (single)** (spec:256-260); Test 1 pins `248/651/744/992` + `72` + guard; Test 8 pins the null band (200 px → bar; 71 px → null); non-vacuity covers the `(count−1)` mutation (spec:1293). |
| Region count | **FIVE everywhere normative** (52 matches). "Three" survives only historically/explanatorily. |
| Activity anchors (prior "9") | **9/9 semantic anchors resolve, 0 invented; 9/9 numeric lines stale** (M1 — corrections below). |
| `JbColors` tokens (prior "7") | **7/7 names exist, 0 invented**; values byte-verified; 5/7 lines stale by 1–3 (m2). |
| `PaperGeometry` / `ViewTransform` / `ChromeKit` / `ToolStripView` / `TopButton` / `ValueHud` | **All exact** (13/14 + 8/8 + 9/9 + 6/6 + full). Token values exact (`jb_raised`→`s_raised`; `jb_board_animation_start/end` = `#FF35F6BF/#FF97FE8B` byte-equal to `s_go/s_go_end`; `Studio.ON_GO=0xFF050507`). `s_on_go` exists — Q1's mirror question well-formed. |
| JB-3.02 core half | **Confirmed**: `core/.../anim/PaperGeometry.kt` (289 lines) + `PaperGeometryTest.kt` with exactly **22 `@Test`s**. Cut table 5/5 assigned. |
| JB-3.00 hole | **Substance confirmed**: no `JbDocument` in `JoyBrushActivity.kt`; `DocOps.newDocument` builds `BoardKind.CANVAS` (`DocOps.kt:36`); `activeBoardId` pre-exists; `ThreeFingerSwipe` has no production caller. **Two citations stale + one context shift** (M2). |
| R30/R32/R33, blueprint, constraints | **No contradictions.** Stop rule present, 9 bullets (spec:1436-1460). |

## MAJOR (refresh at dispatch)

**M1 — All 9 `JoyBrushActivity.kt` numeric anchors stale by 33–175 lines** (Lead landed ~24
commits after 2026-09-30). Semantic anchors resolve, builder still lands correctly.
Corrected readings: "a button that does nothing…" `:355`→**462**; hairline `:271-272`→**362-363**;
"only if changed" `:480-484`→**598-611** (cond. :605-610); `TOP_RESERVE_DP` `:127`→**160**;
`chromeShown` `:192`→**239**; `return overlays` `:430`→**553**; `stripHost` closes `:466`→**557-589**;
`toggleChrome()` `:718-730`→**886-900**; strip added `:403`→**509** (`addView(strip` :518);
imports `:34-35/:56-57/:62/:36`→`:39-40`/:70**/:80**/:40**; five `show`/`showSheet` sites
`:520,:576,:628,:661,:750` → now **13 production sites**
(`:652,:708,:765,:828,:1062,:1094,:1200,:1221,:1395,:1850,:1863,:1887,:1910`).

**M2 — Decision 22 / Region 1b / Q2 premise overtaken: the phone now HAS an animation-board
path.** `JbCanvasView.animateActiveLayer()` (`:289-295`, CANVAS→ANIMATION via `board.copy` at
`:291-293`, reported at `:352`) wired to ⋯ menu "Animate this layer"
(`JoyBrushActivity.kt:717-719`). So "the only document anywhere is the single CANVAS board"
(spec:842-843) and `JbCanvasView.kt:876` (Decision 22, Region 1b) are stale — `newDocument` is
now at **`JbCanvasView.kt:1451`** (JB-3.00's `:1173` correction itself drifted). Build
unaffected (`frame() = null`, `shown = false` still correct), but Q2's "nine rows blocked"
framing and Owner's-check step 1 need a one-sentence qualification acknowledging
`animateActiveLayer` as the partial board source.

## MINOR

- m1 `JbIcon` is ten icons, not nine (spec:32,790,1410,1564-1565; `:17-26`→**`:17-27`**, `GUIDES` at `:27`).
- m2 `JbColors.kt` drift: `raised` :143→**146**; `stateSelected` :131→**132**; `drawerInk` :152→**155**; `ground` :138→**141**; `ink` :146→**149**; `Palette` :116-155→**117-158**.
- m3 `JbCanvasView.kt`: `view` :118→**139**; `strokeInProgress` :196→**234**; `onViewMoved` :130→**151**.
- m4 `Popovers.kt`: idiom :144-145→**153-154**; `onClosed` :96-107→**99-110**.
- m5 `DocModel.kt` `Paper`: spec :38 → actual **:61**.
- m6 `PEG_RADIUS_DP`: spec :49 → actual **:48**.
- m7 Device-only table omits the `shown`-setter→`visibility` row — add it (backstop steps listed correctly).
- m8 R30 note for orchestrator: JB-3.00 (3 Activity sites) and 3.02b (5 sites) both edit
  `JoyBrushActivity.kt` — different sites, but one-row-at-a-time, so dispatch order explicit.

**Bottom line:** the three prior BLOCKERs genuinely landed. What remains is line-number
staleness plus one context qualification — refresh material, not rebuild material.
