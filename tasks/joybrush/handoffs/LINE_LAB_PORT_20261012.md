# Line Lab scoped port — 2026-10-12

Original Line Lab implementation and candidate pencil tuning belong to the vector specialist,
copied from `origin/vector/line-lab` commit `7366c3048dbea55d013e930d02ab4b153b0a14eb`.
Only these eight new files were imported: `core/vector/LineLabSheetTest.kt` (JVM test) and
the seven files under `joybrush/lab/lines/`. No branch core runtime, shaders or shipped presets
were copied. No merge or conflict resolution occurred; preserve the original attribution.

The existing renderer supports `--shaders <dir>` and `u_grainCapacity` from the preset's
`extensions.deposit = "tooth"`. The imported JVM sheet writer matches the current 17-float
stamp ABI and paper APIs. Added checked preset decoding and parse/instance-count gates on the
generated JSON, so invalid fixture data cannot proceed to the GPU image comparison.

Root runs `LineLabSheetTest` through the serialized builder. It writes `pencil.json`, `ink.json`,
`pencil_p1.json`, and `pencil_p2.json` under `joybrush/core/build/linelab/`, plus a summary and
any required packed surface asset. Root's serialized GPU runner produces the four corresponding
PNG sheets; the port itself has not generated or judged those pictures.
