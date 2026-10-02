# JB-0.02d — `DocJson` refuses a file carrying keys this build does not know

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 Ready — Q0 was a BLOCKER (Decisions 2 and 4 were mutually unsatisfiable) and is now **RULED by the orchestrator, 2026-09-29: `ignoreUnknownKeys` STAYS `true` and the scan is the whole guard. PROVISIONAL — Claude to confirm.** Q2 (the refusal wording) and Q3 (one offender or all) remain the Lead's. |
| **Depends on** | JB-0.02 (`DocJson`, Built) · JB-0.02b (the version rule, 🟩 Reviewed) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocJson.kt` · `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/DocModelTest.kt` — **nothing else** |
| **Estimated size** | ~60 lines of code, ~90 lines of tests |

> **This row does NOT unblock additive fields by itself.** R31 freezes them until this lands *and* the
> Lead says so. It closes the hole; it does not lift the freeze.

## Goal

Open a `document.json`, get a drawing back with **nothing thrown away**. Today `DocJson.decode` is
told to ignore keys it does not know, drops them silently, and hands back a `JbDocument` that no
longer knows those keys ever existed — so the next autosave writes the file back **without** them
and stamps the current `DOC_VERSION` on the result. A person opens a drawing, Joy Brush silently
strips a setting it does not understand, and the setting is gone for good, under a version number
that says the file is current. This row makes the parser stop: an unknown key is a `DocException`
naming the key, at the door, before anything is dropped.

## Contract (verbatim)

### `core/.../doc/DocJson.kt` — the whole object, as landed

```kotlin
package cc.joycreator.joybrush.core.doc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Thrown by [DocJson.decode] when a `document.json` cannot be read. */
class DocException(message: String) : Exception(message)

/**
 * `document.json` — the one file that says what a Joy Brush drawing IS.
 *
 * It is written pretty with keys in declaration order and states its defaults outright, so a person
 * opening it can see the whole shape without chasing a schema, and so a diff of two documents shows
 * a change of value rather than a change of presence. Reading IGNORES keys it does not know, so a
 * document written by a later Joy Brush still opens here instead of failing — that is the whole point
 * of [DOC_VERSION] being checked by [DocOps.validate] and not by the parser.
 *
 * Encoding is deterministic: the same document always produces byte-identical JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
object DocJson {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(doc: JbDocument): String = json.encodeToString(JbDocument.serializer(), canonical(doc))

    /**
     * Puts `frameCel` in key order before writing.
     *
     * Without this, a document saved twice can differ byte for byte purely because a map was built in
     * a different order — and a `.joybrush` is a file people diff, sync and ask "did this change?".
     * Sorting loses nothing: play order is `Board.frames`, not the order of these keys. Object keys
     * are already stable, because kotlinx writes properties in declaration order.
     */
    private fun canonical(doc: JbDocument): JbDocument {
        if (doc.layers.none { it.frameCel.size > 1 }) return doc
        return doc.copy(
            layers = doc.layers.map { l ->
                if (l.frameCel.size < 2) l
                else l.copy(frameCel = l.frameCel.entries.sortedBy { it.key }.associate { it.key to it.value })
            },
        )
    }

    /**
     * Decoding never validates. A document from a newer Joy Brush must still decode, so that
     * [DocOps.validate] can report the version problem in words instead of the caller getting a
     * parse error it cannot explain.
     */
    fun decode(text: String): JbDocument = try {
        json.decodeFromString(JbDocument.serializer(), text)
    } catch (e: SerializationException) {
        throw DocException("document.json cannot be read: ${e.message}")
    } catch (e: IllegalArgumentException) {
        // SerializationException and JsonDecodingException both land here.
        throw DocException("document.json cannot be read: ${e.message}")
    }
}
```

**Everything below is what changes and must not change otherwise.** `canonical`, `encode`, the pretty
settings and the `DocException` type all stay exactly as above.

### `core/.../doc/DocModel.kt` — the key universe, as landed (READ ONLY; not in the owner area)

Every `@Serializable` class in `document.json`, with its declared field names. This is the whole of
"a key this build knows". No field is added, removed or renamed by this row.

```kotlin
const val DOC_FORMAT = "joybrush.document"
const val DOC_VERSION = 2

@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable data class Paper(
    val color: String = "#FFFFFF",          // #RRGGBB
    val textureId: String? = null,          // a grain asset id, null = plain
    val textureScale: Float = 1f,
    val includeInExport: Boolean = false,   // the "Include paper" checkbox default
)

@Serializable enum class BoardKind { CANVAS, ANIMATION, SPRITE, PUPPET, CHARACTER }

@Serializable data class Frame(
    val id: String,
    val holdFrames: Int = 1,                // shown for this many ticks of the board's fps
)

