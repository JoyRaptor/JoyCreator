# JB-8.02 / JB-8.04 / JB-8.04b — adversarial audit (bunny)

Read-only. No code, spec, ROADMAP or ORCHESTRATOR_LOG was touched. No Gradle was run.
Branch `joy-creator`, working tree read as found (diverged; other agents' work untouched).

**The one-line answer: on the only real third-party file in the repo, JB-8.04 imports zero brushes
and crashes with an uncaught `StringIndexOutOfBoundsException`.** The proof was already on disk:
`%TEMP%\joybrush_real_probe.txt` (written 2026-10-02 08:32) line 9-10 reads
`krita\deevad-v8-2.bundle [3553039 B, 1 ms] / EXCEPTION StringIndexOutOfBoundsException: begin 143,
end 131, length 10491`. The suite was green. `RealFilesProbeTest` catches `Throwable` and prints.

Corpus: `joybrush/testdata-local/` — 4 real `.abr` (ag-psd, MIT), 1 real `.bundle`
(`krita/deevad-v8-2.bundle`, David Revoy's Brushkit V8.2, CC-BY), `reference/ag-psd-abr.ts`,
`reference/ag-psd-descriptor.ts`. **`testdata-local/procreate/` is empty — zero real Procreate bytes
exist in this repo.**

---

## Findings — worst first

**BLOCKER · every real Krita `.kpp` crashes the XML reader on its first CDATA section ·
`KritaImport.kt:1230-1233` with `KritaImport.kt:1298-1304` · input: any Krita preset, i.e. all 61 of the
61 `preset` chunks in `testdata-local/krita/deevad-v8-2.bundle` contain `<![CDATA[` ·
`skipCdata()` sets `at = end + 3` (line 1302) and then returns `end`; the caller on line 1232 then
evaluates `text.substring(at + 9, end)` with `at` already advanced, i.e.
`text.substring(end + 12, end)`, `begin > end` · the probe's `begin 143, end 131` is `end = 131`
exactly, and `length 10491` is the length of `deevad 1a eraser thin.kpp`'s preset text. A raw
`StringIndexOutOfBoundsException` leaves `convertKpp`/`convertBundle`, whose KDoc promises
`BrushException` · contradicts spec: **yes** — Decision 9 (a hand-written reader for the grammar
Krita writes); the row cannot import a single `.kpp` as built.

**BLOCKER · the engine gate reads an attribute Krita never writes, and matches ids Krita never writes ·
`KritaImport.kt:463-472` (`root.attr("id")`) and `KritaImport.kt:482-491` (`"Pixel"`,
`"ColorSmudge"`, …) · input: `testdata-local/krita/deevad-v8-2.bundle`; 61 of 61 real presets have root
`<Preset name="…" paintopid="…">` and the values observed are `paintbrush`, `colorsmudge`,
`deformbrush`, `duplicate`, `experimentbrush` — never `Pixel`, never `id` · outcome: every brush is
refused with `its preset XML has no <paintop id="…">`, so the row converts 0 of 64. Fixing the
attribute name alone does not help: `engineOf("paintbrush")` falls to `else -> throw` ·
contradicts spec: **yes** — Decision 10, which is wrong on its own terms and is the origin of the bug
(the spec says `<paintop id="…">`, the reader obeys, the fixtures obey: three-way circularity).

**BLOCKER · the Krita test fixtures are the reader's own guesses, and Krita writes none of them ·
`KritaImportTest.kt:1376` (`<paintop id="$engine">`), `:1379` (`<spacing mode="space" value="0.3"/>`),
`:1384` (`<smoothing mode="none" value="0.6"/>`), `:1387` (`<curve sensor="$sensor" name="$name"
commonCurve="$points"/>`), `:1393` (`<texture enabled= pattern= blendmode= …>`) · input: the real
preset `deevad 4a airbrush.kpp` · measured over 61 real presets: `sensor=` appears **0** times,
`<spacing` **0**, `<smoothing` **0**, `PixelSize` **0**, `<texture` **0**; Krita writes the flat form
`<param type="string" name="SizeSensor"><![CDATA[<params id="pressure"/><curve>0,0;1,1;</curve>]]]></param>`
· every mapping assertion in the 40 `KritaImportTest` tests is therefore an assertion about a format
that does not exist · contradicts spec: **yes** — R44 item 4; see `## Circular fixtures`.

**BLOCKER · every setting *value* in a real preset is silently discarded, which is the one thing the
sweep promises never happens · `KritaImport.kt:972-992` (`sweep`) walks `node.attributes` and recurses
into `node.children`, and never reads `KritaNode.text` · input: `deevad 4a airbrush.kpp`, whose preset
text is 8 678 characters, essentially all of it character data inside CDATA · the sweep's own KDoc
(`:965-971`) says "**Nothing is silent.** Every attribute of every element that no reader above claimed
is kept under its own path in `extensions`" — the *attributes* are kept, the values are not, and the
resulting brush has `size.base = 20` px (DEFAULT_DIAMETER_PX), `spacing = 0.04`,
`smoothing = 0.3`, no curves and no grain, with a warning that reads `kept raw in extensions` ·
contradicts spec: **yes** — the row's own stated contract, and R40's "nothing is dropped in silence".

**MAJOR · the licence of the one real bundle is lost, and the pack is CC-BY ·
`KritaImport.kt:372-381` reads `node.text` or `node.attr("text")` for tags in `LICENSE_TAGS =
{rights, license}` (`KritaImport.kt:1754`) · input: the real `meta.xml` in
`testdata-local/krita/deevad-v8-2.bundle`, which is `<meta:meta>` with **no `dc:rights` at all**; the
licence is `<meta:meta-userdefined meta:name="license" meta:value="CC-By with custom rules for
redistribution"/>` · tag-after-last-colon is `meta-userdefined` (not in the table) and the value sits
in `meta:value` (not `text`, an attribute name no ODF document uses) · outcome:
`BrushPreset.license = "unknown"` (`KritaImport.kt:1082`) on every brush of a pack whose licence
requires attribution · contradicts spec: **yes** — R40 / blueprint §5, and Decision 14.

**MAJOR · a lone `.kpp` throws away the author the file states · `KritaImport.kt:309-320` scans only
`preset` and `Title` · input: `deevad 4a airbrush.kpp` carries a 42-byte `zTXt` chunk with keyword
`author` (61 of the 64 real `.kpp` do), inflating to `…oy ( aka Deevad )` · outcome:
`KritaImport.convertKpp` on that file yields `author = ""` (`KritaImport.kt:1083`), so the brush is
re-attributed to nobody; only the bundle path recovers an author, and only by accident, via
`dc:author` in `meta.xml` · contradicts spec: **yes** — R40 / blueprint §5.

**MAJOR · a dangling dictionary-**key** reference crashes instead of refusing ·
`KeyedArchive.kt:519-527` indexes `values[index]` with an index that came from
`reference(cur, what)` (`KeyedArchive.kt:619-624`), which deliberately does **not** bounds-check, per
its own KDoc at `:612-617` ("present in `Resolver`") — but `BplistState.dictionary()`
(`KeyedArchive.kt:581-601`) resolves key references through `get()` at line 593, which never goes
through `Resolver` · input: a `bplist00` whose object table holds 3 objects and whose root dictionary's
first key reference is 200 · outcome: `ArrayIndexOutOfBoundsException` out of
`ProcreateImport.convertBrush`, which documents only `BrushException` ·
contradicts spec: **yes** — JB-8.02 Decision 7 ("a UID that points outside `$objects` **refuses**,
naming the index"); `KeyedArchiveTest.kt:70` covers only the *value* path, and `PlistFixture.Ref`
cannot express a dangling *key* ref at all.

**MAJOR · `MAX_OBJECTS` bounds table objects, not the cost of resolving them: a 64 MiB `.brush` makes
the reader allocate order 1 GB · `KeyedArchive.kt:294-324` (`Resolver.resolve`) has a depth cap and no
total-node or total-edge budget; `KeyedArchive.kt:603-610` (`references`) allows 200 000 references per
collection, checked against `MAX_OBJECTS` (200 000) not against a total · input: a `Brush.archive`
whose table holds ~160 array objects of 200 000 two-byte references each (≈64 MiB, inside
`MAX_ENTRY_BYTES`), all reachable from the root dictionary · outcome: `BplistState` materialises
~32 000 000 `PlistValue.Uid` instances plus their `ArrayList`s, and `Resolver` then materialises a
second full copy of every list; that is `OutOfMemoryError`, not a `BrushException`, on a phone ·
contradicts spec: **yes** — JB-8.02 Decision 11, whose budget list is what this row calls its
security boundary. Both `theObjectBudgetRefusesAtCapPlusOne` (`KeyedArchiveTest.kt:209`) and
`ProcreateImport`'s node counter (`KeyedArchive.kt:542`) count *objects*, so no test can see this.

**MAJOR · `BrushJson` has no unknown-key walk; `DocJson` does — confirmed independently ·
`BrushJson.kt:60-64` sets `ignoreUnknownKeys = true` and `BrushJson.kt:122-127` (`parse`) does no
walk at all; compare `DocJson.kt:112` (`refuseUnknownKeys`) called from `DocJson.kt:77` ·
input: a `brush.json` with `"version": 6` and one extra key, e.g. `"gleam": 0.5` · outcome:
`BrushJson.decodeChecked` returns a validated `BrushPreset` and says nothing, at the same version ·
contradicts spec: **yes** — R31/R3. This is the Lead's open finding, verified here from the code and
from a concrete file rather than restated. It also means the `extensions` keys both importers write
are only as durable as the field name.

**MINOR · strict UTF-8 silently truncates every supplementary code point ·
`PngChunks.kt:489` `sb.append(code.toChar())` — Kotlin's `Int.toChar()` masks to 16 bits ·
input: any `zTXt`/`iTXt` payload or XML plist containing a 4-byte sequence, e.g. U+1F600 ·
outcome: a private-use character U+F600 in a brush name, and two names that collide in `brushId`
(`ImportSupport.kt:139`). Krita packs are full of emoji · contradicts spec: **no**, though the
function's KDoc (`:485-487`) claims to check "every code point" for what a naive decoder drops and
checks only overlong and surrogate · the truncation is the third case and is unchecked.

**MINOR · a directory entry in a `.brushset` refuses the whole pack ·
`ProcreateImport.kt:624-630`: `"SomeUUID/".split('/')` yields a trailing empty segment, and
`unsafeReason` returns "its name holds an empty path segment", which line 592 throws for the **whole
archive** · input: a `.brushset` repacked with `zip -r`, which writes directory entries ·
outcome: every brush in the pack refused because one harmless entry name ends in `/` ·
contradicts spec: **no**. Not proven against Procreate's own writer — the reader is right about
traversal and wrong about a legal name.

**MINOR · the extensions cap is checked after the base64 encoder has already built the string ·
`ProcreateImport.kt:1193-1194` and `:1218-1219`, `KritaImport.kt:1008-1009` and `:1035-1036`:
`kritaBase64Of(stored.bytes)` runs to completion, and only then is
`extensionChars() + b64.length <= MAX_EXTENSION_BYTES` tested · input: a `.brush` whose `Shape.png` is
a 64 MiB STORED entry, which `MAX_ENTRY_BYTES` (`ProcreateImport.kt:55`) permits ·
outcome: an 85 Mi-character `StringBuilder` plus the 85 Mi-character `String` (~340 MB transient on
ART) for a value the next line throws away · contradicts spec: **no** — Decision 3/4's *behaviour* is
right; the cap is a correctness gate and not a memory bound, and it is checked after the allocation.

**MINOR · `convertBundle` can return two presets with the same id ·
`KritaImport.kt:266` + `:1781-1786` derive the name from the entry's base name and
`ImportSupport.brushId` is a pure function of prefix + name · input: a bundle holding both
`brushes/Ink.kpp` and `paintoppresets/Ink.kpp` · outcome: two `ImportResult`s with id `<prefix>.ink`,
no dedupe and no warning; one will overwrite the other on save · contradicts spec: **no**. Krita's
own default bundle has both directories; I could not check whether the names collide.

**MINOR · a test whose name promises a guard its body does not make · `KeyedArchiveTest.kt:278-289`,
`theFlatTableIsWhatAReferenceIndexesAndTheObjectsArrayIsWhatHoldsTheRoot` · the body asserts
`assertEquals("Round", archive.root().text("name"))` at line 285 and then **the identical assertion**
again at line 288 · the KDoc says "If the reader resolved against the `$objects` *contents* instead of
the flat table, every reference would be one out and this name would come back as something else or
not at all" — that is line 285's job; line 288 is a copy and proves nothing · contradicts spec: **no**.

**MINOR · the probe that was supposed to settle R44 item 4 cannot fail ·
`RealFilesProbeTest.kt:38-40` catches `Throwable` into a string, and `:44` writes it to
`java.io.tmpdir` · input: the real bundle, which throws · outcome: a green suite and a text file
nobody reads; the crash above has been in `%TEMP%\joybrush_real_probe.txt` since 2026-10-02 ·
contradicts spec: **yes** — JB-8.05's row states it "fails only on a crash or a hang", and it does
not fail on either.

**MINOR · a source-text grep dressed as a test · `KritaBundleInflateTest.kt:351-365`,
`noSourceFileStillSaysThisBuildHasNoInflater` · reads `KritaImport.kt` and `PngChunks.kt` as text and
asserts three strings are absent · it cannot see behaviour, it will go red on a correct comment that
mentions the old function name, and it found nothing while the CDATA crash sat in the same file ·
contradicts spec: **no**.

---

## Circular fixtures

**JB-8.04 (Krita) — the tests cannot fail against a real file. They are a closed loop.**
The spec writes `<paintop id="…">`; the reader reads `root.attr("id")`; the fixture at
`KritaImportTest.kt:1376` writes `<paintop id="$engine">`; the assertions confirm the reader parses
the fixture's own guess. Krita writes `<Preset paintopid="paintbrush">`. Three artefacts agree and all
three are wrong, so every one of the ~40 tests in `KritaImportTest` that concerns the mapping would
still be green after the reader was corrected to Krita's real grammar — and would go red only if the
*fixture* changed. Same shape five times over: `<spacing>`, `<smoothing>`, `<curve sensor= common=>`,
`<texture>`, and two invented `meta.xml` documents (`KritaImportTest.kt:351-360`, `:1244-1252`,
`KritaBundleInflateTest.kt` `META`) that use `<office:document-meta><dc:rights>` where Krita writes
`<meta:meta>` with no `dc:rights` at all. This is the `.abr` reader's death, repeated: one wrong
assumption, agreed to by the writer, the reader and the spec, and 40 green tests.
The two rows that are *not* circular are the zip ones: `KritaBundleInflateTest` writes its fixtures
with `java.util.zip.ZipOutputStream` (`:395-405`) and a real `Deflater`, which is an independent
writer, and `InflateTest` uses `java.util.zip.Deflater` as the oracle. `RealFilesProbeTest` would
have caught all of this and is structurally incapable of failing.

**JB-8.02 (Procreate) — the tests can fail, but only against the fixture writer.**
`PlistFixture` (`KeyedArchiveTest.kt:323-561`) is a hand-written bplist writer in `commonTest`, and it
is not the code under test, so mutation testing works: removing a bounds check does redden a test. But
it is built from the same reading of the open format notes as `KeyedArchive.kt`, and the file says so
itself (`KeyedArchiveTest.kt:21-27`: "a test that passes proves the reader agrees with a writer built
from the same reading of the open format notes"). The two things it flags as unprovable — the
trailer's five unused bytes, and that a `Uid` indexes the flat table — are exactly the two a real
`.brush` would settle, and there is no real `.brush` in the repo to settle them with. The zip half is
honest: `ProcreateImportTest.kt` is in `jvmTest` and its archives come from `java.util.zip`. The
bplist half is a closed loop and cannot be closed from inside this repo.

**JB-8.04b — the claim is true, and it is the one thing here that is not circular.**
There is exactly one DEFLATE implementation: `Inflate.kt:29` (`internal expect`) with
`Inflate.jvm.kt:24` (`internal actual`, `java.util.zip.Inflater`). `grep -i "inflate|Inflater|
java.util.zip|GZIPInputStream|ZipInputStream"` over every `.kt`/`.kts`/`.java` in `joybrush/`,
`joybrush-android/` and `studiokit/`, excluding `build/`, returns one `actual` and no other
implementation. There is exactly one pair of caps, both in `ImportSupport.kt` — `MAX_INFLATE_RATIO`
(line 83), `MAX_INFLATED_BYTES` (line 86) — one `maxOut` formula (`inflateMaxOut`, line 105), one
zlib probe (`looksLikeZlib`, line 116). All three callers go through it: `ProcreateImport.kt:502`,
`KritaImport.kt:1473`, `PngChunks.kt:426`. No second copy, no second number. Its `maxOut` is checked
*before* the bytes are written (`Inflate.jvm.kt:50-52`), which is the order that matters. The one
thing I would still want is a real-file check: 3 of the 64 real `.kpp` in the bundle store `preset` as
`zTXt`, so the reversed decision is load-bearing on real bytes and untested against them.

---

## Not reproduced

- **A second DEFLATE implementation or a second copy of the caps.** Not there. One `actual`, one
  `MAX_INFLATE_RATIO`, one `MAX_INFLATED_BYTES`, one `inflateMaxOut`, one `looksLikeZlib`.
- **An `Int` wrap in either ratio test.** `ProcreateImport.kt:425` and `KritaImport.kt:1423` both
  compute `entry.uncompressedSize > entry.compressedSize * MAX_INFLATE_RATIO` with the constant as a
  `Long`, so the product cannot wrap. `KritaImportTest.kt:224-228` pins the `> Int.MAX_VALUE` case.
- **An `Int` wrap in a PNG chunk length.** `PngChunks.kt:199-213` assembles the `u32` as `Long`,
  compares against `MAX_PNG_CHUNK_BYTES`, and truncates once, afterwards.
- **An `Int` wrap in `extensionChars()`** (`ProcreateImport.kt:1287-1290`,
  `KritaImport.kt:1099-1103`). Every value added is either a substring of XML bounded by
  `MAX_STRING_BYTES` or a base64 string bounded by `MAX_ENTRY_BYTES`, so the total cannot reach
  `Int.MAX_VALUE`.
- **The `convertBrush` / `convertBrushSet` divide collapsing.** ProcreateImport holds it:
  container faults (`Zip.read`, `brushFolders`, `MAX_BRUSHES`) throw outside the loop
  (`ProcreateImport.kt:155-178`) and brush faults become `RefusedBrush` inside it. KritaImport holds
  it too: `readKppFile` throws outside the per-entry `try` (`KritaImport.kt:275-279`) and the brush
  becomes a `RefusedBrush`. Both are covered by tests that would redden if the `try` were widened —
  `ProcreateImportTest.kt:429`, `:520`; `KritaImportTest.kt:553`.
- **A zip bomb getting through either reader.** Both check the entry's own bytes, then the ratio, then
  the ceiling, all before `inflateRaw` is called, and both compute the ratio in `Long`.
- **`testdata-local/` being committable.** It is ignored: `.gitignore:90` (`joybrush/testdata-local/`),
  `git check-ignore` confirms, `git ls-files joybrush/testdata-local` is empty.
- **A duplicated id from `brushId` on a *single* importer's realistic input.** Only the bundle path can
  produce one (see the MINOR above).
- **The KDoc at `KeyedArchive.kt:487-492`** claiming a flat table is `$objects` — this is the one place
  the reader and the fixture could both be wrong, and I found no internal contradiction. It remains
  unproven against a real file.

---

## What I could not check

- **Anything about Procreate's real bytes.** `testdata-local/procreate/` holds 0 files. Every claim
  about `.brush` / `.brushset` in this audit is a claim about the code and its fixtures, not about
  Procreate. Specifically unverified: the `bplist00` trailer's five unused bytes, `$objects[1]` being
  the root, the `"{x, y}"` curve-point spelling, `brushset.plist`'s `brushes[].brushID` shape
  (`ProcreateImport.kt:254-263`), and whether Procreate ever writes zip directory entries.
- **The `.abr` reference TypeScript** (`ag-psd-abr.ts`, `ag-psd-descriptor.ts`). JB-8.01b has been
  proved against the four real `.abr` files; that work is done and I did not re-open it. JB-8.02 and
  JB-8.04 do not read `.abr`, so it was not the oracle for either row I reviewed.
- **Whether a real Krita bundle's names collide between `brushes/` and `paintoppresets/`.** The one
  bundle I have puts all 64 `.kpp` under `paintoppresets/` and none under `brushes/`, so the
  duplicate-id path is unexercised by real bytes.
- **Whether `MAX_BASE64_CHARS` is reachable.**
  `KritaImportTest.kt:1072` (`theBase64CapIsUnreachableBecauseItIsTheSameNumberAsTheStringCap`) says it
  is not, because it equals `MAX_PNG_STRING_BYTES`. In real presets the base64 is element text inside
  CDATA, not an attribute, so the cap really is bypassed — but the code path is unreachable for a
  different reason than the test gives, and I could not construct the file that reaches it.
- **Whether `KritaXml` truncation would surface as a refusal or a crash in the app**, because I did not
  run Gradle and did not trace the Android caller of `convertBundle` / `convertKpp`.
- **Performance.** The `KritaNode.all()` implementation (`KritaImport.kt:1142`) rebuilds the whole
  node list on each call and is called three times per brush (`curves()` twice, `settings()` once), so
  it is O(depth × nodes) per brush. With `MAX_XML_NODES` at 200 000 this is ugly, but I did not
  measure it and I am not filing it.

---

## Ledger

- Read: `ProcreateImport.kt`, `KritaImport.kt`, `KeyedArchive.kt`, `PngChunks.kt`, `ImportSupport.kt`,
  `Inflate.kt`, `Inflate.jvm.kt`, `BrushJson.kt`, `DocJson.kt`, `BrushPreset.kt`, and the six test
  files in `core/src/{commonTest,jvmTest}/…/brush/imports/`.
- Real bytes opened (read-only, nothing copied into the tree):
  `testdata-local/krita/deevad-v8-2.bundle` — 96 entries, 64 `.kpp`, chunk walk on every one,
  `meta.xml` read, one full preset text read. Extraction went to
  `%TEMP%\opencode\jb\`, never into the repo.
- Nothing was edited except this file. No commit, no checkout, no reset, no stash, no Gradle.