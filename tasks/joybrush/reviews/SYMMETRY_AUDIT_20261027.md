# Symmetry audit (read-only), 2026-10-27

Scope: ROADMAP row SYMMETRY-COMPLETE. Repo `C:\Temp\jb-select-reconcile-20261024`, HEAD `b29573d9e` (branch `lead/select-reconcile-20261024`). No build, no gradle, no source edits, no phone. Every claim is tagged **[code]** (checked in git or source at a stated SHA/path) or **[doc]** (read in a task file, not re-run).

## Verdict

- The symmetry feature is **not in HEAD**. HEAD has no symmetry guide, lane, copy-math, widget or copy-cap code. The 12 `symmetry` grep hits in HEAD's `joybrush*` Kotlin are all the English word in comments ("asymmetry", "symmetry of the drawing") **[code]**.
- The feature lives only on **unmerged** branches, mainly `lead/symmetry-merge` / `lead/symmetry-1019` (a1a16627f) and the local tip `lead/symmetry-e` (789bef0af). Origin has only `origin/lead/symmetry-check` (0e13f2f5f), which does not contain 789bef0af **[code]**.
- No phone acceptance for symmetry is recorded anywhere in the repo. The 10-20 Lead check claims mirror/radial/kaleidoscope/undo on the Note 9, but its evidence is three screenshots only **[doc]**.
- Base capabilities that the row lists (tilt, eraser, Select, save/reopen, handles, media brushes) exist in HEAD as ordinary engine features with unit tests. What is missing is their **symmetry-copy** versions.

## 1. Ancestry

**Branches matching `*symmetry*`** (local unless noted). All are NOT ancestors of HEAD except as noted.

| Branch | Tip | Ancestor of HEAD? |
|---|---|---|
| lead/symmetry-1019, -d2, -e, -f2, -f3, -f4 | 789bef0af (Symmetry E) | NO |
| lead/symmetry-merge, -d | a1a16627f / 651755a6b (Symmetry D1) | NO |
| lead/symmetry-b | a19d73d2a (Symmetry B fix) | NO |
| lead/symmetry-a1 | 112235fee | NO |
| lead/symmetry-a2 | c15a0cb37 (Symmetry A3) | NO |
| lead/symmetry-c | b93f82786 (owner UI) | NO |
| lead/symmetry-check, origin/lead/symmetry-check | 0e13f2f5f (merge) | NO |

Branch divergence from HEAD: lead/symmetry-1019 is 217 commits ahead and 10 behind HEAD; merge-base `b41857e49`. lead/symmetry-check merge-base `4e75cea5b` (204 ahead, 10 behind).

**Commits matching `ymmetry` in any ref:** 35 total. 15 are ancestors of HEAD. Of those, most are false positives ("asymmetry" in unrelated text) except:
- `1e33f1188` Symmetry spec (E4 media review), ancestor **[code]**
- `d9069478d` Symmetry Lead check 10-20 (docs and three PNGs only), ancestor **[code]**
- `f0ce300b7` Symmetry spec and 10-19 handoff, ancestor **[code]**

The 20 non-ancestor symmetry commits carry the code: e7674b3bf (shared shape), f460000cb / cd08fd4e6 (A2 angle frame), c15a0cb37 / 67767bd7a (A3 tracker), 4e67b7564 / cff1f78a0 (B fan-out), 2c05bf408 / a19d73d2a (B cap fix), 651755a6b / a1a16627f (D1 one group, one undo), 789bef0af (E realistic copies), b93f82786 / d43fff47a (owner UI), 112235fee / 9c82bd5f8 (review fixes) **[code]**.

**Doc note:** the 10-23 Engine Lead handoff says "Don't push branches named symmetry*" (`tasks/joybrush/HANDOFF_20261023_ENGINE_LEAD.md:125`). That explains why most symmetry branches never reached origin **[doc]**.

## 2. Symmetry code and tests

