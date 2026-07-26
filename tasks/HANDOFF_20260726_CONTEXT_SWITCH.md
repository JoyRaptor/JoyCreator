# HANDOFF 2026-07-26 — context/account switch

---

## 0. CURRENT STATE (updated 10:20) — READ THIS FIRST

HEAD `0c13dca`, branch `joy-creator`, tree clean except the always-ignorable
`tools/jvm-harness/out*/`. Build watcher ALIVE, installing to the **Note 9 only** (Note 20
unplugged). APK 10:11:27, installed, newer than every source file.

**Pick up here:** the `hasValue()` null-guard sweep (commit `0c13dca`) did the CLIP
deserializer — 44 sites — and left roughly 175 more in the audio, textOverlay and sprite
deserializers plus the transcript/keyframe/effect sub-objects. The helper exists, the pattern
is one token per site, and the device acceptance test is written out in that commit message
(explicitly null every optional field of one payload type, confirm the project loads under its
OWN name with object counts unchanged). That is the highest value-per-risk work remaining and
it is batch-able: one payload type per commit.

**All 10 real sandbox projects are sha256-identical to their safety copies.** Every experiment
in this run used a throwaway `cp -r` clone; nothing needed restoring. Stray artifacts left
deliberately on the sandbox: a handful of 480p test exports in `FadCam/Faditor/`.

### Audit items closed in this run (see the audit's STATUS BOARD for the live list)

1.2, 1.3, 1.4, 2.1, 2.2, 2.4, 2.5 (export leg), 2.6, 2.7 (items 2/3/8), guard hygiene, plus
the timer-export answer owed in §7 and the ROWGESTURE logging in Tier 4. Each has its own
commit whose message states what was proved and how.

**Three of them found more than the audit described** — worth knowing, because it means the
audit's wording is a starting point, not a spec:
- **1.3** is not "drops an object". For 3 of 4 sites the loader treats the file as corrupt and
  silently opens `project.json.bak`. Reproduced: a fixture opened titled "ROLLED BACK TO BAK".
- **2.4** has the direction BACKWARDS. The export was correct; the PREVIEW over-rendered the
  neighbouring clip's words. Fixing the export on the audit's wording would have broken it.
- **2.1** needed a second fix: any re-bind blanked captions while paused, so the slider erased
  the very thing it was meant to show.

### Verification tooling in `tasks/` — REUSE, do not rebuild

- `export_ab_diff.py` — decode two exports, diff PIXELS. `--check-asym` refuses a fixture
  symmetric enough to hide a flip. Proven on 2.7 item 8 and cross-type Z.
- `export_audio_probe.py` — fit a source's amplitude inside an export. 1.0 = mixed once,
  2.0 = doubled. `--expect-absent` for a not-present control.
- `schema_layer_stamp.py` — schema-stamp survey + corruption repro + 4 source tripwires.
- `getlayers_equiv.py`, `visible_equiv.py` — both now hard-fail on zero matched files.

### The habit that actually produced these results

Every proof carried a POSITIVE CONTROL, and that is not ceremony. **Five times in this run a
confident finding turned out to be my own harness**, and each was caught only by a control or
by looking at the actual pixels:
- a 3-way export comparison gave three byte-identical files → my setup deleted only the
  same-id fixture, so "row 1" kept opening the wrong project;
- a cross-encode pixel diff at threshold 8/255 read lossy residual as signal and made a clean
  cross-type-Z result look broken;
- a correlation probe nearly as long as the export had no lag headroom and fitted gain −0.204
  where the truth was 0.993;
- "the lane eye does not persist for text or sprite" — I grepped for `hidden`; the key is
  `objHidden`;
- an export "truncated with no moov atom" was me `am force-stop`-ing during muxing.

If a proof reports "no difference", assume the instrument is blind until a control says
otherwise.

### Next, in the audit's own order

1. **2.3** preset crops during transitions. Deliberately not started — symmetric three-site
   change in the GL transition compositor, and starting it without room to device-verify is
   worse than not starting. **The executable recipe is now written into
   `PERF_SPEC_LONGFILE_20260718.md`'s F12 RE-SCOPED block**, including "capture the FAILING
   baseline before touching code".
2. **Tier 3** items, then Tier 4.
3. Still open and needing a HUMAN, not a harness: 2.5's preview leg (someone must listen to a
   PiP), 2.5 acceptance 4 (needs a real dual-stream pair project — none exists on the
   sandbox), and 2.7 items 5–7 (gesture drag-and-drop; adb injection drifts, and this is why
   `ROWGESTURE_DEBUG` was turned off rather than deleted).

### Found in passing, NOT fixed

1. After an undo with the caption drawer open, the preview canvas collapses to a thin strip
   for a frame or two; it recovers on the next playhead move.
