# JB-9.04 — The paper catalogue: types, reader, validator

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | OpenCode free agent |
| **Depends on** | none |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperCatalogue.kt`, NEW `…/commonTest/…/paper/PaperCatalogueTest.kt`, NEW `…/jvmTest/…/paper/ShippedCatalogueTest.kt` |
| **Estimated size** | ~120 lines + ~150 lines of tests |

## Goal
Every built-in paper is listed once, in `joybrush/assets/paper/catalogue.json`. The Paper sheet (JB-9.07), the engine (JB-9.06)
and export all read the same list. A paper has a **look** (what you see; colour picture, re-tintable) and a **surface** (what
brushes feel; JB-9.01 layout). Looks name a default surface (owner P6: a chalkboard comes with its chalk surface).

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.paper

const val PAPER_CATALOGUE_FORMAT = "joybrush-papers"
const val PAPER_CATALOGUE_VERSION = 1

@Serializable data class SurfaceEntry(
    val id: String,              // [a-z0-9_]{1,40}, unique among surfaces
    val name: String,            // what the sheet shows, 1..40 chars
    val file: String,            // plain *.png name inside assets/paper/
    val size: Int,               // texels across; the PNG is size×size; power of two 64..2048
    val texelPx: Float,          // doc px per texel, 0.25..16
    val slopeRange: Float,       // as packed (JB-9.01), 0.001..4
    val hexTexels: Float,        // hex cell size in texels, 16..size
    val rotatable: Boolean,      // false for weaves, laid lines, papyrus
    val relief: Float = 1f,      // how strongly the relief is LIT on screen, 0..4
)

@Serializable data class LookEntry(
    val id: String,              // [a-z0-9_]{1,40}, unique among looks
    val name: String,
    val base: String,            // "#RRGGBB": the flat colour, and the colour a tint is measured against
    val file: String? = null,    // RGB picture (*.png), or null = flat base colour
    val mean: String? = null,    // "#RRGGBB" mean colour of the picture; REQUIRED when file != null (tinting divides by it)
    val texelPx: Float = 2f,
    val hexTexels: Float = 180f,
    val rotatable: Boolean = true,
    val defaultSurface: String? = null,   // a surface id, or null = smooth
    val lightByDefault: Boolean = true,   // AMOLED black ships false, so black stays black
)

@Serializable data class PaperCatalogue(
    val format: String = PAPER_CATALOGUE_FORMAT,
    val version: Int = PAPER_CATALOGUE_VERSION,
    val surfaces: List<SurfaceEntry>,
    val looks: List<LookEntry>,
)

object PaperCatalogues {
    /** Parses and validates. Unknown keys are an error (strict, like the brush codec, JB-0.02d). */
    fun parse(json: String): PaperCatalogue
    /** Every problem, in words, each naming the entry id and the field. Empty = valid. */
    fun problems(c: PaperCatalogue): List<String>
    fun surface(c: PaperCatalogue, id: String): SurfaceEntry?
    fun look(c: PaperCatalogue, id: String): LookEntry?
}
```

## Decisions already made
1. Ranges in the contract comments are the validator's rules. Out of range → a problem naming the id and field.
2. `defaultSurface` must name an existing surface id. Duplicate ids, a `file` with a path separator, or a missing `mean` on a pictured look are problems.
3. `version > PAPER_CATALOGUE_VERSION` → refused with "made by a newer Joy Brush".
4. The catalogue lives in assets and is NOT part of a document. Documents reference papers by id (JB-9.05).

## Tests
`PaperCatalogueTest`: a valid two-entry catalogue parses with zero problems. Then one test per rule: each makes exactly
one bad field and asserts exactly one problem whose text contains the id and the field name. Unknown key → parse error.
Newer version → refused with the words above.
`ShippedCatalogueTest` (jvmTest): the shipped `assets/paper/catalogue.json` has zero problems, and every `file` it names
exists, decodes (javax.imageio), and is `size`×`size` (surfaces) / square power-of-two (looks).

**Command:** `./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks` in your worktree. Paste counts.