- **HEAD source:** none. Checked: `joybrush/core/.../guide/SymmetryMath.kt`, `.../core/symmetry/SymmetryLane.kt`, `joybrush-android/.../chrome/SymmetryWidgetView.kt`, all ABSENT **[code]**. `git grep "Guide.Symmetry|class Symmetry"` on HEAD: no hits **[code]**.
- **HEAD tests:** none for symmetry. The six `*Test.kt` files that match the word are about fill, flood, mask, cell roll, stroke edit and MyPaint import **[code]**.
- **Branch source (lead/symmetry-check tree, same content as the D/E tips for these files):**
  - `joybrush/core/src/commonMain/.../core/guide/SymmetryMath.kt` (copies, mirror/rotation, lean/twist per copy; `copies` at :114, `apply` at :171)
  - `joybrush/core/src/commonMain/.../core/symmetry/SymmetryLane.kt`, `SymmetryLanes.kt` (`copyCapFor` :186, `familyFor` :204 incl. erase, `capSentence` :244)
  - `joybrush/androidkit/.../androidkit/SymmetryCapNote.kt`
  - `joybrush-android/.../chrome/SymmetryWidgetView.kt`
  - Tests on that branch: `SymmetryMathTest.kt` (`aMirrorAcrossAVerticalAHorizontalAndAFortyFiveDegreeLine` :84, `theMirrorIsAnInvolution` :111, `radialCopiesLandOnTheExpectedPointsAndAnglesAboutTheMiddle` :125), `SymmetryLaneTest.kt` (`nLanesMakeNTimesTheDabsAndOneUndoStep` :179, `aMirrorLanesDabsAreTheMirrorOfTheOriginals` :225), `SymmetryCapNoteTest.kt` (`theNoteIsSaidOnceForTheSameFamilyAndCap` :35), `SymmetryLineRecordsTest.kt` (`aStrokeOfNLanesMakesNLineRecordsInOneGroupAndOneUndoStep` :151, `selectingOneCopySelectsTheWholeSet` :186, `aRecolourOfTheGroupChangesEveryCopyAndOneUndoPutsThemAllBack` :211)
  - **These tests were not run in this audit.** Their pass state is unknown.

## 3. Capability status

Key: PRESENT-TESTED (HEAD code + test), PRESENT-UNTESTED, BRANCH-ONLY (named), ABSENT.

| Capability | Base status in HEAD | Symmetry-copy status | Evidence |
|---|---|---|---|
| **Tilt / scatter** | PRESENT-TESTED (base engine) | BRANCH-ONLY (lead/symmetry-1019 / -check) | Base: `ScatterTest.kt:48` `amountZeroAndCountOneLeavesTheStrokeExactlyWhereItWas`, `ContactDynamicsTest.kt:26` `fortyFiveDegreesMakesLongSideWithoutLosingUprightPoint`, `:76` `leanReversalMirrorsFootprintAroundPen` (joybrush/core/src/commonTest/.../brush/) **[code]**. Per-copy lean/twist: `SymmetryMath.kt` `lean` :277, `twist` :300 on branch only **[code]**. |
| **Eraser** | PRESENT-TESTED for ordinary layers; media eraser NOT built | BRANCH-ONLY, untested on phone | `FillPenDispatchTest.kt:196` `penEraserOverMediaDispatchesExactlyAsOnALayerWithout`, with the assertion message "no media eraser route in slice 1: media changes nothing here" at :204 **[code]**. `StrokeCaptureDispatchTest.kt:224` `watercolorCaptureUsesItsActualPreviewSeedAndRejectsItsEraserFallback` **[code]**. Symmetry erase family: `SymmetryLanes.kt:204` on branch **[code]**. Handoff 10-20 lists "the eraser" as still to test on phone **[doc]**. |
| **Select / group edits** | PRESENT-TESTED (single-line edits) | Group behaviour BRANCH-ONLY; not in HEAD | Base: `SelectRouterTest.kt:51` `aTapPicksAndADragDrawsALoop`, `:162` `theBoxGate`; `StrokeEditTest.kt:186` `aDragMovesTheGrabAndFallsOffAlongTheLine`; `LineTransformTest.kt:38` `aMoveMovesThePointsAndNothingElse` **[code]**. Group (one pick selects all copies, one undo): `SymmetryLineRecordsTest.kt:186` and `:211` on branch only **[code]**. |
| **Save / reopen** | PRESENT-TESTED (codec); editable-line symmetry copies UNPROVEN | ABSENT for copies | `StrokeCodecTest.kt:89` `roundTripWithEveryChannel`, `:203` `everyToolRoundTrips` **[code]**. 10-20 handoff: "an editable-line stroke saved and reopened" still to test on phone **[doc]**. |
| **On-canvas handles** | PRESENT-TESTED for Select box and sprite/board resize | Symmetry line handles BRANCH-ONLY | Select box: `SelectController.kt:124` `box.setHost(boxHost, HandleModel())`; `joybrush-android/src/test/.../select/SelectBodyTapRoutingTest.kt`, `SelectBoxCancelTest.kt` exist **[code]**. Resize handles: `BoardRuntimeControllerTest.kt:835` `resizeHandlesKeepOppositeEdgeAndNeverInvert` (boards, not Select) **[code]**. Symmetry widget and line handles: `SymmetryWidgetView.kt` on lead/symmetry-c / -check only **[code]**. |
| **Realistic media brushes** | PRESENT-TESTED (base) | ABSENT: spec E4 not built; the copy commit 789bef0af is on local branches only | Base: `MediaBrushTest.kt:36` `everyShippedBrushValidatesAndRoundTrips`, `:124` `mediaBrushesLandOnPencilsWatercolourAndOils` **[code]**. Branch E commit "the realistic brushes get their copies" (789bef0af) not on origin **[code]**. Spec Phase 3 needs an owner yes on the memory trade **[doc]**. |
| **30-copy speed (count30)** | ABSENT | ABSENT | No 30-copy bench in HEAD. `BenchCases.kt` has no copy-count case **[code]**. Spec E10 requires Note 9 timing at N = 2, 8, 30 on four boards; no such evidence file exists in `tasks/` **[doc]**. Proposed per-family caps ink 30, pencil ~16, watercolour ~8, oil 4-8 are an open question Q4, unmeasured **[doc]**. |

