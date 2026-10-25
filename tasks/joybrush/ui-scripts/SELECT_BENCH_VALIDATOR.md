# Offline Select bench evidence validator

Run with Python 3 and the standard library only:

```text
python validate_select_bench.py <out-dir> --manifest <manifest.json> --run-id <ID>
python -m unittest -v test_validate_select_bench.py
```

The caller supplies a manifest with this shape (step names and screenshot names are intentionally not hardcoded):

```json
{"version":1,"steps":["step-a","step-b"],"screenshots":["01_step-a.png","02_step-b.png"]}
```

The output directory must contain `results.jsonl`, `build.json`, `temps.txt`, and exactly the manifested PNG files.
Each step report has `selectBench`, `runId`, and boolean `pass`; the unique summary has `selectBench:"summary"`,
`runId`, integer `passed`, and integer `failed`. `build.json` must provide `source40hex`,
`expectedApkSha256`, `installedApkSha256`, `runId`, `deviceModel:"SM-N960U"`, and `deviceApi:29`. APK digests
must match. Temperatures must include one finite numeric value each for `temp_before_C` and `temp_after_C`.

The validator checks internal identity fields, report completeness/counts, PNG container/chunk integrity (including
chunk CRCs, a nonempty IDAT chunk, IHDR dimensions, and a complete IEND), and file presence. It does not decode image
pixels or claim visual proof. It also does not
prove that the source revision produced the APK, that the APK was installed on the stated device, that gestures came
from physical input, or that screenshots visually show the claimed UI. The caller must establish artifact mapping and
device readiness and provide the expected manifest. Passing means only that the supplied evidence is internally
consistent and complete under this contract.
