# Adversarial review — JB-0.02 document model

- Reviewer: mimo (second pass; muse-spark filed `JB-0.02__muse-spark.md` — this file adds independent findings and records which muse findings still stand).
- Task status: 🟧 Built. Commit reviewed: `b74aaf0e` (includes `321e23f1` JB-0.02b and `061fd2b5` Lead rulings — several pass-1 findings have since moved).
- Spec: `tasks/joybrush/specs/JB-0.02_document_model.md` (contract, 11 validation rules, Lead rulings R1–R5).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (DocModelTest 39, EnumFreezeTest 10).
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

## Status of muse-spark's findings at this commit (re-verified by me)

- **F1 unknown enum values break decode (Medium)** — still open, but now **by design**: Lead ruling R3
  (`LEAD_RULINGS.md:22-31`) makes "new enum constant ⇒ version bump" a version rule; readers refuse
  newer versions with words, and `DocModel.kt` carries the KDoc + `EnumFreezeTest` pins it (JB-0.02b).
  I agree with the ruling: refusing beats silently coercing on re-save. The spec's forward-compat
  sentence (`JB-0.02:107` "unknown keys are IGNORED (forward compat)") should be amended to say the
  promise is keys-only; enum constants are a version matter. → **closed as designed; spec wording tweak for the Lead.**
- **F2 `version < 1` accepted (Low)** — still open in code: `DocOps.kt:59-61` checks only
  `doc.version > DOC_VERSION` (verified). Orchestrator's provisional ruling "reject `version < 1`
  with words" (orchestrator log, Open questions §Ruled-provisional) is **not yet implemented**, and
  `BrushValidate.kt:52-53` already does it for brushes — the asymmetry is now visible. → **MINOR, ruled-but-unimplemented.**
- **F3 empty-string ids pass validation (Low)** — still open: rule 2 is uniqueness only
  (`DocOps.kt:63-67`, verified — `duplicateIds` on boards/layers/cels; no `isBlank` anywhere in the
  rule). Provisional ruling "reject blank ids" also not yet implemented; brush side has it
  (`BrushValidate.kt:59`). → **MINOR, ruled-but-unimplemented.**