@Serializable data class SpriteGrid(val cols: Int, val rows: Int, val cellW: Int, val cellH: Int)

@Serializable data class Board(
    val id: String,
    val name: String,
    val kind: BoardKind,
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                   // ANIMATION only
    val frames: List<Frame> = emptyList(),  // ANIMATION only, in play order
    val grid: SpriteGrid? = null,           // SPRITE only
)

@Serializable enum class LayerKind { PAINT, INK }

@Serializable enum class BlendMode { /* 27 constants — see DocModel.kt */ }

@Serializable data class Cel(
    val id: String,
    val tiles: List<String> = emptyList(),  // PAINT: tile keys "tx_ty" that exist (sparse)
    val strokesFile: String? = null,        // INK: path inside the document zip
)

@Serializable data class Layer(
    val id: String,
    val name: String,
    val kind: LayerKind,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.NORMAL,
    val animatedIn: String? = null,         // board id, or null = static
    val cels: List<Cel>,                    // static: exactly 1; animated: >= 1
    val frameCel: Map<String, String> = emptyMap(), // animated: frame id -> cel id
)

@Serializable data class JbDocument(
    val format: String = DOC_FORMAT,
    val version: Int = DOC_VERSION,
    val id: String,
    val name: String,
    val paper: Paper = Paper(),
    val boards: List<Board>,
    val layers: List<Layer>,                // bottom -> top
    val activeLayerId: String? = null,
    val activeBoardId: String? = null,
)
```

**The known-key table, written out, so the spec is executable whichever mechanism is used:**

| Class | Keys, in declaration order |
|---|---|
| root (`JbDocument`) | `format` `version` `id` `name` `paper` `boards` `layers` `activeLayerId` `activeBoardId` |
| `paper` (`Paper`) | `color` `textureId` `textureScale` `includeInExport` `lookId` `tint` `show` `bite` `light` |
| `boards[]` (`Board`) | `id` `name` `kind` `rect` `clipToBoard` `fps` `frames` `grid` `tiled` `currentFrameId` `locked` |
| `boards[].rect` (`RectPx`) | `x` `y` `w` `h` |
| `boards[].frames[]` (`Frame`) | `id` `holdFrames` |
| `boards[].grid` (`SpriteGrid`) | `cols` `rows` `cellW` `cellH` |
| `layers[]` (`Layer`) | `id` `name` `kind` `visible` `locked` `opacity` `blend` `animatedIn` `cels` `frameCel` `mask` `clip` `sharedCelId` `regions` |
| `layers[].regions[]` (`RegionFrames`, v6) | `boardId` `frameCel` `held` |
| `layers[].cels[]` (`Cel`) | `id` `tiles` `strokesFile` |
| `layers[].mask` (`Cel`) | `id` `tiles` `strokesFile` |

`kind` and `blend` are enums, `tiles` and `frames` and `cels` and `boards` and `layers` are lists,
`frameCel` is a map whose KEYS are free-form frame ids (its VALUES are cel ids — a value, not a key,
so the value is not a "key" question). `grid`, `textureId`, `strokesFile`, `animatedIn`,
`activeLayerId`, `activeBoardId` are nullable and may be absent.

### `core/.../doc/DocOps.kt:59-61` — the sentence that must keep winning

```kotlin
        if (doc.version > DOC_VERSION) {
            out += "document is from a newer Joy Brush (version ${doc.version}, this build reads $DOC_VERSION)"
        }
```

### `commonTest/.../doc/DocModelTest.kt:118-141` — the two tests this row is between

```kotlin
    // ---------- 3. reading the future ----------

    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        val doc = fresh()
        val json = DocJson.encode(doc)
            .replaceFirst("\"format\"", "\"future\": 1,\n  \"format\"")
            .replaceFirst("\"visible\"", "\"fromTheFuture\": \"yes\",\n    \"visible\"")
        val back = DocJson.decode(json)
        assertEquals(doc, back)
    }

    @Test
    fun aNewerVersionStillDecodesAndIsReportedInWords() {
        val doc = fresh().copy(version = DOC_VERSION + 1)
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(DOC_VERSION + 1, back.version, "decoding must not refuse a newer document")
        val problems = DocOps.validate(back)
        assertEquals(1, problems.size, "got $problems")
        assertTrue(
            problems.single().contains("newer Joy Brush"),
            "the message must say why, got: ${problems.single()}",
        )
    }
```

**`unknownKeysAreIgnoredAtEveryLevel` is the assertion of the old behaviour. It is deleted, not
updated to still pass.** `aNewerVersionStillDecodesAndIsReportedInWords` is not touched and must stay
green.

### `commonTest/.../doc/DocModelTest.kt:145-149` — a third test this row sits between, which the
### first draft did not name

```kotlin
    @Test
    fun unreadableJsonThrowsDocException() {
        assertFailsWith<DocException> { DocJson.decode("{ not json") }
        assertFailsWith<DocException> { DocJson.decode("[]") }
    }
