# JB-9.05 — The document's paper: look, surface, tint, show, bite, light (DOC_VERSION 4)

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | OpenCode free agent |
| **Depends on** | JB-9.04 (`PaperState` resolves ids against `PaperCatalogue`) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` (`Paper`, `DOC_VERSION` only), EDIT `doc/DocOps.kt` (paper validation only), NEW `core/paper/PaperState.kt`, tests under `core/src/commonTest/.../doc/` and `.../paper/`, including the existing pins `EnumFreezeTest`, `DocModelTest` (`expectedKeyTable`), `CanvasSnapshotTest` (see answers) |
| **Estimated size** | ~100 lines + ~180 lines of tests |

## Goal
Paper becomes ONE setting of the drawing that every brush feels (owner P4/P5) and that saves with the file.
Today `Paper` has a colour and an unused `textureId`, and the canvas refuses any file that sets it. This row gives `Paper` the
owner's controls and resolves them into the numbers the engine needs. Lifting the refusal is JB-9.06.

## Contract (verbatim)
```kotlin
// DocModel.kt
const val DOC_VERSION = 4          // v4: paper look/surface (R10, JB-9.05)

@Serializable data class Paper(
    val color: String = "#FFFFFF",          // #RRGGBB. The flat colour when lookId == null; ignored for the base when a look is set
    val textureId: String? = null,          // the SURFACE id (catalogue), null = smooth. Name kept for compatibility
    val textureScale: Float = 1f,           // the sheet's Scale: multiplies the surface AND look physical size, 0.25..4
    val includeInExport: Boolean = false,
    val lookId: String? = null,             // v4. Catalogue look id, null = flat `color`
    val tint: String? = null,               // v4. "#RRGGBB" recolours the look; null = the look's own colours
    val show: Float = 1f,                   // v4. 0..1 how visible the look + relief are (0 = flat base colour)
    val bite: Float = 1f,                   // v4. 0..1 how strongly brushes feel the surface
    val light: Boolean? = null,             // v4. relief lighting; null = the look's lightByDefault
)

// core/paper/PaperState.kt

data class ResolvedPaper(
    val surface: SurfaceEntry?,   // null = smooth: brushes feel nothing, nothing to light
    val look: LookEntry?,         // null = flat colour
    val baseArgb: Int,            // tint ?: look.base ?: color, opaque
    val scale: Float,             // textureScale clamped 0.25..4
    val show: Float, val bite: Float,
    val light: Boolean,           // light ?: look?.lightByDefault ?: true
)
object PaperState {
    /** Resolve a document's paper against the catalogue. Unknown ids resolve to null (smooth / flat) and are REPORTED via [problems]. */
    fun resolve(p: Paper, c: PaperCatalogue): ResolvedPaper
    fun problems(p: Paper, c: PaperCatalogue): List<String>
}
```

## Decisions already made
1. **`textureId` IS the surface id.** No rename, so a v3 file's field means the same thing. `textureScale` is the Scale slider.
2. **DOC_VERSION 3 → 4** (R3/R31: any new serialised field bumps). The codec always stamps the current version, and an old
   build refuses a v4 file. The Lead accepted that cost for one owner and one phone (R38).
3. **Validation (`DocOps.validate`)**: `show`, `bite` in 0..1, `textureScale` in 0.25..4, `tint`/`color` match `^#[0-9A-Fa-f]{6}$`.
   Unknown catalogue ids are NOT a validation error (a newer catalogue may have them). They resolve to smooth/flat and `problems` says so.
4. **Defaults reproduce today exactly**: a v3 file opened under v4 = white flat paper, no surface, show 1, bite 1.
5. AMOLED black: `light` defaults from the look (`lightByDefault = false`), so relief never greys the black.

## Tests
- Round trip: every field set, encode → decode equal; the written `version` is 4.
- A v3 document JSON (copy a real one from an existing test fixture) decodes with all v4 defaults; `resolve` = flat `#FFFFFF`, surface null.
- Validation: one test per rule (out-of-range show, bite, scale; a bad hex), each naming the field.
- `resolve`: tint wins over look.base, which wins over color; `light = null` → the look's `lightByDefault`; unknown `textureId` → surface null and one problem naming it.
- Every existing test that pins `DOC_VERSION == 3` is updated to 4, with a comment pointing here. List them in your report.

