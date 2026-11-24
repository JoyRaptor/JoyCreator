# SPEC — Device verification sweep: eight things nobody has looked at

**Written:** 2026-08-28 · **For:** an external agent · **Owner:** JoyRaptor.

Claim a lane in `tasks/LANES.md` named after this spec. **Take the `DEVICE:` token**
(LANES rule 7) before any adb command, and set it back to `free` when you finish.

**This task writes no production code.** If you find a defect, REPORT it — do not fix it,
because three other lanes are live in these files. The one exception is a one-line,
obviously-correct fix, and even then say so loudly in your report.

---

## 1. Why

A long polishing session landed many small changes that were built, installed, and never
looked at. JoyRaptor is using the app as a product and reporting what he hits; this sweep is
meant to find the rest before he does.

**The Note 20 IS connected.** `adb devices` → `<note20-serial>`. An older LANES note says
"UNPLUGGED"; it is stale and cost the caption agent its visual checks.

Useful invocations:

```
adb devices
adb shell am force-stop com.fadcam.beta          # export runs in its OWN process; do this
adb shell monkey -p com.fadcam.beta -c android.intent.category.LAUNCHER 1
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml   # exact bounds
adb shell settings put system accelerometer_rotation 0                 # BEFORE user_rotation
adb shell settings put system user_rotation 1                          # landscape
```

Open the project named **openart-90208197…** (top of the Faditor list): a 4.6s video, a
6:11 mp3 with an imported transcript, and a `Blank (auto)` spine spacer.

---

## 2. The checks

Each: state PASS/FAIL, attach a screenshot, and for layout questions give the
`uiautomator` bounds rather than an impression.

### 2.1 Opacity keyframe helper — never seen (`6ac64156`)
Select the video clip, open the Opacity drawer, turn keyframe mode on, drop two
keyframes.
- Standing ON a keyframe: a green dot appears and a red delete icon appears.
- Standing off one: both are hidden.
- The keyframe diamond is **amber** on a keyframe, **green** armed-but-not-on-one, grey off.
- Delete removes the keyframe under the playhead — and the one the dot claimed.

### 2.2 Caption font Import chip — never seen (`90d77721`)
Open the caption Style drawer's font row. `+ Import` must be the FIRST chip, visible
with no horizontal scrolling.

### 2.3 An imported font actually rendering (`49f5fce1`, `d0fecd60`)
Import a `.ttf` and a `.otf` (JoyRaptor's are on the SD card; any font file will do). Then:
- it appears in the caption font row marked with `*`
- selecting it changes the caption's typeface **on the preview**
- it also appears in the TEXT font picker (one folder feeds both)
- fonts land in the app's files dir: `adb shell run-as com.fadcam.beta ls -la files/fonts`

### 2.4 Caption Fit tab (`a9c67386`) — the agent could not verify these
- The Fit tab exists alongside Style / Timing / Position and is no taller than the others.
- Words-per-caption dial is now on **Fit**, and gone from the Style size row.
- Set a cue to ~30 words. `OFF` overflows the box; `UNIFORM` shrinks every cue to one
  size; `PER_CUE` fits each independently.
- The floor slider stops the shrinking (set 45%, use an absurdly long cue).
- Max-lines dial shows `∞` at 0.

### 2.5 Fit `OFF` is byte-identical to before
Export the project with fit `OFF`, then compare against an export from **before**
`bbd21a4b` if one exists, or reason from a stable frame. At minimum: confirm captions
render exactly as they did with the same style and size. Report PSNR if you can produce
both files.

### 2.6 Horizontal reflow on a PORTRAIT canvas — the one reflow case never checked
The reflow is verified on a wide canvas (it correctly does not move) and vertically. Not
on a portrait canvas, where the picture SHOULD travel.
- Set the project canvas to 9:16 (or open a portrait project).
- Open the transcript drawer.
- The picture slides LEFT into the empty pillarbox space, stops before its left edge
  leaves the slot, and is **never clipped**.
- The diagonal workspace hatching fills the whole slot: **no black band anywhere**.
- Rotate landscape and back with the drawer open; all of the above still holds.
Give `uiautomator` bounds for `player_container`, `canvas_frame` and `transcript_panel`.
They should all end at the screen's right edge.

### 2.7 Export GL frames — `SPEC_20260828_EXPORT_GL_FRAMES` §5 was never run
That agent reported a clean typecheck and stopped. Its own acceptance requires:
- a timed export before and after, same project and settings, wall clock for each
- `ffmpeg -i a.mp4 -i b.mp4 -lavfi psnr -f null -`, average AND minimum
- the `SEQ_FRAMES` / `PIP_FRAMES` log lines from the run
Baseline for that project at 720p/Low is **1m38s**. Use `tools/psnr_parity.sh` if it fits.
**Force-stop the app first** or you will time the old code — this has already produced
one false result.

### 2.8 The ~1:03 playback ceiling — unreproduced
JoyRaptor hit a limit around 1 minute 3 seconds: playback would not pass it, and it later
went away on its own. Nobody has reproduced it and no cap exists in the code.
Play the full 6:11 project through, twice, from cold start. If it stalls, capture:
`adb logcat -d > log.txt`, the playhead reading, and whether the timecode keeps counting
while the picture is frozen. **A clean "could not reproduce in N attempts" is a valid and
useful result** — say how many.

---

## 3. Reporting

A table: check, PASS/FAIL, evidence. Screenshots for each. For every FAIL: what you saw,
what you expected, and the exact bounds or log line that shows it. Do not fix; report.

Release the `DEVICE:` token when you are done.
