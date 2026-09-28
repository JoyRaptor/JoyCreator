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
