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
