# JB-9.05 — The document's paper: look, surface, tint, show, bite, light (DOC_VERSION 4)

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | OpenCode free agent |
| **Depends on** | JB-9.04 (`PaperState` resolves ids against `PaperCatalogue`) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` (`Paper`, `DOC_VERSION` only), EDIT `doc/DocOps.kt` (paper validation only), NEW `core/paper/PaperState.kt`, tests under `core/src/commonTest/.../doc/` and `.../paper/` |
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
- [ ] tests pass with counts · [ ] mutation: drop the `show` range check → its test goes red · [ ] owner area only
- [ ] commit "JB-9.05: …", rebased, pushed · [ ] ROADMAP row → 🟧 Built + command + counts

## Questions