**Command:** `./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks` in your worktree. Paste counts.

## Do not
- Do not touch `JbCanvasView.kt` (its `load` refusal is JB-9.06's, a hot file).
- Do not rename `textureId`/`textureScale`.
- Do not add the catalogue to the document.

## Definition of done
- [x] tests pass with counts — 1421/0/0/0, 94 suites; `PaperStateTest` 15, `DocModelTest` 55, `EnumFreezeTest` 10; XML 2026-10-01 14:56:53
- [x] mutation: drop the `show` range check → its test goes red — [showAndBiteMustBeFractions], 1 of 55, nothing else
- [x] owner area only — `DocModel.kt` (Paper + the version), `DocOps.kt` (paper validation only), new `PaperState.kt`, plus the tests specialist answer 2 put in scope
- [x] commit "JB-9.05: …", rebased, pushed
- [x] ROADMAP row → 🟧 Built + command + counts

## Questions

2026-10-01 OpenCode free agent: Paused per the owner's rule for unclear specs. This row's only dependency, JB-9.04, has not landed. At `origin/joy-creator` = ccf2980b there is no `core/paper/PaperCatalogue.kt`, and `PaperCatalogue`, `SurfaceEntry` and `LookEntry` appear in no `.kt` file on any ref (checked by filename, by identifier across `git rev-list --all`, and in every registered worktree; the only `core/paper/` in any worktree is `jb-JB-9.01`'s uncommitted `SurfaceMaps.kt` from JB-9.01). Every type in this row's verbatim contract — `ResolvedPaper.surface: SurfaceEntry?`, `.look: LookEntry?`, `PaperState.resolve(p, c: PaperCatalogue)`, and the required test "`light = null` → the look's `lightByDefault`" — is one of those three missing types, so the row cannot compile, let alone be tested, without writing JB-9.04's owner-area file.

Unlike JB-9.06's spec ("If 9.04/9.05 have not landed when you start, build them first from their specs, in their own commits"), this spec has no such clause: it only lists JB-9.04 under **Depends on**, and this row's **Owner area** is `DocModel.kt` (`Paper`, `DOC_VERSION` only), `DocOps.kt` (paper validation only) and NEW `core/paper/PaperState.kt` — `PaperCatalogue.kt` is JB-9.04's, not this row's. Two ways forward, and they are the owner's call, not mine to guess:

1. Should this row build JB-9.04 first, in its own commit, as JB-9.06 is permitted to? Doing so puts another row's owner-area file in this row's diff and would collide with whichever lane is assigned JB-9.04.
2. Or should this row wait for JB-9.04 to land and rebase onto it?

Note that JB-9.04 appears itself to be blocked, so waiting may be the cheap path: per the Questions already recorded in JB-9.06, the shipped `joybrush/assets/paper/catalogue.json` carries a `detailStrength` key on `off_white` that is absent from JB-9.04's `LookEntry`, while JB-9.04's parser is strict and that file is outside JB-9.04's owner area. Until the specialist answers that, JB-9.04's required `ShippedCatalogueTest` cannot pass either.

One smaller question, which will matter the moment JB-9.04 lands and which I did not want to guess at: the spec's test list says "Every existing test that pins `DOC_VERSION == 3` is updated to 4, with a comment pointing here. List them in your report." `EnumFreezeTest.theVersionsTheNamesWereWrittenFor` (core/src/commonTest/.../doc/EnumFreezeTest.kt:94) asserts `assertEquals(3, DOC_VERSION)` and sits under this row's stated test area, so I read it as in scope and would update it. Its own comment (lines 88-91) says a bump "is a legal, deliberate act … this test does not forbid it", which agrees. Flagging it only so the owner can confirm the freeze test is meant to move rather than to fail loudly.

For the record, the pins and call sites I found that a v4 bump and the narrowed `textureScale` range (0.25..4, versus today's "over 0 and no more than 64") would touch, all outside this row's owner area and therefore all needing a ruling:
- `DocOps.kt:176-179` — the current scale check and its message.
- `DocModelTest.kt:651-663` (`rule10_paperTextureScaleHasToBeSane`) — accepts 0.0001f, 1f and 64f as legal; 0.0001f and 64f both become invalid under 0.25..4.
- `DocModelTest.kt:160` — `expectedKeyTable()` pins `$.paper` to exactly `color, textureId, textureScale, includeInExport`; the six v4 fields make it wrong, and this key table backs the R31 unknown-key refusal.
- `CanvasSnapshotTest.kt:22` — builds `Paper(..., textureScale = 3.5f, ...)`, which survives 0.25..4.
- `JbCanvasView.kt:1208` and `CanvasSnapshot.kt:81` — the `textureId != null` refusal that keeps paper files from loading; the spec assigns lifting it to JB-9.06, so it stays.

Stopping this row here with no code written, rather than inventing a stand-in catalogue that JB-9.04 would then have to unpick.

**Specialist answers (2026-10-01):**
1. **Wait for JB-9.04, then rebase.** No row builds another row's files any more; the dispatch is serial by gates now (PAPER_DISPATCH.md).
   JB-9.04 is unblocked as of this commit.
2. **The pins you listed are IN this row's owner area** (added now): `EnumFreezeTest` (DOC_VERSION 3 → 4, comment pointing here),
   `DocModelTest` (`expectedKeyTable()` gains the five v4 keys: `lookId, tint, show, bite, light`), `CanvasSnapshotTest` if it pins the version.
3. **`textureScale` validation stays as it is today** (over 0 and at most 64; `DocOps.kt:176-179` and `rule10_…` untouched). The 0.25..4 range
   is a CLAMP inside `PaperState.resolve`, not a validation rule. Less churn, and old files stay valid. (Decision 3's sentence about 0.25..4 is
   overridden by this answer.)
4. The `textureId != null` refusals in `JbCanvasView.kt:1208` and `CanvasSnapshot.kt:81` stay; JB-9.06 lifts them.

---

### What the builder did with each answer, and the pins the spec asked to be listed

2026-10-01 OpenCode free agent. Answer 1: waited, then rebased onto the commit carrying it. No other
row's files were built.

**The `DOC_VERSION == 3` pins, as the spec asked them listed.** There was exactly **one** literal in the
repo, and it is now 4:
- `core/src/commonTest/.../doc/EnumFreezeTest.kt:95` — `assertEquals(3, DOC_VERSION)` → `4`, with a
  comment naming JB-9.05, R31 and the R38 cost. This is the one the earlier question flagged, and the
  specialist confirmed it in scope.
- `core/src/commonTest/.../doc/DocModelTest.kt:160` — `expectedKeyTable()`'s `$.paper` row gained
  `lookId, tint, show, bite, light`. It was not a version literal but it is a pin on the same file's
  shape, and it backs the R31 unknown-key refusal, so a wrong answer would have been silently accepted.
- `androidkit/.../io/CanvasSnapshotTest.kt:85` — **needs no change.** Answer 2 said "if it pins the
  version"; it does not. It reads `assertEquals(DOC_VERSION, merged.doc.version)`, so it follows the
  constant, and its `Paper("#123456", textureScale = 3.5f, includeInExport = true)` is inside the v3
  scale rule that answer 3 kept. Left untouched, which is the point of reading it before editing it.

`DocModelTest.kt:651` `rule10_paperTextureScaleHasToBeSane` is untouched per answer 3 — it still accepts
0.0001, 1 and 64 and refuses 0, −1, 64.5, NaN and Infinity.

**Two judgement calls, stated rather than hidden.**
1. **`show` and `bite` are validated in `DocOps`; `lookId` and `textureId` are not** (Decision 3). That
   split is not arbitrary and is pinned by `anUnknownCatalogueIdIsNotAValidationError`: `DocOps` knows
   nothing about the catalogue, and refusing an id it cannot check would stop a drawing opening over a
   background. `PaperState.problems` resolves them instead — it is the only place holding both halves.
2. **`argb` checks the hex's LENGTH before reading its digits.** A short hex like `"#FFF"` parses as a
   small number, so reading first would turn `#FFF` into a nearly transparent black instead of the
   white fallback. `DocOps` refuses such a value first, so this only matters for a caller that skipped
   validation — and the guard costs one comparison.

**Nothing here lifts the canvas refusal.** `JbCanvasView.kt` and `CanvasSnapshot.kt` are untouched, so a
document that sets `textureId` still refuses to open, exactly as answer 4 requires. JB-9.06 lifts it.