2. `ProjectStorage` has ~223 typed JSON reads guarded by `.has()` alone (78 `getAsString`,
   57 `getAsFloat`, 38 `getAsLong`, 34 `getAsBoolean`, 22 `getAsInt`; only 6 null-guarded).
   Audit 1.3 fixed the 4 named `layerId` sites; the rest want one `optString/optFloat` helper
   and a mechanical sweep, with its own proof.

### Device + harness traps (each cost real time)

- Screenshots via the **Bash** tool (`adb exec-out screencap -p > f.png`); PowerShell's `>`
  adds a BOM and corrupts binaries. Same for `adb shell cat` of JSON — read with `utf-8-sig`.
- `adb push` via **PowerShell**; from Bash `/data/local/tmp` becomes
  `C:/Program Files/Git/data/local/tmp` (or prefix `//`).
- `touch` does NOT trigger Gradle's watcher (content hash, not mtime).
- Foreground `sleep` is blocked; wait with a backgrounded `until [ "$APK" -nt "$SRC" ]`.
- An export of a 4-second project takes **~55 s**, not the ~15 s the dialog implies. Poll for
  output-size stability before touching the app.
- Never compare mp4 hashes (container bytes differ, frames identical).
- `FaditorEditorActivity` is not exported — `am start` cannot open a project. Use the UI:
  Faditor `(627,2108)`, row 1 `(538,705)`. **Purge every non-real project dir first**, or
  row 1 is ambiguous.

### Fixture recipe (how every proof here was made)

`adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>`, patch
the JSON on the host, `adb push` to `/data/local/tmp`, then
`adb shell "cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'"`.
Set `lastModified` to now so it sorts to row 1. Delete the clone when done and re-verify the
10 real projects by sha256.
Projects: `/data/data/com.fadcam.beta/files/faditor/projects/<id>/project.json`
Exports: `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`

---

Landed in the first stretch, each with its own offline proof and device verification:

| commit | audit item | proof |
|---|---|---|
| `b8b6234` | guard hygiene | both python guards now hard-fail on zero matched files |
| `d77daf3` | **1.2** LAYER schema hole | `tasks/schema_layer_stamp.py` (survey + corruption repro + guard + 4 source tripwires); device 7→11 with a LAYER def, stays 7 without |
| `eaff34b` | **2.1 + 2.2** caption size | device A/B 3%↔20% live in preview; audio size 0.15 round-trips. Also fixed: any re-bind blanked the captions while paused |
| `7a09eb6` | **1.3** `layerId: null` | device: 15 objects in → 15 out with 4 explicit nulls; control (`cropPreset: null`) silently opens the older `.bak` |
| `6851eca` | **1.4** downgrade drill | drill RAN 7/7; found + fixed the undo-history sidecar being written for a read-only project |

**Device hygiene:** every experiment used throwaway `cp -r` clones. All 10 real sandbox
projects are **sha256-identical to their safety copies** — verified after the last
fixture was removed. Nothing needed restoring. One stray artifact left deliberately: a
480p export in `FadCam/Faditor/` on the sandbox from drill step 6.

**Reusable fixture recipe** (this is the machinery to reuse, it worked well):
`adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>`,
patch the JSON on the host, `adb push` to `/data/local/tmp` then
`cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'`.
Bump `lastModified` to now so it sorts to row 1 of the project list, then tap
Faditor `(627,2108)` → row 1 `(538,705)`. Delete the clone when done.
`FaditorEditorActivity` is **not exported**, so `am start` cannot open a project directly.

**Newly known traps:**
- Screenshots must be captured with the **Bash** tool (`adb exec-out screencap -p > f.png`).
  PowerShell's `>` corrupts binaries with a BOM. Same for `adb shell cat` of JSON — read
  those with `utf-8-sig` or use Bash.
- `adb push /data/local/tmp/...` from **Bash** gets MSYS-mangled to `C:/Program Files/Git/data/...`.
  Use PowerShell for `adb push`, Bash for `exec-out` redirects.
- `touch` does NOT trigger Gradle's continuous build (it hashes content, not mtime). To
  force a rebuild you need a real content change.
- Foreground `sleep` is blocked by the harness; wait on the APK with a backgrounded
  `until [ "$APK" -nt "$SRC" ]; do sleep 3; done`.

**Two things found in passing, NOT fixed, worth their own look:**
1. Immediately after an undo with the caption drawer open, the preview canvas collapses to
   a thin strip for a frame or two; it recovers on the next playhead move.
2. `ProjectStorage` has ~223 typed JSON reads guarded by `.has()` alone (78 `getAsString`,
   57 `getAsFloat`, 38 `getAsLong`, 34 `getAsBoolean`, 22 `getAsInt`; only 6 null-guarded).
   Audit 1.3 fixed the 4 named `layerId` sites; the rest want one `optString/optFloat`
   helper and a mechanical sweep, with its own proof. See `7a09eb6`.

