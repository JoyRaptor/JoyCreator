# JB-3.08 — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from spec writer/reviser `openrouter/stealth/space-bunny-alpha`. Independent read.
- Spec reviewed: `tasks/joybrush/specs/JB-3.08_three_finger_swipe_ui.md` (local HEAD `7bc60742`).
- Prior same-family second read (`JB-3.08__space-bunny-alpha-2nd-reader.md`) consulted, verdict reached independently.
- Method: read-only. No gradle, no edits. ~45 citations verified against the tree; numbers re-derived.
- Board action (bunny's): stays 📝 Draft spec until the two BLOCKERs are fixed.

**Verdict: SEND-BACK — 2 BLOCKERs, 2 MAJORs, 4 MINORs.**
Prior BLOCKER (T3a `copy` vs `data class`) and M1–M7 fixes verified as actually fixed in the spec.

## Verified and holding (send-back is narrow)

- T3a now three clauses/three scopes (spec:984-1029): clause 2 scans `copy` over
  `SwipeFrames`+`SwipeFramesKt` only, payloads exempt; clause 3 fields-only, `Brush` removed
  with reason. Satisfiable.
- M1 fixed (T3a in `jvmTest/SwipeFramesTouchesNoDocumentTest.kt`, spec:971-973; owner area nine
  paths, spec:8). M2 fixed (T4a re-reads `latched`+`displayed`+`swipe.badge` after move,
  spec:839-850). M4 fixed (Decision 10 single-pass + T6h, spec:669-679,923-944).
  M3/M5/M6/M7 fixed per revision log (spec:23-33).
- Re-derived numbers all hold except T4d (MAJOR M-A below): T6a `Rect(1044,52,28,28)` /
  `touch(1036,44,44,44)`; T6b @2.75 exact (2.75 = 11/4); T6c gap 8; T6h bottom 2240;
  T5d density-2 runs; T7c `round(36/36)=1`, `round(16/36)=0`; T5a `10*2^((173-13)/160)=20`;
  T2b index→id maps; Decision 12 `36*3=108px`.
- Citations real: `ThreeFingerSwipe.kt`, `SizeOpacityDrag.kt:40,58,67,81,98,126,156,168`,
  `PaperGeometry.kt:44,45,48,54`, `FilmStrip.kt`, `ViewTransform.kt:32-33`,
  `CanvasGestures.kt:168,324,327`, `JB-3.03_film_strip.md:439` (= Decision 16),
  `JOYBRUSH_VISUAL_LANGUAGE.md:155,:189`, `JbArchive` in androidkit, `INDEX.md` absent.
  Blueprint §6 q1, R25, R33, R42: no contradiction (BRUSH-blank cost now in Decisions 8/13 + Q4(a)/Q5).
- Owner area respected (3 new main + 6 new test files). Stop rule present (spec:742).

## BLOCKERs

**B1 — Contract does not compile: `val latched ... private set`.**
Spec:342-343 declares `val latched: SwipeMode? = null / private set`. A Kotlin `val` cannot
have a setter, but `begin`/`end` must write/clear it (spec:381-388; Decision 6, spec:613-618).
Corrected reading: `var latched: SwipeMode? = null / private set`.

**B2 — T8b census clause contradicts the Contract.**
Contract requires `constructor(density):this(ThreeFingerSwipe(density))` (spec:324) AND a
test-only `constructor():this(ThreeFingerSwipe())` (spec:327); but T8b/T5d assert
"`ThreeFingerSwipe(` only inside the `SwipeGesture()` test-only constructor"
(spec:894-896,1076-1078). Satisfying the Contract fails T8b; satisfying T8b breaks
Decision 12/T5d. Corrected reading: allow `ThreeFingerSwipe(density)` in
`constructor(density)`; forbid only parameterless `ThreeFingerSwipe()` outside the test-only
constructor (assert on the exact `ThreeFingerSwipe()`, not the `ThreeFingerSwipe(` prefix).

## MAJORs

**M-A — T4d off-by-one.** Spec:865-867: first `ShowFrame(5)` makes readout `5 / 10`.
`readout` is 1-based (T7a, spec:951-954), `ShowFrame` index is 0-based, so index 5 is the 6th
frame. Corrected reading: `readout(FRAMES, 6, 10) == "6 / 10"`.

**M-B — Step 6 points the builder at the wrong helper.** Spec:1018 cites
`FilmStripNoSecondCopyTest.kt:239-262` for `routeTo`/`edgesOf`. Actual: `:239-242` is
`primitiveConstantsInThisFile`, `:248-264` is `classesInThisFile`; the type walk is `routeTo`
at `:307`, `edgesOf` at `:347`, `typesReachableFrom` at `:380`. Corrected reading: copy
`routeTo` (`:307-344`) + `edgesOf` (`:347-365`) with the `Type`-keyed `expanded` guard.

## MINORs (one pass with the above)

- m1 `DocModel.kt:67,:77,:76` stale — actual `SpriteGrid` :95, grid comment :105,
  frames/grid comments :103-105 (spec:559-562). Order claim still true.
- m2 `Eyedropper.kt:42` stale — `DRAG_OFF_DP=8f` is at `:49`; `:42` is `PEN_BUTTON_TAP_MS`
  (spec:661-662).
- m3 `DefaultPresetsTest.kt:460` stale — `joybrushRoot()` at `:538` (spec:1051);
  `FilmStripNoSecondCopyTest.kt:123` is `:122`; `:247-265` is `:248-264` (spec:977).
- m4 T6d clamp derivation thin (spec:917-922): `(-26,8,28,28)` → `(0,8,10,2)` assumes
  `w=min(w,safe.w-x)`, `h=min(h,safe.h-y)` — state that rule; Contract's "clamped inside
  [safe]" (spec:499-503) otherwise leaves the clamp to the builder.
