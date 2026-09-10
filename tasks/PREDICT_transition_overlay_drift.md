# PREDICTION — transition/overlay export drift (LEDGER §2d)

**Committed BEFORE the capture, per the house rule that a model must be provably frozen.**
Written 2026-08-03. Device: Note 9 `<note9-serial>`, sole attachment. Rotation lock verified `0`.

## Fixture — project `302da9ac-1b96-48ca-bbe9-c659b4dd3ba0`, UNMODIFIED

Chosen rather than authored so nothing on the user's device is edited. Read-only inspection only;
no write of any kind to the device.

| | |
|---|---|
| clips (in/out) | 3 clips, spans **3200 / 5526 / 5000** ms |
| editor total | **13726** ms (plain sum — `getSegmentStartTime` ignores transitions) |
| transition | GL_SHADER `tangentMotionBlur`, **600 ms**, `clipIndex 0` → seam at editor **3200** ms |
| PiP overlay | `6126e8b4`, `overlayStartMs` = **5501** ms — i.e. **2301 ms AFTER the seam** |
| text overlays | none (so the PiP is the only rider under test) |

## The claim being tested (LEDGER §2d)

The editor timeline ignores transitions; the export Composition is compressed by them; and
`CompositeExportOverlay:449` compares a compressed clock (`presentationTimeUs`) against overlay
start times authored in uncompressed editor time.

## PREDICTIONS

**P1 — the timebases differ.** The exported video stream duration is **≈ 13126 ms**, not 13726.
- If it comes back ≈13726, the export does NOT compress and §2d's mechanism is wrong at step one.
- Measured per-stream (`ffprobe -show_entries stream=index,codec_type,duration,nb_frames`), never
  from the FORMAT duration, which is the max across streams and has misled this project before.

**P2 — the PiP renders LATE relative to its content.** Its first visible frame lands at export
timestamp **≈ 5501 ms** (its authored number, read against the compressed clock).
- **CORRECT behaviour would be ≈ 4901 ms** — the export time at which the same *content moment*
  now sits, 5501 − 600.
- Separation between the two hypotheses: **600 ms ≈ 18 frames @30fps.** Comfortably resolvable by
  frame extraction.

## THE CONTROL THAT MUST DISCRIMINATE

P1 alone is not sufficient: a duration of 13126 proves the export compresses, but says nothing
about where the overlay landed. **P2 is the discriminating measurement**, and its two hypotheses
predict different frames:

| | PiP first frame |
|---|---|
| §2d is RIGHT (drift) | ≈ 5501 ms |
| §2d is WRONG (compensated somewhere) | ≈ 4901 ms |

If the PiP's onset lands between the two, or the PiP is absent entirely, **the experiment is
INVALID and proves nothing in either direction** — record that outcome rather than picking the
nearer number. This project has already spent a session on an experiment whose control could not
discriminate (LEDGER, the 2^61−1 repro); the rule earned there is that an invalid instrument is
reported, not quietly retried.

## What would make this a NON-result

- Export fails, or the PiP is not composited at all → tests nothing about timing.
- The transition is dropped at export (e.g. clamped to zero by `effectiveTransitionMs`) → then
  there is no compression to detect; verify the export duration first (P1) before trusting P2.
