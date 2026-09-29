# Adversarial review — JB-0.07 GL context loss (`forgetEverythingFromTheLastContext` + `lostContent` + `GlContextLossTest`)

- Reviewer: claude (second adversarial pass on the context-loss fix; **muse-spark reviewed the task
  first** — `tasks/joybrush/reviews/JB-0.07__muse-spark.md`, F1 being the finding this fix answers).
  The other reviewer is **mimo** (`JB-0.07__mimo.md`); I did not read it in full, so overlap is
  stated only where I can see it.
- Task status: 🟧 Built. Suite run by me at HEAD:
  `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test --rerun-tasks` → **BUILD
  SUCCESSFUL**. `GlContextLossTest` 5/5 green.
- Spec: **none in `tasks/joybrush/specs/`** (T1 pre-spec work). The contract is the review F1 text
  plus the engine's own KDoc, which is what I attack.
- §5b: I edited only this file. No git. No source edits.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: 3 MINOR, 0 MAJOR, 0 BLOCKER.** The fix is real, the clearing order is right, and the
tests are non-vacuous. All three findings are false *claims* attached to correct code, plus one
cross-file attribution error.

---

## Finding 1 (MINOR — FALSE CLAIM, cross-file): `lostContent` has **no production consumer**, so the
## test named `aContextLossIsReportedSoTheCallerCanTellThePerson` pins a flag nobody reads

**Claim to attack.** My brief: *"after re-init nothing is carried across, and `lostContent` reports
it."* The second half is not true of the tree.

**Proof — exhaustive grep.** `lostContent` appears in exactly **nine** places in the whole
`joybrush/` tree (excluding `build/`):

| file:line | what |
|---|---|
| `GlPaintEngine.kt:64` | `var lostContent = false` (declaration) |
| `GlPaintEngine.kt:107` | `lostContent = forgetEverythingFromTheLastContext()` (the only write) |
| `GlPaintEngine.kt:167` | a KDoc cross-reference |
| `GlContextLossTest.kt:18, 41, 79, 84, 89, 106` | the tests |

**The one production call site of `init` never reads it.**
`androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:181-185`:

```kotlin
override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    engine.init()
    engine.addLayer(layerId)      // ← the flag is not consulted, and nothing else is either
}
```

