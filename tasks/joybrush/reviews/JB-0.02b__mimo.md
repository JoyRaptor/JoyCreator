# Adversarial review — JB-0.02b "new enum constant ⇒ version bump" (docs + guard test)

- Reviewer: mimo (second adversarial pass; muse-spark did not review this task — this is its first review).
- Task status: 🟧 Built. Commit reviewed: `321e23f1` (tree at `b74aaf0e`).
- Spec: `tasks/joybrush/specs/JB-0.02b_enum_version_rule.md` (rules from `LEAD_RULINGS.md` R3).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (EnumFreezeTest 10).
- §5b checks: owner area = KDoc-only edits in `DocModel.kt`/`BrushPreset.kt` + NEW `EnumFreezeTest.kt`. Commit stat matches; comments-only verified by the orchestrator's own non-comment diff filter (orchestrator log) and re-checked by me for `DocModel.kt` enum region.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER, no MAJOR. This is a documentation + tripwire task done exactly to its spec;
its own Questions 1–2 state the two real limitations. I confirm both and assess them below. No
behaviour change was allowed, so none of this is a code defect in JB-0.02b — the gaps are for the
Lead to schedule.**

## Findings

### F1 (MINOR, disclosed by the builder and the orchestrator — confirmed): the rule itself is untested; the freeze test is a tripwire, not enforcement

- **Proof (test):** `EnumFreezeTest.kt` contains 10 tests: four name-list freezes
  (`boardKindNamesAreFrozenInOrder` `:38`, `layerKind…` `:47`, `blendMode…` `:56`, `brushInput…` `:65`),
  `theVersionsTheNamesWereWrittenFor` `:79`, `theNamesAreWhatActuallyGoIntoTheFile` `:90`, four
  refuse-rather-than-guess tests (`anUnknownBoardKind…` `:170`, `anUnknownLayerKind…` `:179`,
  `anUnknownBlendMode…` `:188`, `anUnknownBrushInputIsRefusedRatherThanSwappedForAnother` `:214`).
  Nothing anywhere asserts (a) the KDoc sentence is still on the four enums, or (b) that a changed
  name list forces a version bump.
- **Proof (spec Q1/Q2):** `JB-0.02b:27-37` — "The rule itself is still untested, and cannot be from
  commonTest" (needs a jvmTest file-grep, outside owner area) and "A tripwire is not enforcement:
  someone can add `BoardKind.HOLOGRAM` and satisfy my test by editing one list literal, without ever
  touching `DOC_VERSION` … Real enforcement needs the v1 list kept as data".
- **Proof (orchestrator):** ORCHESTRATOR_LOG open-questions section states the same bluntly
  ("the suite green with no version bump … What the test buys is conspicuousness, not enforcement;
  the documentation half is entirely unguarded").
- **Assessment:** correct on all three counts, and correctly *not* worked around inside a
  comments-only owner area. Suggested ruling, in priority order: (1) adopt Q2's `V1_BOARD_KINDS`
  (+ the other three) with "lists differ ⇒ `DOC_VERSION` > 1" — test-only, cheap, closes the real
  hole; (2) Q1's jvmTest KDoc grep — nice-to-have; (3) Q3's `version: Int = BRUSH_VERSION` one-liner
  in `BrushPreset` when someone owns that file.

### F2 (MINOR, cross-reference — the promise this rule leans on is currently caller-conditional): R3 says "readers already refuse a newer version with a clear message", but that is `validate`, not `decode`

- **Proof:** spec Q4 raises it (`JB-0.02b:40-43`): `DocJson.decode`/`BrushJson.decode` decode newer
  files happily on purpose; the friendly message comes from `DocOps.validate`/`BrushValidate.validate`,
  which — as of this commit — have **no production caller for documents and only tests for brushes**
  (verified in my `JB-0.02__mimo.md` F1 and JB-0.03b Q1: the in-flight `JbArchive.kt` is untracked).
  The builder wrote the KDoc to say exactly that, which is the honest wording.
- **Assessment:** not a defect in this task — but it means R3's user-facing promise ("an old app
  shown a new file says 'can't open, newer version'") **does not hold until something calls
  `validate` on open** (JB-0.08a's archive does, in flight). One sentence to schedule with
  JB-0.08a: "read-refuse path must run `DocOps.validate` before accepting bytes" — already true of
  the in-flight `JbArchive.read` (`:353`), so the risk is other entry points (clipboard, importers).

## Verified sound

- The four KDoc sentences exist on `BoardKind`, `LayerKind`, `BlendMode` (`DocModel.kt`) and
  `BrushInput` (`BrushPreset.kt`) — spec step 1 verbatim (`JB-0.02b:16-17`), and the do-not list
  (no behaviour/name/value change) holds: commit `321e23f1` contains no code changes beyond comments
  and the new test file.
- Refuse-don't-coerce is genuinely pinned end-to-end for all four enums (the `…RatherThan…` tests
  above), which is what makes muse-spark's JB-0.02 F1 a *version rule* rather than an open bug —
  the cross-review loop worked as §5b intends.
- `theNamesAreWhatActuallyGoIntoTheFile` (`:90`) writes real documents/brushes and checks the
  serialised tokens — i.e. the enum *wire representation* is pinned, not just the Kotlin lists.
- Spec DoD met: tests green, commit message matches, board row Built.

## Bottom line

No send-back. Book Q1/Q2/Q3 as one small test-only follow-up (`enum-freeze enforcement`) and attach
the Q4 sentence to JB-0.08a's dispatch. Task is 🟨-ready once triage accepts F1/F2 as recorded.
