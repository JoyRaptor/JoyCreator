# JB-4.03a — Sprite sheet packer + SpriteLab sidecar (`.sprite.json`)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02 (Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/SpritePacker.kt`, NEW `.../commonTest/.../export/SpritePackerTest.kt` |
| **Estimated size** | ~200 lines + ~180 lines of tests |

## Goal
A sprite board (or an animation's frames) becomes ONE sheet image plus the JSON sidecar that
SpriteLab and the Studio already read — so it opens there with no conversion. The sidecar format is
the app's existing contract: `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java`
`toJson()` (read it; do not change it).

## Contract
```kotlin
package cc.joycreator.joybrush.core.export
data class Clip(val name: String, val frames: List<Int>, val type: String = "loop", val fps: Float = 0f) // type: loop | pingpong | once
data class PackedSheet(val width: Int, val height: Int, val rgba: ByteArray, val sidecarJson: String)
object SpritePacker {
    /**
     * @param cells straight RGBA8 images, all cellW × cellH, in reading order.
     * @param cols cells per row; rows = ceil(cells.size / cols). Empty trailing cells are transparent.
     * @param sheetFileName e.g. "Walk cycle.png" — written as sheetUri (relative, next to the sidecar).
     * @param cellNames optional index → name.
     */
    fun pack(cells: List<ByteArray>, cellW: Int, cellH: Int, cols: Int, id: String, name: String,
             sheetFileName: String, fps: Float, clips: List<Clip>, cellNames: Map<Int, String> = emptyMap()): PackedSheet
}
```

## Sidecar written (exact keys, matching `SpriteSheet.toJson`)
```json
{
  "spriteSchemaVersion": 1,
  "id": "<id>", "name": "<name>", "sheetUri": "<sheetFileName>",
  "cols": C, "rows": R, "fps": F,
  "presets": [ { "id": "<id>-clip-<i>", "name": "...", "type": "loop", "frames": [0,1,2] } ],
  "cellNames": { "0": "idle_01" }
}
```
- No margins/spacing keys (packer uses none, and the app omits zeros).
- A preset's `fps` is written only when > 0 (as the app does). `cellNames` only when non-empty.
- `presets` only when non-empty.
- Pretty-printed with 2-space indent, keys in the order above (kotlinx.serialization JsonObject
  built by hand preserves insertion order).

## Tests
1. 5 cells of 16×16, cols 3 → 48×32 sheet; cell 4 at (16,16); cells 5 area transparent.
2. Sidecar parses (kotlinx JsonObject) with exactly the keys above; frames match; `fps` absent on a
   clip with fps 0.
3. Pixel copy exact for a known pattern in each cell. 4. Cells of the wrong size → IllegalArgumentException.
5. cellNames present only when given.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Do not edit any app file. Do not add fields the app does not write.

## Definition of done
Tests pass (paste) · commit `JB-4.03a: sprite sheet packer` · ROADMAP row → 🟧 Built.

## Questions

All the below were open when this was written and are now decided in `SpritePacker.kt`. They are
recorded because the sidecar is a contract, not a convenience, and the next thing to touch it
should not have to re-derive them.

**Decided (tested in `SpritePackerTest`):**
1. **Zero cells is refused**, not packed into a 0-row sheet. A 0-row sidecar is self-consistent
   and still an image with nothing in it that no reader opens. An empty board is a mistake, and
   the two-button flow should say so.
2. **`cellNames` may only name a POPULATED cell** — `0 until cells.size`, not `0 until cols *
   rows`. A name on a transparent trailing slot is a name on nothing, and `describe_sprite_sheet`
   would report a named cell that renders blank.
3. **Clip frame indices are range-checked; duplicates are not an error.** A repeat is how
   ping-pong is written (`0,1,2,1`). Only an index past the packed cells is refused.
4. **A clip with no frames is refused, and so is an unknown `type`.** The app stores an unknown
   type verbatim and then behaves as `loop`, which is the quiet wrongness a writer should catch.
   The vocabulary is the app's three values and nothing else.
5. **Sheet `fps` 0 is allowed and written.** It means "no cadence chosen"; the app's `setFps`
   clamps a read 0 up to its own 0.1 minimum, so the round trip is the app's behaviour rather
   than a lie. Negative, NaN and Infinity are refused — JSON has no word for them, and the app
   would read the absent key as its own default.
6. **`cellNames` is written in ascending cell order, not the caller's map order.** A `HashMap`
   re-export would otherwise reorder keys, and a re-export that reorders keys looks like a change
   to anyone watching the repo.

**Still open, and cheap to close later:**
7. A name of `"   "` is written as `"   "`, where the app's `setCellName` would trim it to nothing
   and drop it. Not refused, because a UI that trims before calling should not be second-guessed
   here. One line in the writer (`if (value.trim().isNotEmpty())`) if the owner decides the file
   should mirror the app's setter exactly.
8. No maximum sheet dimension is enforced. PNG's practical limits are encoder-specific, so the
   number belongs with whoever writes the PNG, not invented here.
9. **Handed to the writer task:** `PackedSheet.assertEncodedSize(fileName, pngWidth, pngHeight)`.
   The sidecar cannot carry the tie between itself and the file beside it — a checksum or size
   field would be a key the app does not write, and the format is closed — and `pack()` never
   sees a file. The check is therefore a runtime call the encoder makes, and it belongs before
   either file is written so a wrong size costs a message instead of a disagreeing pair of files.

