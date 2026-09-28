# JB-0.08a — The `.joybrush` file: write and read the archive (no UI)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02 (Built), JB-0.04 (Built) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt`, NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchiveTest.kt`, EDIT `joybrush/androidkit/build.gradle.kts` (add ONLY `testImplementation(kotlin("test"))`) |
| **Estimated size** | ~220 lines + ~180 lines of tests |

## Goal
A Joy Brush drawing saved as one file that can never be half-written, and read back bit-for-bit.
Plain JVM code (`java.util.zip`, `java.io`) so it is tested here without a phone. The save/open
buttons are JB-0.08b.

## Layout (decided)
A zip:
| Entry | Content | Compression |
|---|---|---|
| `mimetype` | `application/x-joybrush` | **STORED, first entry** (like OpenRaster) |
| `document.json` | `DocJson.encode(doc)` | deflate |
| `layers/<layerId>/<celId>/<tx>_<ty>.rgba` | one PAINT tile: 262,144 bytes premultiplied RGBA8, row 0 = top | deflate (level 6) |
| `layers/<layerId>/<celId>/strokes.jbs` | INK cel: `StrokeCodec.encodeAll(records)` | deflate |
| `thumbnail.png` | optional, written by 0.08b | stored |
Tile keys come from `Cel.tiles` ("tx_ty"). Unknown entries are ignored on read (forward compat).

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.io
class JbArchiveException(message: String) : Exception(message)
data class JbContents(
    val doc: JbDocument,
    val tiles: Map<Triple<String, String, String>, ByteArray>,  // (layerId, celId, "tx_ty") → 262,144 bytes
    val strokes: Map<Pair<String, String>, List<StrokeRecord>>,  // (layerId, celId) → records
    val thumbnailPng: ByteArray? = null,
)
object JbArchive {
    /** Writes atomically: to "<file>.tmp", fsync, then: if <file> exists rename it to "<file>.bak", rename tmp → file. */
    fun save(file: java.io.File, contents: JbContents)
    fun write(out: java.io.OutputStream, contents: JbContents)
    fun read(input: java.io.InputStream): JbContents   // throws JbArchiveException
    fun open(file: java.io.File): JbContents
}
```

## Decisions
1. `write` refuses (JbArchiveException) a tile that is not exactly 262,144 bytes, a tile whose key is
   not listed in its cel's `tiles`, or a document that fails `DocOps.validate`.
2. `read` refuses: missing/incorrect `mimetype` or not first; missing `document.json`; a tile entry of
   the wrong size; any entry path containing `..` or starting with `/` (zip-slip guard). Validates
   the document and throws with the problems joined if invalid.
3. Tiles listed in a cel but missing from the zip are an error ("missing tile …").
4. `save` must never leave the user with no good file: if anything throws before the final rename,
   delete the `.tmp` and leave the existing file untouched.

## Tests (JbArchiveTest, JVM)
1. Round trip: a document with one PAINT layer (3 tiles incl. a negative key), one INK layer (2
   stroke records) → `read(write(x)) == x` (compare byte arrays with `contentEquals`).
2. `mimetype` is the first entry and STORED (inspect with `ZipInputStream`).
3. Zip-slip entry → exception. Wrong-size tile → exception. Missing tile → exception.
4. Unknown extra entry → ignored.
5. `save` twice → `.bak` holds the first version; a save that throws mid-way (simulate with an
   invalid document) leaves the original file byte-identical and no `.tmp` behind.

**Command:** `./gradlew -p joybrush :androidkit:test -Pjoybrush.androidJar=<path>` (android.jar is only
needed to compile; tests use no Android classes). 0 failures.

## Do not
No Android APIs in `JbArchive`. Don't touch the engine or the view.

## Definition of done
Tests pass (paste) · commit `JB-0.08a: .joybrush archive` · ROADMAP row → 🟧 Built.

## Questions
