# Note9 actual capture replay parity — October14

Source `ccb4913bfdaa16f7f4f5173335c639fedf9c4f02`, arm64 APK SHA256
`D2FBA4C2857D78D98600509BBD336F8DFEED5BCC5B5DD5FC7613D270A8602346`.
App assembly PASS1m33; native193/0/0/0 in22 suites independently XML counted,
PASS3m. In-place install changed update time and installed bytes matched the preserved APK.
Fresh sandbox identity, awake/unlocked and registered Home checks preceded installation/runs.
No device identity is included in these artifacts.

Both `recordedCaptureProbe` reports passed: Ink normal and the built-in Watercolour multiply.
Each dispatched24 synthetic stylus MotionEvent samples over384ms through actual View capture,
with pressure0.7, tilt0.4, orientation0.3. Actual post-guide raw samples, frozen effective brush,
seed and first-dab properties then replayed on the original engine/context into a temporary
scene. The host verified the empty physical plane before DOWN, its capture publication token,
and exactly one scoped legacy stroke. WC was settled before comparison; pooling stays full resolution.

Each report has2 actual/derived tiles, exact rawRGBA8 capture-versus-replay comparison and
original-pixels-unchanged comparison for both tiles. All8 rows have zero differing channels,
pixels and byte delta. Actual/derived nonzero-alpha counts agree: Ink539, WC1430. Across both:
24 external GL checks/zero errors,4 framebuffer checks/zero incomplete,2 external cache-versus-
history/live-handle checks/zero aliases. Both temporary scenes closed, original engine not
released; history/document/paper/live pixels mutation guards passed. Internal engine checks
are not counted. Qualcomm Adreno630, GLES3.2 V@415.0, same retained driver as bake evidence.

Working archive was present and its hash unchanged. This is synthetic input through actual
live raw capture and legacy GPU commit. Shipping recorded publication, selection, Working/
recovery installation and physical artist-quality acceptance remain unfinished.

Same-APK bake regression also passed five stamp operators plus normal WC,4 actual tiles each:
144 exactRGBA8 rows/zero differing channels,12 real GPU-source archive roundtrips/120 payload
comparisons, public undo/redo and seq/operator preserved. External318 GL/240 FBO/288 ownership
checks, zero failures. First request mistakenly named `recordedBakeNormalProbe`, which is not
a case and produced4/5 reports; the follow-up actual `recordedBakeProbe` produced2/2 normal
Ink/WC reports. The six completed cases are the union, not a claim that the first request passed.
Both bake Working receipts also report present and unchanged.

Full capture JSON: `evidence/NOTE9_CAPTURE_PARITY_20261014.json`.
Current bake JSON: `evidence/NOTE9_BAKE_CURRENT_OPS_20261014.json` and
`evidence/NOTE9_BAKE_CURRENT_NORMAL_20261014.json`. Historical bake proof remains
`NOTE9_BAKE_ARCHIVE_20261014.md`; no earlier APK identity is relabeled as this source.
