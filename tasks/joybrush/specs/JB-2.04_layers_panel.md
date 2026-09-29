# JB-2.04 — The layers panel: paint and ink layers, blend modes, opacity, and the runtime layer budget

| | |
|---|---|
| **Tier** | T2-V |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-0.07 (`GlPaintEngine` — Built), JB-2.01 (screen chrome — Draft) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/layers/LayerRules.kt`, NEW `.../layers/LayerBudget.kt`; NEW `.../commonTest/.../layers/LayerRulesTest.kt`, `LayerBudgetTest.kt`; NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/layers/LayersPanelView.kt`; EDIT `.../androidkit/gl/GlPaintEngine.kt` (a `blend` field on the layer record, a setter, and a `setLayerOrder`); EDIT `joybrush-android/.../JoyBrushActivity.kt` (the panel host + the doc→engine sync) |
| **Estimated size** | ~180 lines of core + ~200 lines of tests; ~450 lines of views; ~200 lines of engine and wiring |

## Goal

Blueprint §2: *"A layer-count budget is computed at runtime from the device's memory and shown on
screen (what Procreate and Infinite Painter do), so the Note 9 never gets pushed into a crash."*
And §4 Phase 2: *"layers panel with blend modes"*.

The person needs to add a layer, name it, hide it, reorder it, set its opacity, set its blend mode,
and see how much room the device has left — without the app ever getting pushed into a crash.

> ## 🔴 THE ONE DECISION A BUILDER MUST NOT GET WRONG
>
> **As of this writing the GPU composites exactly ONE blend mode: `NORMAL`.**
> `GlPaintEngine`'s layer record (`:70-74`) holds `id`, `tiles`, `opacity`, `visible` — and **no
> blend field at all**. `jb_tile.frag:13` is `o_color = texture(u_layer, v_uv) * u_layerOpacity`,
> and `GlPaintEngine.kt:437` calls `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` **once, before the
> layer loop**, and never changes it. Not a missing branch — a missing field. The CPU
> (`RegionRenderer` / `Blend` / `BlendRgb`) implements **27 of 27**.
>
> So: **27 on the CPU, 1 on the GPU.** JB-2.20a Decision 6 says it in terms, and it is repeated as
> Decision 2 below because getting it wrong ships a mode that **previews wrong on the phone and
> exports right** — the exact divergence the whole blend-parity apparatus exists to prevent.
> JB-2.20b (T1) is the row that closes the gap. Until it lands, **the picker offers `NORMAL` only.**

## Contract

```kotlin
package cc.joycreator.joybrush.core.layers

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.LayerKind

/**
 * Which blend modes this build may OFFER. THE RULE, in one place, testable in the cloud.
 *
 * Decision 2: the list is derived from what the GPU composites, never hand-written. Until
 * JB-2.20b, [gpuComposite] holds only NORMAL and so does [offerable].
 */
object LayerRules {
    /** Modes the GL layer path composites correctly today. */
    fun gpuComposite(): Set<BlendMode>

    /** Modes this build may put in the picker. Always a subset of [gpuComposite]. */
    fun offerable(): List<BlendMode>

    /**
     * Why [mode] may not be chosen right now, in the person's words — or null when it may.
     * A refusal, never a silent substitution: quietly showing NORMAL and saving NORMAL changes the
     * painting without telling anybody.
     */
    fun refusalFor(mode: BlendMode): String?

    /**
     * Can a brush of [engine] ("stamp", "fill", "smudge", "wet", …) be used on a layer of [kind]?
     * LEAD_RULINGS R20: only `stamp` and `fill` on an INK layer. smudge and wet read the pixels
     * underneath, so they are paint-layer brushes.
     */
    fun engineAllowedOn(engine: String, kind: LayerKind): Boolean
    fun refusalForEngine(engine: String, kind: LayerKind): String?
}

/**
 * The runtime layer budget, from the device's own reported memory class. Blueprint §2.
 */
object LayerBudget {
    /** Fraction of the app's memory class a Joy Brush document may hold. */
    const val SHARE = 0.5

    /**
     * How many more layers may be added.
     *
     * @param memoryClassMb `ActivityManager.getMemoryClass()` — the per-app heap in MB. 256 on a
     *   Note 9, which is the ruled floor.
     * @param currentLayers layers the document already has.
     * @param tileBytes bytes one 256² premultiplied RGBA8 tile costs.
     * @param tilesPerLayer the LAYER's own tile count today; a new layer starts at 0.
     * @return a count ≥ 0, or 0 when the device cannot afford another layer.
     */
    fun layersAffordable(
        memoryClassMb: Int,
        currentLayers: Int,
        tileBytes: Int,
        tilesPerLayer: Int,
    ): Int
}
```

