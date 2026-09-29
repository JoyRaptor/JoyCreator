# JB-4.03c — sprite `weights`: a held frame exports at the speed it was held

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 📝 Draft spec |
| **Depends on** | JB-4.03a (`SpritePacker`, 🟩 Reviewed) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/SpritePacker.kt` · `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/SpritePackerTest.kt` — **nothing else** |
| **Estimated size** | ~25 lines of code, ~70 lines of tests |
| **Re-review** | **Required.** This edits a file a cross-reviewer has already cleared, so the row goes back to review, not straight to 🟩. |

## Goal

On a sprite board a person can hold a cell for three ticks instead of one. The app's own
`.sprite.json` format has always had a way to say that — a `weights` array beside `frames` — and
`SpritePacker` cannot write it. So a roll with a hold exports as a sheet that plays every cell once,
with nothing anywhere saying a hold was lost. This row makes the packer write the key under the
app's own rule, so a held frame stays held on the other side.

## Contract (verbatim)

### The app's rule — READ ONLY. `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java`

This is the format's owner. **This row does not edit this file, or anything else in `app/`.**

`SpriteSheet.java:184-215` — the model:

```java
    /** A reusable frame sequence (fast-follow A authors these; the model ships now). */
    public static class Preset {
        @NonNull public final String id;
        @NonNull public String name;
        /** "loop" | "pingpong" | "once" */
        @NonNull public String type = "loop";
        /** 0 = inherit the sheet's default fps. */
        public float fps = 0f;
        @NonNull public final List<Integer> frames = new ArrayList<>();

        /**
         * Per-frame WEIGHTS (SPEC_IMAGE_SEQUENCE §2), parallel to {@link #frames}.
         *
         * <p>EMPTY means "every frame weighs 1", which is precisely the behaviour presets had
         * before weights existed — so an unweighted preset resolves down the identical code path
         * and serialises byte-identically. See {@link SequenceTiming} for the arithmetic and for
         * why weights ride the preset instead of becoming a parallel model.</p>
         */
        @NonNull public final List<Integer> weights = new ArrayList<>();

        public Preset(@NonNull String id, @NonNull String name) {
            this.id = id;
            this.name = name;
        }

        /** True when any frame is held longer than one tick. */
        public boolean hasWeights() {
            for (Integer w : weights) {
                if (w != null && w != SequenceTiming.DEFAULT_WEIGHT) return true;
            }
            return false;
        }
    }
```

`SpriteSheet.java:635-658` — the writer, and **the key order**:

```java
        if (!presets.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (Preset p : presets) {
                JsonObject pj = new JsonObject();
                pj.addProperty("id", p.id);
                pj.addProperty("name", p.name);
                pj.addProperty("type", p.type);
                if (p.fps > 0f) pj.addProperty("fps", p.fps);
                JsonArray frames = new JsonArray();
                for (Integer f : p.frames) frames.add(f);
                pj.add("frames", frames);
                // Written only when something is actually held: an all-1s array carries no
                // information, and omitting it keeps a pre-weights preset byte-identical.
                if (p.hasWeights()) {
                    JsonArray ws = new JsonArray();
                    for (int i = 0; i < p.frames.size(); i++) {
                        ws.add(SequenceTiming.weightAt(p.weights, i));
                    }
                    pj.add("weights", ws);
                }
                arr.add(pj);
            }
            j.add("presets", arr);
        }
```

`SequenceTiming.java:44-83` — the range and the read:

```java
    /** Weight of a frame that has never been edited. */
    public static final int DEFAULT_WEIGHT = 1;

    /** A frame may not vanish: the minimum weight is one tick (§5b "min 1"). */
    public static final int MIN_WEIGHT = 1;

    /**
     * Upper bound on a single frame's weight. Not a model limit — a guard, so a fat-fingered
     * drag or a hallucinated AI value cannot produce a hold measured in hours that the user then
     * has to find and undo.
     */
    public static final int MAX_WEIGHT = 9999;
    // …
    public static int weightAt(@Nullable List<Integer> weights, int i) {
        if (weights == null || i < 0 || i >= weights.size()) return DEFAULT_WEIGHT;
        Integer w = weights.get(i);
        if (w == null) return DEFAULT_WEIGHT;
        return clampWeight(w);
    }

    public static int clampWeight(int w) {
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, w));
    }
