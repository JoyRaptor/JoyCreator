# JB-0.04 — Save and load stroke recordings (binary codec)

| | |
|---|---|
| **Tier** | T2 (no vision needed) |
| **Status** | Ready |
| **Depends on** | JB-0.01 (done: `PenSample`, `Tool`, `StrokeRecord` exist) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/io/ByteWriter.kt`, `.../core/io/ByteReader.kt`, `.../core/stroke/StrokeCodec.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/stroke/StrokeCodecTest.kt` |
| **Estimated size** | ~250 lines + ~200 lines of tests |

## Goal
Every stroke a painter makes is recorded (what the pen did, exactly). Ink layers, undo, replay and
timelapse all depend on writing those recordings to disk and reading them back **bit-for-bit
identical**. This spec builds that codec.

## Contract (verbatim — these already exist, do not change them)
```kotlin
package cc.joycreator.joybrush.core.input
data class PenSample(
    val x: Float, val y: Float, val timeMs: Double,
    val pressure: Float = 1f, val tilt: Float = Float.NaN, val azimuth: Float = Float.NaN,
    val barrel: Float = Float.NaN, val tool: Tool = Tool.STYLUS, val predicted: Boolean = false,
)
enum class Tool { STYLUS, ERASER, FINGER, MOUSE }

package cc.joycreator.joybrush.core.stroke
data class StrokeRecord(
    val id: String, val brushId: String, val seed: Long, val smoothing: Float,
    val screenPerDoc: Float, val samples: List<PenSample>,
)  // init: require(samples.none { it.predicted })
```

## The API to build
```kotlin
package cc.joycreator.joybrush.core.stroke
object StrokeCodec {
    const val MAGIC = "JBS1"
    const val VERSION: Int = 1
    fun encode(record: StrokeRecord): ByteArray
    fun decode(bytes: ByteArray): StrokeRecord          // throws StrokeCodecException
    fun encodeAll(records: List<StrokeRecord>): ByteArray
    fun decodeAll(bytes: ByteArray): List<StrokeRecord> // throws StrokeCodecException
}
class StrokeCodecException(message: String) : Exception(message)

package cc.joycreator.joybrush.core.io
class ByteWriter {                       // growable, little-endian
    fun u8(v: Int); fun u16(v: Int); fun u32(v: Long); fun i64(v: Long)
    fun f32(v: Float); fun f64(v: Double); fun utf8(s: String)   // utf8 = u16 byte length + bytes
    fun bytes(b: ByteArray)
    fun toByteArray(): ByteArray
}
class ByteReader(private val buf: ByteArray, private var pos: Int = 0) {
    fun u8(): Int; fun u16(): Int; fun u32(): Long; fun i64(): Long
    fun f32(): Float; fun f64(): Double; fun utf8(): String; fun bytes(n: Int): ByteArray
    val remaining: Int
}
```

## File layout — one record (all little-endian)
| Field | Type | Notes |
|---|---|---|
| magic | 4 bytes ASCII `J` `B` `S` `1` | |
| version | u16 | = 1 |
| flags | u16 | bit0 = tilt present, bit1 = azimuth present, bit2 = barrel present; other bits 0 |
| id | utf8 | u16 length then UTF-8 bytes |
| brushId | utf8 | |
| seed | i64 | |
| smoothing | f32 | |
| screenPerDoc | f32 | |
| sampleCount | u32 | |
| samples | sampleCount × sample | |

**Sample:** `timeMs f64, x f32, y f32, pressure f32, [tilt f32 if flag], [azimuth f32 if flag],
[barrel f32 if flag], tool u8 (Tool.ordinal)`.

A channel's flag is set if **any** sample has a non-NaN value for it. When the flag is set, every
sample stores that channel (NaN is stored as NaN). When clear, the channel is not stored and decodes
as NaN.

**Multiple records (`encodeAll`):** `u32 count`, then for each record `u32 byteLength` followed by
exactly that many bytes of the single-record encoding.

## Decisions already made
1. Binary, not JSON — a 1,000-sample stroke is ~33 KB instead of ~150 KB, and floats round-trip
   exactly. The document zip compresses it further.
2. Floats are written with `toRawBits()` so NaN and -0.0 survive exactly.
3. Absolute `timeMs` as f64 (lossless); no delta encoding in v1.
4. Unknown future version → `StrokeCodecException("unsupported stroke version N")`. Never guess.
5. Pure Kotlin common code only: no `java.*`, no `ByteBuffer`, no external libraries.

## Steps
1. Write the tests first (below).
2. `ByteWriter` over a growable `ByteArray` (double capacity when full). Little-endian by hand.
3. `ByteReader`; every read checks bounds and throws `StrokeCodecException("truncated")`.
4. `StrokeCodec.encode/decode`, then `encodeAll/decodeAll`.

## Tests (`StrokeCodecTest.kt`)
1. Round trip of a 500-sample record with all channels present → `decode(encode(r)) == r`.
2. Round trip with tilt present but azimuth and barrel absent → absent channels decode as NaN;
   flags == 0b001 (check by reading byte 6–7 of the output).
3. A record where only some samples have tilt → the others decode as NaN tilt, equality holds.
4. Ids with non-ASCII text (`"étoile-星"`) round trip.
5. `Float.NaN`, `-0.0f`, `Float.MIN_VALUE` in pressure survive exactly (compare raw bits).
6. All four `Tool` values round trip.
7. Empty samples list round trips.
8. `encodeAll`/`decodeAll` with 3 records of different flags round trips in order.
9. Bad magic → exception; version 2 → exception with "unsupported stroke version 2";
   truncated input (drop the last byte) → exception "truncated".
10. Size check: a 1,000-sample record with only pressure (no tilt/azimuth/barrel) encodes to
    exactly `4+2+2 + (2+len(id)) + (2+len(brushId)) + 8+4+4+4 + 1000×(8+4+4+4+1)` bytes.

(`StrokeRecord` equality uses `PenSample.equals`, and NaN == NaN is true for data-class `Float`
fields in Kotlin, so equality checks work with NaN channels.)

**Command:** `./gradlew -p joybrush :core:jvmTest` from the repo root. Passing = `BUILD SUCCESSFUL`
and the StrokeCodecTest suite shows 10+ tests, 0 failures in
`joybrush/core/build/test-results/jvmTest/`.

## Do not
- Do not change `PenSample`, `StrokeRecord` or anything outside the owner area.
- Do not use `java.nio`, `java.io`, kotlinx-serialization, or any new dependency.
- Do not write predicted samples (they cannot exist in a `StrokeRecord`).
- Do not "compress" or reorder anything beyond this layout.

## Definition of done
- [ ] tests pass (paste the output)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] committed as `JB-0.04: stroke codec`; pushed
- [ ] `tasks/joybrush/specs/INDEX.md` → "Built — awaiting T1 review"

## Questions
