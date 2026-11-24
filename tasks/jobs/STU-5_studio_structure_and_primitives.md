# STU-5: Studio's structure and new primitives

**Milestone:** M1 (structure) / after launch (primitives)  ·  **State:** 🟡 time authority built for the export, agent phone A/B done; the rest planned  ·  **Owner:** open (Opus; one at a time, they touch the editor core)  ·  **Checked:** 2026-11-12

## Why it matters
Plans from the Studio lead and the owner that fell out of the 10-27 roadmap (rows in [`FUTURE_PLANS.md`](../FUTURE_PLANS.md) §1.2).
The structural ones make every later Studio fix cheaper and stop "fiddly" bugs; the primitives are the owner's asks.

## The pieces
| Piece | Source | State |
|---|---|---|
| **One authority for timeline time to source time** (four copies disagree; "the single most valuable structural fix left") | FUTURE_PLANS 1.2 S6 | **export half built 11-12**: `model/TimeMap` places sound lanes, picture-in-picture sound, the black tail, range cuts and a clip's own clock after a transition (code review 11-11 D5, D6, E2). Note 9 A/B done by an agent (numbers in [the code review](../audits/code_review_20261111.md) §A and STATE §2). Left: the editor side (seek at a cut D7, rounding D9, the playhead during a blend), E5 and the blend's missing fades, D2, D3, D8 (owner ruling), D10, D11 |
| **One snapping authority, app-wide** (owner: "fiddly"): the playhead snaps to keys, objects align to each other | 1.2 S7 | magnet built; the rest not |
| **Export speed**: the one-pass GPU stage, the owner's "one tap, fast" | 1.2 S17; old roadmap 1.9 | not built. 11-12: per-frame picture copies removed from four passes (code review E17); a 9 min 20 s export measured on the Note 9 at an even 1.2x real time, no slowdown. Next speed lead from its profile: Canvas blits and GL draws dominate, copies were about 1% |
| **Primitives**: a Path object, text on a path, per-letter channels, paths as masks and motion paths | 1.2 S8, owner 09-24 | not built |

## Done when
- [ ] Each piece is phone-proven with a STATE row, or parked by the owner.

## Files and traps
- `FaditorEditorActivity` is 45,000+ lines: new code goes in small classes (owner rule), legacy kept until the new is better.

## One snapping authority: written up for an Opus session (2026-11-24, Sonnet lane, queue row 25)
Not a contained change, so not started. Facts a builder needs, from the code today:
- **Where snapping lives now:** `FaditorEditorActivity` (540 mentions), `timeline/EditorTimelineView` (50), `overlay/TextOverlayLayer` (30), `model/Clip` (24), `overlay/PreviewHandlesOverlay` (18, a private `snapRotation`), `crop/CropOverlayView` (16), `puppet/PuppetDrawerTabs` (3), word sync and onset code (`transcript/WordSyncMode`, `WordSyncOnsets`, `waveform/OnsetDetector.snapToleranceMs`), and a private pivot snap in `TextOverlayItem.snapPivotAxis`. The magnet panel (`SnapSettingsSheet`, `tools/SnapSettings`, `Kind.BEATS` and the rest) already holds the person's switches and strengths.
- **What "one authority" means here:** every drag that can snap (playhead, clip edges, objects on the canvas, rotation, crop, tape ends) asks ONE pure class for "given this position, these candidate points, this tolerance and these switches, where do I land and what did I snap to", and the magnet indicator reads what it answered. Today each caller keeps its own candidates and tolerance, which is why the same drag can snap in one place and not another.
- **Why not now:** it touches the editor core and the timeline's touch code that the timeline-clock work (`TimeMap`, STU-5's other row) also touches; the feel (tolerance in pixels, strength) can only be judged by hand on a phone. Do it after the clock work, one editor session at a time, with a pure `SnapAuthority` class and tests that pin today's tolerances first.