```

and `SequenceTiming.java:93-106`, which is the reason Decision 5 is a refusal:

```java
    /**
     * {@code weights} normalised to exactly {@code frameCount} entries — padded with the default
     * and truncated as needed.
     *
     * <p>Called wherever a frame count can change under an existing array (a re-import that finds
     * more files, an ABSOLUTE resize, a reorder). Without it a stale short array reads as "the
     * tail is all 1s", which is a silent timing change rather than a visible one.</p>
     */
```

### `core/.../export/SpritePacker.kt` — the two parts this row changes

```kotlin
data class Clip(
    val name: String,
    val frames: List<Int>,
    val type: String = "loop",
    val fps: Float = 0f,
)
```

```kotlin
        if (clips.isNotEmpty()) {
            putJsonArray("presets") {
                clips.forEachIndexed { index, clip ->
                    addJsonObject {
                        put("id", "$id-clip-$index")
                        put("name", clip.name)
                        put("type", clip.type)
                        if (clip.fps > 0f) put("fps", clip.fps)
                        putJsonArray("frames") {
                            for (frame in clip.frames) add(frame)
                        }
                    }
                }
            }
        }
```

`pack`'s signature, the argument list and the `require` block above the sidecar are unchanged except
for the two additions named in Decision 6. The class KDoc stays: *"The sidecar is not a report on
the sheet, it is the contract: the key set, the key ORDER and the sparse-write rule … all follow
`SpriteSheet.toJson()` on the Android side, because that file is what parses it."* That sentence is
the reason this row exists and it stays true after it.

### `commonTest/.../export/SpritePackerTest.kt` — the test this row edits

```kotlin
    @Test
    fun nothingTheAppDoesNotWriteIsWritten() {
        val root = parse(pack(board(5), cols = 3).sidecarJson)
        for (absent in listOf(
            "marginX", "marginY", "spacingX", "spacingY", "order", "bgKeyColor", "keyTolerance",
            "pivotX", "pivotY", "cells", "cellXf", "visemeMap", "bakedFrom", "cellOrder",
            "kind", "frameUris", "resizeMode", "weights",
        )) {
            assertFalse(root.containsKey(absent), "$absent must not be written")
        }
    }