## Decisions

1. **The panel is the Studio's list idiom, rebuilt on Joy Brush's own rows** — not a copy of any
   Studio file (R23: share, don't copy; the Studio has no layer panel to share). A row is
   `[visibility] [kind glyph] [name (tap to rename)] [opacity drag] [blend chip] [menu]`, top
   layer first, in the drawer/popover form JB-2.01 provides. No new tokens, no new colours: a
   selected row is `ARMED` cyan, which is a state colour and stays one.
2. **🔴 No UI may offer a mode the GPU cannot composite.** `offerable()` is computed from
   `gpuComposite()`, and a test asserts the relationship rather than trusting a comment. Today that
   is `[NORMAL]`, so the blend chip reads "Normal" and is **greyed with the reason in words** when
   tapped, not hidden: a control that is missing looks broken, and a control that says why looks
   like a version. The chip's own label names the row it will appear in —
   "Blend modes arrive with the export/GL work (JB-2.20b)".
   **The failure this prevents, stated as a test:** if a builder hard-codes a 27-entry list into the
   picker, `everyOfferedModeIsOneTheGpuComposites` stays green (it only checks the subset
   relation) and a MULTIPLY layer renders source-over on the phone while exporting W3C-correct. So
   there is a SECOND test: `theModeListIsNotHardCodedInTheView` — the view reads
   `LayerRules.offerable()` and nothing else, asserted by the view having no `BlendMode` literal in
   it (a source-level check, see Tests).
3. **A mode is refused, never substituted.** `refusalFor(MULTIPLY)` returns a sentence naming the
   mode and the reason. A document that arrives from disk carrying a mode this build cannot
   composite keeps it in the FILE (round-trip is lossless) and shows the layer as *composited as
   Normal on screen*, with a visible marker, because silently rewriting it on the next save is how
   a painting changes without telling anybody. Saving such a document is allowed and unchanged.
4. **The engine gains a blend field NOW, even though only `NORMAL` can be set.** `Layer` gains
   `var blend: BlendMode = BlendMode.NORMAL` and `setLayerBlend(id, mode)` **refuses in words** for
   any mode not in `LayerRules.gpuComposite()` — the same rule as the UI, enforced at the door
   rather than only in the panel, so no caller can walk around it. This is the field JB-2.20b
   needs, and it is the smallest change that makes the gap structural instead of invisible.
5. **R20's engine↔layer rule is enforced in `LayerRules`, not in the UI** — this is the gap
   JB-1.08a's Q6 referred ("no engine↔`LayerKind` check exists in core"). `engineAllowedOn` is the
   one function, the layers panel asks it, the brush picker asks it, and a test asserts a `smudge`
   brush on an INK layer is refused in words naming both.
6. **The budget is a NUMBER ON SCREEN, and the refusal is in words too.** `layersAffordable` uses
   Long arithmetic for the byte total (R19 — check in Long before any Int pixel arithmetic; the same
   lesson as `SpriteGridMath` and `SpritePacker`). Zero affordable → the "+" is disabled and tapping
   it says *"this device has room for 4 layers"* with the count. It is **not** a silent cap: the
   panel shows `6 / 24 layers` all the time, so the limit is visible before it is reached.
7. **Opacity and visibility go straight to the engine**; name, blend, order and kind go to the
   document. Visibility toggling is NOT undoable (it changes no pixel); opacity, blend, order, add,
   duplicate, delete and merge ARE, each as **one** undo step.
8. **Deleting a layer is `DANGER` fill — the one state colour allowed to fill** (visual language
   §1.3), and always confirms. Merging down is only offered when the layer above is a PAINT layer.
9. **Ink layers appear in the panel and are refused as targets** until JB-5.01 renders them; a row
   that cannot be drawn on is shown with a marker and tapping the canvas on it says so. Never
   silently missing — a layer the person made must never be invisible in the list.

## Tests

`LayerRulesTest` (JVM, `:core:jvmTest`) — the load-bearing ones:
1. `everyOfferedModeIsOneTheGpuComposites`: `offerable() ⊆ gpuComposite()`, asserted for the whole
   enum, so a 28th `BlendMode` cannot pass unnoticed.
2. `todayTheGpuCompositesNormalOnly`: `gpuComposite() == setOf(NORMAL)`, with a KDoc naming the
   evidence (`GlPaintEngine.Layer` has no blend field; `jb_tile.frag:13`; `glBlendFunc` set once
   before the layer loop) and naming JB-2.20b as the row that changes it. **This test is
   deliberately RED the day JB-2.20b lands** — that is the point: it is the tripwire, not a claim.
   The builder must not "fix" it by editing the constant; JB-2.20b's spec says which line changes.
3. `refusalFor` returns null for NORMAL and a non-blank sentence naming the mode for every other
   one of the 26.
4. `engineAllowedOn("stamp", INK)` and `("fill", INK)` true; `("smudge", INK)` and `("wet", INK)`
   false with a refusal naming both; every engine true on PAINT. `engineAllowedOn("nonsense", PAINT)`
   is **refused**, not defaulted to stamp (a brush file that failed to decode must not silently draw).

`LayerBudgetTest`:
5. 256 MB / 0 layers → a number > 0, and the exact figure with its derivation written in the test
   (never "assertTrue > 0" for the headline case).
6. Monotonic: more memory ⇒ never fewer affordable; more existing layers ⇒ never more affordable.
7. `memoryClassMb = 0` and `memoryClassMb < 0` ⇒ 0 affordable, never an exception and never a
   negative count.
8. **Overflow, in Long:** `currentLayers = 100_000` and `tilesPerLayer = 1_000_000` with a 256 MB
   class ⇒ 0, and the test asserts the Long total is computed (the Int version would wrap to a
   positive number and pass the "≥ 0" test while being wrong). This is the R19 test.

Source-level checks (a `commonTest` that reads the two view files as text — the only honest way to
pin "the view has no hard-coded list"):
9. `theLayersViewHasNoBlendModeLiteral`: `LayersPanelView.kt` contains no `BlendMode.` name other
   than through `LayerRules.offerable()`.
10. `everyRefusalIsASentence`: every non-null string from `refusalFor`/`refusalForEngine` is
    ≥ 20 characters, ends with a full stop, and contains the thing it is about. (The house rule: a
    refusal is in words, and "invalid" is not words.)

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures (test 2 is expected to be RED until
JB-2.20b, and the spec says so explicitly). Then the watcher green with
`:joybrush-android:compileDebugKotlin` EXECUTED.

## Owner check (Note 9)

Add three layers, name them, drag the middle one above the top → the order changes and one undo puts
it back. Set the middle layer's opacity to 50 % by dragging the row → the art behind shows through
*on screen*. Tap the blend chip → it is greyed and says why. Add layers until the panel says
`N / N` → the "+" refuses in words with the count. Tap a layer's eye → it disappears; tap again →
it returns; **undo does not** touch the eye (nothing changed on the canvas). Turn a PAINT layer to
an INK layer → refused in words.

## Do not

- **Do not implement the GPU blend modes.** That is JB-2.20b (T1). This row adds the FIELD and the
  refusal, not the arithmetic.
- Do not edit `Blend.kt`, `BlendRgb.kt` or `RegionRenderer` — JB-2.20a owns the CPU half and its
  `theParityClaimNamesExactlyTheModesEachSideHas` test, which currently says "27 on the CPU, 1 on
  the GPU" and is **correct**. If this row makes it wrong, JB-2.20b is what makes it right.
- Do not render ink layers. Do not add masks or clipping (JB-2.23), adjustment layers (JB-2.21) or
  a merge-down (Decision 8 offers it only when the layer above is PAINT — do not generalise it).
- Do not hard-code a colour, a mode list, or a memory number in the view.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste; test 2's red-on-purpose state stated)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted
- [ ] committed `JB-2.04: layers panel`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. Written after reading both
JB-2.13a reviews; the corrected finding — 27-on-CPU vs 1-on-GPU, and `GlPaintEngine`'s layer record
having **no blend field** — is Decision 2/4 and test 2.)_

