# NEXT SESSION — paste everything below the rule into a fresh conversation

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
The working directory may be the PARENT (`C:\+Projects\Screenrecorder`), in which case task docs
are at `FadCam/tasks/…`.

**Read `FadCam/tasks/LEDGER.md` first.** It is the single record of what is fixed, what is open,
and what was decided — each with the evidence that proved it. Keep it current. Never delete an
entry to make the list shorter.

Tree is clean at **`9c40056`** and builds. Nothing is half-finished.

**Work autonomously. Send one message confirming the state and your plan, then get to work.
Prove things rather than asserting them — a green build proves nothing here.**

## WHAT'S DONE

§3g text animation is **finished and user-approved on the phone** — engine, presets, persistence,
preview, export, and the authoring UI. He drove it himself: *"animations per word and per letter
look great! i tried all styles carets worked well."* The timing model was then redesigned at his
direction and that landed too: caption zones are a **fraction of each caption line** (`97eb73b`),
and captions got a **range control in the style panel and lost their timeline carets**
(`27762a6`). The preset tiles animate (`aac13f0`).

**Do not reopen any of that.** Two questions that were open for four sessions are now answered
and recorded in LEDGER §4:

- **The tiles are done.** Motion-only distinction is accepted. They read as distinct when you
  watch them and not in a screenshot, and that is fine. **Do not add a decorative cue to fix the
  still-frame half** — the tiles are trustworthy because they can only advertise motion the
  renderers really produce.
- **Preset build order: MATRIX → UNSCRAMBLE → ODOMETER → MASK_WIPE → NEON_FLICKER.**

## WHAT TO DO NEXT, IN ORDER

**1. MATRIX.** Its blocker is named in `CaptionAnimator.unsupportedReason`; today it returns
identity, never an approximation. The shape of the job is "remove a reason, add a transform", and
the picker un-greys it through `Preset.implemented`. The harness is the proof —
`tools/jvm-harness/CaptionAnimatorTest.java`, runs off-device in seconds; the command is in
LEDGER §3g.

**2. The text-box half of §3g — but tell the user how big it is before starting.** His direction
was that the carets become text-box-only, measured against *that line's* display duration. The
caret code is written and PARKED (`27762a6`), and the reason it is parked is the real work:
**there is no text-box path into the caption style panel at all.** `caption_drawer` opens only
from `toggleCaptions()`, `tweakCaptionStyle` targets a video or audio clip, and `TextOverlayItem`
has its own colour/font and keyframes with no `CaptionStyle` and no animation zones. That is a
build, not a context branch. LEDGER §3g has the detail. If text boxes get dropped instead, delete
the parked caret block with them.

**3. §3a — the masking / chroma-key AUTHORING UI.** The engine is built and device-proven, the
matte and feather work landed (`d0d9759`, `da28310`), and **nothing in the app can still create
one**. This is the feature that was built once and then LOST for weeks, which is why the ledger
exists. Four binding scope answers are in LEDGER §3a. **One finding to raise first:** *"image
overlays in v1"* has nothing to put a mask on — no IMAGE track can be created, and the single PiP
entry point builds from a picked video. So that item is "build the image overlay", not mask
plumbing, and the user should get to decide whether it stays in v1.

**4. §3e** — the two-stage AI clip reorder.

**§3d is CLOSED. Measured, then deleted on the user's call (`14e07ee`). Do not rebuild it.**

## ONE THING STILL UNMEASURED

Whether **LETTER granularity holds frame rate on a long phrase**. It is the cost centre — per-glyph
layout in both the preview and the export path. The user says it looks great; nobody has put a
number on it.

## HOW TO WORK HERE — every rule below was paid for in a bug

- **Pair every check with a positive control**, and prefer a control that a stale artefact could
  not fake. The strongest one found here is a symbol you **deleted**: a dex scan showing it ABSENT
  cannot come from a stale dex.
- **Gradle lies.** It has printed `UP-TO-DATE` for a file just changed, and a `.class` has carried
  an mtime older than the compile that wrote it. Do not reason about mtimes. Verify with
  `javap -constants` on a value you changed, then dex-scan for your new symbol **and** for
  `FadCamApplication` — a partial-dex build once shipped an APK missing it that crash-looped, and
  scanning only for your own new symbol does not catch that.
- A **too-long classpath** fails with "Argument list too long" on stderr with **no `error:` line**,
  so a grep for compile errors comes back clean over an empty output directory. Check the `.class`
  file exists. `tools/jvm-harness/run-matte.sh` shows the pattern.
- Don't pipe gradle through `Select-String` — exit 255 on a run that succeeded. Capture to a
  variable and slice it.
- Screenshots must go through the **Bash** tool (`adb exec-out screencap -p > f.png`); PowerShell
  `>` corrupts binary. To settle "do these two look different", diff the pixels **and run the same
  diff on a pair that obviously differs** as the control.
- `uiautomator dump` fails on an animating screen and leaves you reading a stale `/sdcard/ui.xml`
  — delete it first and confirm the new one landed. In Git Bash, `export MSYS_NO_PATHCONV=1` or
  `/sdcard/...` gets mangled into a Windows path.
- `adb logcat` without `-T` replays the whole ring buffer. Never `logcat -c`.
- `adb` is at `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- Commit with `git commit -F <file>`, written without a BOM
  (`[System.IO.File]::WriteAllText($p,$m,(New-Object System.Text.UTF8Encoding($false)))`).
- Strings are hardcoded with `// TODO(strings)`; extraction is frozen behind the rebrand
  (`road_map.md:49`). Follow that, don't "fix" it.

## THE TEST PROJECT

`bb2a9deb-5651-4f12-93b1-512980185a14` — "P0 control no image", 3 captioned clips.
**Navigate by id, never by remembered position or date** — the Recent Projects list re-sorts every
time a project is opened, and the ledger's old "second from the bottom" locator was already wrong
once. Map rows with `run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.
To reach the UI: select a captioned clip → **Captions** in the bottom toolbar → the **Motion** row
→ `≡A` opens the preset popover.

Opening this project migrates it to the transcript pool and takes `"words"` 3 → 2. **That is the
§1 dedup working, not data loss.** Also note `adb shell run-as … cat` adds CR to every line, so a
pulled copy reads larger than the file on the device — never push one back.

## DEVICE RULES — NON-NEGOTIABLE

- The Note 9 `<note9-serial>` is the sandbox and must be the ONLY phone attached.
- **If the Note 20 `<note20-serial>` appears, STOP all device work.** It holds the user's real
  45-minute project. It was attached once and blocked a whole session; the rule earned its keep.
- **A human may be using the Note 9.** A screen once switched to Chrome mid-sequence and an
  injected tap may have landed in that browser. Check `dumpsys activity activities | grep
  topResumedActivity` before and after a tap run, and stop if it isn't FadCam.
- Installing kills whatever session is running — say so.
- If adb drops the device, `adb kill-server; adb start-server`, then **re-check orientation** —
  a rotation invalidates every tapped coordinate (`settings put system user_rotation 0`).