Next per the audit's own order: **2.5** (PiP audio plumbing), **2.6** (cross-type Z
absolute-geometry A/B), **2.7** (neutral substrate queue items 2,3,5–8), then **2.3**
(preset crop — read the F12 RE-SCOPED block, not the older note).

---

Written at the end of a long Opus 5 session because the account ran out of credits and work
continues on a **different login, same machine**. Everything below lived only in that
session's context and would otherwise be lost. HEAD at write time: `11c4927`, tree clean
(except the always-ignorable `tools/jvm-harness/out*/`).

---

## 1. The transcript migration RAN and is VERIFIED

Observed live on the user's phone at 01:21 and confirmed on disk:

```
27221664  collapsed=11 recovered=2824 shared=7    <- legacy partitions reconstructed
a32d24e2  collapsed=26 recovered=0    shared=8    <- modern full copies, collapse only
00024cc7  collapsed=4  recovered=0    shared=2
66623e32  collapsed=11 recovered=0    shared=2
```

On-disk proof for the legacy project, before vs after:

```
BEFORE  78253218: [141, 272, 524, 1291]   (four partial forks)
AFTER   78253218: [2228]                   (one shared, complete)
        bdb7ca98: [173,262,506,1261] -> [2202]
        synth_17: [182,258,506,1266] -> [2212]
```

2824 = exactly the offline prediction. Every clip now shares one complete transcript.

**Follow-up already applied (`ProjectStorage.shareTranscriptsWithBackup`):** because each
clip still SERIALISES its own copy, forks reappear on every load and this migration runs
every time. It was writing a fresh multi-megabyte backup on each app launch — two 5.3MB
files from a single launch were measured. It now only backs up and rewrites when
`recovered > 0` (a real reconstruction); a pure in-memory re-collapse persists nothing.
The permanent fix is the deferred file-level dedup (serialise each transcript once, clips
hold ids) — see `SPEC_TRANSCRIPT_SHARING.md` open item 4.

**Housekeeping owed:** the redundant `project-preshare-*.json` files already written on the
user's device can be deleted; the migration has succeeded and byte-exact originals are in
the safety copies below. Total project dir is 213MB.

## 2. SAFETY COPIES — read-only originals of the user's real projects

```
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note20-projects\27221664.json   (1,149,763 bytes)
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note20-projects\a32d24e2.json   (5,337,181 bytes)
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note9-projects\proj_*.json      (10 sandbox files)
```

These are byte-exact pulls taken BEFORE the sharing migration existed. They are the recovery
path if the migration goes wrong. **Do not delete them.** The migration also writes its own
`backups/project-preshare-<ts>.json` on device, but that is one layer; these are the other.

## 3. DEVICES — two phones, different everything

