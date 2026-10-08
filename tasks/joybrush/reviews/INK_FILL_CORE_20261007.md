# R51 line-side groundwork — Codex, October 7

Scope: JB-5.02 selection; JB-5.12 CPU ink tiles/RegionRenderer/ORA; JB-5.13 fill tracing. Separate worktree C:/Temp/jb-region-routing. No phone operations, model/schema/undo/media/GL changes.

## Selection
Published 3ee46253: deterministic closest-remaining-window extraction; cycle by ordered position, independent of duplicate ids. Changed candidate geometry resets cycling. Session reconstruction correction: compare deep geometry snapshots instead of object identity; independent worker2 review clean, actual session rapid cycle verified. Subsequent targeted XML29 picker+22 session=51, zero failures/errors/skips. Targeted XML: 26 tests, zero failures/errors/skips.

## Ink tiles
InkTiles prepares replay once per cel, renders records in list order, preserves their blend modes, and returns null for transparent tiles while retaining indexed refusal reports. RegionRenderer uses the existing ownership projection and optional brush/record lookup; no lookup retains its previous refusal. Original trailing-lambda APIs remain available. ORA exports ink layer PNGs, merged image and thumbnail, with built-ins by default or caller-provided lookup for custom presets. Incomplete ink throws by default; an explicit refusal receiver can accept reported omissions.

The first full regression (1733 tests, four optional corpus skips) caught one new fractional seam equality failure. Existing Float dab-minus-origin subtraction rounded a tile differently from a larger rectangle (one-byte difference). Corrected InkRaster to compute document pixel-centre offsets in Double, converting only the final local offset to Float. Tests retain exact byte equality at positive/negative boundaries; new float crop tests cover 0.5/1/2 scale and fractional RefCanvas parity.

Limits: CPU procedural tips only; image tips and tip grain explicitly refused. Paper appearance is not reproduced here. Brush copies frozen into the document remain JB-5.20a; current ORA can be supplied an exact brush lookup. No GPU/live-appearance or phone speed claim.

## Fill trace
Exact .75 document-pixel square growth matching FloodFill's metric, after gap closing with integer grow=0. Expanded row-run union is traced once; holes have opposite winding, connected by exactly reversed zero-width orthogonal bridges. One filled record avoids repeated translucent compositing. Collinear reduction has zero geometric error; stored smoothing0/zoom4 retains quarter-grid corners through existing replay resampling. Empty regions yield no records; actual bounds and coordinate precision are checked with refusal text.

Ten tests include an independent supersampled rectangle-union oracle over forty generated masks, holes and collapsed holes, disconnected/diagonal components, negative origins, gap closing and alpha. This is core geometry only; tap-fill screen wiring waits for the Lead's JB-2.40 gate. JVM2048 dense-art timing is being measured by JB-5.14 worker1.

## Coordination
JB-5.20b is stopped at a concrete D6/D8/D12 blend/baking contradiction, recorded in JB-5.20b_BLEND_QUESTION.md and LEAD_DESK for Claude's ruling. Worker1 owns measurement fixtures in its separate checkout. Media's four slices are not called complete until all requirements pass; no unposted media implementation approved.

Final tile/fill validation: full core1735 (four optional corpus skips), backend314; zero failures/errors, 5m23s. Subsequent selection-session follow-up: 51 targeted checks passed (29 picker,22 session), 2m19s. Test commands take jb-gradle.lock atomically, pause the exact primary watcher tree and restore it afterward; XML is counted only after a successful run.