## 4. Docs and phone acceptance

Docs mentioning symmetry (grep `tasks/`, about 60 files; key ones):
- `tasks/joybrush/specs/SYMMETRY_20261019.md` (spec, 110 lines, in HEAD) **[code]**. Phases 1-4 at its §4; questions Q1-Q6 at its §5.
- `tasks/joybrush/reviews/JOYBRUSH_CHECKLIST_20261025.md:99` ticks "Recovered symmetry feature refs and bounded old ink mirror/radial/kaleidoscope proof" and leaves `:100-101` open: "Symmetry latest source integration and current-source acceptance: tilt/scatter, eraser, Select/group edits, save/reopen, handles, realistic media and count30 speed." **[doc]**. This matches the roadmap row.
- `tasks/joybrush/LEAD_PLAN_20261023.md:39` lists "Symmetry tool: lead/symmetry-merge (+ jb-symmetry-* worktrees), ~10 commits never landed" under owed orphans **[doc]**.
- `tasks/joybrush/HANDOFF_20261020_LEAD.md` (lines 55-63) says the 10-19 branch was "Not yet verified by the Lead" and lists phone tests still to do **[doc]**.
- `tasks/joybrush/reviews/ROADMAP_FRESHNESS_20261026.md:42,56` and `HANDOFF_20261026_COORDINATOR.md:70,170` keep symmetry as OPEN AUDIT **[doc]**.
- `tasks/joybrush/ROADMAP_ARCHIVE_20261026.md:145` lists "Symmetry tool (lead/symmetry-merge, ~10 commits never landed)" as orphan: recover **[doc]**.
- Evidence: `tasks/joybrush/evidence/symmetry-check-20261020/` with `mirror_x2.png`, `radial_x8.png`, `kaleidoscope_x8.png` (in HEAD) **[code]**. No README and no timing or pass/fail log.

**Phone acceptance:** no recorded pass for symmetry on the phone. The 10-20 check commit message claims "Note 9 mirror / radial / kaleidoscope / one undo verified" **[doc]**. Those three PNGs are the only evidence, and I did not verify them.

## 5. Discrepancies to resolve

1. The checklist's "[x] Recovered symmetry feature refs" is true only in the sense that refs exist. The source is not in HEAD and not on origin except `origin/lead/symmetry-check`.
2. The 10-20 handoff names `lead/symmetry-1019` / `a1a16627f` as the code to integrate. The latest code tip is 789bef0af, which is not on origin.
3. Doc claims of "verified" on the 10-20 check are not reproducible from anything in the repo beyond three PNGs.

## 6. Suggested next step (not done)

Build a candidate integration on a fresh branch from HEAD by bringing in the 20 non-ancestor commits (or the lead/symmetry-e tip), then run the branch suites, then phone-test the list in the 10-20 handoff. This audit does not do that.