```

**`"weights"` comes out of that list. Nothing else changes in it.** Two further facts about this
test, both verified, and both reasons the entry was wrong rather than merely stale:

- `pack(board(5), cols = 3)` passes **no clips**, so the sidecar has no `presets` key at all.
  `root.containsKey("weights")` was therefore trivially `false` — the entry could never have caught
  anything, even before weights existed in `Clip`.
- `weights` is a key **inside each preset**, not a root key, so `root.containsKey` is the wrong level
  to ask the question at regardless.

## Decisions already made

1. **`weights` is appended as the LAST field of `Clip`, after `fps`.**
   *Why, verified:* the existing test suite calls `Clip` **positionally** —
   `Clip("walk", listOf(0, 1, 2), "pingpong")` and `Clip("blink", listOf(3), "once", 12f)`
   (`SpritePackerTest.kt:206, 223`). Inserting `weights` anywhere but the end breaks those two calls
   and every future one. A default on the last field also means **no existing call site changes**,
   which is what makes this row additive on the Kotlin side.
2. **Default is `emptyList()`.** *Why:* R36 says so, and it is the same shape as the app's `Preset`,
   where an empty `weights` list means "every frame weighs 1" and serialises byte-identically to a
   preset from before weights existed.
3. **The key is written inside the preset object, immediately AFTER `frames`.**
   *Why:* `SpriteSheet.toJson()` writes `frames` then `weights` (lines 645 and 653, read above).
   `SpritePacker`'s stated contract is byte-order parity with the app, and the root-level test
   `sidecarHasExactlyTheAppsKeysInTheAppsOrder` already pins order as part of the contract. Order is
   not cosmetic here; it is the difference between a format and a guess.
4. **The write rule is the app's `hasWeights()`, and nothing else: write the key if and only if at
   least one weight is not 1.** An empty list writes nothing; `[1, 1, 1]` writes nothing.
   *Why:* the app's own comment — *"an all-1s array carries no information, and omitting it keeps a
   pre-weights preset byte-identical"* — and this packer's matching promise that *"Everything
   optional is omitted rather than written as a default, so a sheet that gains nothing does not churn
   the file."* The two must agree, or a sheet exported by Joy Brush churns on every re-export.
5. **`weights.size` must equal `frames.size` — REFUSED otherwise. Never padded, never truncated.**
   *Why:* the app writes `p.frames.size()` entries and reads them back with `weightAt`, which
   returns `1` for anything past the end. A short array therefore exports as *"the tail is all 1s"*,
   which the app's own `SequenceTiming.fit` KDoc calls *"a silent timing change rather than a visible
   one"*. This packer's stated policy is to refuse what it cannot write truthfully: *"a clip frame
   [outside] the packed cells, a clip with no frames, an unknown clip type"*. A length mismatch is
   exactly that.
6. **Every weight must be in 1..9999 (`SequenceTiming.MIN_WEIGHT`..`MAX_WEIGHT`) — REFUSED otherwise.
   Never clamped.**
   *Why:* `weightAt` **clamps on read**. A written `10000` arrives in the app as `9999`, and a `0`
   arrives as `1` — a timing change nobody is told about, in a file whose whole purpose is to be
   read by that app. Refusing is the packer's policy and it is the same policy as every other
   `require` in `pack`. R36 fixes the range; this decides clamp-or-refuse, and refusal is what the
   rest of the file does. **PROVISIONAL — Claude to confirm** (Questions 1).
7. **`SPRITE_SIDECAR_SCHEMA_VERSION` stays as it is.** *Why:* the app already writes this key, at
   `SpriteSheet.SPRITE_SCHEMA_VERSION = 1` (verified in `SpriteSheet.java:29`). This row makes Joy
   Brush agree with the format; it does not extend it, so nothing about the schema changes.
   **PROVISIONAL — Claude to confirm** (Questions 2). Per R30 this spec writes no number.
8. **`weights` is per preset, parallel to `frames`. It is not a root key, not a map, and not named
   `holdFrames`.** *Why:* the app's key is `weights` and its shape is an array parallel to `frames`.
   Renaming it to something that reads better in Joy Brush would produce a file the app cannot read,
   which is the one outcome this row exists to prevent.
9. **`pack`'s signature is unchanged.** *Why:* `clips: List<Clip>` is already there, so the field
   arrives through a value that already exists. `SpriteGridMathTest` (JB-4.01a) calls the landed
   `SpritePacker.pack` and must not be disturbed by this row — a cross-lane dependency the JB-4.01a
   report flagged, so it is named here as a thing to check rather than discover.
10. **The refusal messages follow the packer's existing `"clip \"$name\" …"` shape.**
    `"clip \"walk\" has 2 weights for 3 frames; the app reads a short array as 1s, so a hold would be lost"`
    and `"clip \"walk\" has weight 0; the app reads weights 1..9999"`. **PROVISIONAL** (Questions 1).

## Steps

1. Write the new tests (below) and make the one-line deletion from
   `nothingTheAppDoesNotWriteIsWritten`. Run the command; they are red.
2. Add `val weights: List<Int> = emptyList()` as the **last** field of `Clip`, with a KDoc line naming
   the app's rule it follows (`hasWeights()`, `1..9999`, parallel to `frames`).
3. Add the two `require` checks inside the existing per-clip `for (clip in clips)` block, with the
   other clip checks.
4. Add the write inside the preset `addJsonObject`, after the `frames` array, under the
   `hasWeights()` rule.
5. Run the command. Green.
6. Confirm `SpriteGridMathTest` is still green and that no test in the suite constructs a `Clip`
   positionally in a way that has changed meaning.

## Tests

**New, in `commonTest/.../export/SpritePackerTest.kt`:**

| # | Test name | Input → expected |
|---|---|---|
| 1 | `aHeldFrameIsWrittenAsWeightsBesideItsFrames` | `Clip("walk", listOf(0, 1, 2), weights = listOf(3, 1, 1))` → the preset's keys are exactly `["id", "name", "type", "frames", "weights"]`, `frames == [0,1,2]`, `weights == [3,1,1]`. **This is the row**: a cell held ×3 survives the export. |
| 2 | `aClipWithNothingHeldWritesNoWeightsKey` | three cases, all with no `weights` key in the preset: the default `Clip("walk", listOf(0,1,2))`; `weights = emptyList()`; `weights = listOf(1,1,1)`. Then the stronger half: **the sidecar string of the all-1s case is byte-identical to the default case's** — that is the app's "byte-identical" promise, pinned as a string comparison, not as a key-list comparison. |
| 3 | `weightsIsWrittenAfterFramesAndNotBefore` | covered by the key-order assertion in test 1; if you want it separate, the preset's `keys.toList()` is the whole assertion. The app writes `frames` then `weights`, and the packer's contract is order parity. |
| 4 | `weightsOfTheWrongLengthIsRefused` | 5 frames with `listOf(1, 3)` → `IllegalArgumentException` whose message contains `walk`, `2` and `5`. The other direction too: 2 frames with `listOf(1, 1, 1)`. **No padding, no truncation.** |
| 5 | `aWeightOutsideOneToNineThousandNineHundredAndNinetyNineIsRefused` | `0` → refused; `10000` → refused; `-1` → refused. `1` and `9999` → **accepted** (both edges are legal, and 1 is the default weight). Not clamped: the exported file must carry what the caller asked for or the row refuses. |
| 6 | `aHoldOnTheFirstCellIsTheOrdinaryCaseAndItSurvives` | `Clip(frames = [0,1,2], weights = [3,1,1])` written out and read back through `Json.parseToJsonElement`, then: the sidecar's `frames` has 3 entries and its `weights` has 3 entries, entry 0 is `3`. State the meaning in the test's comment: without the key, a reader gives cell 0 one tick, and the person who held it for three has lost it with nothing to show for it. |
| 7 | `packingTwiceWithTheSameWeightsProducesTheSameJson` | two identical packs → `assertEquals(first.sidecarJson, second.sidecarJson)`. Determinism, now including the new key. |

**Edited, in the same file:**

- `nothingTheAppDoesNotWriteIsWritten` — **delete the string `"weights"`** from the `listOf` and
  change nothing else. Every other entry stays, including the six that are also preset-level
  (`kind`, `frameUris`, `resizeMode`, `visemeMap`, `bakedFrom`, `cellOrder`) — out of scope here, see
  Questions 3.

**Must stay green, unchanged, and worth reading before you start:**

- `presetsCarryTheirFramesAndOmitAnInheritedFps` — asserts the preset keys are
  `["id", "name", "type", "frames"]` for a clip with **no** weights. It is the test that says the
  sparse-write rule still holds.
- `sidecarHasExactlyTheAppsKeysInTheAppsOrder` — the root key list. The new key is not a root key, so
  this test does not change.
- `aClipWithItsOwnFpsWritesIt`, `theEncodedPngIsCheckedAgainstWhatTheSidecarSays`,
  `everyIndexInTheSidecarIsACellTheSheetHolds`.
- `SpriteGridMathTest` (JB-4.01a, a different file) calls the landed `pack`. It must not break.

**Command:** `./gradlew -p joybrush :core:jvmTest` — **0 failures**. Paste the output.

## Do not

- **Do not edit `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java`, `SequenceTiming.java`,
  or anything else in `app/`.** The format is theirs; this row reads them and matches. An app-file
  edit here would be an app-file row under the serialised app order (D.02a → D.02 → D.02c / D.05),
  and it is not this row.
- **Do not clamp an out-of-range weight.** The app clamps on read, so a clamp here is a silent change
  written into a file. Refuse (Decision 6).
- **Do not pad or truncate a wrong-length `weights` list.** Refuse. `SequenceTiming.fit` exists
  precisely because a short array is a silent timing change.
- **Do not write the `weights` key when every weight is 1**, and do not write it for an empty list.
  That is the app's rule (Decision 4), and a sheet that gained nothing must not churn the file.
- **Do not put `weights` before `frames`,** or at the root, or inside `cellNames`. It is a preset-level
  array, after `frames`.
- **Do not rename it to `holdFrames`**, or make it a map from cell index. The app reads `weights`.
- **Do not insert the field anywhere but the end of `Clip`.** The suite constructs `Clip`
  positionally; a new field in the middle breaks `Clip("blink", listOf(3), "once", 12f)`.
- **Do not import the roll's `1..16` clamp.** `1..16` is SpriteLab's `bumpHold` (JB-4.02) and is
  about a tap's drag; `1..9999` is `SequenceTiming`'s. Both are correct, in different places, and
  using the wrong one would refuse a hold the app is perfectly willing to play.
- **Do not change `pack`'s signature or `SPRITE_SIDECAR_SCHEMA_VERSION`.**
- **Do not "fix" the other six preset-level keys in `nothingTheAppDoesNotWriteIsWritten`.** They have
  the same level problem as `weights` did, and it is a separate finding (Questions 3).
- **Do not change JB-4.03b.** Its Decision 7 (*"refuse a roll with a hold"*) is **deleted by the
  Lead's ruling** once this row lands — that is the orchestrator's board edit, not yours, and not a
  change to any source file here.
- **Do not mark this row 🟩.** It edits a reviewed file. It goes to 🟧 Built and then back to review.

## Definition of done

- [ ] New tests 1–7 in `SpritePackerTest.kt`; `"weights"` deleted from the absent list and nothing
      else in that test touched.
- [ ] `./gradlew -p joybrush :core:jvmTest` — 0 failures. **Paste the output.**
- [ ] `presetsCarryTheirFramesAndOmitAnInheritedFps` still passes untouched — it is the proof the
      sparse-write rule survived.
- [ ] `git status --short` shows **only** `SpritePacker.kt` and `SpritePackerTest.kt`. **Paste it.**
      In particular: no file under `app/`.
- [ ] Committed as `JB-4.03c: sprite sidecar weights`; pushed.
- [ ] ROADMAP row → 🟧 Built **and flagged for re-review** (this edits JB-4.03a's reviewed file).
- [ ] JB-4.03b's Decision 7 is struck (orchestrator, after this lands).

## Stop rule

**Stop and write the question in Questions if any of these is true. Do not guess:**

- The app's `toJson()` no longer writes `weights` where this spec says it does, or
  `SequenceTiming.MIN_WEIGHT`/`MAX_WEIGHT` are no longer `1`/`9999`. **Re-read the app file** and
  change the spec, not the code — and if you cannot find `SpriteSheet.java`, say which directories
  you searched. A plausible finding is not a refuted finding until you have looked in the module it
  names: that mistake already cost this project one row.
- A **shipped or already-exported** sidecar in the repo turns out to carry a `weights` array of a
  length that disagrees with its `frames`. That is a real fact about real files, and what to do with
  it is the Lead's call, not a `require` you may add.
- Test 2's byte-identical comparison fails. It must not be "fixed" by writing the key anyway — that
  comparison is the app's promise, quoted in Decision 4.
- You cannot add the field without breaking a positional `Clip(...)` call. There is no other answer
  than to append the field; if that is impossible, stop.

## Questions

### For the Lead

1. 🟠 **Clamp or refuse for an out-of-range or wrong-length `weights`?** Decisions 5 and 6 say
   **refuse**, on the grounds that the packer's whole policy is to refuse what it cannot write
   truthfully, and that the app's own `weightAt` clamps on read — so a clamp here is a silent change
   written into a file. The alternative is to clamp to 1..9999 and fit to `frames.size`, which is
   what the app's own `SequenceTiming.fit` does on its side. This is about what goes in a file
   somebody else reads, so it is the Lead's; refusal is my recommendation and the packer's habit.
2. **Confirm `SPRITE_SIDECAR_SCHEMA_VERSION` does not move** (Decision 7). The reasoning is that the
   app already writes this key at version 1, so the schema is unchanged. If the Lead wants a bump
   anyway, that is a one-line change here and one in `SpriteSheet.java` — and the second is an
   app-file edit with its own row, which would make this row not-tiny.
3. 🟡 **Six more entries in `nothingTheAppDoesNotWriteIsWritten` are preset-level, not root-level:**
   `kind`, `frameUris`, `resizeMode`, `visemeMap`, `bakedFrom`, `cellOrder`. The test packs with no
   clips, so none of them could ever be caught either — the same defect `weights` had, and it survived
   a cross-review because the test looks like it is guarding a key set. Out of scope here, but it
   should be a row rather than a quiet fix inside this one.
4. **Who picks the weights from the board?** `Clip.weights` is a value the packer writes; something
   has to fill it from JB-4.02's roll (`List<Entry>` with a per-entry hold of 1..16, which fits
   1..9999 with room to spare). JB-4.03b is the row that packs a board. This row deliberately does
   not touch it — but if the Lead expects the wiring here, say so now rather than after it lands.