So after a real context loss the canvas silently shows an empty document. The test name at
`GlContextLossTest.kt:76` — *"aContextLossIsReportedSoTheCallerCanTellThePerson"* — asserts a
capability that exists in the flag but is not connected to anything. The engine's own KDoc at
`GlPaintEngine.kt:60-62` is **honest** about this ("*A caller that can tell the person … reads
this*" — hypothetical, and the very next sentence concedes "This class shows nothing on screen"),
so the false claim is in the **test name**, not the KDoc. That is exactly the name-vs-body class
this review is for.

**Severity reasoning.** MINOR, not MAJOR: the flag, its semantics and its tests are all correct,
and the wiring is a UI concern the Lead owns (`JbCanvasView` is outside this fix's area). But a test
name is documentation, and this one documents a feature that does not exist. Rename it to
`theFlagReportsThatTheLastInitFoundSomethingToLose`, or add the `JbCanvasView` branch.

---

## Finding 2 (MINOR — FALSE CLAIM, and it is load-bearing for the fix's own safety argument):
## "The releases themselves delete nothing" is false — `trimPool` calls `glDeleteTextures`

**File:line.** `GlPaintEngine.kt:172-174`, inside `forgetEverythingFromTheLastContext`'s KDoc:

> *"**The releases themselves delete nothing**: the names were minted by the dead context, and this
> runs before the new context's first `glGen*`, so a pool trimmed here can only be deleting
> something that does not exist."*

**They do delete.** The two calls made on this path are not passive:

```kotlin
// :185   cancelStroke()  ->  :521-525 releaseStrokeTiles()
private fun releaseStrokeTiles() {
    freeStrokeTex.addAll(strokeTiles.values)
    strokeTiles.clear()
    trimPool(freeStrokeTex, 32)        // :524  <-- may glDeleteTextures
}
private fun trimPool(pool: ArrayDeque<Int>, keep: Int) {                 // :532
    while (pool.size > keep) GLES30.glDeleteTextures(1, intArrayOf(pool.removeFirst()), 0)   // :533
}
```

and

```kotlin
// :187   undo.clear()  ->  UndoLog.kt:63-67 calls release = ::recycleLayerTex
private fun recycleLayerTex(tex: Int) {                                 // :527
    freeLayerTex.addLast(tex)
    trimPool(freeLayerTex, 64)             // :529  <-- may glDeleteTextures
}
```

`release` is bound at `GlPaintEngine.kt:80` (`release = ::recycleLayerTex`), and `UndoLog.clear()`
(`paint/UndoLog.kt:64-65`) invokes it for every held `before`/`after`. So a document whose undo log
holds more than 64 tiles, or a stroke spanning more than 32 tiles, issues real
`glDeleteTextures` calls from inside the "forget" routine.

**Is the outcome wrong?** No — and this is why it is MINOR. The KDoc's *conclusion* is sound by a
different argument: on a genuinely lost context there is no current context, so `glDeleteTextures`
is a no-op; and on the **live** re-init the KDoc at `:178-180` explicitly says is supported
("*init is also how a second init on a live context is recovered from*"), the names being deleted
are the engine's own discarded tiles, so deleting them is correct. I traced both.

**Why it still matters.** The sentence is the fix's *own* safety argument, and the argument as
written is false; the real argument is one the author did not write. A future reader who trusts the
sentence will believe the path is allocation- and driver-free, and may move the call (e.g. into
`create()` "to be safe", which the same KDoc forbids for a *different* reason) or add a
`glDeleteTextures` of its own believing the context is untouched. Replace the sentence with the
true one: the deletes are harmless because a dead context ignores them and a live re-init's names
are being discarded anyway.

---

## Finding 3 (MINOR — cross-context state the fix does NOT clear, so "nothing is carried across" is
## not literally true): `strokeInternal` / `strokeType` are decided once and only ever raised

**File:line.** Declared `GlPaintEngine.kt:48-49`:

```kotlin
private var strokeInternal = GLES30.GL_R8
private var strokeType = GLES30.GL_UNSIGNED_BYTE
```

Set at `:115-117`, **with no `else`**:

```kotlin
val halfFloatRenderable = version.contains("OpenGL ES 3.2") ||
    ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
if (halfFloatRenderable) { strokeInternal = GLES30.GL_R16F; strokeType = GLES30.GL_HALF_FLOAT }
```

`forgetEverythingFromTheLastContext` (`:176-191`) clears the stroke (`cancelStroke`), the layers,
the undo log and both pools. It does **not** reset these two, and nothing else in the file ever
does. So they are engine state derived from the **old** context's extension strings that survives
into the new one.

**The consequence, concretely.** Re-init the same engine on a context that does *not* advertise
`GL_EXT_color_buffer_half_float` (a driver fallback, a different GPU, the emulator, or a
context switch on a hybrid device), and `newStrokeTile()` (`:512-515`) allocates
`GL_R16F` / `GL_HALF_FLOAT` on a context that cannot render to it. `strokeBufferIsHalfFloat`
(`:68`), the public diagnostic, also reports `true` for a GPU that has no half-float.

**Reachability, honestly.** This needs the same engine instance to be re-inited on a
*less capable* context. The extensions are a property of the GPU, so this does not fire on an
ordinary same-device loss. It fires on a fallback path, and the file already models "init is also
how a second init on a live context is recovered from", so the second-init path is real. The
drawing is already lost by this point, so the damage is a bad texture format and a lying diagnostic
— not user ink. MINOR, but it is the one field that survives, and the claim under attack
("after re-init nothing is carried across") is false because of it.

---

## Verified CORRECT — the fix itself, and the tests' non-vacuity

1. **The clearing order is right, and the KDoc's reason for it is the non-obvious part.**
   `GlPaintEngine.kt:182-190`:
   ```kotlin
   val had = heldTextureNames() > 0 || undo.canUndo || undo.canRedo
   cancelStroke()          // strokeTiles -> freeStrokeTex
   layers.clear()
   undo.clear()            // before/after -> freeLayerTex via recycleLayerTex
   freeLayerTex.clear()
   freeStrokeTex.clear()
   return had
   ```
   The pools are cleared **last**, after both release paths have drained into them. I checked the
   alternative order would be wrong: clearing the pools first and then calling `undo.clear()`
   would leave up to N recycled dead names in `freeLayerTex` for `newLayerTile()` (`:509`) to hand
   straight back out, and a recycled tile is never re-specified (`newLayerTile` only calls
   `glTexImage2D` inside `newTexture`, not on the recycled path) — which is precisely the failure
   mode F1 described. The comment at `:168-171` names this. ✓
2. **`heldTextureNames()` is the single definition of "there was something to lose", and it is
   complete.** `:199-200` = every layer tile + `strokeTiles` + both pools. `undo.canUndo/canRedo`
   covers the tiles the log owns that are in no layer. Nothing that holds a name is omitted — I
   enumerated the mutable name-holders in the class: `layers` (`:76`), `freeLayerTex` (`:77`),
   `freeStrokeTex` (`:78`), `undo` (`:80`), `strokeTiles` (`:84`), `strokeLayer` (`:83`), plus the
   `Layer.tiles` maps (`:71`). All covered or cleared. And using one function for both the "was
   there anything" question and the test's assertion is what makes the two unable to disagree —
   the KDoc at `:177-178` says exactly that, and it is the right design. ✓
3. **`ready` is deliberately excluded from the "had" test, and the reason is sound.**
   `GlPaintEngine.kt:178-181` — "init is also how a second init on a live context is recovered
   from, and what the caller needs to be told is that the DRAWING did not survive — not that init
   ran twice." Correct: counting `ready` would make every first-init report a loss. The tests pin
   the consequence in both directions (`GlContextLossTest.kt:36-47` and `:79`, `:89`). ✓
4. **The tests are non-vacuous — I checked each one's fixture rather than trusting its name.**
   * `plantDeadHandles` (`:129-142`) plants through **public API only** and then **asserts it
     worked** (`:139-140`: `assertTrue(engine.undoStep())` and
     `assertEquals(setOf(7L, 9L), engine.tileKeys(layer).toSet())`). So a fixture that silently
     stopped planting would fail the plant, not make the loss tests vacuous.
   * `:57` `assertTrue(engine.heldTextureNames() > 0, "the test must plant something for the loss to
     lose")` and `:101` `assertTrue(engine.heldTextureNames() > 0, "the test must leave a name in a
     free pool")` — both fixtures are guarded against the degenerate case. This is the specific
     discipline that makes the rest of the file trustworthy, and it is present.
   * `aContextLossLeavesNoStaleHandleToBeMistakenForTheDrawing` (`:50-73`) asserts the whole surface
     that F1 was about: `heldTextureNames() == 0`, `layerIds()` empty, `tileKeys` empty,
     `tileCount == 0`, `undo.heldBytes == 0L`, `undoDepth == 0`, `!canUndo`, `!canRedo`,
     `!strokeInProgress`, `!undoStep()`, `!redoStep()`. Each of those would be non-zero under the
     pre-fix code, so the test is a real pin, not a smoke test.
   * `namesSittingInTheFreePoolAreLostTooAndAreNeverHandedOutAgain` (`:93-107`) exercises the
     *worst* case the KDoc calls out — a dead name sitting in a pool that `newLayerTile` would
     recycle — by planting, calling `resetDocument()` (`:314-319`, which recycles layer tiles into
     `freeLayerTex`), asserting the pool is non-empty, then re-initing and asserting zero.
   * `aContextLossIsReportedSoTheCallerCanTellThePerson` (`:76-90`) also pins the **negative**:
     a third re-init on an empty engine reports `false` again. Without that, `lostContent` could be
     a sticky flag and the test would still pass the positive case.
5. **`heldTextureNames()` is honestly scoped.** The KDoc at `:193-198` says it "is not a count of GL
   objects, because the programs, buffers, framebuffer and `clearTex` are the driver's business and
   `initWith` makes new ones rather than reusing old." Correct: `createGlObjects` (`:112-157`)
   unconditionally reassigns `dabProg`/`commitProg`/`tileProg`, the three VBOs, both VAOs, `fbo` and
   `clearTex`, so none is reused. (Those old objects leak on a live second init — a pre-existing
   consequence of supporting that path at all, not of this fix, and not filed.)
6. **`initWith`'s ordering is right and the test seam does not weaken the production path.**
   `:106-110`: `lostContent = forgetEverythingFromTheLastContext()` runs **before** `create()`, so
   "an object made on the new context must never meet a name the old one minted" (`:103-104`). The
   test's `reInit() = initWith { }` (`:33`) substitutes an empty `create`, which is exactly the
   point: it isolates the bookkeeping from GL. It cannot mask an ordering bug, because the ordering
   under test is entirely *before* `create`. ✓
7. **`resetDocument` is untouched and still correct** (`:314-319`): cancel stroke, clear undo,
   recycle layer tiles, clear layers. Its recycling is exactly why Finding-in-the-test-name-3 above
   matters — after a *reset* (not a loss) a pooled name is live and recycling is right.

---

## Recommendation

No send-back. Three MINORs, all documentation-grade or a one-line `else`:

1. Rename `aContextLossIsReportedSoTheCallerCanTellThePerson`, **or** add the `JbCanvasView` branch
   that reads `lostContent` — the flag is correct and tested, it just has no listener, and on a
   real device that is a blank canvas with no explanation.
2. Correct "The releases themselves delete nothing" to the true argument (dead context ignores the
   deletes; a live re-init is discarding those names anyway).
3. Add `else { strokeInternal = GL_R8; strokeType = GL_UNSIGNED_BYTE }` at `GlPaintEngine.kt:117`
   so a re-init on a less capable context cannot keep a format the new context cannot render to.

The core of F1 is genuinely closed: the engine now drops every name it holds, in an order that
cannot leave a dead name in a recyclable pool, reports that it did, and the tests are guarded
against vacuity in a way I have not seen elsewhere in this codebase. That is a good fix.
