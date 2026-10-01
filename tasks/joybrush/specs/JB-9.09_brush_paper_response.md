# JB-9.09 — Each brush's relation to the paper: Paper influence · Directional · Wet (brush version 6)

| | |
|---|---|
| **Tier** | T1 (pure core) |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | OpenCode free agent |
| **Depends on** | none |
| **Owner area** | EDIT `joybrush/core/.../brush/BrushPreset.kt` (new `PaperResponse`, new field), `BrushJson.kt` (`BRUSH_VERSION`, `VERSION_PAPER`, `wordsNeedingVersion`), `BrushValidate.kt` (ranges), `core/chrome/BrushKnobs.kt` (three knobs); EDIT shipped brush files under `joybrush/brushes/` (values only, see Decision 4); tests: `EnumFreezeTest`, `BrushJsonTest`/`BrushValidateTest`, `BrushKnobsTest`/`TuftTuningTest` as they pin knob lists, `ShippedBrushFilesTest`, `DefaultPresetsTest` |
| **Estimated size** | ~90 lines + ~150 lines of tests |

## Goal
Owner P4: the paper is universal. Every brush feels it by default, at a strength that suits it, and holding a brush shows that strength
as sliders with the live preview (memory: hold a brush = its advanced settings). This row adds the three numbers. The engine reads
them in JB-9.08 (Codex). Until then they are stored, validated and tunable, with no visible effect, and the knob hints say so (Decision 5).

## Contract (verbatim)
```kotlin
// BrushPreset.kt
@Serializable data class PaperResponse(
    val influence: Float = 0f,     // 0 = ignores the document paper … 1 = feels it fully. 0..1
    val directional: Float = 0f,   // 0 = deposit ignores stroke direction … 1 = full (R10 §5). 0..1
    val wet: Float = 0f,           // 0 = dry: rides the peaks … 1 = wet: pools in the valleys. 0..1
)
// in BrushPreset, after `response`:
    val paper: PaperResponse = PaperResponse(),   // version 6 when not default (R10, JB-9.09)

// BrushJson.kt
const val BRUSH_VERSION = 6
/** The brush version that introduced `paper` (JB-9.09). */
const val VERSION_PAPER = 6
// wordsNeedingVersion: a non-default `paper` needs VERSION_PAPER (the LOWEST-version rule stays: a brush whose paper is default
// still writes at the lowest version that expresses it).

// BrushKnobs.kt: three knobs, for EVERY engine except smudge/push/fill:
//   "paper.influence"  "Paper"        "How much this brush feels the paper's surface."
//   "paper.directional" "Direction"   "Dry paint catches the side of each bump that faces the stroke."
//   "paper.wet"        "Wet"          "Dry rides the tops of the paper; wet sinks into the dips."
```

## Decisions already made
1. **Default 0/0/0** in the type, so an old brush file means exactly what it meant.
2. Placement in `forBrush`: after the engine's own knobs and before Smoothing. The existing "Paper grain" knob (`stamp.grain`, the depth)
   stays where it is.
3. Validation: each value finite and in 0..1, else a `BrushValidate` sentence naming `paper.<field>`.
4. **Shipped values (the owner tunes on the Note 9):** Pencil 1 / 0.6 / 0 · Sable 1 / 0.4 / 0 (its tooth already reads the paper) · Ink 0.15 / 0 / 0 ·
   Marker 0.35 / 0 / 0.6 · Soft air 0.2 / 0 / 0.3 · Eraser 0 / 0 / 0 · Smudge, push, fill: absent. Brushes that change become version 6.
   A test asserts that only these files changed version.
5. Until JB-9.08 lands, each hint ends with " (takes effect when the paper engine lands)". JB-9.08 removes that tail (say so in its report).

## Tests
- Round trip: non-default `paper` encodes and decodes equal and writes `version: 6`. A default `paper` writes the old lowest version.
- A version-5 file that contains `paper` is refused: "paper needs brush version 6".
- `EnumFreezeTest` pins `BRUSH_VERSION == 6`.
- Validation: −0.1, 1.1 and NaN for each field are refused, naming the field.
- Knobs: Pencil's knob list contains the three keys in the stated position; Smudge's does not. Each knob's get/set round-trips 0, 0.5, 1.
- Shipped brushes: values as in Decision 4.

**Command:** `./gradlew --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks` in your worktree (brush JSON is not a declared input, so `--rerun-tasks` is required). Paste counts.

## Do not
Do not touch shaders, `GlPaintEngine`, or anything in androidkit. Do not change `GrainSpec`.

## Definition of done
- [ ] tests pass with counts · [ ] mutation: drop the version word → the refusal test goes red · [ ] owner area only · [ ] pushed · [ ] ROADMAP → 🟧 Built

## Questions