## Do not
Do not add papers to the catalogue (that is the specialist's JB-9.10). Do not touch androidkit.

## Definition of done
- [ ] tests pass with counts · [ ] mutation: remove the duplicate-id rule → its test goes red · [ ] owner area only
- [ ] commit "JB-9.04: …", rebased, pushed · [ ] ROADMAP row → 🟧 Built + command + counts

## Questions

### ⛔ BLOCKER — the shipped `catalogue.json` carries a key this row's contract does not have. Stopped here; not committed.

2026-10-01 OpenCode free agent. Per the owner's rule I stopped rather than guess. **All three owner-area
files are written and the 33 `PaperCatalogueTest` tests are green. The 4 `ShippedCatalogueTest` tests
cannot be, and the reason is one line of the shipped asset.**

`joybrush/assets/paper/catalogue.json`, the `off_white` look:

```json
      "file": null,
      "detailStrength": 0.0,        <-- not in LookEntry
      "texelPx": 2.0,
```

`LookEntry` in the Contract above has no `detailStrength`, and the contract also says **"Unknown keys
are an error (strict, like the brush codec, JB-0.02d)"**. So `PaperCatalogues.parse` refuses the file at
the door, which is the correct behaviour and exactly what the strictness rule asks for — and it means
every test in `ShippedCatalogueTest` fails before it can measure a single picture:

```
kotlinx.serialization.json.internal.JsonDecodingException: Encountered an unknown key
'detailStrength' at offset 464 at path: $.looks[0]
```

**Why I could not just fix it.** Both repairs are outside this row's owner area, which the Definition
of done pins to the three new files:

- **Delete the key from `catalogue.json`.** The right fix if the key is obsolete — no Kotlin, GLSL or
  spec anywhere in the repo reads `detailStrength` (verified: the only two hits in the whole repo are
  this line and JB-9.06's question about it), and R10 P11's "detail octave" is a per-surface sampler
  feature (JB-9.06 Decision 4), not a per-look file field. But `catalogue.json` is the specialist's
  file, and "Do not add papers to the catalogue" plus the owner-area pin means I do not edit it.
- **Add `detailStrength` to `LookEntry`.** That is a file-format change to a "Contract (verbatim)" block,
  and R31 freezes new serialised fields until the Lead rules. Not mine either.

**The one question, for the specialist:** is `"detailStrength": 0.0` on `off_white` obsolete (delete the
line), or is a real per-look field the contract is missing (add it to `LookEntry`, name its range, and
the version rule applies)? A one-line answer unblocks the row; the implementation and its tests are
written and waiting.

**Note for whoever rules:** Codex hit this exact wall on JB-9.06 on the same day and recorded it there.
It is one decision, not two, and both rows are waiting on it.

### Also worth the specialist's eye (not blocking — the row's own judgement calls, stated rather than hidden)

1. **A malformed surface `id` is two problems in the natural fixture, not one.** `PaperCatalogueTest`'s
   good look names `pulp_artisan` as its `defaultSurface`, so breaking the surface's id also breaks the
   look's reference. The test sets `defaultSurface = null` to keep `id` the only wrong field. This is a
   real coupling in the data model, not a test artefact — worth knowing that the two rules interact.
2. **A shared id is spoken ONCE, not once per entry carrying it.** Two entries claiming `pulp_artisan` is
   one mistake with two victims, and `assertOneProblem` demands exactly one problem. The first draft
   printed it twice and its own test caught it.
3. **`parse` has no exception type of its own.** The contract gives `PaperCatalogues` three functions and
   no exception class, so an unknown key raises the serialization library's `SerializationException` and
   this row invents no `PaperException`. JB-9.06, which loads the file for real, may want to wrap it in a
   sentence for a person.
4. **A non-finite number is refused twice over, and the parse gets there first.** JSON has no word for
   Infinity, so `"texelPx": 1e999` never reaches `problems` from a file; the rule is only reachable from a
   catalogue built in memory, and the test says so. Same split as `BrushValidate`.
5. **A mean on a look with no picture is a problem** (`a number nobody reads`, the `BrushValidate`
   grain-rule reason), and **`defaultSurface = null` is not** — null means smooth, and a rule written
   without the null case would refuse every flat-colour look. Both are pinned by tests.

**Specialist answer (2026-10-01):** my error. `detailStrength` is obsolete and is DELETED from the shipped `catalogue.json` (same commit
as this answer). `LookEntry` stays exactly as the contract says. Finish the row: rebase onto this commit and land your 33 + 4 tests.
