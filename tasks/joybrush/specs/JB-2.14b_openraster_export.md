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

_(Orchestrator, provisional — Claude to confirm. Nothing here is a contract decision; all three are
mechanical and all three are recorded so the next reader does not "fix" them again.)_

1. **The two failures were both the test's arithmetic, and both were the same mistake: counting the
   fixture instead of the output.**
   - `anInkLayerIsSkippedAndNamedWhileThePaintLayersBesideItAreExported` asserted **3** `data/*`
     entries. The fixture has three *layers*; the writer exports two *files*, because the INK layer
     is skipped. The same test already asserts the two `src` values are `data/0.png` and
     `data/1.png`, so 3 contradicted its own assertions two lines above. Now 2.
   - `aLayerAtZeroOpacityOrHiddenOrMeaninglessIsNamedRatherThanVanishing` asserted **5** omissions and
     **1** survivor — while its own following lines asserted that the layer named "Loud", at
     `opacity = 2f`, is exported at `1.000`. Loud clamps to fully opaque, which is an ordinary
     layer, not an omission. So: 4 omissions (Wash, Backwards, Nonsense, Sketch) and 2 survivors
     (Loud, Gloss). Loud is now asserted to be **present by name**, which is the stronger claim: it
     pins where the line is drawn rather than only how many things sit on the far side of it.

2. **`joybrush/core/build.gradle.kts`: `implementation` → `api` for kotlinx-serialization.** This is
   outside this spec's owner area and is the one change I made outside a task, so here is why.
   `:androidkit:test` was failing with `NoClassDefFoundError: cc/joycreator/joybrush/core/doc/JbDocument`
   for **every** test class in the module — including JB-0.08a's already-landed `JbArchiveTest`.
   `JbDocument` and `BrushPreset` are `@Serializable` data classes on core's *public* API; declaring
   serialization as `implementation` makes it an implementation detail of core, which it is not,
   because it is written into the type of everything the document and brush formats return. Consumers
   then load a class whose annotations and generated serializers are absent, and the failure appears
   at class-load time rather than at compile time — which is exactly why it survived JB-0.08a
   landing. With `api`, the whole module's test suite runs again: **85 tests, 0 failures.**

   If the Lead prefers core to keep its dependency private, the alternative is
   `testImplementation(...)` in `joybrush/androidkit/build.gradle.kts`. That fixes the tests and
   leaves the phone one runtime accident away from the same error, which is why I did not choose it.
