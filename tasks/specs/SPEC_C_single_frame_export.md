# SPEC C — Export a single frame as JPG or PNG

**Difficulty: MEDIUM. There is ONE trap that makes the obvious implementation wrong.**
**Read `_RULES_READ_FIRST.md` first.**

## What JoyRaptor asked for, verbatim

> "Export should give the option of exporting a single frame as JPG or PNG. Either where the
> playhead is, or the user inputs a time."

## The task

Add a single-frame image export: a format choice (PNG default, JPG optional), a source time
defaulting to the current playhead, and a field to type an exact timecode. Output at the PROJECT'S
EXPORT RESOLUTION.

## THE TRAP — read before writing any code

The obvious implementation is to screenshot the preview surface. **That is wrong.** It produces a
file that:

- contains editing chrome — the crop overlay, safe-zone guides, selection handles, transform
  handles, the keyframe ribbon;
- is at PREVIEW resolution, not export resolution;
- may be missing GL-composited layers depending on which capture path you take.

**Do it this way instead.** `export/CompositeExportOverlay.getBitmap(...)` already composes exactly
the right thing for a given timeline millisecond during a video export: clip picture + grade + spine
FX + PiPs + adjustment layers + image overlays + text + captions + waveform, with the clip's
opacity/fade applied to the CLIP ONLY (a ruling from 2026-09-02 — a clip's fade must not dim the
overlays above it). Reuse that path.

If you grow a second compositor, preview/export parity will drift the way it has repeatedly in this
project. One composer, called twice.

## Other things to watch

- The caption renderers hold per-frame caches that assume a monotonically advancing clock. Rendering
  one arbitrary frame may need them reset — check `export/CaptionExportRenderer` and
  `compositor/CaptionTextureCache`, and say what you found.
- Write the file where video exports already go. Do not invent a new location or a new permission
  flow — scoped storage on Android 13 has bitten this project before.
- JPG has no alpha channel. If the composed frame carries transparency, state what you do —
  flattening onto black is the sane answer, but say so in the UI rather than silently.

## Acceptance criteria

1. The exported image is pixel-identical to the corresponding frame of a video export at that time —
   or state precisely why it cannot be, and by how much it differs.
2. No editing chrome anywhere in the output.
3. Output dimensions equal the project's export resolution.
4. A typed timecode outside the project's range is rejected gracefully, not clamped silently.
5. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL after your last edit.

## Deliver

Which composing path you reused and the evidence it is the same one video export uses; what you did
about the caption caches; the output location; the build verdict; compile-verified vs device-verified.