```

**It must stay green, and it constrains where the walk may sit.** (Cross-review, 2026-09-29.)

- `"{ not json"` is not JSON at all, so any parse of it — the walk's or the typed decode's — throws
  `SerializationException`. **The walk must therefore be inside the existing `try`**, not in front of
  it. Placed before, the exception escapes `decode` uncaught and a person gets a raw library message
  where they should get a `DocException`; that is exactly what test 10 exists to prevent.
- `"[]"` **is** valid JSON, and its root is a `JsonArray`, not a `JsonObject`. Decision 2's "read the
  root `version` off the parsed `JsonObject`" has no object to read. **A root that is not a
  `JsonObject` must fall straight through to the typed decode untouched**, which throws today's message
  and the test passes. A `as JsonObject` cast in the walk turns this red.

### `androidkit/.../io/JbArchive.kt:381-386` — the only production caller, and it needs no change

```kotlin
            DocJson.decode(docBytes.toString(Charsets.UTF_8))
        } catch (e: DocException) {
            // DocJson.decode states the problem in words. This only says which file it was reading.
            throw JbArchiveException("\"$DOCUMENT_NAME\" cannot be read: ${e.message ?: "it is not JSON the document shape allows"}")
        }
```

The refusal message therefore reaches a person as
`"document.json" cannot be read: <the new sentence>`, with no edit in `androidkit`. The archive is
the one way a `document.json` enters the app, so the refusal at `decode` is the refusal everywhere.

## Decisions already made

1. **The check lives in `DocJson.decode`, on the JSON tree, BEFORE the typed decode.**
   *Why:* the moment kotlinx builds a `JbDocument`, an unknown key is gone — there is nothing left to
   notice and nothing to name. `DocOps.validate` receives a document that has already been
   flattened, and `DocJson.encode` will then write that flattened document back under the current
   `DOC_VERSION`. The door has to be at the parse. This is the trap in this row and the reason the
   Lead's ruling says "DocJson only".

2. **A file whose root `version` is GREATER than `DOC_VERSION` is not scanned for unknown keys.**
   *Why:* that file is from the future and `DocOps.validate` already says so, in better words than
   any key sentence could be. Scanning first would pre-empt it and hand a person "this file has
   `audio`, which this version of Joy Brush does not know" instead of "from a newer Joy Brush",
   which sends them looking for a build that exists. Read the root `version` off the parsed
   `JsonObject` first; if it is `> DOC_VERSION`, fall through to today's behaviour untouched.

   **R44 item 5 — THE SENTENCE ABOVE WAS WRONG IN ITS TAIL, and the CODE was right.** The original
   text also let a file whose `version` is **absent or not an integer** fall through unscanned, on
   the reasoning that such a file has no usable version and so might as well be passed on. That
   reasoning is backwards, and it is the same failure this row exists to stop: a file with no
   `version` at all is a file this build **cannot place in time**, and if it also carries a key this
   build does not know, the one sentence that would have told the person so is the one thing the
   scan was for. Scattering it silently is exactly the "silently dropped" bug R31 was raised about,
   only reached by a different road.

   **So the rule is narrower and the scan is wider:** **only** a root `version` that is a JSON
   integer strictly greater than `DOC_VERSION` skips the scan. **Absent, non-integer, `null`,
   `1.5`, a string `"2"`, or anything else IS scanned** and, if it carries unknown keys, is refused
   in words. The landed code already does this — the audit confirmed the code is right — and this
   decision now matches it.

   There is a test for the shape that matters: a v1 document (a real integer, below
   `DOC_VERSION`) carrying an unknown key is **refused**, which is what "absent and non-integer are
   scanned" buys alongside a malformed one. A reader who only tested the missing-`version` case
   would not notice the difference.

3. **The known-key set is the declared property names of the class being walked.**
   *Why:* the alternative is a hand-written table, and a hand-written table of a data class drifts
   the first time somebody adds a field and forgets it — which is the exact failure this row exists to
   stop. **PROVISIONAL — Claude to confirm**, because it is a file-format decision (see Questions 1).
   Either mechanism must produce the table printed above, so the Tests section below is written to
   hold under both. The intended mechanism is the serial descriptor
   (`JbDocument.serializer().descriptor`), which is multiplatform and available in
   `kotlinx-serialization-json:1.8.1`; a hand-written table with a test that pins it against
   `DocJson.encode` output is the fallback. A **JVM-only** mechanism (reflection, `::class.memberProperties`)
   is forbidden either way — it would break the iOS door in `OWNER_CONSTRAINTS.md`.
   *(Cross-review, 2026-09-29: the 1.8.1 claim is verified, not assumed. The build pins
   `kotlinx-serialization-json:1.8.1` (`joybrush/core/build.gradle.kts:31`), and
   `SerialDescriptor.elementNames` and `getElementDescriptor` are both present in the 1.8.1 core jar
   — `elementNames` as the extension `SerialDescriptorKt.getElementNames`, and unlike `getNullable` it
   carries no `$annotations` sibling, so it is **not** `@ExperimentalSerializationApi`. The
   `@OptIn(ExperimentalSerializationApi::class)` already on the `DocJson` object covers the rest. One
   note the first draft did not give: the walk needs `StructureKind.CLASS` / `LIST` / `MAP` to know
   when to stop — a `MAP` descriptor's keys are data, not key names, which is the `frameCel` case.)*

4. ✅ **RULED by the orchestrator 2026-09-29 (PROVISIONAL — Claude to confirm): `ignoreUnknownKeys`
   STAYS `true`, and this scan is the whole guard. Decision 4 as originally drafted ("becomes
   `false`") is WITHDRAWN.** The first draft contradicted its own Decision 2 and test 7; the
   cross-reviewer found that and declined to rule it, and the ruling is in Questions → Q0.
   **The builder's one obligation here is to make the scan exhaustive**, because with the flag `true`
   a hole in the walk is silent — and silent is the exact failure R31 exists to stop. So: walk every
   `JsonObject` in the tree, including the ones nested inside arrays, and add a test that a key buried
   at a known depth is still caught. That test is the fail-closed guarantee `false` would have given,
   bought without a second code path.
   library. Both raise `DocException`; only one is safe against a hole in Decision 3.
   **The `false` half of that is what costs the version gate**, and that is the trade the Lead has to
   make, not this row:
   - **(a) keep `true`.** The scan is the whole guard. Cheapest, and test 7 passes as written. The
     risk is a hole in the walk failing open.
   - **(b) flip to `false`, as first drafted.** Fails closed, but the typed decode then refuses an
     unknown key **in every file, including one from a newer Joy Brush** — which is the common case
     for the very files this row exists to protect. Test 7 becomes unpassable, Decision 2's gate
     becomes unreachable, and R3's promise ("an old app says *from a newer Joy Brush*") is replaced by
     "cannot be read: Encountered an unknown key 'audio'" — a worse sentence that sends people
     looking for a bug.
   - **(c) two `Json` instances** — strict for the same-version path, lenient for the newer-version
     path — gets both, at the cost of one extra field and a branch. The cross-review has **not**
     chosen between these: which sentence a person sees for a file from the future is a file-format
     decision and the Lead's.
   *Until the Lead rules, a builder must not start: any of (a), (b), (c) makes a different test in
   this spec pass or fail.*

5. **One `DocException`; every unknown key found is in it, joined `"; "`.**
   *Why:* there is no object to hold a list, because no object was built. `BrushJson.decodeChecked`
   already establishes the house shape for exactly this situation — one exception, all the problems
   joined with `"; "`. A person fixing a file should see the whole list once. **PROVISIONAL — Claude
   to confirm** (Questions 2).

6. **The message names the key AND where it was found, in one sentence.** Shape (exact wording is
   Question 3): `this file has "audio", which this version of Joy Brush does not know, at
   $.boards[0]`. The path matters — `frameCel` and `cels` keys recur at every level, and "a key called
   `id`" with no location is not an actionable message. **PROVISIONAL.**

7. **`encode` is not changed at all.** *Why:* the data loss happened at the door, so closing the door
   closes it. A document built in memory (a new document, or one this build just made) never went
   through `decode` and cannot be missing a key it did not have.

8. **`DOC_VERSION` is NOT bumped by this row.** *Why:* R31's trigger is "ANY new serialised field", and
   R3's is a new enum constant. This row adds no field and no constant — it changes what the reader
   *accepts*, not what the file can *say*. Bumping would make every file an older build had already
   written fail in that build with "from a newer Joy Brush", which is a real cost (the owner's own
   past drawings) bought for nothing.
   **The full cost of a bump, verified (cross-review):** it is **two** files outside this row's owner
   area, not one. (i) `DocModel.kt:7`, `const val DOC_VERSION = 2`; and (ii)
   `EnumFreezeTest.theVersionsTheNamesWereWrittenFor` at line 94, `assertEquals(2, DOC_VERSION)`, which
   goes red. `DocModel.kt` is named in the "Do not" list; `EnumFreezeTest.kt` was not, and the Lead
   should know both files are in play before ruling. Per R30 this spec deliberately writes no number.
   **PROVISIONAL — Claude to confirm** (Questions 4).

9. **The row's KDoc on `DocJson` is rewritten, because the landed one now states the opposite.**
   The lines *"Reading IGNORES keys it does not know, so a document written by a later Joy Brush
   still opens here instead of failing"* and *"Decoding never validates"* must both go. The new text
   must say: a **same-version** file carrying a key this build does not know is refused by name,
   because a key that is dropped is a key that is lost on the next save; and a file from a **newer**
   version is still read, so `DocOps.validate` can say so in words.

10. **`BrushJson` is not touched.** *Why:* brushes are a separate door with a separate ruling, the
    Lead's row is titled "DocJson only", and `brush.json`'s ignoring policy is load-bearing in a way
    `document.json`'s is not (importer extensions).

## Steps

1. Write the tests in `DocModelTest.kt` first — all of the Tests section below, including the deletion
   of `unknownKeysAreIgnoredAtEveryLevel`. Run the command; they must be red.
2. Rewrite the `DocJson` class KDoc (Decision 9).
3. Add the unknown-key walk and wire it into `decode`, before the typed decode, behind the version
   gate of Decision 2. Flip `ignoreUnknownKeys` to `false` (Decision 4).
4. Run the command. Green.
5. Grep the whole repo for anything else that decodes `document.json` and confirm it goes through
   `DocJson.decode`. The only caller is `JbArchive.documentFrom` (verified above) — if you find
   another, **stop and put it in Questions**; do not edit it.

## Tests

All in `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/DocModelTest.kt`.
`fresh()` and `richDocument()` are the existing private helpers in that file — reuse them.

**DELETE `unknownKeysAreIgnoredAtEveryLevel` (line 121).** It asserts the behaviour this row removes.
Do not rewrite it into something that still passes.

| # | Test name | Input → expected |
|---|---|---|
| 1 | `aFileWithNoKeysThisBuildDoesNotKnowStillDecodes` | `DocJson.decode(DocJson.encode(fresh()))` → the same document. **The control**: without it, the tests below could pass because the fixture was broken. |
| 2 | `anUnknownKeyAtTheRootIsRefusedAndNamed` | `encode(fresh()).replaceFirst("\"format\"", "\"future\": 1,\n  \"format\"")` → `DocException`, and the message contains `future`. |
| 3 | `anUnknownKeyInsideALayerIsRefusedAndNamed` | `encode(fresh()).replaceFirst("\"visible\"", "\"fromTheFuture\": \"yes\",\n    \"visible\"")` → `DocException`, message contains `fromTheFuture`. |
| 4 | `anUnknownKeyInsideABoardIsRefused` | insert a key into a board object of `richDocument()` → `DocException`, message names it. |
| 5 | `anUnknownKeyAtTheDeepestLevelIsRefused` | one inside a `Cel` (`layers[0].cels[0]`) and one inside a `Frame` (`boards[…].frames[0]`) → `DocException` naming both (or at least the first). These are two levels down; a walk that only checks the root passes 2–4 and fails here. |
| 6 | `everyKeyInAFileThisBuildWritesIsAKnownKey` | Walk `DocJson.encode(richDocument())` as a JSON tree, collect the key at every object, and assert the set equals the eight rows of the table in the Contract. **This is the drift guard**: a field added to `DocModel.kt` without the walker knowing it turns this red. It is written against the *file*, not against the implementation, so it holds under either mechanism in Decision 3. **One trap, stated because it is the obvious wrong turn: a JSON object's *members* are only keys where the value is an object or an array. `frameCel` is a `Map<String, String>`, so `f1`/`f2`/`f3` are DATA (cel ids keyed by frame id), not keys, and collecting them makes this test red for a correct implementation. The Contract section says so; this row says it again because a "collect the key at every object" instruction without it is a trap.** |
| 7 | `aNewerVersionWithAnUnknownKeyStillDecodesAndIsStillReportedAsNewer` | `fresh().copy(version = DOC_VERSION + 1)`, encode, insert an unknown key at the root → **decodes** (no throw), and `DocOps.validate` still says `newer Joy Brush`. This is Decision 2, and it is the test that would catch a scan placed before the version gate. |
| 8 | `aVersionOneDocumentStillOpens` | The `"version": 1` fixture from `EnumFreezeTest.documentWith` (all keys known) → decodes. **An older file must not be refused**: refusing old files is the other way this rule could break a person's drawings. **It has to be copied into this file.** `documentWith` is `private` to `EnumFreezeTest` in a different file that is NOT in the owner area, so it cannot be called from here and must not be edited to expose it. Copy the text verbatim into `DocModelTest.kt` as a private helper of its own — the cross-review verified every key in it is known, and that its `"version": 1` is what makes it the case this test needs:<br>`{ "format": "joybrush.document", "version": 1, "id": "d", "name": "D", "boards": [ { "id": "b", "name": "B", "kind": "CANVAS", "rect": { "x": 0, "y": 0, "w": 8, "h": 8 } } ], "layers": [ { "id": "l", "name": "L", "kind": "PAINT", "blend": "NORMAL", "cels": [ { "id": "c" } ] } ] }` |
| 9 | `nothingIsEverReSavedWithoutAKeyItDidNotUnderstand` | Take a same-version file carrying an unknown key and try to get a document out of it at all: `assertFailsWith<DocException>`. The R31 bug is that a document *came back*; this test names the bug and fails if any future change lets one through again. |
| 10 | `aFileWithAnUnknownKeyStillRefusesWithADocExceptionNotALibraryError` | Same as 2, and the throwable is `DocException` — not `SerializationException`, not `IllegalArgumentException` escaping. `JbArchive` catches only `DocException` (line 383), so anything else crashes the archive read instead of being reported. |

**Command:** `./gradlew -p joybrush :core:jvmTest` — **0 failures**, and the count is higher than it
is on `main` by the number of tests added minus the one deleted. Paste the output in the report.

## Do not

- **Do not put this in `DocOps.validate`.** By the time `validate` is called the key is already gone.
  A check there can only ever say "the document looks fine", which is what happens today. This is
  the single most likely wrong move in this row, and the Lead's title ("`DocJson`") is the warning.
- **Do not try to make `unknownKeysAreIgnoredAtEveryLevel` pass.** It is the old contract. It is
  deleted, and its replacement is test 2, which uses the same `replaceFirst` trick.
- **Do not refuse a file from a NEWER version at the parse.** `DocOps` owns that sentence
  (`"document is from a newer Joy Brush (version N, this build reads M)"`), and it is the better one.
- **Do not leave `ignoreUnknownKeys = true`** as the only line of defence — but note the first draft
  made that absolute, and it is the OPEN QUESTION rather than a rule (Decision 4, Question 0). If the
  Lead rules (a), this bullet inverts. Until then the honest statement is the narrower one: a guard
  that can fail open is not a guard, and a guard that fails closed *everywhere* breaks the version
  sentence.
- **Do not edit `DocModel.kt`.** No field is added, removed or renamed. It is not in the owner area.
  If you believe a field must move, **stop** and write the question.
- **Do not edit `DocOps.kt`, `JbArchive.kt`, `EnumFreezeTest.kt` or anything in `androidkit`.**
  The refusal needs no change at any of them, and `EnumFreezeTest` is a different file's row.
- **Do not bump `DOC_VERSION`, and do not write a version number in this spec** (R30, Decision 8).
- **Do not use JVM-only reflection** to discover the fields. No `kotlin-reflect` is on the classpath
  and the iOS door must stay open.
- **Do not widen this to `BrushJson`,** the importers, or any other file format.
- **Do not "helpfully" keep a copy of the unknown keys** and write them back on save. R31 chose
  refusal, not preservation. Preserving is a different row with a different contract.
- **Do not report this row as "additive fields are safe now."** The freeze is the Lead's to lift.

## Definition of done

- [ ] `unknownKeysAreIgnoredAtEveryLevel` is gone; tests 1–10 exist and the deletion is in the diff.
- [ ] `./gradlew -p joybrush :core:jvmTest` — 0 failures. **Paste the output.**
- [ ] Test 7 (a newer file with an unknown key still reaches `DocOps`) is green — this is the one
      that proves the version gate is in the right place.
- [ ] The `DocJson` KDoc no longer claims reading ignores unknown keys.
- [ ] `git status --short` shows **only** `DocJson.kt` and `DocModelTest.kt` changed. **Paste it.**
- [ ] Committed as `JB-0.02d: refuse a document with keys this build does not know`; pushed.
- [ ] ROADMAP row → 🟧 Built, and **a re-review is requested** (this edits a 🟩 Reviewed row's
      behaviour, and R31 changed a ruling a review had cleared).

## Stop rule

**Stop and write the question in Questions if any of these is true. Do not guess:**

- The walk and the descriptor disagree about a key — for example a class whose descriptor names a
  field the file does not write, or vice versa. A disagreement here is a real fact about the format
  and is worth more than a row.
- You cannot make a file with an unknown key be refused **without** also refusing test 1, test 7 or
  test 8. Those three are the whole safety net; a rule that cannot tell an unknown key from a newer
  file from an older file is not this row, it is a wrong rule.
- You are tempted to touch `DocModel.kt`, `DocOps.kt` or `androidkit` to make a test pass.
- The exact wording in Decision 6 matters to you and differs from the question below. **Wait for the
  Lead.** A sentence is the cheapest thing in this project to change and the most expensive to
  change twice.
- `aNewerVersionStillDecodesAndIsReportedInWords` (the landed test, line 131) goes red. That is not
  yours to fix; it means the version gate is wrong, and it is the Lead's call whether the gate moves.
- **Test 7 goes red with the message `Encountered an unknown key`.** That is not a bug in your walk:
  it is Question 0 below, already known before you started, and the fix is the Lead's ruling, not a
  change to the walk. Do not "fix" it by skipping the scan, by special-casing the version, or by
  turning `ignoreUnknownKeys` back on to make it pass. Report it and stop.

## Questions

### For the cross-reviewer — **this spec stays at 📝 Draft because of Q0**

0. ⛔ **BLOCKER. `ignoreUnknownKeys` — keep it `true`, or flip it to `false`? The first draft's
   Decisions 2 and 4 contradict each other, and no implementation can satisfy both.**
   **Cross-review finding, 2026-09-29 — this is the reason the row is not 🟦 Ready.**
   The chain, in full, so the ruling is one line:
   - Decision 2 says a file whose root `version` is greater than `DOC_VERSION` is **not scanned**.
   - Decision 4 says `ignoreUnknownKeys` becomes **`false`**, "so a hole in the walk fails closed".
   - With `ignoreUnknownKeys = false`, kotlinx.serialization's typed decode **refuses an unknown key
     in every file** — that is what the flag means, and it is why the landed
     `unknownKeysAreIgnoredAtEveryLevel` passes today only because the flag is `true`.
   - Therefore a file that is **both** newer than this build **and** carries a key this build does not
     know — which is the *normal* shape of a file from a later Joy Brush, and exactly the case R31 is
     about — is refused by the library before `DocOps.validate` ever sees it. **Test 7 is unpassable.**
   - R3's promise is broken at the same time: an old app handed a new file says *"cannot be read:
     Encountered an unknown key 'audio'"* instead of *"from a newer Joy Brush"*. R31 says the file must
     be refused **in words**, and R3 says which words.
   The three ways out, and what each costs — the Lead picks one:
   - **(a) keep `true`.** The scan is the guard, whole and alone. Test 7 passes as written; Decision 2's
     gate keeps its meaning; R3 keeps its sentence. The residual risk is a hole in the walk failing
     open, which test 6 and tests 2-5 are there to bound. **The cross-review's recommendation** — it
     is also the smallest change and the one that keeps R3 whole.
   - **(b) flip to `false`** as first drafted, and accept that a newer file no longer reaches
     `DocOps.validate`. Then test 7 must be **deleted**, not fixed, and Decision 2, Decision 9 and the
     `DocJson` KDoc all have to be rewritten to say a newer file is refused at the parse. That is a
     different, more hostile reader than R31 asked for.
   - **(c) two `Json` instances** — strict on the same-version path, lenient on the newer-version path
     — so the same-version hole fails closed *and* the version sentence survives. Costs one extra
     field and a branch, and it is the only option that gets both properties. The cross-review has not
     chosen it over (a) because it adds a second code path to a row whose whole risk is one code path.
   **This is a file-format decision and the cross-review did not rule it.** Everything else in this spec
   is executable the moment Q0 is answered.

   ### ORCHESTRATOR RULING on Q0 — **(a), keep `ignoreUnknownKeys = true`.** 2026-09-29.
   **PROVISIONAL — Claude to confirm.** The reviewer's costings were right and I took (a) for three
   reasons, in order of weight:

   1. **(a) is the only option that does not quietly weaken a ruling that is already in force.** R3 and
      R31 both say readers "refuse newer versions **in words**". Option (b) keeps the refusal but moves
      it from *our sentence* to a library error — the person sees "Encountered an unknown key 'audio'"
      instead of "this file is from a newer Joy Brush" — and it names a key that is not the reason the
      file cannot be read. R31's actual subject is *this build's ignorance of a field*, not a future
      build's fields, so (b) trades a promise that was never in question for one nobody asked for.
   2. **(a) makes the scan the whole guard, which is the simplest thing that satisfies R31's real
      requirement** — a same-version file carrying unknown keys is refused in words. The scan is
      already written, already walks the tree, and already produces the message. There is nothing for
      `false` to add.
   3. **(c) is right and I am not taking it.** It gets both properties, and the reviewer's objection —
      a second code path in the row whose whole risk is one code path — is the right objection. This
      row's job is to stop a save from silently discarding a field. The cheapest correct version of
      that wins over the most complete one, and (c) can be adopted later without rework if the
      newer-version path ever needs a different sentence.

   **What this means for the spec:** Decision 4 is **withdrawn** — the flag stays `true` and the scan
   is the guard. Decision 2's version gate stands as written and **test 7 passes unchanged**, which is
   the test that pins the whole point. The "Do not" entry that made "leave `true` as the only line of
   defence" an OPEN QUESTION is now a settled rule: **do not flip it**, and do not weaken the scan's
   coverage to justify flipping it.

   **Still the Lead's, and I am not deciding it:** the refusal **wording** (Q2) and whether the message
   lists one offender or all of them (Q3). Those are sentences, not behaviour, and the behaviour is
   now fixed.

### Also for the Lead

1. 🔴 **What counts as a "known" key — and how is the set obtained?** (Decision 3, PROVISIONAL.)
   Two ways, and they have different failure modes:
   - **(a) derived from the serial descriptor** (`JbDocument.serializer().descriptor`, walked by
     `elementNames` / `getElementDescriptor`, recursing into `OBJECT` and `LIST` kinds). Cannot
     drift: adding a field to `DocModel.kt` changes the answer automatically. Costs ~40 lines of
     descriptor recursion that must be right for `frameCel`'s free-form map keys (which must **not**
     be checked) and for `List<List<Float>>` inside a `Param` (not in this file, but the same shape).
   - **(b) a hand-written table**, exactly the eight rows printed in the Contract above. ~10 lines,
     dead simple, and it drifts the first time a field is added and forgotten. It is pinned by test 6,
     so the drift is at least loud — but it is a convention, and this project's own KDoc says a
     hand-maintained list of a data class's fields is "the unsafe kind of trap".
   **I recommend (a).** This is a file-format decision, so it is the Lead's, not mine.
2. 🔴 **The exact refusal wording.** Decision 6 gives a shape; R31 gives another
   (*"this file has X, which this version of Joy Brush does not know"*). Does the message carry the
   **path** (`$.boards[0].grid`) as well as the key, and does it also say what the person can do?
3. **One message listing every unknown key, joined `"; "`** (Decision 5), or strictly the first one,
   in document order?
4. 🔴 **Confirm: `DOC_VERSION` is NOT bumped by this row** (Decision 8). The reading is that R31's
   trigger is a new *serialised field* and this row adds none. If the Lead rules that a stricter
   reader is itself a format change, then this row needs "bump to the next `DOC_VERSION`" — which puts
   **two** files in the owner area, `DocModel.kt` **and** `EnumFreezeTest.kt` (line 94 asserts
   `2 == DOC_VERSION`; verified). That is a scope change worth knowing about now.
5. **Does the freeze lift?** R31: *"Until it lands nothing new may rely on additive fields."* Does
   landing it lift the freeze by itself, or does the Lead say so separately? Several rows (JB-0.02c's
   `Board.audio`, JB-2.21, JB-2.23) are waiting on the answer to this, so it is worth an explicit
   word in the ruling rather than an inference.

---

xr: stealth/space-bunny-alpha 2026-09-29 — **Left at 📝 Draft. One blocker (Q0), and it is the spec's
own Decisions 2 and 4 contradicting each other, not a missing detail.** Verified as already correct
first, against the landed files rather than the spec's restatement of them: the `DocJson.kt` contract
quote is byte-accurate including the `@OptIn` and the two KDoc paragraphs Decision 9 says must go; the
`DocModel.kt` key universe and the eight-row table match exactly (`BlendMode` really is 27 constants,
`DOC_VERSION` really is 2); `DocOps.kt:59-61` and `JbArchive.kt:381-386` are quoted accurately and
`JbArchive.documentFrom` **is** the only production caller of `DocJson.decode` in the repo; the
`kotlinx-serialization-json:1.8.1` pin is real and `SerialDescriptor.elementNames` is genuinely
available and non-experimental in it; and `unknownKeysAreIgnoredAtEveryLevel` is a genuine deletion
requirement — the landed test really does assert the old behaviour (`assertEquals(doc, back)` after
inserting two foreign keys), so it cannot be kept under its old name. **Four gaps fixed:** test 8
called `EnumFreezeTest.documentWith`, which is `private` to another class in a file outside the owner
area (the fixture text is now pasted inline); the landed test `unreadableJsonThrowsDocException`
(line 146, `"[]"` and `"{ not json"`) was never mentioned and it constrains the walk to sit *inside*
the existing `try` with a non-`JsonObject` root falling through — added to the Contract and the
"Do not"; test 6's "collect the key at every object" would have collected `frameCel`'s map keys and
failed a correct implementation — the trap is now spelled out; and Decision 8's cost of a bump named
one file where there are two. **Not ruled:** Q0. Whether a file from a newer Joy Brush says
"from a newer Joy Brush" or "cannot be read: unknown key 'audio'" is a file-format decision, and the
two answers produce different sentences to a person with an old build.
