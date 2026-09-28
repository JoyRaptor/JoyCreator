# JB-3.06a — Animated GIF encoder (pure Kotlin, deterministic)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | none (uses JB-3.01's timing when wired later) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/GifEncoder.kt`, NEW `.../commonTest/.../export/GifEncoderTest.kt`, NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/export/GifDecodeTest.kt` |
| **Estimated size** | ~300 lines + ~200 lines of tests |

## Goal
Animations and sprite loops shared as GIFs — the one format every phone, chat app and browser plays.
No dependency (the app is zero-dependency here), identical output everywhere.

## Contract
```kotlin
package cc.joycreator.joybrush.core.export
class GifEncoder(val width: Int, val height: Int, val loop: Boolean = true) {
    /** Straight RGBA8 (row 0 = top). delayMs is rounded to centiseconds, minimum 2 cs (browsers clamp lower values). */
    fun addFrame(rgba: ByteArray, delayMs: Int)
    /** Builds the whole file. Palette is computed over ALL frames (see Decisions). */
    fun finish(): ByteArray
}
```

## Decisions
1. **Palette:** one global palette of ≤ 255 colours + 1 transparent index (index 0). Build by
   **median cut** over the RGB of every opaque pixel of every frame (alpha ≥ 128), splitting the box
   with the largest range on its longest axis, at the median; box colour = mean. Ties broken by lower
   channel index. Pixels with alpha < 128 → transparent index.
2. **Mapping:** nearest palette colour by squared RGB distance; no dithering (dithering flickers in
   animation). Cache lookups in a 32×32×32 table (5 bits per channel) for speed.
3. **File:** `GIF89a`, logical screen = width × height, global colour table (size rounded up to a power
   of two), NETSCAPE2.0 application extension with loop count 0 if `loop`; per frame: Graphic Control
   Extension (disposal 2 = restore to background, transparency flag on, index 0, delay), Image
   Descriptor (full frame), LZW-compressed data (min code size = max(2, bits of table size)), trailer `;`.
4. **LZW:** standard variable-length codes up to 12 bits with clear/end codes; emit a clear code when
   the table fills.

## Tests
- commonTest: header bytes; frame count via counting Image Descriptors (0x2C); two identical encodes
  are byte-identical; a single-colour frame gives a palette with that colour; delay 10 ms → 2 cs.
- jvmTest (`GifDecodeTest`): encode 3 frames (red, green, half-transparent blue) 64×48, decode with
  `javax.imageio` GIF reader → 3 frames, correct size, pixel colours within ±8 per channel of the
  source, transparent where alpha < 128, delays read back from the metadata.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-3.06a: GIF encoder` · ROADMAP row → 🟧 Built.

## Questions
