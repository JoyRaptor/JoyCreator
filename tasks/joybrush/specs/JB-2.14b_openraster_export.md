# JB-2.14b — Export OpenRaster (.ora): layers that open in Krita, GIMP, MyPaint

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.13a, JB-2.14a, JB-0.08a (`JbContents`) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/OraExport.kt`, NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/OraExportTest.kt` |
| **Estimated size** | ~200 lines + ~150 lines of tests |

## Goal
A layered export any desktop painting app can open (OpenRaster is the open standard; Krita, GIMP and
MyPaint read it). PSD comes later.

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.io
object OraExport {
    /** Exports [board]'s rectangle, one PNG per visible PAINT layer, for [frameId] (null = static). */
    fun write(out: java.io.OutputStream, contents: JbContents, boardId: String, frameId: String?, includePaper: Boolean)
}
```

## Decisions (OpenRaster 0.0.x layout)
1. Zip entries in this order: `mimetype` = `image/openraster` (**STORED, first**), `stack.xml`,
   `data/<n>.png` per layer, `Thumbnails/thumbnail.png` (longest side ≤ 256, aspect kept, nearest
   downscale is fine), `mergedimage.png` (full size).
2. `stack.xml`:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <image version="0.0.3" w="W" h="H">
     <stack>
       <layer name="…" src="data/0.png" x="0" y="0" opacity="1.000" visibility="visible" composite-op="svg:src-over"/>
       …
     </stack>
   </image>
   ```
   Layers listed **top first** (OpenRaster order). Names XML-escaped.
3. Each layer PNG = that layer ALONE rendered over the board rect with `RegionRenderer` (a document
   copy containing only that layer, opacity 1, NORMAL), straight alpha via `PngWriter`. Opacity and
   blend go in the XML: NORMAL→`svg:src-over`, MULTIPLY→`svg:multiply`, SCREEN→`svg:screen`,
   OVERLAY→`svg:overlay`, ADD→`svg:plus`, DARKEN→`svg:darken`, LIGHTEN→`svg:lighten`,
   ERASE_BELOW→`svg:dst-out`.
4. If `includePaper`, add a bottom layer named "Paper" filled with the paper colour.
5. `mergedimage.png` = `RegionRenderer.render` of the whole stack (with paper if included).
6. INK layers: skipped in v1 (they are rendered by JB-5.01 later) — write an XML comment noting it.

## Tests (JVM)
Build `JbContents` with two PAINT layers (one MULTIPLY at 0.5 opacity) on a 300×200 board:
1. Entry order and STORED mimetype. 2. stack.xml parses (javax.xml), top-first, correct ops/opacity.
3. Each layer PNG decodes (ImageIO) to the expected size and a known pixel.
4. includePaper adds the Paper layer and makes mergedimage opaque.
5. Thumbnail longest side ≤ 256.

**Command:** `./gradlew -p joybrush :androidkit:test -Pjoybrush.androidJar=<path>` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-2.14b: OpenRaster export` · ROADMAP row → 🟧 Built.

## Questions