- **F4 `Cel.tiles` list order not canonical (Info)** — still open by design (orchestrator: "do not
  'fix' this by sorting"). `DocJson.canonical` sorts `frameCel` only (`DocJson.kt:33-49` region).
  → **agreed, no action.**

## F1 (MAJOR — silent data loss on the save path; forward-compat promise has no save-side counterpart): re-saving a document drops every key the parser did not know, and `DocJson.encode` will even write a *newer* version number over content it has already discarded

- **Proof (code):** `DocJson.kt:15-17` (kdoc): "Reading IGNORES keys it does not know, so a
  document written by a later Joy Brush still opens here" — a promise about *opening* only.
  `ignoreUnknownKeys = true` (`:28`) discards unknown keys at decode; `encode` (`:31`) is
  `json.encodeToString(..., canonical(doc))` with **no version guard and no unknown-key preservation**
  — `canonical` (`:33-49`) sorts `frameCel` and nothing else. `validate` does report a newer version
  (`DocOps.kt:59-61`) but at this commit **no production code calls `DocOps.validate`** — the only
  callers in the tree are tests plus the *untracked, in-flight* `JbArchive.kt` (`:164,:353`, not part
  of any Built task yet).
- **Loss path A (works today through the documented API pair):** a future optional key added at the
  *same* version — the spec's own rules only force a version bump for **enum constants**
  (JB-0.02b / R3), not for new optional keys, and `ignoreUnknownKeys` exists precisely to let that
  happen (spec test 3 pins decoding `"future": 1`). Open (`decode` drops the key) → `validate` →
  **clean** → `encode` → the key is gone, permanently, with every check green.
- **Loss path B:** a `version: 2` document decoded and re-encoded without `validate` (any importer,
  clipboard, tool): unknown content dropped **and** `version: 2` written back (`DocJson.kt:31`,
  `encodeDefaults = true` `:27`), so the file claims a version whose data it no longer carries.
  Muse's enum finding made exactly this argument from the other side — `DocModel.kt`'s own KDoc warns
  that falling back "would look like it worked and then write [it] out again on the next save, which
  is how a drawing loses its boards for good".
- **What the spec says:** decode's contract is "throws DocException; unknown keys are IGNORED
  (forward compat)" (`JB-0.02:107`) and decode "never validates on purpose" with "callers should use
  both" (`JB-0.02:211-213`) — so validate is meant to guard this. It doesn't guard *keys*, only
  versions, and nothing guards encode at all. `BrushJson.encode` has the precedent guard (non-finite
  → `BrushException`, `BrushTest` pins it); `DocJson.encode` has none.
- **For the Lead (spec defect, not a silent fix):** either (a) `encode` refuses `version > DOC_VERSION`,
  or (b) decode/encode round-trips unknown keys, or (c) the spec states plainly that unknown keys are
  discard-on-save and that a version bump is required for *any* new field — in which case R3's rule
  must be widened from enums to keys. Filed per §5b as "the spec is wrong/incomplete".

## F2 (MINOR — validate says valid, encode throws a library exception of the wrong type): `Board.fps` is ranged only on ANIMATION boards, so a non-ANIMATION board with NaN fps passes validation and then crashes `DocJson.encode` with `JsonEncodingException`, not `DocException`

- **Proof (code):** rule 4 is inside `for (b in doc.boards) if (b.kind == BoardKind.ANIMATION)`
  (`DocOps.kt:78`), and `fps` appears nowhere else in `DocOps.kt` (grep: only `:87`, inside that
  branch) — so a `CANVAS`/`SPRITE`/… board's fps is never checked. `DocJson.encode` (`:31`) has no
  try/catch, unlike `BrushJson.encode` (which wraps to `BrushException`).
- **Behaviour:** kotlinx-serialization refuses non-finite floats unless
  `allowSpecialFloatingPointValues = true` (not set, `DocJson.kt:24-29`; default false in
  kotlinx-json 1.8.1) → encoding `Board(..., kind = CANVAS, fps = Float.NaN)` throws
  `JsonEncodingException` (a `SerializationException`/`IllegalArgumentException`, **not** `DocException`),
  so `catch (e: DocException)` around a save misses it, while `DocOps.validate` returns `[]`
  (rules 10/11 don't read fps).
- **Reachability, stated honestly:** a JSON file cannot carry NaN (the token is invalid; decode
  rejects it), so this needs in-memory construction — an importer or animation tool. Contrast the
  brush side, where rule 16 explicitly makes "a number no range speaks for must still be a number"
  (`BrushValidate.kt:135-147`) and the spec's R3 ruling (JB-0.03) says every number must be finite.
  The doc validator has no such catch-all rule. Spec gap for the Lead: either range fps for all
  board kinds or add a finiteness rule for document floats.

## F3 (MINOR, status): two ruled-but-unimplemented provisional rulings remain open — see F2/F3 rows in the muse table above (`version < 1`, blank ids). Both already have brush-side counterparts; one-line fixes each, orchestrator has them logged as provisional pending Lead confirmation.

## Verified sound (checked independently this pass)

- **All 11 rules walked against code:** rule 1 version/format (`DocOps.kt:58-61` — `>`-only, see
  above); rule 2 uniqueness incl. frames within a board (`:64,:67,:80`, `duplicateIds` `:191`);
  rule 3 rect > 0; rule 4 frames/hold/fps (`:79-87`, `holdFrames < 1` at `:82`); rule 5 sprite grid;
  rule 6 static layer shape; rule 7 animated layer both directions (board→frameCel **and**
  frameCel→board per ruling R3's converse, `DocOps.kt:121-134` region); rule 8 PAINT/INK exclusivity;
  rule 9 active ids; rule 10 opacity/paper/textureScale incl. NaN ("`!in`" idiom, ruling R4);
  rule 11 ≥1 board and layer (ruling R2). Pinned one test per rule (`DocModelTest` 39/39 green).
- **Rulings R1–R5 implemented:** `TILE_SIZE` aliases the engine constant; rule 11; rule 7 converse;
  textureScale 0 < v ≤ 64 with explicit NaN reject; `newDocument` refuses `w/h ≤ 0`. Each has a named test.
- **Determinism:** `encodingIsStableWhateverOrderTheFrameMapWasBuiltIn` — `canonical()` sorts
  `frameCel` when >1 entry (`DocJson.kt:33-49`); kotlinx writes properties in declaration order →
  byte-identical re-encodes (test 8). One known asymmetry: `Cel.tiles` order (muse F4) and, on the
  brush side, `BrushPreset.extensions` is *not* canonicalised — cross-task note only.
- **Round-trip:** every board kind + animated INK layer with shared cels round-trips (`DocModelTest`), unknown *keys* ignored at both levels (spec test 3), `version: 2` decodes but validate reports it (spec test 4).
- **`celFor` / tile helpers:** static → only cel, animated → frameCel[frameId], missing → null;
  `tileOf(-1,-1) = (-1,-1)`, `key(3,-2) = "3_-2"` (spec tests 6–7), `TILE_SIZE` drift test (ruling R1).
- **Enum freeze:** `EnumFreezeTest` (10) pins all four name lists, `DOC_VERSION == 1`, and
  refuse-don't-coerce — this is what makes muse's F1 a version rule rather than a code bug.

## Bottom line

No BLOCKER. **F1 is the one to rule before JB-0.08b wires autosave** — once save/open is live, the
unknown-key drop is user-visible data loss. F2 is a guard gap with a precedent fix on the brush side.
F3 items are ruled, only awaiting application. Task may move 🟨 Reviewed when the orchestrator
accepts/rejects F1–F3 in triage.
