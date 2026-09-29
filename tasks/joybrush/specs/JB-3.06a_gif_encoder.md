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

_Nothing outstanding. The questions this spec's previous builder could not answer were all
answerable from the file format itself, so I answered them on the main thread and left no contract
decision for the Lead._

### What was actually wrong (two encoder bugs, not one arithmetic slip)

The handover note for this task said "a row stride / buffer length is being passed where a height
belongs". **That diagnosis was wrong in detail, and following it is why four dispatches found
nothing.** Both real bugs were elsewhere, and neither would ever have been found by reading the
code alone — both were found by asking a *third-party decoder* to read the file.

1. **The Logical Screen Descriptor was five bytes instead of seven.** The descriptor is width(2),
   height(2), packed(1), **background colour index(1)**, **pixel aspect ratio(1)**. The last two
   were never written. The colour table therefore started two bytes early, and since every decoder
   locates the first block by *skipping* the table, **every block after it was read at the wrong
   offset** — the whole file was malformed. GDI+ refused it outright, and `javax.imageio` said
   `Unexpected block type 11!`, which is the NETSCAPE application-id length (0x0B) being read as a
   block introducer. Two independent decoders, one cause, and the message pointed straight at it.
   This is the bug that made the task unlandable, and it was a missing pair of bytes nobody
   remembered exists.

2. **The LZW code width grew one code too early.** The writer hands out a code *before* the reader
   gets round to minting the matching one, so the writer's dictionary is exactly one entry ahead
   and must widen one code later than "the table is full" sounds like. `nextCode == (1 shl codeSize)`
   widens early; `nextCode > (1 shl codeSize)` is right. The test's own decoder has the mirror-image
   rule (`next >= (1 shl codeSize)`) and the two must be one entry apart **on purpose** — copying
   the writer's comparison into the reader desynchronises every stream that crosses a boundary.

   The oracle here was GDI+ via `System.Drawing`, driven from a throwaway test that wrote the
   encoder's output to disk: a 4x1 single-colour file is the smallest case that separates the two
   rules. Once the encoder was fixed, thirteen widths from 1 to 32 all decoded in GDI+.

### Five test-side bugs, recorded because the pattern is the lesson

All five were the *test* disagreeing with a correct encoder, and all five had been "fixed" by
adjusting the expectation at least once:

- the parser read the image height at `at + 6` instead of `at + 7` — one byte, and it reported
  itself as `frame is 8x2048, screen is 8x8`, which reads exactly like a stride bug and is not one;
- it located the colour table at byte 11 rather than 13, which agreed with the broken five-byte
  descriptor and so hid bug 1 completely;
- `findImageData` skipped the LZW minimum-code-size byte only on the path that *collects* data, so
  asking for the second frame ran off the end of the file;
- a hand-derived 60-byte expectation for a 1x1 GIF carried a third LZW data byte, which pushed the
  sub-block terminator out and made the expected file unparseable; its own comment said `4C 01`;
- a fixture indexed `frame[4 * 4 + 3]` into a 16-byte array, so the alpha-threshold test was
  asserting on an `ArrayIndexOutOfBoundsException`.

The two oracle-driven findings (GDI+ and ImageIO) are what separated "the code looks right" from
"the file is right". **Lesson for every future format task in this project: the suite must contain
at least one assertion made by a decoder that did not write the bytes.** `GifDecodeTest` is that
assertion and it is worth more than every hand-derived byte array in `GifEncoderTest` put together.
