# First consolidated performance batch

Root reviewed and integrated299d3288 (render parity checker), f409dfa6 (submission traces), f6ea5b23 (direct upload reuse), then the conditional media paper-read shader.

Actual checker: Edge/SwiftShader,80 cases/163840pixels, all RGBA8 bytes including alpha equal, maxDifference0/mismatch0; separate broken control56 supported/56 detected, GLerror0. Candidate file SHA256094100f3eb063386e4e21855af9e0ff9d60fea5e5e9ee1263f1623202a71a015 exactly matches root shader. Retains canonical reads for nonzero relief and height-debug mode1. This is visual output parity on the desktop fixtures, not a phone performance result.

Actual combined Androidkit compile plus FloatUploadBufferTest:12 tests, zero failures/errors/skips,1m45s. Worker-tested implementation matches integrated source. Engine-owned native direct buffers reuse capacity for dry/erase/wet uploads; same Float bytes, upload sizes/usages/order. Reset on sleep/release drops retained reference. Desktop JVM NaN payload/signed-zero checks pass; ART verification pending.

Trace markers are disabled unless Android tracing is enabled and SDK>=29. Markers show CPU command submission, not GPU execution. No changed precision, brush equations or input density.

No speed comparison measured. One original-draw GPU query cannot establish a speedup. Three-worker trial had about17s measured overlap, lowest observed free RAM852MiB; true three-worker peak and user interface responsiveness were not instrumented. All completed free workers reported cost0; paid supervision token/dollar totals unavailable.

Next gate: same-input phone CPU/GPU timing, latency and sustained fast strokes, plus device pixel/ART checks. Multi-window clipping, tilt calibration and memory refusal remain separate unresolved issues.