### 🔴 For the Lead

1. **The chip is greyed-with-a-reason rather than hidden.** A mode the GPU cannot composite is not
   offered, but the control that will offer them is present and explains itself. My reasoning: a
   disabled control that says "more blend modes are on the way" reads as a version, and a missing
   one reads as a bug — and the owner is on a Note 9 with a 27-vs-1 gap that will last as long as
   JB-2.20b takes. **The cost of my reading:** a person may tap a dead chip and be told nothing
   they can act on. **The alternative** — hide the chip until JB-2.20b — is one line and I would
   not argue hard against it. Which?
2. **A document that arrives with a mode this build cannot composite.** Decision 3 keeps it in the
   file, shows the layer as composited-as-Normal, and marks it. But that means the person is LOOKING
   at a different picture from the one they saved, on this build only, and the marker is the only
   thing telling them. The alternative is to refuse to open the file in words — which is this
   project's usual posture (see `JbCanvasView.refusalFor`, "this drawing has 3 layers, and this
   screen holds one") and is arguably the more honest of the two. I chose keep-and-mark because a
   refusal costs the person their file to protect a rendering detail, and because JB-2.20b will
   make the marker wrong. **Confirm, or rule the refusal.**
3. **The budget number itself is a product decision in a constant.** `SHARE = 0.5` and the
   per-layer tile estimate are mine, not the owner's, and they decide how many layers a Note 9
   shows. The honest position: the Note 9 reports 256 MB and `JB-0.10` (CPU benchmark harness,
   ⚪ Outline) is the row that measures what a layer really costs. Until 0.10 lands this number is an
   estimate with a derivation in the test, which is the most I can do without a device. **What I
   need ruled:** is 0.5 of the memory class the right share, or should the budget be measured per
   BOARD (a 4K board is 8 294 400 px = 33 MB flat, before any layers) with the board size in the
   formula? I have used the *tile* count, which is sparse and cheap, and that is the number that
   actually predicts the crash.
4. **`JB-0.07` is listed as "Needs" and is `🟧 Built` but not `🟩 Reviewed`.** It is the row whose
   review cleared the GL context-loss BLOCKER, and this row edits `GlPaintEngine`. Is editing that
   file while its row is Built-but-unreviewed acceptable, or does the Lead want the T1 review first?
   I have assumed acceptable (the review is clean and the file's contracts are pinned) but it is a
   question, not a decision.

### Low-risk, ruled provisionally

5. **Visibility is not undoable** (Decision 7). It changes no pixel and Procreate/Infinite Painter
   both treat it that way. Reversible, and the cheapest thing here to reverse.
6. **Ink layers are listed and refused, not hidden** (Decision 9), so a layer the person made is
   never invisible in the list.
7. **The row is `[eye] [kind] [name] [opacity drag] [blend chip] [⋯]`** — one row, no sideways
   scroll (blueprint §3.5: "one row of actions, nothing hidden in a sideways scroll"). The `⋯`
   opens the per-layer menu (rename, duplicate, merge down, delete, convert kind).