| | Note 9 (sandbox) | Note 20 (THE USER'S REAL PHONE) |
|---|---|---|
| serial | `SANDBOX_SERIAL` | `REAL_SERIAL` |
| model | SM-N960U, Android 10 | SM-N986U, Android 13 |
| screen | **1080x2220** | **1440x3088** |
| holds | disposable sandbox projects | **the user's real work** |
| attached now | NO | yes |

**Tap coordinates are NOT portable between them.** Everything below is Note 9 / 1080x2220
ONLY and must be re-derived from a screenshot on the Note 20:
Faditor nav icon `(627,2108)` · project row 2 `(538,896)` · play/pause `(540,1306)` ·
split `(387,1305)` · export icon `(1013,55)` · export-now `(758,1625)`.

`adb` is NOT on PATH:
`C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`

**DANGER — the build watcher auto-installs to whatever single device is attached.** Saving a
source file will push a build to the user's real phone. That is fine for shipping a fix and
bad for anything experimental. Prefer the Note 9 for experiments; if only the Note 20 is
attached, think before saving.

## 4. Hard-won operational lessons from this session

- **Never `adb logcat -c`.** Clearing the buffer during a reinstall destroyed the one capture
  of the bug being hunted. It was only recoverable because a copy had been saved seconds
  earlier. Just note a timestamp instead.
- **A "compile-green" claim means nothing without a fresh APK timestamp.** This session opened
  by discovering the previous session's ~35 commits had NEVER been compiled: the watcher had
  died and a stale-from-07-06 javac intermediates dir made every build fail *before*
  type-checking, hiding a real error. Check `dumpsys package … lastUpdateTime` against
  `git log`, and that java processes exist.
  Fix for that failure: `Remove-Item app/build/intermediates/javac/<variant> -Recurse`.
  Never `gradlew --rerun-tasks` (corrupts media3-patched jars).
- **Validate data-touching changes offline against real pulled files BEFORE shipping.** A
  first-wins transcript merge looked obviously safe, and would have replaced a 1112-word
  transcript with a 1110-word one on the user's live project. It was caught only by a
  word-count guard added on a hunch. The rule now: pull the real file, run the algorithm on
  it in Python, print the before/after, THEN write the Java.
- **The device is the user's, and they may be using it.** Screenshot immediately before every
  tap; if focus or orientation changes between screenshots, stop injecting.
- `build.log` is UTF-16 — read via PowerShell `Get-Content -Encoding Unicode`, not `iconv`.
- Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- The `Glob` tool is broken on this repo's `C:\+Projects` path — use Bash `ls` or `Grep`.
- Windows Python cannot read `/c/...` paths; pass `C:/...`. A guard script "passed" silently
  over **zero matched files** because of this. Always print the file count.

## 5. What landed this session

| commit | what |
|---|---|
| `292b791` | fixed the build — `staticProp` lives on `Prop`; nothing had compiled since 13:56 |
| `4eda119` | seeded-lane pre-emption: a payload on another type's seeded lane stole that lane, renaming it, flipping its kind and hoisting it up the band (= a silent paint-order change). Device-proven; 4 assertions added to `getlayers_equiv.py` |
| `cfb0452` | **countdown/count-up timer text objects** — `TimerSpec`/`TimerText`, one authority shared by preview+export, 38/38 harness, device-verified counting 0:05→0:01 |
| `2b4530d` | `selectSegment` resolved positions against the OLD selection (stale view index) |
| `58485af` | transcript word-tap: resolve against the transcript's clip, re-home onto the clip that contains the tapped time. **Fixed the user's 0:00 teleport**, verified on their own taps |
| `108cff9` | playhead glides through a cut instead of freezing 110–330ms at every seam |
| `71fd1f8` | **transcript sharing** + legacy-partition migration (see §1) |
| `11c4927` | the unfinished-work audit |

## 6. Open, in priority order

Read `tasks/AUDIT_UNFINISHED_20260726.md` first — it is the map. Highlights:

1. ~~Verify the transcript migration~~ — DONE and verified on device, see §1.
2. **`TrackKind.LAYER` schema hole** (audit 1.2) — DATA-LOSS class, cheapest fix in the list.
   `SCHEMA_VERSION` is still 10 so the downgrade guard never fires for a neutral lane; an
   older build coerces LAYER→VIDEO and re-saves, permanently changing paint order.
3. **Caption size slider** (audit 2.1/2.2) — wired to model + export but never to the preview
   overlay, for a month. Users size captions blind. One setter + one bind + two serializer
   lines.
4. **`layerId: null` drops objects** (audit 1.3) — four `isJsonNull()` guards.
5. Transcript panel step 3 — but per `SPEC_TRANSCRIPT_SHARING.md`, show the WHOLE source with
   the current clip highlighted, NOT the windowing the old plan specified. The user needs to
   read ahead to place breaks.
6. Standing device-verify debt: PiP audio acceptance 2+4 (nobody has listened to a PiP or
   exported one), cross-type Z absolute-geometry A/B, neutral substrate queue items 2,3,5-8.
7. `SPEC_TEXT_ANIMATION.md` — designed, not started.

## 7. Things the user is owed an answer on

- Whether the transcript migration recovered their ~937 words (§1).
- ~~Timer export leg: no export has ever been rendered with a timer in it.~~
  **ANSWERED 2026-07-26 09:05 — the timer renders in the export and counts correctly.**
  A throwaway clone carrying one countdown timer text (`COUNT_DOWN`/`RELATIVE`, min+sec,
  precision NONE, span 0–4000 ms, off-centre at 0.32/0.30) was exported at 480p/Low and
  sampled at four timestamps. Rendered values, read off the frames:

  | t | expected `ceil((4000−t)/1000)` | in the exported frame |
  |---|---|---|
  | 0.35 s | 0:04 | **0:04** |
  | 1.2 s | 0:03 | **0:03** |
  | 2.2 s | 0:02 | **0:02** |
  | 3.2 s | 0:01 | **0:01** |

  4/4, matching `TimerText.format`'s ceiling-seconds contract (the same values
  `tools/jvm-harness/TimerTextTest.java` asserts). This is self-validating in a way a single
  frame would not be: a static text overlay cannot produce four different values in the right
  order, so it also rules out "the timer is baked as one frozen string at export time" — which
  was the plausible failure mode given preview and export share the authority but not the
  render loop.

  *Caveat worth stating: this verifies the RELATIVE countdown path with minutes+seconds and
  precision NONE. `ABSOLUTE` basis, `COUNT_UP`, hours, and the FRAMES/MILLIS precisions are
  covered by the 38/38 JVM harness but have not been through an export.*
- They asked for animated text; the spec exists, the build does not.
