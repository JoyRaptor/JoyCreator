# JB-9.11 — Imported brush textures become surfaces (slopes derived automatically)

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready for the core half (paper specialist, 2026-10-01). The drawing half belongs to JB-1.05d (spec file still missing; the Lead's row) |
| **Builder** | OpenCode free agent |
| **Depends on** | JB-9.01 |
| **Owner area** | NEW `joybrush/core/.../paper/ImportedTexture.kt` + test; EDIT the importers' texture-storing code ONLY to call it (`core/.../import/` or wherever JB-8.01/8.02/8.04 store `patt`, `Grain.png` and Krita patterns: find them with grep and list them in your report) |
| **Estimated size** | ~120 lines + ~150 lines of tests |

## Goal
Owner P4: "if a brush came with a texture that's like a height map, we should derive a normal map out of it automatically … so a
Photoshop brush loaded in will work better in our app than in its native app." Importers already KEEP these textures (R40,
"kept but not drawn yet"). This row converts each one, at import time, into our surface layout (JB-9.01), so JB-1.05d/JB-9.08 can draw it
with direction.

## Contract (verbatim)
```kotlin
package cc.joycreator.joybrush.core.paper

object ImportedTexture {
    /**
     * Any imported greyscale pattern (w×h bytes, any size ≥ 3, tileable as the source app tiles it) → a packed surface.
     * invert = the source app's "Invert" flag (Photoshop Texture → Invert; Procreate Grain is WHITE = paint lands, i.e. high).
     * Returns the RGBA bytes plus the slopeRange used (SurfaceMaps.defaultSlopeRange).
     */
    fun toSurface(grey: ByteArray, w: Int, h: Int, invert: Boolean): Surface
    class Surface(val w: Int, val h: Int, val rgba: ByteArray, val slopeRange: Float)

    /** Colour patterns (ABR patterns can be RGB): luminance = (0.2126 R + 0.7152 G + 0.0722 B) on sRGB bytes, rounded. */
    fun luminance(rgb: ByteArray, w: Int, h: Int): ByteArray
}
```

## Decisions already made
1. **Height convention:** our height 1 = peak = takes paint first under light pressure. Photoshop's Height modes treat WHITE as high
   (R4). Procreate's grain: white = paint shows (high). Krita: as Photoshop. A source "invert" flag flips it. Verify each against R4/R3 and quote the line in your report.
2. **Store, do not replace:** the importer keeps the original texture bytes as today AND adds the packed surface beside it. A re-import can always be redone.
3. **No resizing.** Keep the source size. `GrainTextures` already handles any size.
4. A pattern that is flat (all one value) gets slopeRange 0.001 and is flagged "flat texture" in the import report.

## Tests
- A 4×4 checkerboard (0/255) → B round-trips, slopes non-zero at the edges, `invert` flips B (255 − b) and negates slopes.
- `luminance` on pure R, G, B, white and black against the formula.
- Each importer that now calls `toSurface`: one existing real-file test (JOYBRUSH_TESTDATA, LEAD_DESK order 5) asserts a packed surface is stored for a brush that has a texture. Synthetic files alone do not count (R44).

**Command:** `JOYBRUSH_TESTDATA=C:\+Projects\Screenrecorder\FadCam\joybrush\testdata-local ./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks` in your worktree. Never commit anything from `testdata-local`.

## Do not
Do not draw anything (JB-1.05d/JB-9.08). Do not remove the "kept but not drawn yet" warning; that goes when drawing lands.

## Definition of done
- [ ] tests pass with counts · [ ] the importer list in the report · [ ] pushed · [ ] ROADMAP → 🟧 Built

## Questions
