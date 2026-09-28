# JB-2.14a — PNG writer (pure JVM, works on Android and in tests)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | none |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PngWriter.kt`, NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PngWriterTest.kt`, EDIT `joybrush/androidkit/build.gradle.kts` (add `testImplementation(kotlin("test"))` if JB-0.08a has not already) |
| **Estimated size** | ~120 lines + ~80 lines of tests |

## Goal
Exports (OpenRaster layers, sprite sheets, thumbnails) need PNG files written the same way on the
phone and in tests. Android's `Bitmap.compress` can't run in a JVM test and premultiplies; this
writer is exact.

## Contract
```kotlin
package cc.joycreator.joybrush.androidkit.io
object PngWriter {
    /** Straight RGBA8, row 0 = top → a complete PNG file (colour type 6, bit depth 8). */
    fun encode(width: Int, height: Int, rgba: ByteArray, compressionLevel: Int = 6): ByteArray
}
```

## Decisions
1. Chunks: signature, IHDR (w, h, 8, 6, 0, 0, 0), one or more IDAT (zlib via `java.util.zip.Deflater`),
   IEND. CRC with `java.util.zip.CRC32` over type + data.
2. Filtering: per row choose among None/Sub/Up/Average/Paeth the one with the smallest sum of
   absolute signed byte values (the standard heuristic).
3. Validate `rgba.size == w*h*4`, `w, h in 1..65535` → IllegalArgumentException.
4. No gamma/sRGB chunks (sRGB is assumed by every reader).

## Tests (JVM)
1. Encode a 3×2 image with known pixels, decode with `javax.imageio.ImageIO.read` → identical RGBA
   (including alpha 0 and 255 and mid values).
2. A 300×200 gradient round-trips exactly. 3. Bad size throws.
4. Output starts with the 8-byte PNG signature; IHDR fields correct.

**Command:** `./gradlew -p joybrush :androidkit:test -Pjoybrush.androidJar=<path>` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-2.14a: PNG writer` · ROADMAP row → 🟧 Built.

## Questions
