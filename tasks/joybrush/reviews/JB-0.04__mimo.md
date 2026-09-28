# Adversarial review — JB-0.04 stroke recording codec (binary save/load)

- Reviewer: mimo (second adversarial pass; muse-spark filed `JB-0.04__muse-spark.md`).
- Task status: 🟧 Built. Commit reviewed: `f8366435` (tree at `b74aaf0e`).
- Spec: `tasks/joybrush/specs/JB-0.04_stroke_codec.md` (layout table, Decisions 1–5, Tests 1–10, empty Questions section — builder raised none).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (StrokeCodecTest 11).
- §5b checks: commit touches only `io/ByteWriter.kt`, `io/ByteReader.kt`, `stroke/StrokeCodec.kt`, `StrokeCodecTest.kt`, board row — owner area as declared. `PenSample.kt`/`StrokeRecord.kt` untouched (contract verbatim at spec:15-30 still matches source, I diffed field-by-field: defaults `pressure=1f`, `tilt/azimuth/barrel=NaN`, `predicted=false`, `PenSample.kt:28-37`; `StrokeRecord.init` reject-predicted `StrokeRecord.kt:27-28`). No `java.*`, no `ByteBuffer`, no new dependency (grep over `core/io` + `StrokeCodec.kt`: only `StrokeCodecException` import).
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER, no MAJOR. Muse's three findings reproduced; I sharpen one and add one of my
own (all MINOR, all file-format/hardening). The codec's core promise — bit-exact round trip, loud
refusal on anything else — I re-verified line by line against the spec layout table.**

## Status of muse-spark's findings at this commit (each re-read by me in source)

- **F1 (pre-size ArrayList from untrusted count)** — **CONFIRMED, sharpened; MINOR.** See M1 below.
- **F2 (trailing bytes silently ignored)** — **CONFIRMED; MINOR, by design defensible.** See M2 below.
- **F3 (dead "bad UTF-8" catch)** — **CONFIRMED; MINOR (comment/code mismatch).** See M3 below.

## M1 (MINOR — hostile-file memory spike, worse in `decodeAll` than in `decode`): both decoders pre-size an `ArrayList` from a count bounded only by file size

- **Proof (decode):** `StrokeCodec.kt:91-95` — `count = r.u32()` (up to 4 294 967 295), the only
  guard is `count > Int.MAX_VALUE || count * MIN_SAMPLE_BYTES > r.remaining` (`:92`,
  `MIN_SAMPLE_BYTES = 21`, `:35`) so `count ≤ remaining/21`, then `ArrayList<PenSample>(count.toInt())`
  at `:95`. `ArrayList(Int)` allocates the backing array **eagerly**. Input: a 210 MB hostile file
  with `sampleCount = 10_000_000` → ~10 M-slot reference array (~80 MB) + one `PenSample` per
  `repeat` iteration, all allocated before any value is validated beyond bounds.
- **Proof (decodeAll — the worse one):** `StrokeCodec.kt:127-131` — the guard is only
  `count > r.remaining` ("one byte each at the very least", `:130`), so `ArrayList<StrokeRecord>(count.toInt())`
  at `:131` can be sized **one slot per remaining byte**: a 200 MB file of `count = 199_000_000`
  requests a ~1.6 GB backing array *before* the first record length is read. (The subsequent
  per-record `n > r.remaining` check at `:135` would refuse each record anyway — the spike happens
  first.)
- **Fix shape (as muse says):** `ArrayList()` + `ensureCapacity(min(count, remaining/21, someCap))`,
  or grow on insert. Not reachable through any current caller (JB-0.08's archive passes bytes the
  app itself wrote), so MINOR hardening for the JB-0.08 author — file-local, no spec change needed.

## M2 (MINOR — trailing bytes accepted as success; decision needed at JB-0.08): `decode` never checks `remaining == 0`, `decodeAll` never checks the tail

- **Proof:** `decode` returns `StrokeRecord` at `StrokeCodec.kt:110` straight out of
  `repeat(count.toInt())` (`:99-109`) with no `if (r.remaining != 0) throw`; `decodeAll` returns at
  `:138` after `count` records without a tail check. Input: `encode(r) + byteArrayOf(0x7F)` →
  `decode` succeeds and drops the byte; appended garbage in a `.joybrush` archive is invisible
  until/unless JB-0.08 adds its own framing/CRC.
- Spec does not require rejection (layout table defines the fields, nothing about EOF), so this is
  **not a spec violation** — record it as an explicit decision item for JB-0.08 (where checksums and
  the archive envelope live). The sub-count/truncation paths *are* refused (spec Test 9, `:92,:130,:135`).

## M3 (MINOR — a promise the code cannot keep): `ByteReader.utf8`'s catch around `decodeToString()` is dead code

- **Proof:** `ByteReader.kt:50-58` — `b.decodeToString()` is called with default arguments, and
  Kotlin's default is **replacement** (malformed sequences → U+FFFD), not `throwOnInvalidSequence = true`;
  the `catch (e: Exception)` at `:55-56` can therefore never fire from decoding. Malformed id bytes
  silently become `""`/`"\uFFFD"`-laden ids instead of `StrokeCodecException("bad utf-8 in stroke data: …")`.
