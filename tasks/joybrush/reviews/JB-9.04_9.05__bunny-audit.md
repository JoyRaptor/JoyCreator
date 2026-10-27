# JB-9.04 / JB-9.05 — adversarial review (bunny-audit)

Read-only audit of the landed `PaperCatalogue.kt`, `PaperState.kt`, `Paper`/`DOC_VERSION` 4, `DocOps` paper
rules, their tests and `joybrush/assets/paper/`. No gradle was run (R43/R44 item 6), so every finding
below is proved by reading code and by constructing the input, not by executing a test.

Line format: `SEVERITY · claim · file:line · the input · what goes wrong · contradicts spec`.

## Findings

1. **MAJOR** · A catalogue from a newer Joy Brush that ADDED a field is refused with a library
   `Encountered an unknown key` error, not with the words the KDoc and Decision 3 promise, because
   strict kotlinx decoding has no version gate · `PaperCatalogue.kt:99` (the claim), `:101` (the parse),
   `:91-94` (`ignoreUnknownKeys` left false on purpose) · input: `assets/paper/catalogue.json` at
   `"version": 2` with one extra key on a look, e.g. `"sheen": 0.5` — which is exactly what R30/R3
   *require* the next row to write when it adds a field to `LookEntry` and bumps
   `PAPER_CATALOGUE_VERSION` · `PaperCatalogues.parse` throws `JsonDecodingException` out of `parse`,
   `problems` is never reached, and the sentence "catalogue was made by a newer Joy Brush"
   (`PaperCatalogue.kt:117`) is never produced; the reader therefore violates its own documented
   contract (`PaperCatalogue.kt:76-79`: "That split is what lets a catalogue from a newer Joy Brush
   still be read") · **contradicts spec: yes — JB-9.04 Decision 3** ("`version > PAPER_CATALOGUE_VERSION`
   → refused with 'made by a newer Joy Brush'"). The document codec solves the identical case with an
   explicit gate — `DocJson.kt:112-120` skips the unknown-key scan when root `version` is an integer
   `> DOC_VERSION` — and has a test that names exactly this input, `DocModelTest.kt:287`
   `aNewerVersionWithAnUnknownKeyStillDecodesAndIsStillReportedAsNewer`. The catalogue reader has no
   such gate and no such test. Severity is MAJOR and not BLOCKER because the catalogue ships inside the
   same APK as the code (`androidkit/build.gradle.kts:58` copies `assets/paper` into the APK), so today
   no user path delivers a mismatched pair; the day a catalogue is loaded from storage or hot-replaced
   this becomes a person-facing crash message. Detail: `PaperState.kt` is untouched by this.

2. **MAJOR** · The shipped `slopeRange` is copied as a literal into a test instead of being read from
   `catalogue.json`, so a catalogue edit leaves every test green while the shipped PNG is unpacked with
   the wrong slope scale · `SurfaceAssetTest.kt:79-82` (`const val SLOPE_RANGE = 0.099f`, commented "The
   catalogue's value for `pulp_artisan`") against its own KDoc `SurfaceAssetTest.kt:30` ("with the
   catalogue's own `slopeRange`"), duplicating `assets/paper/catalogue.json:11` · input: edit
   `catalogue.json:11` to `"slopeRange": 0.2` and rebuild · `ShippedCatalogueTest` checks ids, sizes,
   pictures and means, never `slopeRange`; `SurfaceAssetTest` still packs with the hard-coded `0.099`
   and still passes (the PNG bytes did not change), so the build is green and the engine decodes R,G
   with `SurfaceMaps.decodeSlope(byte, 0.2f)` (`HexTile.kt:119-120`) — every slope on the only shipped
   surface is wrong by 2× with nothing red · **contradicts spec: no** (JB-9.04's contract does not own
   this file) but it is the same "a stray key that had to be deleted" drift class the row was stopped
   for, and it is a second copy of shipped data, which R23 forbids.

3. **MAJOR** · `Paper.color`'s KDoc says it is "ignored for the base when a look is set", which is
   false in every shipped consumer: nothing reads `lookId`, so a document that sets a look saves a
   white `color` and exports white · `DocModel.kt:62` (the claim) against `JbCanvasView.kt:1293`
   (`paperArgbOf(doc.paper.color)`), `CanvasPng.kt:29`, `AnimExport.kt:129` and `:198`,
   `OraExport.kt:333` · input: `Paper(lookId = "off_white")` with `color` left at its `#FFFFFF`
   default, saved and exported as PNG/GIF/ORA · the resolved chain in `PaperState.kt:55` is correct and
   unused; the export backdrop is `doc.paper.color`, so the exported file is pure white and no message
   says why · **contradicts spec: no** (JB-9.05 Decisions and answer 4 assign the wiring to JB-9.06),
   so the defect is the KDoc sentence, which asserts a behaviour the app does not have yet. To the
   Lead: JB-9.07's sheet can write a `lookId` into a document, and nothing in the repo records that
   such a document renders as `color` until 9.06 lands.

4. **MINOR** · `LookEntry.texelPx` and `LookEntry.hexTexels` carry no range rule and no documented
   range, while every `SurfaceEntry` number has one · `PaperCatalogue.kt:45-46` (bare defaults, no
   comment) against `:148-155` (six enforced ranges for surfaces) · input: a shipped look with
   `"hexTexels": 0.0` · `problems` returns empty, `ShippedCatalogueTest` only measures the PNG, and the
   value reaches `HexTile.lattice` where `qx = px / hexTexels` (`HexTile.kt:60`) and `i0.toInt()` on an
   infinity becomes `Int.MAX_VALUE`, so `i + 1` wraps · **contradicts spec: no** — the contract states
   no range for these two fields, so this is a spec gap for the Lead, not a code-vs-spec mismatch. The
   row's own stated rationale (`PaperCatalogue.kt:16-18`, "a number a file cannot hold is a number the
   engine would clamp silently") applies to them.

5. **MINOR** · Two validator rules have no test, so a mutation that deletes either stays green ·
   `PaperCatalogue.kt:171` (`l.name.length !in 1..40`, the look-name twin of the tested surface rule at
   `:138`) and `PaperCatalogue.kt:205` (`file.isBlank()`) · input: delete line 171, or delete the
   `file.isBlank()` branch · all 33 `PaperCatalogueTest` tests stay green: the fixture builder is only
   ever called with `look(name = "Duplicate")` (`PaperCatalogueTest.kt:324`, length 9) and never with
   a blank `file` · **contradicts spec: yes — JB-9.04 Tests** ("Then one test per rule: each makes
   exactly one bad field and asserts exactly one problem").

6. **MINOR** · `ShippedCatalogueTest`'s KDoc claims it catches a file "that is not a PNG"; it cannot,
   because `ImageIO.read` dispatches on content, not on the name · `ShippedCatalogueTest.kt:21-25`
   (the claim) against `:60` and `:76` (`ImageIO.read(png)`) and `PaperCatalogue.kt:207`, which checks
   only that the name ends in `.png` · input: replace `surface_pulp_artisan.png` with a JPEG that has
   the same dimensions and rename it `.png` · `javax.imageio` has a JPEG reader registered, `read`
   returns a non-null `BufferedImage`, and every assertion passes; the app then gets 512×512 JPEG
   bytes under a `.png` name · **contradicts spec: no**, test-KDoc inaccuracy.

7. **MINOR** · `Paper.textureScale`'s KDoc states a range the code does not enforce anywhere ·
   `DocModel.kt:64` ("0.25..4") against `DocOps.kt:181` (over 0, at most 64) and `PaperState.kt:59`
   (the clamp) · input: a v3 file with `"textureScale": 64` · `DocOps.validate` accepts it and
   `resolve` clamps it to 4, which is the behaviour specialist answer 3 asked for; the defect is that
   the field comment still reads as the field's legal range and was not updated when answer 3 moved
   the window out of validation, so the next reader of `DocModel.kt` will "fix" `DocOps` to match ·
   **contradicts spec: no** (answer 3 overrides Decision 3's sentence) — comment drift only.

8. **MINOR** · A non-integer `version` is refused by a library type error rather than in words ·
   `PaperCatalogue.kt:101` · input: `"version": "1"` (string) or `"version": 1.0` · the `Int` field
   fails during decode, `problems` is unreachable, and the message is kotlinx's rather than a
   sentence · **contradicts spec: no** — nothing is silently dropped, the file is refused, which is
   what R31 requires. Noted for symmetry with finding 1.

9. **MINOR** · `PaperStateTest.aPaperWithNoRecognisedIdStillResolvesToSomethingDrawable` ends on an
   assertion that cannot fail · `PaperStateTest.kt:249` · input: none — `assertNotNull(r, ...)` on a
   non-null `ResolvedPaper` is vacuous · the test is otherwise honest (the five real assertions above it
   would each go red if their guard were removed), so this is dead weight rather than a missing guard ·
   **contradicts spec: no**.

10. **MINOR** · `tint` with no `lookId` silently replaces the flat colour instead of having nothing to
    recolour · `PaperState.kt:55` (`p.tint ?: look?.base ?: p.color`) and `DocModel.kt:68` ("`tint`
    `#RRGGBB` recolours the look") · input: `Paper(color = "#112233", tint = "#445566")` with
    `lookId = null` · the resolved colour is `#445566`, and `DocOps.kt:202-205` accepts it because the
    tint is a legal hex; the owner set a flat colour and got a different one, with the field comment
    saying the tint recolours a look that is not there · **contradicts spec: no** — the precedence is
    the contract's verbatim `tint ?: look.base ?: color`, so the comment is what misleads.

11. **MINOR** · `PaperCatalogues.FORMAT` configures `prettyPrint` and `encodeDefaults` for an encoder
    that does not exist · `PaperCatalogue.kt:91-94` · input: n/a — grep finds no
    `encodeToString(PaperCatalogue…)` and no `PaperCatalogues.encode` anywhere in the repo; the contract
    gives the object no encode function · two settings that promise a write path nobody can call, and
    a future row that adds one will assume they are already right · **contradicts spec: no**.

12. **MINOR** · `aNewerVersionIsRefusedWithTheWordsAbove` asserts `any`, and does not cover the case its
    own KDoc names · `PaperCatalogueTest.kt:176-184` (body) against `:170-175` ("The file still PARSES,
    because refusing at the door would replace this sentence with 'Encountered an unknown key'") ·
    input: the version bumped with no key added, which is what the test does — a v2 file that adds a
    field is never tried · the test would also stay green if `problems` emitted the sentence alongside
    nine other problems, so it pins none of the one-problem discipline the rest of the file insists on ·
    **contradicts spec: no** — this is the test that should have caught finding 1.

## Not reproduced

- **Unknown keys on a same-version or version-less catalogue are refused, not dropped, and the scan is
  not version-gated.** `FORMAT` leaves `ignoreUnknownKeys` at `false` (`PaperCatalogue.kt:91-94`), so
  an unknown key throws at decode whatever `version` says. Absent `version` defaults to
  `PAPER_CATALOGUE_VERSION` (`PaperCatalogue.kt:62`) and the file still decodes strictly; a non-integer
  `version` throws a type error (finding 8), which is a refusal, not a silent drop. This is strictly
  stronger than JB-0.02d, whose scan had to be written by hand with an explicit absent/non-integer
  rule (`DocJson.kt:112-120`, R44 item 5), and no KDoc sentence in `PaperCatalogue.kt` misdescribes it
  the way `DocJson.kt:100-110` had to be corrected.
- **NaN does not pass a range check in either row.** Every numeric rule is written `!in lo..hi`
  (`DocOps.kt:194,197`; `PaperCatalogue.kt:148,149,152,155`), and Kotlin's `ClosedFloatingPointRange`
  is `>= && <=`, so `NaN` fails `contains` and the `!in` fires. `DocModelTest.kt:765` puts
  `Float.NaN` and both infinities through `show` and `bite`; `PaperCatalogueTest.kt:277-292` puts
  `POSITIVE_INFINITY` through `texelPx` in memory and `1e999` through the file.
  `1.0000001f`, `-0.0f` and `1.01f` were all reasoned through: the first two are inside 0..1 and
  legal, the third is refused and is in the test.
- **A shared id is spoken once.** `PaperCatalogue.kt:129-135` and `:162-168` iterate
  `surfaceIds.distinct()` / `lookIds.distinct()` and count claimants inside the loop, so the message is
  appended once per distinct id, not once per entry. `twoSurfacesSharingAnIdAreRefused` and
  `twoLooksSharingAnIdAreRefused` hold it at exactly one problem.
- **The 0.25..4 window is a real clamp in both directions and never throws.**
  `PaperState.kt:59` is `coerceIn` with NaN handled by hand, and `theScaleIsClampedToAQuarterThroughFour`
  asserts `0f → 0.25`, `0.0001f → 0.25`, `64f → 4`, `1e9f → 4`, `NaN → 1` as separate assertions, so a
  clamp that worked at one end only would be red.
- **Unknown ids resolve and are reported, and both-at-once gives two messages.** `PaperState.kt:50-51`
  uses `PaperCatalogues.look/surface` (no second implementation — R23 clean), `:78-87` produces one
  message per unknown id naming that id, and `twoUnknownIdsAreTwoProblems` pins the count at 2 with
  `anUnknownSurfaceIdResolvesToSmoothAndIsReported` / `anUnknownLookIdResolvesToFlatAndIsReported` as
  the single-id cases.
- **Nothing is dropped on write.** Every place that rebuilds a `Paper` uses `copy`, not a constructor:
  `JbCanvasView.kt:1467`, `CanvasSnapshot.kt:80`. `DocJson` runs with `encodeDefaults = true`
  (`DocJson.kt:46`), so a re-save of a v2/v3 file writes all nine paper keys and stamps version 4
  (`DocModelTest.kt:702-708`, `aVersionThreeDocumentOpensWithEveryPaperDefault` for the read side).
- **The shipped catalogue is clean.** `joybrush/assets/paper/catalogue.json` parses under the strict
  reader, has `detailStrength` deleted, and every field is inside every range: `size` 512 is a power of
  two in 64..2048, `texelPx` 2.0 in 0.25..16, `slopeRange` 0.099 in 0.001..4, `hexTexels` 180 in
  16..512, `relief` 1.0 in 0..4; `off_white`'s `base` is `#RRGGBB`, `file` and `mean` are both null,
  and its `defaultSurface` names a surface that exists. No orphans either way (the one surface is named
  by the one look). `surface_pulp_artisan.png` is a real PNG, 512×512, matching the entry's declared
  `size`. One surface and one look is thin but not wrong.
- **`EnumFreezeTest` is honest.** All four name lists are
  `assertEquals(listOf(…), Enum.entries.map { it.name })` (`EnumFreezeTest.kt:40,49,59,81`), so a
  reorder, a middle insertion or a deletion each goes red — the assertions carry order and count, not
  a set. `theVersionsTheNamesWereWrittenFor:98` pins `DOC_VERSION == 4`, matching
  `DocModel.kt:11`, and `everyPaperFieldSurvivesARoundTrip` asserts the written version is 4 from the
  constant. The row followed R30 item 3 in substance (3 → 4, no collision) even though the spec
  wrote the number out.
- **`DocModelTest.expectedKeyTable` was updated correctly and backs R31.**
  `DocModelTest.kt:160-164` gained `lookId, tint, show, bite, light`, and the comparison at `:276-279`
  is `assertEquals` on the whole map, so a key in the table that the writer stopped emitting is red as
  well as a key the writer invented.
- **The spec's own "list them in your report" item is complete.** Grepping the whole repo for a
  literal `3` where a document version would be: the only version literal was `EnumFreezeTest:98`
  (now 4); `CanvasSnapshotTest` reads `DOC_VERSION` and follows it; the v3 raw fixture at
  `DocModelTest.kt:723` is deliberately a v3 file.

## What I could not check

- **Nothing was executed.** No gradle, per the reviewer's rules, so every claim above is from reading
  code and constructing the input. The rows' counts (1399/0/0/0 and 1421/0/0/0) and their mutation
  results were not reproduced.
- **The `"texelPx": 1e999` refusal is taken on the report's word.**
  `PaperCatalogueTest.kt:289-291` asserts `SerializationException` for that token, which depends on
  the pinned kotlinx-serialization's JSON lexer rejecting an overflowing literal rather than decoding
  it to `Infinity`. If it decodes instead, the test is red and the in-memory half at `:277-287` is the
  only guard that exists. I could not run it.
- **Whether the reported problem reaches a person.** `PaperState.resolve` / `PaperState.problems` /
  `PaperCatalogues.problems` have no production caller anywhere in `joybrush/`, `joybrush/androidkit`
  or `joybrush-android` — grep finds them only in tests. So finding 3's white export and the
  "this drawing asks for a paper this build does not have" message are both currently unreachable, and
  which row wires them (JB-9.06 reads the catalogue for real, per JB-9.04's Question 3) is a
  scheduling fact I cannot verify from here.
- **How a mismatched catalogue could reach a device at all.** `assets/paper` is copied into the APK
  (`androidkit/build.gradle.kts:58`) and the only reader is `GrainTextures.getResourceAsStream`, so
  there is no hot-reload or sideload path today. That is why finding 1 is MAJOR rather than BLOCKER; if
  JB-9.10's library ends up loading a catalogue from storage, re-rank it.
- **The `detailStrength` deletion itself.** The key is gone from `catalogue.json` and appears in no
  `.kt`, `.glsl` or `.json` in the repo, but I did not diff the specialist's commit against the file it
  edited, so I cannot say whether that commit touched anything else in the asset.