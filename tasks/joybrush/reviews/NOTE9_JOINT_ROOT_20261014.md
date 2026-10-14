# Note 9: joint root GPU evidence — 2026-10-14

The exact source revision `5b3ebdcaf355801cc58c07db1dddf0d5347e7ae5` produced the installed APK whose SHA-256 is `6f1176ac8e3d28ba60b6aaa87375ec01225d498ed9f230a358ad23be34cdd40b` (matches the APK receipt). The install receipt records an update-time change and identifies both brushes and both probe cases. The captured report set has four reports, all `passed: true`, with four unique case/brush/open-mode combinations:

| Probe case | Brush | Mode | Comparison rows | Result |
|---|---|---|---:|---|
| `recordedJointProbe` | `joybrush.ink` | eager | 141 | 0 differing channels/pixels; max byte delta 0 |
| `recordedJointProbe` | `joybrush.watercolour` | eager | 141 | 0 differing channels/pixels; max byte delta 0 |
| `recordedJointColdProbe` | `joybrush.ink` | cold | 137 | 0 differing channels/pixels; max byte delta 0 |
| `recordedJointColdProbe` | `joybrush.watercolour` | cold | 137 | 0 differing channels/pixels; max byte delta 0 |

I independently counted each `comparisons` array: row counts exactly match each report's `comparisonCount` (total 556). Every row has zero `differentChannels`, zero `differentPixels`, and zero `maxByteDelta`; aggregate checked volume is 145,752,064 RGBA channels across 36,438,016 pixels. Each run's actual old/new union contains 8 tile keys, within its declared 16-key cap.

Across the four runs: 572 readbacks, 676 GL error checks, 572 complete-framebuffer checks, and 744 feedback/ownership checks. There were 0 observed GL errors, 0 incomplete framebuffers, and 0 feedback failures. Each report records 5 published-root identity checks and 4 public-history endpoint checks; each also records 4 native API checks, 4 `readTile` checks, 4 untouched-native-sibling checks, 48 retained independent-source checks, and 32 physical-slab checks. Eager runs each record 2 opening line replays, 12 opening compositions, then 1 line replay and 8 compositions for the first edit. Cold runs each record 0 opening line replays/compositions, 8 saved-look uploads, then 2 line replays and 8 compositions on the first edit. All four ran one bind, publish, undo, redo, and reset; engine-release GL errors are zero.

The working receipt says the Working archive was present and unchanged, all three archives in the app working-copy directory were hash-checked and unchanged, and four expected reports were captured. This joint probe did not archive/recover drawings. `joint-space-apk-receipt.json` reports APK assembly PASS and `gpuExecuted: false`; the four captured reports are the separate device-executed GPU evidence.

Scope limits: these results exercise actual joint engine root and public undo/redo with canonical GPU line/look comparisons for the listed Ink and Watercolour fixtures. They do not establish optional native-media execution, native-base promotion/projection in a live engine path (the support proof classes are pure support only), view activation, Select, save activation, archive roundtrip, general brush compatibility, quality, or performance. A later caller-thread load-admission fix is outside this exact APK and is checked separately by the native regression.

The independent machine-readable recount is in `NOTE9_JOINT_ROOT_20261014.json`.