- Blast radius: ids only (display, keying), never pixel data; valid text round-trips (spec Test 4,
  `étoile-星`-class ids, verified green). Two consistent fixes: (a) `decodeToString(throwOnInvalidSequence = true)`
  — one argument, the catch becomes live and the KDoc promise (`ByteReader.kt:6-10` "refuses to invent
  bytes that are not there") becomes true for text too; or (b) reword the comment. Prefer (a).

## M4 (MINOR — my own; near-unreachable but a hang rather than an exception): `ByteWriter.reserve` overflow-loops forever once capacity would exceed 2³⁰

- **Proof (code):** `ByteWriter.kt:67-72` — `while (cap < len + n) cap *= 2` with `cap: Int`.
  Trace for `w.bytes(ByteArray(1 shl 30))` (or any write needing `len + n > 2³⁰`): `cap` doubles to
  `1_073_741_824`, still `< len + n`, so `cap *= 2` overflows to `-2_147_483_648`; the condition
  `-2_147_483_648 < len + n` holds → `cap *= 2` → `0`; then `0 *= 2` forever → **infinite loop
  (thread hangs, no exception, no OOM)**. `buf.copyOf(cap)` is never reached with the negative value,
  so it is a spin, not a crash.
- **Reachability:** encode-side only, and only past ~1 GB of encoded output in one writer — which
  means a >1 GB input `ByteArray` already sat in memory (`decode` bounds sample counts by
  `remaining/21`, so you cannot get there from a small file). Every realistic path dies of OOM or
  is refused earlier; still, the failure mode (silent hang on a painter's save) is the worst kind,
  and the fix is one line: `cap = maxOf(cap * 2L, (len + n).toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()`
  or bail with `StrokeCodecException("record too large")` past a sane cap (JB-0.08's archive should
  enforce a file ceiling anyway — pairs with M1).
- Filed MINOR per §5b (edge/robustness, no realistic input) — flagging so the JB-0.08 author picks
  it up with M1/M2 in the same pass.

## Verified sound (re-walked against the spec layout table this pass)

- **Layout fidelity:** magic `JBS1` (`:39,:73`), version u16 refused ≠ 1 with the exact spec
  message (`:76-77`, Decision 4), flags bits 0–2 and unknown-bit refusal (`:81-83`, Decision "never
  guess"), id/brushId u16-byte-length UTF-8 (byte- not char-count: writer `ByteWriter.kt:52-57`,
  reader `ByteReader.kt:50-52`), seed i64, smoothing/screenPerDoc f32, sampleCount u32, per-sample
  `f64 timeMs, f32 x, y, pressure, [channels]`, tool u8 ordinal — each field in spec order.
- **Channel flags:** set iff any sample non-NaN (`:43-45`); when set every sample writes the channel
  incl. NaN raw bits (`:63-65`); when clear reader fills `Float.NaN` (`:104-106`) — exactly Decision
  layout + spec Test 3 behaviour.
- **Bit-exactness:** `toRawBits` on both sides (`ByteWriter.kt:42,44`, `ByteReader.kt:45,47`) —
  NaN/−0.0/MIN_VALUE and absolute f64 timeMs survive (Test 5).
- **Refusals:** bad magic, version 0/2, truncation at every boundary (all via `need(n)` →
  `StrokeCodecException("truncated")`, `ByteReader.kt:76-78`), lying sample count, unknown tool
  ordinal (`toolOf` `:141` `getOrNull` → refuse, not coerce), unknown flag bit, empty/short multi-record
  files — spec Test 9 + my read of each throw site.
- **`predicted` can never round-trip:** decoder constructs samples without the flag
  (`:108`, default `false` per `PenSample.kt:37`) and `StrokeRecord.init` refuses predicted at
  encode time (`StrokeRecord.kt:28`) — matches spec Do-not 3.
- **Size formula (Test 10):** fixed fields `4+2+2+(2+len id)+(2+len brushId)+8+4+4+4` + per-sample
  `8+4+4+4+1` when no optional channels — matches `MIN_SAMPLE_BYTES = 21` (`:35`) and the exact-size
  test; 11/11 green this session.
- **Multi-record framing:** `u32 count`, per record `u32 byteLength` then exactly that many bytes
  (`:114-123`), decode refuses `n > remaining` before slicing (`:134-136`) — order-preserving (Test 8).
- **Pure common Kotlin:** no `java.*`/`ByteBuffer` in owner area (grep clean); little-endian by hand
  verified (`u16` writes low byte first `ByteWriter.kt:26-27`; `u16` reads low first
  `ByteReader.kt:24`; pinned by `byteWriterAndReaderAreLittleEndianAndGrow`).

## Bottom line

No send-back. M1–M4 are one pass for whoever authors **JB-0.08** (file ceiling + tail policy +
`decodeToString(throwOnInvalidSequence = true)` + `reserve` guard); M3 is also worth a standalone
one-word fix now since the comment sits in owner-area code. The codec keeps its promise: identical
strokes in, identical strokes out, loud failure on anything else.
